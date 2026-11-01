"""
SSH Lite — 터미널 + 서버 파일 탐색기가 한 창에 있는 가벼운 SSH 접속 도구

  · 오른쪽: 터미널 탭 (서버 터미널 + 내 PC의 cmd / PowerShell)
  · 왼쪽:   서버 폴더 트리. 윈도우 탐색기에서 파일/폴더를 끌어다 놓으면 업로드
  · 한 번 비밀번호로 접속하면 SSH 키를 등록해서 다음부터 비밀번호 없이 접속

필요: Python 3.9 이상  ->  pip install paramiko pywebview pywinpty
실행: 이 파일(ssh_lite.pyw)을 더블클릭
"""
import base64
import codecs
import getpass
import hashlib
import json
import os
import posixpath
import queue
import re
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import time
import webbrowser

import paramiko

APP_VERSION = "2026.11.01-4"  # 창 제목과 ⋯ 메뉴에 보임 (어느 exe 를 쓰는지 확인용)
HOME = os.path.expanduser("~")
DEFAULTS = {
    "host": "",   # 처음 한 번 화면에서 입력하면 기억함 (~/.ssh_lite.json)
    "port": 22,
    "user": "",
    "download_dir": os.path.join(HOME, "Downloads"),
    "auto_connect": False,
    "show_hidden": False,
    "sidebar_width": 300,
    "tab_titles": True,  # 서버 탭 이름에 폴더/실행 중인 명령 표시 (서버 ~/.bashrc 에 설정 추가)
    "follow_cwd": True,  # 파일 트리가 서버 탭의 현재 폴더를 따라감
    "font_size": 14,
    "server_stats": True,  # 상태 줄에 서버 CPU/메모리/디스크 사용량
}
# 개인용 빌드: defaults.local.json 이 함께 있으면 처음 켤 때 서버 정보가 채워져 있음 (저장소에는 안 올림)
try:
    with open(os.path.join(getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__))), "defaults.local.json"),
              encoding="utf-8") as _f:
        DEFAULTS.update({k: v for k, v in json.load(_f).items() if k in ("host", "port", "user", "auto_connect")})
except (OSError, ValueError):
    pass
CONFIG_PATH = os.path.join(HOME, ".ssh_lite.json")
SSH_DIR = os.path.join(HOME, ".ssh")
KNOWN_HOSTS = os.path.join(SSH_DIR, "known_hosts")  # cmd의 ssh와 같은 파일
KEY_PATH = os.path.join(SSH_DIR, "id_ed25519")       # cmd의 ssh도 기본으로 쓰는 키
EDIT_DIR = os.path.join(tempfile.gettempdir(), "ssh-lite-edit")  # 서버 파일을 내 PC에서 편집할 때 쓰는 임시 폴더


# 서버 셸 설정 파일에 넣는 "탭 이름 표시" 설정. 탭 이름 형식:
#   프롬프트에서 기다릴 때: "사용자@서버: 폴더"        (Ubuntu 기본 bash와 같은 형식)
#   명령 실행 중:           "명령 (사용자@서버: 폴더)"
SHELL_MARK_BEGIN = "# >>> SSH Lite: 탭 이름에 폴더와 실행 중인 명령 표시 (이 블록을 지우면 원래대로) >>>"
SHELL_MARK_END = "# <<< SSH Lite <<<"
# bash: 4.4 이상은 PS0(명령을 읽은 직후, 실행 직전에 출력됨), 그보다 오래된 bash는 DEBUG trap 으로 명령을 알아냄
BASH_SNIPPET = SHELL_MARK_BEGIN + r"""
if [ -n "$PS1" ] && [ -z "$__SSHLITE_TITLES" ]; then
  __SSHLITE_TITLES=1
  __sshlite_where() { printf '%s@%s: %s' "${USER:-$(id -un)}" "${HOSTNAME%%.*}" "${PWD/#$HOME/\~}"; }
  if [ "${BASH_VERSINFO[0]:-0}" -gt 4 ] || { [ "${BASH_VERSINFO[0]:-0}" -eq 4 ] && [ "${BASH_VERSINFO[1]:-0}" -ge 4 ]; }; then
    __sshlite_cmd() { HISTTIMEFORMAT= builtin history 1 | sed 's/^ *[0-9]* *//'; }
    PROMPT_COMMAND="${PROMPT_COMMAND:+$PROMPT_COMMAND; }"'printf "\033]0;%s\007" "$(__sshlite_where)"'
    PS0='\e]0;$(__sshlite_cmd) ($(__sshlite_where))\a'"$PS0"
  else
    __sshlite_preexec() {
      [ "$__sshlite_ready" = 1 ] || return
      case "$PROMPT_COMMAND" in *"$BASH_COMMAND"*) return ;; esac
      __sshlite_ready=0
      printf '\033]0;%s (%s)\007' "$BASH_COMMAND" "$(__sshlite_where)"
    }
    PROMPT_COMMAND="${PROMPT_COMMAND:+$PROMPT_COMMAND; }"'printf "\033]0;%s\007" "$(__sshlite_where)"; __sshlite_ready=1'
    trap '__sshlite_preexec' DEBUG
  fi
fi
""" + SHELL_MARK_END + "\n"
ZSH_SNIPPET = SHELL_MARK_BEGIN + r"""
if [[ -o interactive && -z "$__SSHLITE_TITLES" ]]; then
  __SSHLITE_TITLES=1
  autoload -Uz add-zsh-hook
  __sshlite_where() { printf '%s@%s: %s' "${USER:-$(id -un)}" "${HOST%%.*}" "${PWD/#$HOME/~}"; }
  __sshlite_precmd() { printf '\033]0;%s\007' "$(__sshlite_where)"; }
  __sshlite_preexec() { printf '\033]0;%s (%s)\007' "$1" "$(__sshlite_where)"; }
  add-zsh-hook precmd __sshlite_precmd
  add-zsh-hook preexec __sshlite_preexec
  # zsh 기본 설정은 Home/End/Delete/Ctrl+화살표를 모름
  bindkey '^[[H' beginning-of-line; bindkey '^[OH' beginning-of-line
  bindkey '^[[F' end-of-line;       bindkey '^[OF' end-of-line
  bindkey '^[[3~' delete-char
  bindkey '^[[1;5D' backward-word;  bindkey '^[[1;5C' forward-word
fi
""" + SHELL_MARK_END + "\n"


