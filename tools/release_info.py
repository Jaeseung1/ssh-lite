"""release/version.json 만들기 (앱의 자동 업데이트가 읽는 파일)

사용 순서:
  1) release/SSH-Lite.exe, release/SSH-Lite.apk 를 새 버전으로 바꿔서 커밋
  2) python3 tools/release_info.py "바뀐 점 1" "바뀐 점 2" ...
       -> 방금 커밋(HEAD)의 주소로 고정된 파일 주소 + SHA-256 + 크기를 version.json 에 씀
          (raw.githubusercontent.com 은 main 주소를 몇 분 캐시해서, 고정 주소를 써야 버전이 섞이지 않음)
  3) version.json 커밋 후 push
버전은 ssh_lite.pyw 의 APP_VERSION, android/AndroidManifest.xml 의 versionName 에서 읽음."""
import hashlib
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = "Jaeseung1/ssh-lite"


def file_info(rel, version, commit):
    path = os.path.join(ROOT, rel)
    data = open(path, "rb").read()
    committed = subprocess.run(["git", "-C", ROOT, "show", f"{commit}:{rel}"], capture_output=True).stdout
    if committed != data:
        sys.exit(f"{rel} 가 {commit[:8]} 커밋의 것과 달라요. 먼저 커밋하세요.")
    return {"version": version, "url": f"https://raw.githubusercontent.com/{REPO}/{commit}/{rel}",
            "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}


def main(notes):
    commit = subprocess.run(["git", "-C", ROOT, "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    pc = re.search(r'^APP_VERSION = "([^"]+)"', open(os.path.join(ROOT, "ssh_lite.pyw"), encoding="utf-8").read(), re.M).group(1)
    apk = re.search(r'android:versionName="([^"]+)"', open(os.path.join(ROOT, "android", "AndroidManifest.xml"), encoding="utf-8").read()).group(1)
    out = {"exe": file_info("release/SSH-Lite.exe", pc, commit), "apk": file_info("release/SSH-Lite.apk", apk, commit), "notes": notes}
    with open(os.path.join(ROOT, "release", "version.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main(sys.argv[1:])
