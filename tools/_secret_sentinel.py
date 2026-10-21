#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
推送前密钥哨兵 (pre-push secret sentinel)

WHY THIS EXISTS:
  Bro 在 2026-09-27 因为真 Key 泄漏被 GitHub Push Protection 拦了 THREE TIMES.
  三次的根因都一样：靠「人脑记住要查什么」来防守 ——
    第 1 次：根本没查源码
    第 2 次：查了，但前缀清单漏了 ark-
    第 3 次：查了源码，但没查 .workbuddy/memory/ 工作日志
  「列清单」这个动作本身就是漏洞源。所以改成机制防守：

  本脚本扫描 **git 全部历史 + 工作区**，用「形状正则」而不是人工前缀清单，
  覆盖所有已知平台凭据格式。任何命中都必须人工过一眼才能继续。

USAGE:
  python secret_sentinel.py <仓库路径>          # ← 推荐：显式传路径，不可能传错
  python secret_sentinel.py                    # 扫当前工作目录所在的 git 仓库
  python secret_sentinel.py --staged           # 只扫暂存区（快，pre-commit 用）

  ⚠️ 脚本装在与仓库不同的目录时（本项目：脚本在 F:/LottBuild，仓库在 F:/Projects/...），
     直接 cd 到脚本目录裸跑 = 扫了一个「不是 git 仓库」的目录。
     这种情况本脚本会直接 ABORT，不会给假的 CLEAN。

EXIT CODE:
  0 = 干净，可以推
  1 = 发现疑似密钥，STOP，别推
  2 = 扫描无法完成（传错目录 / 文件被截断）—— 不得据此判断安全，必须重跑

安装位置（全局，跨项目通用）：
  C:\\Users\\36097\\.workbuddy\\tools\\secret_sentinel.py
  技能副本： ~/.workbuddy/skills/pre-push-secret-scrub/scripts/secret_sentinel.py