def strip_block(text):
    """설정 파일에서 SSH Lite 블록만 지움"""
    start = text.index(SHELL_MARK_BEGIN)
    end = text.index(SHELL_MARK_END, start) + len(SHELL_MARK_END)
    before, after = text[:start].rstrip("\n"), text[end:].lstrip("\n")
    return (before + "\n" if before else "") + after


class Cancelled(Exception):
    """사용자가 전송을 취소함"""


class UnknownHostKey(Exception):
    def __init__(self, hostname, key):
        super().__init__(hostname)
        self.hostname = hostname
        self.key = key


# ---------------------------------------------------------------- 도우미

def load_config():
    cfg = dict(DEFAULTS)
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            cfg.update(json.load(f))
    except (OSError, ValueError):
        pass
    return cfg


def save_config(cfg):
    try:
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
    except OSError:
        pass


def fingerprint(key):
    """OpenSSH와 같은 형식(SHA256:...)의 서버 키 지문"""
    digest = hashlib.sha256(key.asbytes()).digest()
    return "SHA256:" + base64.b64encode(digest).decode().rstrip("=")


def append_line(path, line):
    """파일 끝에 한 줄 추가 (마지막 줄바꿈이 빠져 있으면 보충)"""
    prefix = ""
    if os.path.exists(path) and os.path.getsize(path) > 0:
        with open(path, "rb") as f:
            f.seek(-1, os.SEEK_END)
            if f.read(1) != b"\n":
                prefix = "\n"
    with open(path, "a", encoding="utf-8") as f:
        f.write(f"{prefix}{line}\n")


def safe_local_name(name):
    """Windows 파일 이름에 못 쓰는 글자를 _ 로 바꿈"""
    return re.sub(r'[<>:"\\|?*\x00-\x1f]', "_", name) or "_"


def launch_file(path, choose=False):
    """내 PC의 기본 프로그램으로 파일 열기. choose=True 면 '연결 프로그램 선택' 창"""
    if sys.platform == "win32":
        if choose:
            subprocess.Popen(["rundll32.exe", "shell32.dll,OpenAs_RunDLL", path])
            return
        try:
            os.startfile(path)
        except OSError:
            subprocess.Popen(["notepad.exe", path])  # 확장자가 없는 파일처럼 연결된 프로그램이 없을 때
    elif sys.platform == "darwin":
        subprocess.Popen(["open", path])
    else:
        subprocess.Popen(["xdg-open", path])


def clipboard_get():
    """Windows 클립보드의 글자 (브라우저 권한 창 없이 붙여넣으려고 직접 읽음)"""
    if sys.platform != "win32":
        return ""
    import ctypes
    from ctypes import wintypes
    user32, kernel32 = ctypes.windll.user32, ctypes.windll.kernel32
    user32.GetClipboardData.restype = wintypes.HANDLE
    kernel32.GlobalLock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalLock.restype = wintypes.LPVOID
    kernel32.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
    for _ in range(10):  # 다른 프로그램이 잠깐 잡고 있을 수 있음
        if user32.OpenClipboard(None):
            break
        time.sleep(0.02)
    else:
        return ""
    try:
        handle = user32.GetClipboardData(13)  # CF_UNICODETEXT
        if not handle:
            return ""
        ptr = kernel32.GlobalLock(handle)
        try:
            return ctypes.wstring_at(ptr) if ptr else ""
        finally:
            kernel32.GlobalUnlock(handle)
    finally:
        user32.CloseClipboard()


def clipboard_set(text):
    if sys.platform != "win32":
        return False
    import ctypes
    from ctypes import wintypes
    user32, kernel32 = ctypes.windll.user32, ctypes.windll.kernel32
    kernel32.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
    kernel32.GlobalAlloc.restype = wintypes.HGLOBAL
    kernel32.GlobalLock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalLock.restype = wintypes.LPVOID
    kernel32.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
    user32.SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
    data = (text.replace("\r\n", "\n").replace("\n", "\r\n") + "\0").encode("utf-16-le")
    handle = kernel32.GlobalAlloc(0x0002, len(data))  # GMEM_MOVEABLE
    ptr = kernel32.GlobalLock(handle)
    ctypes.memmove(ptr, data, len(data))
    kernel32.GlobalUnlock(handle)
    for _ in range(10):
        if user32.OpenClipboard(None):
            break
        time.sleep(0.02)
    else:
        return False
    try:
        user32.EmptyClipboard()
        return bool(user32.SetClipboardData(13, handle))  # 성공하면 클립보드가 메모리를 맡음
    finally:
        user32.CloseClipboard()


# 서버 사용량: 맥은 ps / vm_stat / sysctl, Linux 는 /proc. 디스크는 둘 다 df (홈 폴더가 있는 디스크)
STATS_CMD = r"""export LC_ALL=C; case "$(uname -s)" in
Darwin) echo "OS darwin"; echo "NCPU $(/usr/sbin/sysctl -n hw.ncpu)"; echo "MEMSIZE $(/usr/sbin/sysctl -n hw.memsize)"; ps -A -o %cpu= | awk '{s+=$1} END {print "PSCPU", s+0}'; vm_stat
  echo "NOW $(date +%s)"; echo "BOOT $(/usr/sbin/sysctl -n kern.boottime)"
  IF=$(/sbin/route -n get default 2>/dev/null | awk '/interface:/ {print $2}')
  [ -n "$IF" ] && /usr/sbin/netstat -ibn -I "$IF" | awk '$3 ~ /^<Link/ {print "NET", $(NF-4), $(NF-1); exit}';;
*) echo "OS linux"; head -1 /proc/stat; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo
  echo "UPTIME $(cut -d' ' -f1 /proc/uptime)"
  awk 'NR > 2 {sub(/:/, " "); if ($1 != "lo") {i += $2; o += $10}} END {print "NET", i+0, o+0}' /proc/net/dev;;
esac; echo "DISK $(df -Pk "$HOME" | tail -1)"
"""


