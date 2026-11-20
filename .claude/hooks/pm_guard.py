"""PM harness hooks. Usage: pm_guard.py session | pretool  (reads hook JSON on stdin)."""
import json, os, re, subprocess, sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CL = REPO / ".claude"
BOOT = ["ProjectManager/PROJECT_SUMMARY.md",  # CLAUDE.md is auto-loaded by Claude Code "ProjectManager/CODING_STYLE.md",
        "ProjectManager/PREFERENCES.md", "PROJECT_STATUS.md", "TASKS.md", "ISSUES.md",
        "ProjectManager/DECISIONS.md", "ProjectManager/GLOSSARY.md"]
SECRET_FILE = re.compile(r"(^|[/\\])(\.env(\..*)?|credentials\.[^/\\]*|[^/\\]*\.(key|pem|p12|pfx|jks|keystore))$", re.I)
SECRET_TEXT = re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY|(api[_-]?key|secret|token|passw(or)?d)\s*[:=]\s*['\"][^'\"\s]{8,}", re.I)


def block(msg):
    print(msg, file=sys.stderr)
    sys.exit(2)


def session():
    sys.stdout.reconfigure(encoding="utf-8")
    for rel in BOOT:
        p = CL / rel
        if p.exists():
            print(f"===== {p.name} =====\n{p.read_text(encoding='utf-8')}\n")


def git(*a):
    return subprocess.run(["git", "-C", str(REPO), *a], capture_output=True, text=True, encoding="utf-8").stdout


def pre_commit(cmd):
    if re.search(r"co-authored-by", cmd, re.I):
        block("Commit message contains Co-Authored-By. User rule: no tool attribution in commits.")
    staged = [f for f in git("diff", "--cached", "--name-only").splitlines() if f]
    bad = [f for f in staged if SECRET_FILE.search(f)]
    added = "\n".join(l for l in git("diff", "--cached", "-U0").splitlines() if l.startswith("+"))
    if bad or SECRET_TEXT.search(added):
        block(f"Possible secret staged ({', '.join(bad) or 'secret-like text in diff'}). Unstage it or get explicit user approval.")
    m = re.search(r"^\s*mod_version\s*=\s*(\S+)", (REPO / "gradle.properties").read_text(encoding="utf-8"), re.M)
    ver, last = (m.group(1) if m else "?"), CL / "ProjectManager" / ".last_version"
    old = last.read_text().strip() if last.exists() else ver
    last.write_text(ver)
    if old != ver:  # blocks once; the retry passes after the PM has asked the user
        block(f"Version changed from {old} to {ver}. Ask the user: roll out as a new version (release workflow)?")


def pretool():
    d = json.load(sys.stdin)
    tool, inp = d.get("tool_name", ""), d.get("tool_input", {})
    if tool in ("Write", "Edit", "NotebookEdit"):
        path = inp.get("file_path") or inp.get("notebook_path") or ""
        if SECRET_FILE.search(path):
            block(f"Writing {path} is blocked (secrets policy). Needs explicit user authorization.")
    elif tool in ("Bash", "PowerShell") and re.search(r"\bgit\b[^|;&]*\bcommit\b", inp.get("command", "")):
        pre_commit(inp["command"])


if __name__ == "__main__":
    {"session": session, "pretool": pretool}[sys.argv[1]]()
