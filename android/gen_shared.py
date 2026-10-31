"""PC 앱(ssh_lite.pyw)과 폰 앱이 똑같이 쓰는 서버 명령·셸 설정을 assets/shared.json 으로 꺼냄 (두 곳을 따로 고치지 않게)"""
import importlib.machinery
import importlib.util
import json
import os
import sys

here = os.path.dirname(os.path.abspath(__file__))
loader = importlib.machinery.SourceFileLoader("ssh_lite", os.path.join(here, "..", "ssh_lite.pyw"))
spec = importlib.util.spec_from_loader("ssh_lite", loader)
m = importlib.util.module_from_spec(spec)
loader.exec_module(m)
out = sys.argv[1]
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w", encoding="utf-8") as f:
    json.dump({"mark_begin": m.SHELL_MARK_BEGIN, "mark_end": m.SHELL_MARK_END, "bash": m.BASH_SNIPPET,
               "zsh": m.ZSH_SNIPPET, "stats_cmd": m.STATS_CMD}, f, ensure_ascii=False, indent=1)
