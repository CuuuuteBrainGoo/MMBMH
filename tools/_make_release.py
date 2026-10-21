# -*- coding: utf-8 -*-
"""为当前版本创建 GitHub Release，并把两个 APK 作为附件传上去。

## 为什么必须单独做这步

README 顶部的版本徽章读的是 **GitHub Release**，不是仓库里的文件。
「代码 push 了」不等于「Release 存在」—— 只 push 不建 Release，
徽章会一直显示旧版本（2026-09-28 踩过）。

## 安全要求（踩过两次泄漏，绝不重犯）

  - token 从 git credential 现取，**绝不写进文件、绝不打印**
  - 只打印 HTTP 状态码和 Release 元数据，不打印任何凭据

## 用法

    python _make_release.py                      # 发布说明用默认文案
    python _make_release.py --notes "本次改动…"   # 自定义公布说明
    python _make_release.py --notes-file notes.md

重复跑是安全的：Release 已存在会复用，同名附件会跳过。
"""
import io
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

from _paths import PROJ, APK_DIR, VERSION_FILE, REPO_OWNER, REPO_NAME

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

OWNER = REPO_OWNER
REPO = REPO_NAME


def version_name():
    with io.open(VERSION_FILE, encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if s.startswith("versionName="):
                return s.split("=", 1)[1].strip()
    raise SystemExit("读不到 versionName")


def read_token():
    p = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True, cwd=PROJ)
    for line in p.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    return None


def notes():
    argv = sys.argv[1:]
    for i, a in enumerate(argv):
        if a == "--notes" and i + 1 < len(argv):
            return argv[i + 1]
        if a == "--notes-file" and i + 1 < len(argv):
            with io.open(argv[i + 1], encoding="utf-8") as f:
                return f.read()
    return "下载 `release` 版 APK 安装即可。"


VER = version_name()
TAG = "v" + VER
TOK = read_token()
if not TOK:
    raise SystemExit("拿不到凭据 —— 先在仓库里配好 git credential（见 SETUP.md 第 5 步）")
print("准备发布 %s（token 已取得，长度 %d，不打印内容）" % (TAG, len(TOK)))


def req(url, method="GET", payload=None, ctype="application/json"):
    r = urllib.request.Request(url, method=method)
    r.add_header("Authorization", "Bearer " + TOK)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("User-Agent", "lottery-ledger-release")
    body = None
    if payload is not None:
        r.add_header("Content-Type", ctype)
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
    try:
        with urllib.request.urlopen(r, body) as resp:
            txt = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(txt) if txt.strip().startswith(("{", "[")) else txt)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:400]


api = "https://api.github.com/repos/%s/%s" % (OWNER, REPO)

# ---- 1. 建 Release（已存在就复用）----
code, res = req(api + "/releases", "POST", {
    "tag_name": TAG,
    "name": "%s 彩票账本" % TAG,
    "body": notes(),
    "draft": False,
    "prerelease": False,
})
if code == 201:
    rel = res
    print("[1] Release 已创建  id=%s" % rel["id"])
elif code == 422:
    code2, rel = req(api + "/releases/tags/" + TAG)
    if code2 != 200:
        raise SystemExit("Release 既建不出来也读不到：%s %s" % (code2, rel))
    print("[1] Release 已存在，复用  id=%s" % rel["id"])
else:
    raise SystemExit("建 Release 失败：%s %s" % (code, res))

rel_id = rel["id"]

# ---- 2. 传附件 ----
existing = {a["name"] for a in rel.get("assets", [])}
for variant in ("debug", "release"):
    name = "LotteryLedger-v%s-%s-signed.apk" % (VER, variant)
    path = os.path.join(APK_DIR, name)
    if not os.path.exists(path):
        print("[2] 跳过（文件不存在）：%s" % name)
        continue
    if name in existing:
        print("[2] 已有同名附件，跳过：%s" % name)
        continue
    with open(path, "rb") as fh:
        blob = fh.read()
    # 大文件必须走 uploads.github.com，走 api.github.com 会失败
    up = "https://uploads.github.com/repos/%s/%s/releases/%d/assets?name=%s" % (
        OWNER, REPO, rel_id, name)
    code, res = req(up, "POST", blob, "application/vnd.android.package-archive")
    if code == 201:
        print("[2] 已上传 %s  (%.2f MB)" % (name, len(blob) / 1048576))
    else:
        print("[2] 上传失败 %s：%s %s" % (name, code, res))

# ---- 3. 验证：徽章读的就是这个接口 ----
code, latest = req(api + "/releases/latest")
if code == 200:
    print("[3] /releases/latest -> tag=%s  name=%s" % (latest.get("tag_name"), latest.get("name")))
    print("    徽章会显示：release: %s" % latest.get("tag_name"))
else:
    print("[3] 查 latest 失败：%s %s" % (code, latest))