def parse_stats(text, prev=None, now=None):
    """STATS_CMD 출력 -> (사용량, 다음 계산에 쓸 값). 셸 설정이 찍은 다른 글자가 섞여 있어도 필요한 줄만 골라 읽음.
    CPU 와 네트워크 속도는 직전 값과의 차이로 계산 (prev). 읽은 것만 돌려줌.
    CPU·메모리·디스크를 하나도 못 읽으면 ValueError"""
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    value = lambda key: next((l[len(key):].strip() for l in lines if l.startswith(key)), None)
    prev = prev if isinstance(prev, dict) else {}
    result, next_prev, got = {"cpu": None, "net_in": None, "net_out": None}, {}, False
    try:  # 네트워크: 받은/보낸 바이트 누적값 -> 초당 속도
        net_in, net_out = (int(x) for x in value("NET ").split()[:2])
        if now is not None:
            next_prev["net"] = (now, net_in, net_out)
            t0, in0, out0 = prev.get("net") or (None, None, None)
            if t0 is not None and now > t0 and net_in >= in0 and net_out >= out0:
                result.update(net_in=round((net_in - in0) / (now - t0)), net_out=round((net_out - out0) / (now - t0)))
    except (AttributeError, ValueError):
        pass
    try:  # 가동 시간
        if "OS darwin" in lines:
            result["uptime"] = int(value("NOW ")) - int(re.search(r"sec = (\d+)", value("BOOT ")).group(1))
        else:
            result["uptime"] = int(float(value("UPTIME ")))
    except (TypeError, ValueError, AttributeError):
        pass
    disk = (value("DISK ") or "").split()
    pct_at = next((i for i, f in enumerate(disk) if f.endswith("%") and f[:-1].isdigit()), None)
    if pct_at is not None and pct_at >= 3:
        result.update(disk_total=int(disk[pct_at - 3]) * 1024, disk_used=int(disk[pct_at - 2]) * 1024,
                      disk_pct=int(disk[pct_at][:-1]), mount=" ".join(disk[pct_at + 1:]) or "/")
        got = True
    if "OS darwin" in lines:
        try:
            result["cpu"] = max(0, min(100, round(float(value("PSCPU ")) / max(int(value("NCPU ")), 1))))
            got = True
        except (TypeError, ValueError):
            pass
        try:
            memsize = int(value("MEMSIZE "))
            page = int(re.search(r"page size of (\d+) bytes", text).group(1))
            pages = {}
            for l in lines:
                m = re.match(r'^"?([A-Za-z][A-Za-z -]+)"?:\s+(\d+)\.?$', l)
                if m:
                    pages[m.group(1)] = int(m.group(2))
            if "Anonymous pages" in pages:  # 활성 상태 보기의 '사용된 메모리' = 앱 메모리 + 고정 + 압축
                used = pages["Anonymous pages"] - pages.get("Pages purgeable", 0)
            else:
                used = pages["Pages active"]
            used += pages.get("Pages wired down", 0) + pages.get("Pages occupied by compressor", 0)
            result.update(mem_used=min(used * page, memsize), mem_total=memsize)
            got = True
        except (TypeError, ValueError, AttributeError, KeyError):
            pass
    elif "OS linux" in lines:
        try:
            nums = [int(x) for x in value("cpu ").split()[:8]]
            total, idle = sum(nums), nums[3] + (nums[4] if len(nums) > 4 else 0)
            next_prev["cpu"] = (total, idle)
            cpu_prev = prev.get("cpu")
            if cpu_prev and total > cpu_prev[0]:
                result["cpu"] = max(0, min(100, round(100 * (1 - (idle - cpu_prev[1]) / (total - cpu_prev[0])))))
            got = True
        except (AttributeError, ValueError, IndexError):
            pass
        try:
            mem = {l.split(":")[0]: int(l.split()[1]) * 1024 for l in lines if l.startswith("Mem")}
            result.update(mem_used=mem["MemTotal"] - mem["MemAvailable"], mem_total=mem["MemTotal"])
            got = True
        except (KeyError, ValueError, IndexError):
            pass
    if not got:
        raise ValueError("서버 응답에서 사용량을 찾지 못했어요.")
    return result, next_prev


def fit_window_to_screen(hwnd):
    """창이 화면(작업 표시줄 뺀 영역)을 벗어나면 화면 안에 들어오게 줄이고 가운데로.
    화면이 작은 노트북이나 배율 150% 에서 아래쪽 상태 줄이 작업 표시줄 뒤로 숨는 것을 막음"""
    import ctypes
    from ctypes import wintypes

    class MONITORINFO(ctypes.Structure):
        _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT),
                    ("rcWork", wintypes.RECT), ("dwFlags", wintypes.DWORD)]

    u = ctypes.windll.user32
    u.MonitorFromWindow.argtypes = [wintypes.HWND, wintypes.DWORD]
    u.MonitorFromWindow.restype = wintypes.HMONITOR
    u.GetMonitorInfoW.argtypes = [wintypes.HMONITOR, ctypes.POINTER(MONITORINFO)]
    u.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
    u.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND] + [ctypes.c_int] * 4 + [wintypes.UINT]
    r = wintypes.RECT()
    if not u.GetWindowRect(hwnd, ctypes.byref(r)):
        return False
    mi = MONITORINFO()
    mi.cbSize = ctypes.sizeof(MONITORINFO)
    if not u.GetMonitorInfoW(u.MonitorFromWindow(hwnd, 2), ctypes.byref(mi)):  # 2 = 가장 가까운 모니터
        return False
    work = mi.rcWork
    if r.left >= work.left and r.top >= work.top and r.right <= work.right and r.bottom <= work.bottom:
        return False
    ww, wh = work.right - work.left, work.bottom - work.top
    w, h = min(r.right - r.left, ww), min(r.bottom - r.top, wh)
    return bool(u.SetWindowPos(hwnd, None, work.left + (ww - w) // 2, work.top + (wh - h) // 2, w, h,
                               0x0004 | 0x0010))  # 순서·활성 상태는 그대로


def clean_old_edits(days=3):
    """지난번에 편집하던 임시 파일 중 오래된 것 정리"""
    limit = time.time() - days * 86400
    for root, _, files in os.walk(EDIT_DIR):
        for f in files:
            p = os.path.join(root, f)
            try:
                if os.path.getmtime(p) < limit:
                    os.remove(p)
            except OSError:
                pass


def is_auth_error(e):
    return isinstance(e, paramiko.AuthenticationException) or "No authentication methods" in str(e)


