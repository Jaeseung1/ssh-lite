"""JSch 의 Ed25519·Curve25519·ChaCha20 (com/jcraft/jsch/bc/*) 이 쓰는 Bouncy Castle 클래스만 골라 작은 jar 로 만듦.
(폰에는 이 암호들이 기본으로 없는 기종이 있음. Bouncy Castle 전체는 8MB 라서 필요한 것만)
사용: python3 bc_subset.py jsch.jar bcprov.jar out.jar"""
import re
import struct
import sys
import zipfile

ROOTS = ["com/jcraft/jsch/bc/SignatureEdDSA", "com/jcraft/jsch/bc/SignatureEd25519", "com/jcraft/jsch/bc/SignatureEd448",
         "com/jcraft/jsch/bc/KeyPairGenEdDSA", "com/jcraft/jsch/bc/XDH", "com/jcraft/jsch/bc/ChaCha20Poly1305"]


def class_refs(data):
    """클래스 파일 상수 풀에서 다른 클래스 이름 (Class 항목 + 타입 설명 안의 L...;)"""
    count = struct.unpack(">H", data[8:10])[0]
    i, idx, utf8, classes = 10, 1, {}, []
    while idx < count:
        tag = data[i]
        if tag == 1:
            n = struct.unpack(">H", data[i + 1:i + 3])[0]
            utf8[idx] = data[i + 3:i + 3 + n].decode("utf-8", "replace")
            i += 3 + n
        elif tag in (7, 8, 16, 19, 20):
            if tag == 7:
                classes.append(struct.unpack(">H", data[i + 1:i + 3])[0])
            i += 3
        elif tag == 15:
            i += 4
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 5
        elif tag in (5, 6):
            i += 9
            idx += 1
        else:
            raise ValueError(f"tag {tag}")
        idx += 1
    out = set()
    for c in classes:
        name = utf8.get(c, "")
        name = name.lstrip("[")
        if name.startswith("L") and name.endswith(";"):
            name = name[1:-1]
        out.add(name)
    for s in utf8.values():
        out.update(re.findall(r"L([\w/$]+);", s))
    return out


def main(jsch_jar, bc_jar, out_jar):
    jsch = zipfile.ZipFile(jsch_jar)
    bc = zipfile.ZipFile(bc_jar)
    bc_names = {n[:-6] for n in bc.namelist() if n.endswith(".class") and not n.startswith("META-INF/")}
    todo, seen, need = list(ROOTS), set(), set()
    while todo:
        c = todo.pop()
        if c in seen:
            continue
        seen.add(c)
        if c.startswith("com/jcraft/"):
            try:
                data = jsch.read(c + ".class")
            except KeyError:
                continue
        elif c in bc_names:
            need.add(c)
            data = bc.read(c + ".class")
        else:
            continue
        for r in class_refs(data):
            if r.startswith("org/bouncycastle/") or r.startswith("com/jcraft/jsch/bc/"):
                todo.append(r)
    with zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for c in sorted(need):
            z.writestr(c + ".class", bc.read(c + ".class"))
        z.writestr("META-INF/LICENSE-bouncycastle.txt", "Bouncy Castle (MIT License) https://www.bouncycastle.org/licence.html\n")
    print(f"{len(need)} classes")


if __name__ == "__main__":
    main(*sys.argv[1:4])