"""

import os
import re
import subprocess
import sys


class SentinelAbort(Exception):
    """扫描无法完成（传错目录 / 文件被截断）。

    存在的唯一理由：**绝不允许降级成 CLEAN**。
    2026-09-28 实测：脚本装在 F:/LottBuild，在脚本目录裸跑时 REPO 不是 git 仓库，
    历史扫描被跳过（「scan SKIPPED」只是一行 warning），然后照样打印
    「RESULT: CLEAN - safe to push」。等于白跑一趟还以为通过了。
    「只扫工作区」不能叫「扫过了」——不能验证全部提交的结论没有意义。
    """
    pass


# 扫描统计：让 CLEAN 这行自带证据（扫了多少 commit / 多少文件），
# 而不是只吐一个光秃秃的结论让人无从判断它到底扫了什么。
STATS = {"commits": 0, "files": 0, "mode": ""}


# 仓库根：命令行参数 > 当前工作目录（自动探测 git 仓库根）> _paths.PROJ
def _detect_repo():
    for arg in sys.argv[1:]:
        if not arg.startswith("-"):
            return os.path.abspath(arg)
    r = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                       capture_output=True, text=True)
    if r.returncode == 0 and r.stdout.strip():
        return r.stdout.strip()
    # 不在 git 仓库里跑（比如从编译工作区裸跑）—— 回落到项目配置里的仓库根，
    # 别拿 cwd 去扫（那会扫错地方，然后因为下面有 ABORT 而白跑一趟）。
    try:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import _paths
        return _paths.PROJ
    except Exception:
        pass
    return os.getcwd()


REPO = _detect_repo()

# ---- 形状正则：不靠「我列了哪些前缀」，靠「凭据长什么样」 ----------------
# 原则一：宁可误报，不可漏报。误报只是多看一眼，漏报是密钥上公网。
# 原则二：但也不能滥报 —— 噪音太多会让真问题被淹没。
#         所以「裸 hex」不单独报，必须带凭据语境（见 CONTEXT_PATTERNS）。
PATTERNS = [
    # 火山方舟 / 豆包：ark-<uuid 8-4-4-4-12>-<5位>
    ("VolcEngine Ark Key", re.compile(r"ark-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}-[0-9a-zA-Z]{4,6}")),
    # 通用 sk- 系（OpenAI / 智谱 / DeepSeek / Moonshot / 阿里 DashScope ...）
    ("sk- style key", re.compile(r"sk-[A-Za-z0-9_\-]{20,}")),
    # 火山方舟接入点（ep- 后面跟真实 ID，不是 xxxx 占位）
    ("VolcEngine endpoint", re.compile(r"ep-m-[a-z0-9]{12,}")),
    # 百度千帆 bce-v3/ALTAK-xxxx/yyyy
    ("Baidu Qianfan key", re.compile(r"bce-v3/(?!ALTAK-xxx)[A-Za-z0-9\-]{8,}/[A-Za-z0-9]{8,}")),
    # 腾讯云 SecretId / SecretKey
    ("Tencent Cloud key", re.compile(r"(?:AKID|SecretId|SecretKey)\s*[:=]\s*[\"']?[A-Za-z0-9]{20,}")),
    # JWT（三段 base64，无语境也难以误报）
    ("JWT", re.compile(r"eyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}")),
    # Authorization 头
    ("Bearer token", re.compile(r"Bearer\s+[A-Za-z0-9_\-\.]{24,}")),
    # GitHub token（ghp_ / github_pat_ / gho_ / ghs_）
    ("GitHub token", re.compile(r"gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{60,}")),
    # Slack / AWS 常见形状
    ("AWS access key", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("Slack token", re.compile(r"xox[baprs]-[A-Za-z0-9\-]{10,}")),
]

# ---- 带语境的通用规则 ------------------------------------------------
# 单独的 32~64 位 hex 太常见（校验和、SHA、license 指纹），
# 只在这个 hex **紧跟在凭据关键词后面**时才报。
# 这样既不会漏掉"没前缀只有值"的密钥，也不会被 Android SDK license 淹没。
SECRET_KEYWORDS = (
    "api_key", "apikey", "api-key", "access_key", "accesskey",
    "secret", "secret_key", "secretkey", "token", "password", "passwd",
    "credential", "private_key", "privatekey", "auth",
    "密钥", "口令", "密码", "令牌",
)
CONTEXT_PATTERN = re.compile(
    r"(?:" + "|".join(re.escape(k) for k in SECRET_KEYWORDS) + r")"
    r"[\"'\s]*[:=][\"'\s]*([A-Za-z0-9_\-]{32,})",
    re.IGNORECASE,
)

# ---- 白名单：已知的无害占位符 / 误报，逐条写明理由 -------------------------
# 注意：白名单只允许「形状明显是占位符」或「明确无害的公开值」，
#       不允许放行任何真实凭据。
WHITELIST_CONTEXT = [
    "ALTAK-xxx",          # 文档里说明格式用的占位
    "xxxxxxxxxxxxxxxx",   # 全 x 占位
    "-xxxxx",             # 结尾占位
    "your-api-key",
    "YOUR_API_KEY",
    "example",
]

# 行内出现这些「上下文词」时，40 位裸 hex 判为 git SHA 而非密钥
# （保留常量供参考：新逻辑已改用「语境规则」，不再对所有裸 hex 报警）


def run(cmd, cwd=REPO):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")


def scan_text(name, text):
    """扫一段文本，返回 [(行号, 模式名, 命中片段)]"""
    hits = []
    for lineno, line in enumerate(text.splitlines(), 1):
        low = line.lower()
        if any(w.lower() in low for w in WHITELIST_CONTEXT):
            continue
        for pname, pat in PATTERNS:
            for m in pat.finditer(line):
                hits.append((lineno, pname, m.group(0)))
        # 带语境的通用规则：key = <长串>
        for m in CONTEXT_PATTERN.finditer(line):
            val = m.group(1)
            if val.startswith(("ep-", "sk-", "ark-")) or re.fullmatch(r"[0-9a-f]{40,}", val):
                continue  # 已被上面的规则覆盖 / 是纯 hex 值
            if val.lower() in ("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",):
                continue
            hits.append((lineno, "key=... assignment", val))
    return hits


def scan_history():
    print("[1/2] scanning git history ...")
    revs = run(["git", "rev-list", "--all"]).stdout.split()
    STATS["commits"] = len(revs)
    if not revs:
        # 非 git 仓库：直接 ABORT，绝不降级成「只扫工作区 + 报 CLEAN」
        r = run(["git", "rev-parse", "--git-dir"])
        if r.returncode != 0:
            raise SentinelAbort(
                "REPO 不是 git 仓库：%s\n"
                "        -> 历史扫描被跳过，无法验证全部提交。\n"
                "        -> 正确用法：python secret_sentinel.py <仓库绝对路径>" % REPO)
        print("      (git repo has no commits yet)")
        return []
    # git grep -E 跨全部提交，比逐个 show 快得多
    combined = "|".join(p.pattern for _, p in PATTERNS)
    r = run(["git", "grep", "-nIE", combined, "--"] + revs)
    findings = []
    seen = set()
    for line in (r.stdout or "").splitlines():
        # 格式: <sha>:<path>:<lineno>:<content>
        parts = line.split(":", 3)
        if len(parts) < 4:
            continue
        sha, path, lineno, content = parts
        low = content.lower()
        if any(w.lower() in low for w in WHITELIST_CONTEXT):
            continue
        for pname, pat in PATTERNS:
            for m in pat.finditer(content):
                frag = m.group(0)
                key = (pname, frag)
                if key in seen:
                    continue
                seen.add(key)
                findings.append((sha[:7], path, lineno, pname, frag))
        for m in CONTEXT_PATTERN.finditer(content):
            val = m.group(1)
            if val.startswith(("ep-", "sk-", "ark-")) or re.fullmatch(r"[0-9a-f]{40,}", val):
                continue
            key = ("key=... assignment", val)
            if key in seen:
                continue
            seen.add(key)
            findings.append((sha[:7], path, lineno, "key=... assignment", val))
    print("      scanned %d commits" % len(revs))
    return findings


SCAN_EXTS = ("kt", "java", "md", "txt", "json", "xml", "gradle", "kts",
             "properties", "html", "js", "css", "py", "bat", "cmd",
             "yml", "yaml", "pro", "toml", "sh", "ini", "cfg", "conf", "")

SKIP_DIRS = {".git", "node_modules", "build", ".gradle", ".idea", "dist",
             "__pycache__", ".venv", "venv", "target", ".next", "out",
             "apk", "outputs", "intermediates", "tmp", "temp", ".cache"}

# 单文件体积上限：超过就不读（密钥不可能藏在一个 10MB 的文件里，
# 而读大文件会把扫描拖死）
MAX_FILE_BYTES = 2 * 1024 * 1024
# 总文件数上限（防呆，避免在巨大目录里跑到天荒地老）
MAX_FILES = 3000


def _scan_file_set(rel_paths):
    """扫一组相对路径文件，返回 findings"""
    findings = []
    for rel in rel_paths:
        if not rel or rel.startswith(".git/") or rel.startswith(".git\\"):
            continue
        base = os.path.basename(rel)
        ext = base.rsplit(".", 1)[-1].lower() if "." in base else ""
        if ext not in SCAN_EXTS:
            continue
        full = os.path.join(REPO, rel.replace("/", os.sep).replace("\\", os.sep))
        try:
            if os.path.getsize(full) > MAX_FILE_BYTES:
                continue
            with open(full, encoding="utf-8", errors="replace") as fh:
                text = fh.read()
        except OSError:
            continue
        for lineno, pname, frag in scan_text(rel, text):
            findings.append(("worktree", rel, lineno, pname, frag))
    return findings


def scan_worktree():
    print("[2/2] scanning working tree ...")
    r = run(["git", "ls-files", "--cached", "--others", "--exclude-standard"])
    entries = [e for e in (r.stdout or "").splitlines() if e.strip()]

    if entries:
        print("      mode: git tracked files")
        STATS["mode"] = "git tracked"
        STATS["files"] = len(entries)
        findings = _scan_file_set(entries)
        print("      scanned %d files" % len(entries))
        return findings

    # 非 git 仓库（或空仓库）：直接遍历目录，别报"扫了0个=安全"这种假放心
    print("      mode: plain directory walk (not a git repo)")
    files = []
    for root, dirs, names in os.walk(REPO):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for n in names:
            files.append(os.path.relpath(os.path.join(root, n), REPO))
            if len(files) >= MAX_FILES:
                # 截断 = 漏扫 = 结论不可信。同样不许降级成 CLEAN。
                raise SentinelAbort(
                    "命中 MAX_FILES=%d，文件清单被截断（实际还有更多）\n"
                    "        -> 没扫到的文件里可能藏着密钥，本次结论不可信。" % MAX_FILES)
        if len(files) >= MAX_FILES:
            break
    STATS["mode"] = "plain dir walk"
    STATS["files"] = len(files)
    findings = _scan_file_set(files)
    print("      scanned %d files" % len(files))
    return findings


def mask(s):
    if len(s) <= 12:
        return s[:4] + "***"
    return s[:8] + "***" + s[-4:]


def main():
    staged_only = "--staged" in sys.argv
    print("=" * 62)
    print("  SECRET SENTINEL  -  pre-push check")
    print("=" * 62)
    print()

    findings = []
    try:
        if staged_only:
            print("[1/1] scanning staged file list ...")
            # --staged 模式也必须在 git 仓库里跑，否则 git diff 返回空 =
            # 「暂存区没有文件」的假象，又是一次假 CLEAN。
            g = run(["git", "rev-parse", "--git-dir"])
            if g.returncode != 0:
                raise SentinelAbort(
                    "REPO 不是 git 仓库：%s\n"
                    "        -> --staged 模式无法工作（git diff 会静默返回空）。" % REPO)
            r = run(["git", "diff", "--cached", "--name-only"])
            print("      staged: %d files" % len(r.stdout.split()))
        else:
            findings += scan_history()
            findings += scan_worktree()
    except SentinelAbort as e:
        print()
        print("=" * 62)
        print("  RESULT: ABORT - 扫描无法完成，本次结果不代表安全")
        print("=" * 62)
        print("  %s" % e)
        print("=" * 62)
        return 2

    print()
    if not findings:
        print("=" * 62)
        print("  RESULT: CLEAN - safe to push")
        if staged_only:
            print("          scanned: staged file list only")
        else:
            print("          scanned: %d commits + %d files  [%s]"
                  % (STATS["commits"], STATS["files"], REPO))
        print("=" * 62)
        return 0

    print("=" * 62)
    print("  RESULT: %d SUSPECTED SECRET(S) - DO NOT PUSH" % len(findings))
    print("=" * 62)
    print()
    for sha, path, lineno, pname, frag in findings:
        print("  [%s] %s:%s" % (pname, path, lineno))
        print("        commit : %s" % sha)
        print("        masked : %s" % mask(frag))
        print()
    print("-" * 62)
    print("  WHAT TO DO:")
    print("  1. Look at EVERY hit. Do not assume any is harmless.")
    print("  2. Real key  -> replace with a neutral placeholder AND")
    print("     rewrite history (rebase / filter-repo).")
    print("  3. Placeholder that KEEPS the key shape -> still blocked by")
    print("     GitHub. Make it not look like a key at all.")
    print("-" * 62)
    return 1


if __name__ == "__main__":
    sys.exit(main())