def friendly_error(e):
    if isinstance(e, socket.gaierror):
        return "서버 주소를 찾을 수 없어요. 호스트 이름을 확인해주세요."
    if isinstance(e, (paramiko.ssh_exception.NoValidConnectionsError, ConnectionRefusedError)):
        return "서버에 연결할 수 없어요. 포트 번호와 서버가 켜져 있는지 확인해주세요."
    if isinstance(e, (socket.timeout, TimeoutError)):
        return "서버가 응답하지 않아요. 네트워크 상태를 확인해주세요."
    if isinstance(e, paramiko.BadHostKeyException):
        return ("서버의 호스트 키가 예전과 달라요! 서버를 다시 설치한 게 아니라면 접속하지 마세요. "
                f"(정상이라면 {KNOWN_HOSTS}에서 이 서버 줄을 지운 뒤 다시 연결하세요)")
    if isinstance(e, paramiko.BadAuthenticationType):
        return "이 서버는 비밀번호 접속을 허용하지 않아요. SSH 키가 필요해요."
    if isinstance(e, PermissionError):
        return "권한이 없어요."
    if isinstance(e, FileNotFoundError):
        return "파일이나 폴더를 찾을 수 없어요."
    if isinstance(e, FileExistsError):
        return "같은 이름이 이미 있어요."
    return str(e) or e.__class__.__name__


def unique_local_path(path):
    """이미 있으면 '이름 (1).확장자' 처럼 겹치지 않는 이름을 만듦 (브라우저 다운로드와 같은 방식)"""
    if not os.path.exists(path):
        return path
    stem, ext = os.path.splitext(path)
    if os.path.isdir(path):
        stem, ext = path, ""
    n = 1
    while os.path.exists(f"{stem} ({n}){ext}"):
        n += 1
    return f"{stem} ({n}){ext}"


def ensure_local_key():
    """~/.ssh/id_ed25519 키가 없으면 새로 만들고, 공개키 한 줄을 돌려줌"""
    pub_path = KEY_PATH + ".pub"
    if os.path.exists(pub_path):
        with open(pub_path, encoding="utf-8") as f:
            return f.read().strip()
    if os.path.exists(KEY_PATH):
        raise RuntimeError(f"{KEY_PATH} 키는 있는데 공개키(.pub) 파일이 없어요.")
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

    key = Ed25519PrivateKey.generate()
    private = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.OpenSSH,
                                serialization.NoEncryption())
    public = key.public_key().public_bytes(serialization.Encoding.OpenSSH, serialization.PublicFormat.OpenSSH)
    line = f"{public.decode()} {getpass.getuser()}@{socket.gethostname()}"
    os.makedirs(SSH_DIR, exist_ok=True)
    fd = os.open(KEY_PATH, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)  # 나만 읽을 수 있게
    with os.fdopen(fd, "wb") as f:
        f.write(private)
    with open(pub_path, "w", encoding="utf-8") as f:
        f.write(line + "\n")
    return line


def agent_available():
    """ssh-agent를 쓸 수 있는지. 확인하다 오류가 나는 PC도 있어서, 그럴 땐 에이전트 없이 키 파일만 씀"""
    try:
        paramiko.Agent().close()
        return True
    except Exception:
        return False


def entry_dict(path, attr, sftp):
    mode = attr.st_mode or 0
    is_link = stat.S_ISLNK(mode)
    is_dir = stat.S_ISDIR(mode)
    if is_link:  # 바로가기(심볼릭 링크)는 가리키는 대상이 폴더인지 확인
        try:
            is_dir = stat.S_ISDIR(sftp.stat(path).st_mode)
        except OSError:
            pass
    return {"name": attr.filename, "path": path, "dir": is_dir, "link": is_link,
            "size": attr.st_size or 0, "mtime": attr.st_mtime or 0}


class SshShell:
    """서버 터미널 탭 하나 = SSH 셸 채널 하나"""
    remote = True

    def __init__(self, chan):
        self._chan = chan
        self._decoder = codecs.getincrementaldecoder("utf-8")("replace")  # 한글이 조각나서 와도 깨지지 않게

    def read(self):
        """받은 글자. 끝났으면 None"""
        try:
            data = self._chan.recv(65536)
        except Exception:
            return None
        return self._decoder.decode(data) if data else None

    def write(self, text):
        self._chan.sendall(text.encode("utf-8"))

    def resize(self, cols, rows):
        self._chan.resize_pty(width=cols, height=rows)

    def close(self):
        self._chan.close()


class LocalShell:
    """내 PC 터미널 탭: Windows는 ConPTY(pywinpty)로 cmd/PowerShell 실행, 그 밖의 OS는 기본 셸"""
    remote = False

    def __init__(self, kind, cols, rows):
        if sys.platform == "win32":
            from winpty import PtyProcess
            exe = "powershell.exe" if kind == "powershell" else "cmd.exe"
            self._proc = PtyProcess.spawn(exe, cwd=HOME, dimensions=(rows, cols))
            self._fd = None
        else:
            import pty
            master, slave = pty.openpty()
            self._fd = master
            self.resize(cols, rows)
            self._proc = subprocess.Popen([os.environ.get("SHELL") or "/bin/bash"], stdin=slave, stdout=slave,
                                          stderr=slave, cwd=HOME, start_new_session=True,
                                          env={**os.environ, "TERM": "xterm-256color"})
            os.close(slave)
            self._decoder = codecs.getincrementaldecoder("utf-8")("replace")

    def read(self):
        if self._fd is None:
            try:
                return self._proc.read(65536)
            except EOFError:
                return None
        try:
            data = os.read(self._fd, 65536)
        except OSError:
            return None
        return self._decoder.decode(data) if data else None

    def write(self, text):
        if self._fd is None:
            self._proc.write(text)
        else:
            os.write(self._fd, text.encode("utf-8"))

    def resize(self, cols, rows):
        if self._fd is None:
            self._proc.setwinsize(rows, cols)
        else:
            import fcntl
            import struct
            import termios
            fcntl.ioctl(self._fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))

    def close(self):
        try:
            if self._fd is None:
                self._proc.terminate(force=True)
            else:
                self._proc.terminate()
        except Exception:
            pass


class HostKeyCheck(paramiko.MissingHostKeyPolicy):
    """처음 보는 서버: 화면에서 사용자가 지문을 확인한 경우에만 통과시키고 known_hosts에 저장"""

    def __init__(self, trusted_fingerprint):
        self.trusted = trusted_fingerprint

    def missing_host_key(self, client, hostname, key):
        if fingerprint(key) != self.trusted:
            raise UnknownHostKey(hostname, key)
        client.get_host_keys().add(hostname, key.get_name(), key)
        os.makedirs(SSH_DIR, exist_ok=True)
        append_line(KNOWN_HOSTS, f"{hostname} {key.get_name()} {key.get_base64()}")


# ---------------------------------------------------------------- 화면(JS)에서 부르는 기능들
# pywebview가 밑줄(_) 없는 메서드를 window.pywebview.api.<이름>() 으로 노출함.
# 결과를 기다리지 않는 일(터미널 출력, 전송 진행률)은 self._emit()으로 화면에 알림.

