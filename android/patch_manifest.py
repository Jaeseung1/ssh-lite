"""aapt(우분투 패키지, API 23 기준)가 모르는 최신 manifest 속성을 넣기.
AndroidManifest.xml 에는 자리만 잡아 두고(android:maxRecents="1073741824"), 묶인 apk 안의 바이너리 manifest 에서
그 속성의 리소스 ID 를 foregroundServiceType 으로 바꿈. 안드로이드는 manifest 속성을 이름이 아니라 리소스 ID 로 읽음.

  foregroundServiceType = 0x01010599 (Android 10 에 생김), 값 1073741824 = specialUse (Android 14)
  안드로이드 14+ 를 대상으로 하는 앱은 포그라운드 서비스(연결 유지 알림)에 이 속성이 꼭 있어야 함

사용: python3 patch_manifest.py unsigned.apk"""
import struct
import sys
import zipfile

PLACEHOLDER = "maxRecents"
OLD_ID, NEW_ID = 0x01010446, 0x01010599  # android.R.attr.maxRecents (16843846), foregroundServiceType (16844185)


def read_strings(data, off):
    """문자열 풀 -> 목록"""
    _, hsize, _ = struct.unpack_from("<HHI", data, off)
    count, _, flags, start, _ = struct.unpack_from("<IIIII", data, off + 8)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from(f"<{count}I", data, off + hsize)
    out = []
    for o in offsets:
        p = off + start + o
        if utf8:
            n = data[p]
            p += 2 if n & 0x80 else 1      # 글자 수
            n = data[p]
            if n & 0x80:                   # 바이트 수
                n = ((n & 0x7F) << 8) | data[p + 1]
                p += 2
            else:
                p += 1
            out.append(data[p:p + n].decode("utf-8"))
        else:
            n = struct.unpack_from("<H", data, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
                p += 2
            out.append(data[p:p + 2 * n].decode("utf-16-le"))
    return out


def patch(manifest):
    data = bytearray(manifest)
    assert struct.unpack_from("<H", data, 0)[0] == 0x0003, "바이너리 XML 이 아니에요"
    off, strings, done = 8, None, False
    while off < len(data):
        ctype, _, csize = struct.unpack_from("<HHI", data, off)
        if ctype == 0x0001:
            strings = read_strings(data, off)
        elif ctype == 0x0180:  # 리소스 ID 표: 문자열 풀 앞쪽 n 개와 짝
            i = strings.index(PLACEHOLDER)
            pos = off + 8 + 4 * i
            assert struct.unpack_from("<I", data, pos)[0] == OLD_ID, "자리 속성의 ID 가 예상과 달라요"
            struct.pack_into("<I", data, pos, NEW_ID)
            done = True
            break
        off += csize
    assert done, "자리 속성(android:maxRecents)을 찾지 못했어요"
    return bytes(data)


def main(apk):
    with zipfile.ZipFile(apk) as z:
        items = [(info, z.read(info.filename)) for info in z.infolist()]
    with zipfile.ZipFile(apk + ".tmp", "w") as z:
        for info, body in items:
            if info.filename == "AndroidManifest.xml":
                body = patch(body)
            z.writestr(info, body, compress_type=info.compress_type)
    import os
    os.replace(apk + ".tmp", apk)
    print("manifest: foregroundServiceType=specialUse 넣음")


if __name__ == "__main__":
    main(sys.argv[1])
