# 함께 들어 있는 외부 코드

SSH Lite 자체는 [MIT 라이선스](LICENSE)예요. 아래 외부 코드는 각자의 라이선스를 따라요.

## 저장소 안에 들어 있는 것

| 이름 | 쓰는 곳 | 라이선스 | 라이선스 글 |
|---|---|---|---|
| [xterm.js](https://github.com/xtermjs/xterm.js) (+ fit · search · web-links 애드온) | 터미널 화면 (PC·폰) | MIT | [`ui/LICENSE-xterm.txt`](ui/LICENSE-xterm.txt) |
| [Pretendard](https://github.com/orioncactus/pretendard) | 화면 글꼴 | SIL OFL 1.1 | [`ui/fonts/LICENSE-Pretendard.txt`](ui/fonts/LICENSE-Pretendard.txt) |
| [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) | 터미널 글꼴 | SIL OFL 1.1 | [`ui/fonts/LICENSE-JetBrainsMono.txt`](ui/fonts/LICENSE-JetBrainsMono.txt) |
| [JSch (mwiede)](https://github.com/mwiede/jsch) | 폰 앱의 SSH·SFTP (`android/libs/jsch-*.jar`) | BSD 스타일 | [`licenses/JSch.txt`](licenses/JSch.txt) |
| [Bouncy Castle](https://www.bouncycastle.org) 일부 | 폰 앱의 Ed25519·Curve25519·ChaCha20 (`android/libs/bc-subset.jar`) | MIT | [`licenses/BouncyCastle.txt`](licenses/BouncyCastle.txt) |
| [Retrolambda](https://github.com/luontola/retrolambda) | 폰 앱 빌드 도구 (`android/tools/`, 앱 안에는 안 들어감) | Apache 2.0 | [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) |

폰 앱(`SSH-Lite.apk`) 안에도 JSch·Bouncy Castle 라이선스 글을 넣어 뒀어요 (`assets/licenses/`).

## Windows exe 안에 함께 묶인 것

`SSH-Lite.exe`는 PyInstaller로 Python과 아래 라이브러리를 한 파일로 묶은 거예요 (라이선스는 각 패키지 정보에 적힌 그대로).

| 이름 | 라이선스 |
|---|---|
| Python | PSF License |
| [paramiko](https://github.com/paramiko/paramiko) | LGPL-2.1 (소스: 링크 참고, 고친 곳 없음) |
| [pywebview](https://github.com/r0x0r/pywebview) | BSD-3-Clause |
| [pywinpty](https://github.com/andfoy/pywinpty) | MIT |
| cryptography | Apache-2.0 OR BSD-3-Clause |
| bcrypt, PyNaCl | Apache-2.0 |
| cffi | MIT-0 |
| pycparser, pywin32-ctypes | BSD-3-Clause |
| invoke | BSD-2-Clause |
| bottle, proxy-tools, pythonnet | MIT |
| typing_extensions | PSF-2.0 |
| clr_loader | MIT |
| [PyInstaller](https://pyinstaller.org) 실행기 | GPL-2.0 이상 + 묶은 프로그램은 자유롭게 배포할 수 있게 하는 예외 조항 |