class Backend:
    def __init__(self, emit):
        self._emit = emit
        self._cfg = load_config()
        self._lock = threading.RLock()  # 목록/이름 변경 같은 짧은 SFTP 작업용
        self._client = None
        self._sftp = None
        self._terms = {}                # 터미널 탭 번호 -> SshShell / LocalShell
        self._next_term = 1
        self._session = 0               # 연결할 때마다 1씩 증가 (이전 연결의 작업을 무시하려고)
        self._jobs = queue.Queue()      # 업로드/다운로드 대기열
        self._cancel = threading.Event()
        self._stats_prev = None         # CPU 사용률·네트워크 속도는 직전 값과의 차이로 계산
        self._edits = {}                # 편집 중인 내 PC 임시 파일 -> 서버 경로와 마지막으로 올린 상태
        self._open_file = launch_file
        self._transferring = False      # 업로드/다운로드 하는 중 (창을 닫을 때 물어보려고)
        self._force_close = False
        threading.Thread(target=self._transfer_worker, daemon=True).start()
        threading.Thread(target=self._edit_watcher, daemon=True).start()
        threading.Thread(target=clean_old_edits, daemon=True).start()

    # ------------------------------------------------ 설정

    def get_config(self):
        return {**self._cfg, "has_key": os.path.exists(KEY_PATH), "version": APP_VERSION}

    def save_ui(self, values):
        for k in ("show_hidden", "sidebar_width", "auto_connect", "tab_titles", "follow_cwd", "font_size", "server_stats"):
            if k in values:
                self._cfg[k] = values[k]
        save_config(self._cfg)

    # ------------------------------------------------ 연결

    def connect(self, host, port, user, password=None, trust=None):
        host, user, port = str(host).strip(), str(user).strip(), str(port).strip()
        if not re.fullmatch(r"[\w.:-]+", host) or not re.fullmatch(r"[\w.-]+", user) or not port.isdigit():
            return {"error": "호스트, 포트, 사용자를 확인해주세요."}
        port = int(port)
        with self._lock:
            self._close()
            client = paramiko.SSHClient()
            if os.path.exists(KNOWN_HOSTS):
                client.load_system_host_keys(KNOWN_HOSTS)
            client.set_missing_host_key_policy(HostKeyCheck(trust))
            try:
                # SSH 키(~/.ssh/id_*)가 있으면 먼저 쓰고, 안 되면 비밀번호 사용
                client.connect(host, port, user, password=password or None, timeout=10, auth_timeout=30,
                               allow_agent=agent_available())
                client.get_transport().set_keepalive(15)
                sftp = client.open_sftp()
                home = sftp.normalize(".")
            except UnknownHostKey as e:
                client.close()
                return {"need": "hostkey", "host": e.hostname, "type": e.key.get_name(),
                        "fingerprint": fingerprint(e.key)}
            except paramiko.SSHException as e:
                client.close()
                if is_auth_error(e) and not isinstance(e, paramiko.BadAuthenticationType):
                    return {"need": "password", "retry": bool(password)}
                return {"error": friendly_error(e)}
            except Exception as e:
                client.close()
                return {"error": friendly_error(e)}
            self._client, self._sftp = client, sftp
            self._session += 1
            self._stats_prev = None
            self._cfg.update(host=host, port=port, user=user)
            save_config(self._cfg)
            # 탭 이름 표시는 기본으로 켬. 첫 서버 탭이 열리기 전에 넣어야 첫 탭부터 적용됨
            titles = {}
            if self._cfg.get("tab_titles", True):
                titles = self.shell_integration(True)
            return {"ok": True, "home": home, "label": f"{user}@{host}:{port}", "used_password": bool(password),
                    "titles_added": titles.get("added", False), "titles_error": titles.get("error")}

    def disconnect(self):
        with self._lock:
            self._close()

    def _close(self):
        self._cancel.set()
        self._session += 1
        if self._client is not None:
            self._client.close()
        self._client = self._sftp = None
        for tid, shell in list(self._terms.items()):  # 서버 탭만 정리 (내 PC 탭은 그대로)
            if shell.remote:
                self._terms.pop(tid, None)

    def _connected(self):
        transport = self._client.get_transport() if self._client else None
        return transport is not None and transport.is_active()

    def setup_key(self):
        """이 PC의 공개키를 서버 ~/.ssh/authorized_keys에 등록 -> 다음부터 비밀번호 없이 접속"""
        try:
            line = ensure_local_key()
            with self._lock:
                sftp = self._sftp
                ssh_dir = posixpath.join(sftp.normalize("."), ".ssh")
                try:
                    sftp.stat(ssh_dir)
                except FileNotFoundError:
                    sftp.mkdir(ssh_dir, 0o700)
                auth = posixpath.join(ssh_dir, "authorized_keys")
                try:
                    with sftp.open(auth, "r") as f:
                        existing = f.read().decode("utf-8", "replace")
                except FileNotFoundError:
                    existing = ""
                if " ".join(line.split()[:2]) not in existing:
                    prefix = "\n" if existing and not existing.endswith("\n") else ""
                    with sftp.open(auth, "a") as f:
                        f.write(prefix + line + "\n")
                sftp.chmod(auth, 0o600)  # sshd는 권한이 느슨하면 키를 무시함
                sftp.chmod(ssh_dir, 0o700)
            return {"ok": True, "key": KEY_PATH}
        except Exception as e:
            return {"error": friendly_error(e)}

    def set_tab_titles(self, enable):
        """⋯ 메뉴의 켜기/끄기: 서버 설정을 바꾸고, 이 선택을 기억함 (끈 뒤에는 다시 자동으로 켜지 않음)"""
        result = self.shell_integration(bool(enable))
        if "error" not in result:
            self._cfg["tab_titles"] = bool(enable)
            save_config(self._cfg)
        return result

    def shell_integration(self, enable=None):
        """서버 셸 설정의 탭 이름 표시. enable=None: 상태 확인 / True: 추가(예전 버전이면 새로 바꿈) / False: 제거"""
        try:
            with self._lock:
                sftp = self._sftp
                home = sftp.normalize(".")
                texts = {}
                for name in (".bashrc", ".bash_profile", ".zshrc"):
                    try:
                        with sftp.open(posixpath.join(home, name), "r") as f:
                            texts[name] = f.read().decode("utf-8", "replace")
                    except FileNotFoundError:
                        texts[name] = None
                installed = any(t and SHELL_MARK_BEGIN in t for t in texts.values())
                if enable is None:
                    return {"installed": installed}

                want = {}  # 파일 이름 -> 넣을 설정
                if enable:
                    _, out, _ = self._client.exec_command('printf %s "$SHELL"', timeout=10)
                    login_shell = out.read().decode("utf-8", "replace").strip()
                    shell = posixpath.basename(login_shell)
                    if shell == "zsh":
                        want[".zshrc"] = ZSH_SNIPPET
                    elif shell == "bash":
                        want[".bashrc"] = BASH_SNIPPET
                        # SSH 로그인 셸은 ~/.bash_profile 만 읽는 경우가 있음 (.bashrc 를 안 불러오면 거기에도 넣음)
                        profile = texts[".bash_profile"]
                        if profile is not None:
                            rest = strip_block(profile) if SHELL_MARK_BEGIN in profile else profile
                            if ".bashrc" not in rest:
                                want[".bash_profile"] = BASH_SNIPPET
                    else:
                        return {"error": f"서버 기본 셸({login_shell or '알 수 없음'})은 지원하지 않아요. bash와 zsh만 돼요."}

                changed = False
                for name, text in texts.items():
                    current = text or ""
                    has = SHELL_MARK_BEGIN in current
                    if name in want:
                        if has and want[name] in current:
                            continue  # 이미 최신
                        base = strip_block(current) if has else current
                        if text and not has:
                            backup = posixpath.join(home, name + ".ssh-lite.bak")
                            try:
                                sftp.stat(backup)
                            except FileNotFoundError:
                                with sftp.open(backup, "w") as f:  # 처음 한 번만 원래 파일을 백업
                                    f.write(text.encode("utf-8"))
                        new = base + ("\n" if base and not base.endswith("\n") else "") + ("\n" if base else "") + want[name]
                    elif has:
                        new = strip_block(current)
                    else:
                        continue
                    with sftp.open(posixpath.join(home, name), "w") as f:
                        f.write(new.encode("utf-8"))
                    changed = True
                return {"installed": bool(enable), "added": bool(enable) and changed and not installed}
        except Exception as e:
            return {"error": friendly_error(e)}

    # ------------------------------------------------ 터미널

    # 서버 탭: SSH 연결 하나 안에서 셸 채널을 하나씩 따로 엶 (다시 로그인할 필요 없음)
    # 내 PC 탭: cmd / PowerShell 을 이 PC에서 직접 실행

    def term_open(self, cols, rows, kind="ssh"):
        try:
            if kind == "ssh":
                with self._lock:
                    if not self._connected():
                        return {"error": "서버에 연결되어 있지 않아요."}
                    shell = SshShell(self._client.invoke_shell(term="xterm-256color", width=int(cols),
                                                               height=int(rows)))
            else:
                shell = LocalShell(kind, int(cols), int(rows))
        except Exception as e:
            return {"error": friendly_error(e)}
        tid = self._next_term
        self._next_term += 1
        self._terms[tid] = shell
        threading.Thread(target=self._term_reader, args=(tid, shell, self._session), daemon=True).start()
        return {"ok": True, "id": tid}

    def _term_reader(self, tid, shell, session):
        while True:
            text = shell.read()
            if text is None:
                break
            if text:
                self._emit("term", {"id": tid, "data": text})
        self._terms.pop(tid, None)
        if not shell.remote:
            self._emit("term_closed", {"id": tid})
        elif session == self._session:  # 일부러 연결을 끊은 경우는 알리지 않음
            if self._connected():
                self._emit("term_closed", {"id": tid})
            else:
                self._emit("disconnected", None)

    def term_input(self, tid, data):
        shell = self._terms.get(tid)
        if shell is not None:
            try:
                shell.write(data)
            except Exception:
                pass

    def term_resize(self, tid, cols, rows):
        shell = self._terms.get(tid)
        if shell is not None:
            try:
                shell.resize(int(cols), int(rows))
            except Exception:
                pass

    def term_close(self, tid):
        shell = self._terms.pop(tid, None)
        if shell is not None:
            shell.close()

    # ------------------------------------------------ 파일 목록 / 정리

    def list_dir(self, path):
        try:
            with self._lock:
                sftp = self._sftp
                path = sftp.normalize(path)
                entries = [entry_dict(posixpath.join(path, a.filename), a, sftp) for a in sftp.listdir_attr(path)]
            entries.sort(key=lambda e: (not e["dir"], e["name"].lower()))
            return {"path": path, "entries": entries}
        except Exception as e:
            return {"error": friendly_error(e), "disconnected": not self._connected()}

    def mkdir(self, parent, name):
        return self._simple(lambda s: s.mkdir(posixpath.join(parent, name)))

    def rename(self, path, new_name):
        return self._simple(lambda s: s.rename(path, posixpath.join(posixpath.dirname(path), new_name)))

    def move(self, paths, target_dir):
        def work(s):
            for p in paths:
                dest = posixpath.join(target_dir, posixpath.basename(p))
                if dest != p:
                    s.rename(p, dest)
        return self._simple(work)

    def delete(self, items):
        def remove(s, path, is_dir):
            if is_dir:
                for a in s.listdir_attr(path):
                    remove(s, posixpath.join(path, a.filename), stat.S_ISDIR(a.st_mode or 0))
                s.rmdir(path)
            else:
                s.remove(path)

        def work(s):
            for i in items:
                # 바로가기는 바로가기만 지움 (가리키는 원본 폴더는 건드리지 않음)
                remove(s, i["path"], i["dir"] and not i.get("link"))

        return self._simple(work)

    def existing_names(self, remote_dir, names):
        """업로드 전에 서버에 같은 이름이 있는지 확인"""
        result = self.list_dir(remote_dir)
        have = {e["name"] for e in result.get("entries", [])}
        return [n for n in names if n in have]

    def _simple(self, work):
        try:
            with self._lock:
                work(self._sftp)
            return {"ok": True}
        except Exception as e:
            return {"error": friendly_error(e), "disconnected": not self._connected()}

    # ------------------------------------------------ 내 PC 쪽

    def pick_files(self):
        """[업로드] 버튼: 파일 선택 창"""
        import webview
        result = _window.create_file_dialog(webview.FileDialog.OPEN, allow_multiple=True)
        return list(result or [])

    def pick_folder(self):
        import webview
        result = _window.create_file_dialog(webview.FileDialog.FOLDER, directory=self._cfg["download_dir"])
        if not result:
            return None
        return result[0] if isinstance(result, (list, tuple)) else result

    def basenames(self, paths):
        return [os.path.basename(p.rstrip("\\/")) for p in paths]

    def show_in_explorer(self, path):
        if sys.platform == "win32":
            if os.path.isfile(path):
                subprocess.Popen(["explorer", "/select,", os.path.normpath(path)])
            else:
                os.startfile(path)
        elif sys.platform == "darwin":
            subprocess.Popen(["open", "-R", path])
        else:
            subprocess.Popen(["xdg-open", path if os.path.isdir(path) else os.path.dirname(path)])

    def clipboard_get(self):
        return clipboard_get()

    def clipboard_set(self, text):
        return clipboard_set(str(text))

    def server_stats(self, raw=False):
        """서버 CPU·메모리·디스크 사용량 (맥, Linux). 아주 짧은 명령 하나만 실행.
        못 읽으면 이유와 서버가 보낸 내용 일부를 돌려줌 (상태 줄의 '사용량 ⚠'을 누르면 보임).
        raw=True 면 성공해도 서버가 보낸 내용을 같이 돌려줌 (⋯ 메뉴의 '서버 사용량 지금 확인')"""
        client = self._client
        if not self._connected():
            return {"error": "서버에 연결되어 있지 않아요."}
        text = err = ""
        try:
            _, out, errs = client.exec_command(STATS_CMD, timeout=8)
            text = out.read().decode("utf-8", "replace")
            err = errs.read().decode("utf-8", "replace")
            stats, self._stats_prev = parse_stats(text, self._stats_prev, time.monotonic())
            if raw:
                stats["detail"] = "\n".join((text + ("\n[stderr]\n" + err if err.strip() else "")).strip().splitlines()[:40])
            return stats
        except Exception as e:
            detail = (text + ("\n[stderr]\n" + err if err.strip() else "")).strip()
            return {"error": friendly_error(e), "detail": "\n".join(detail.splitlines()[:40])}

    def open_url(self, url):
        """터미널에서 Ctrl+클릭한 주소를 브라우저로 열기"""
        if re.match(r"^https?://", str(url)):
            webbrowser.open(url)

    # ------------------------------------------------ 서버 파일을 내 PC 프로그램으로 바로 편집
    # 임시 폴더에 받아서 기본 프로그램으로 열고, 1초마다 확인해서 저장되면 서버에 다시 올림

    def open_remote(self, remote, choose=False):
        try:
            parts = [safe_local_name(p) for p in remote.strip("/").split("/")]
            local = os.path.join(EDIT_DIR, safe_local_name(self._cfg["host"]), *parts)
            info = self._edits.get(local)
            pending = info and os.path.exists(local) and os.stat(local).st_mtime_ns != info["mtime"]
            if not pending:  # 아직 못 올린 수정이 있으면 덮어쓰지 않고 그대로 엶
                os.makedirs(os.path.dirname(local), exist_ok=True)
                with self._lock:
                    self._sftp.get(remote, local)
                st = os.stat(local)
                self._edits[local] = {"remote": remote, "mtime": st.st_mtime_ns, "size": st.st_size}
            self._open_file(local, choose)
            return {"ok": True, "local": local}
        except Exception as e:
            return {"error": friendly_error(e), "disconnected": not self._connected()}

    def _edit_watcher(self):
        while True:
            time.sleep(1)
            for local, info in list(self._edits.items()):
                try:
                    st = os.stat(local)
                except FileNotFoundError:
                    self._edits.pop(local, None)
                    continue
                if (st.st_mtime_ns, st.st_size) == (info["mtime"], info["size"]):
                    continue
                name = posixpath.basename(info["remote"])
                if not self._connected():
                    if not info.get("waiting"):  # 연결되면 그때 올림
                        info["waiting"] = True
                        self._emit("edit_waiting", {"name": name})
                    continue
                time.sleep(0.3)  # 아직 저장 중일 수 있으니 잠깐 기다렸다가 그대로인지 확인
                st2 = os.stat(local)
                if (st2.st_mtime_ns, st2.st_size) != (st.st_mtime_ns, st.st_size):
                    continue
                info.update(mtime=st2.st_mtime_ns, size=st2.st_size, waiting=False)
                try:
                    with self._lock:
                        self._sftp.put(local, info["remote"])
                    self._emit("edit_saved", {"name": name, "remote": info["remote"]})
                except Exception as e:
                    self._emit("edit_error", {"name": name, "error": friendly_error(e)})

    # ------------------------------------------------ 업로드 / 다운로드 (대기열에서 하나씩)

    def upload(self, local_paths, remote_dir):
        self._jobs.put(("upload", list(local_paths), remote_dir, self._session))
        return {"queued": self._jobs.qsize()}

    def download(self, items, local_dir=None):
        if local_dir:
            self._cfg["download_dir"] = local_dir
            save_config(self._cfg)
        self._jobs.put(("download", list(items), local_dir or self._cfg["download_dir"], self._session))
        return {"queued": self._jobs.qsize()}

    def cancel_transfers(self):
        self._cancel.set()
        while not self._jobs.empty():
            try:
                self._jobs.get_nowait()
            except queue.Empty:
                break

    def _transfer_worker(self):
        sftp, sftp_session = None, None
        while True:
            kind, items, dest, session = self._jobs.get()
            if session != self._session:
                continue  # 그새 연결이 바뀐 작업은 버림
            self._cancel.clear()
            self._transferring = True
            try:
                if sftp_session != session:  # 전송은 목록 보기와 따로 SFTP 채널을 하나 더 씀
                    with self._lock:
                        sftp, sftp_session = self._client.open_sftp(), session
                if kind == "upload":
                    count = self._do_upload(sftp, items, dest)
                    self._emit("transfer_done", {"kind": kind, "count": count, "dir": dest})
                else:
                    count, first = self._do_download(sftp, items, dest)
                    self._emit("transfer_done", {"kind": kind, "count": count, "dir": dest, "first": first})
            except Cancelled:
                self._emit("transfer_cancelled", {"kind": kind, "dir": dest})
            except Exception as e:
                self._emit("transfer_error", {"kind": kind, "dir": dest, "error": friendly_error(e),
                                              "disconnected": not self._connected()})
            finally:
                self._transferring = False

    def _progress(self, kind, label, index, total, base, job_total):
        """base: 이번 전송에서 앞 파일들로 이미 보낸 양, job_total: 이번 전송 전체 크기 (속도·남은 시간 계산용)"""
        last = [0.0]

        def callback(sent, size):
            if self._cancel.is_set():
                raise Cancelled()
            now = time.monotonic()
            if now - last[0] < 0.1 and sent < size:
                return
            last[0] = now
            self._emit("transfer", {"kind": kind, "label": label, "index": index, "total": total, "sent": sent, "size": size,
                                    "done": base + sent, "all": max(job_total, base + sent), "queued": self._jobs.qsize()})

        return callback

    def _do_upload(self, sftp, local_paths, remote_dir):
        files, dirs = [], []  # 폴더는 안의 내용까지 통째로

        def collect(local, remote_parent):
            remote = posixpath.join(remote_parent, os.path.basename(local.rstrip("\\/")))
            if os.path.isdir(local):
                dirs.append(remote)
                for child in sorted(os.listdir(local)):
                    collect(os.path.join(local, child), remote)
            else:
                files.append((local, remote, os.path.getsize(local)))

        for p in local_paths:
            collect(p, remote_dir)
        for d in dirs:
            try:
                sftp.stat(d)
            except FileNotFoundError:
                sftp.mkdir(d)
        job_total, base = sum(f[2] for f in files), 0
        for i, (local, remote, size) in enumerate(files, 1):
            if self._cancel.is_set():
                raise Cancelled()
            try:
                sftp.put(local, remote, callback=self._progress("upload", os.path.basename(local), i, len(files),
                                                                base, job_total))
                base += size
            except Cancelled:
                sftp.remove(remote)  # 덜 올라간 파일 정리
                raise
        return len(files)

    def _do_download(self, sftp, items, local_dir):
        files = []
        top_paths = []

        def collect(remote, local, is_dir, size=None):
            if not is_dir:
                files.append((remote, local, sftp.stat(remote).st_size if size is None else size))
                return
            os.makedirs(local, exist_ok=True)
            for a in sftp.listdir_attr(remote):
                child = posixpath.join(remote, a.filename)
                mode, size = a.st_mode or 0, a.st_size or 0
                if stat.S_ISLNK(mode):
                    try:
                        st = sftp.stat(child)
                    except OSError:
                        continue  # 깨진 바로가기
                    mode, size = st.st_mode, st.st_size
                    if stat.S_ISDIR(mode):
                        continue  # 폴더 안의 폴더 바로가기는 건너뜀 (무한 반복 방지)
                collect(child, os.path.join(local, a.filename), stat.S_ISDIR(mode), size)

        os.makedirs(local_dir, exist_ok=True)
        for item in items:
            local = unique_local_path(os.path.join(local_dir, posixpath.basename(item["path"])))
            top_paths.append(local)
            collect(item["path"], local, item["dir"])
        job_total, base = sum(f[2] for f in files), 0
        for i, (remote, local, size) in enumerate(files, 1):
            if self._cancel.is_set():
                raise Cancelled()
            try:
                sftp.get(remote, local, callback=self._progress("download", posixpath.basename(remote), i, len(files),
                                                                base, job_total))
                base += size
            except Cancelled:
                os.remove(local)  # 덜 받은 파일 정리
                raise
        return len(files), (top_paths[0] if len(top_paths) == 1 else local_dir)

    # ------------------------------------------------ 탐색기에서 끌어다 놓기

    def _on_drop(self, event):
        files = (event.get("dataTransfer") or {}).get("files") or []
        paths = [f["pywebviewFullPath"] for f in files if f.get("pywebviewFullPath")]
        if paths:
            self._emit("dropped", {"paths": paths, "names": self.basenames(paths)})

    def _pending(self):
        """창을 닫으면 사라질 일: 전송 중이거나, 편집한 파일이 아직 서버에 안 올라감"""
        out = []
        if self._transferring or not self._jobs.empty():
            out.append("파일을 주고받는 중이에요.")
        for local, info in list(self._edits.items()):
            try:
                st = os.stat(local)
            except OSError:
                continue
            if info.get("waiting") or (st.st_mtime_ns, st.st_size) != (info["mtime"], info["size"]):
                out.append(f"'{posixpath.basename(info['remote'])}'에서 고친 내용이 아직 서버에 안 올라갔어요.")
        return out

    def _on_closing(self):
        """창 닫기(X): 사라질 일이 있으면 닫지 않고 화면에서 물어봄 (False 를 돌려주면 pywebview 가 닫기를 취소)"""
        if not self._force_close:
            reasons = self._pending()
            if reasons:
                threading.Thread(target=self._emit, args=("close_blocked", {"reasons": reasons}), daemon=True).start()
                return False
        self._shutdown()

    def force_close(self):
        """'그래도 닫기'"""
        self._force_close = True
        if _window is not None:
            _window.destroy()

    def _shutdown(self):
        save_config(self._cfg)
        self.disconnect()
        for shell in list(self._terms.values()):
            shell.close()


# ---------------------------------------------------------------- 창 띄우기

_window = None


def main():
    global _window
    try:
        import webview
        from webview.dom import DOMEventHandler
    except ImportError:
        import ctypes
        ctypes.windll.user32.MessageBoxW(0, "pywebview가 설치되어 있지 않아요.\n\ncmd에서 아래 명령을 실행한 뒤 다시 열어주세요.\n\n"
                                            "pip install paramiko pywebview pywinpty", "SSH Lite", 0x10)
        sys.exit(1)

    def emit(kind, data):
        if _window is not None:
            _window.run_js(f"window.app && app.onEvent({json.dumps({'type': kind, 'data': data})})")

    backend = Backend(emit)
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))  # exe로 묶었을 때도 동작
    _window = webview.create_window(f"SSH Lite {APP_VERSION}", url=os.path.join(base, "ui", "index.html"), js_api=backend,
                                    width=1280, height=800, min_size=(560, 380), background_color="#0E1014")

    def on_loaded():
        if sys.platform == "win32":
            try:
                try:
                    hwnd = int(_window.native.Handle.ToInt64())
                except Exception:
                    import ctypes
                    hwnd = ctypes.windll.user32.FindWindowW(None, _window.title)
                if hwnd:
                    fit_window_to_screen(hwnd)
            except Exception:
                pass
        # 탐색기에서 떨어뜨린 파일의 실제 경로는 파이썬 쪽 drop 이벤트로만 받을 수 있음
        _window.dom.document.events.drop += DOMEventHandler(backend._on_drop, prevent_default=True)

    _window.events.loaded += on_loaded
    _window.events.closing += backend._on_closing
    webview.start(http_server=True)


if __name__ == "__main__":
    main()
