# -*- coding: utf-8 -*-
"""
发布一致性守卫 —— 检查「版本号」在 4 个地方是否一致。

背景：versionCode 停在 1.1.0、.gitignore 放行 v1.5.0、README 写 v1.5.0 ——
同一个病根：**版本号散落在多个文件，改了一处漏了别处，没人检查**。
所以这里用机制代替记性。

用法：
    python _release_guard.py [仓库根]
返回码 0 = 全部一致；1 = 有不一致。
"""
import io
import os
import re
import sys

from _paths import PROJ

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

REPO = sys.argv[1] if len(sys.argv) > 1 else PROJ


def read(*parts):
    p = os.path.join(REPO, *parts)
    with io.open(p, "r", encoding="utf-8") as f:
        return f.read()


def main():
    # ---- 权威版本号 ----
    vp = read("LotteryLedger", "version.properties")
    m_name = re.search(r"^\s*versionName\s*=\s*(.+)$", vp, re.M)
    m_code = re.search(r"^\s*versionCode\s*=\s*(\d+)\s*$", vp, re.M)
    if not m_name or not m_code:
        print("[X] version.properties 里读不到 versionName / versionCode")
        return 1
    ver = m_name.group(1).strip()
    code = m_code.group(1).strip()

    print("=" * 56)
    print("  RELEASE GUARD  -  version consistency")
    print("=" * 56)
    print("  权威值：versionName=%s  versionCode=%s" % (ver, code))
    print("-" * 56)

    fails = []

    # ---- [1] .gitignore 放行版本 ----
    gi = read(".gitignore")
    allowed = sorted(set(re.findall(
        r"!apk/LotteryLedger-v([0-9.]+)-(?:debug|release)-signed\.apk", gi)))
    ok1 = allowed == [ver]
    print("  [1] .gitignore 放行版本 %s : %s" % ("OK" if ok1 else "FAIL", allowed or "（无）"))
    if not ok1:
        fails.append(".gitignore 放行的是 %s，应为 ['%s']（否则推上去的是旧包）"
                     % (allowed or "空", ver))

    # ---- [2] README 下载表里的 APK 名 ----
    rm = read("README.md")
    readme_vers = sorted(set(
        re.findall(r"LotteryLedger-v([0-9.]+)-(?:debug|release)-signed\.apk", rm)))
    ok2 = (not readme_vers) or readme_vers == [ver]
    print("  [2] README 提到的 APK 版本 %s : %s" % ("OK" if ok2 else "FAIL",
                                                 readme_vers or "（无）"))
    if not ok2:
        fails.append("README 提到 %s，应为 ['%s']" % (readme_vers, ver))

    # ---- [3] README 的 version.properties 示例块 ----
    blk = re.search(r"versionCode\s*=\s*(\d+)\s*\n\s*versionName\s*=\s*([0-9.]+)", rm)
    if blk:
        ok3 = (blk.group(1) == code and blk.group(2) == ver)
        print("  [3] README 示例块 %s : %s / %s"
              % ("OK" if ok3 else "FAIL", blk.group(1), blk.group(2)))
        if not ok3:
            fails.append("README 示例块是 %s/%s，应为 %s/%s"
                         % (blk.group(1), blk.group(2), code, ver))
    else:
        print("  [3] README 示例块 SKIP : 未找到")

    # ---- [4] 验收清单标题 + 安装路径 ----
    cl = read("apk", "真机验收清单.md")
    t = re.search(r"^#\s+.*?v([0-9.]+)\s*真机验收清单", cl, re.M)
    cl_vers = sorted(set(re.findall(r"LotteryLedger-v([0-9.]+)-(?:debug|release)-signed\.apk", cl)))
    ok4 = (t and t.group(1) == ver) and ((not cl_vers) or cl_vers == [ver])
    print("  [4] 验收清单 %s : 标题=%s 路径版本=%s"
          % ("OK" if ok4 else "FAIL", t.group(1) if t else "?", cl_vers or "（无）"))
    if not ok4:
        fails.append("验收清单 标题=%s/路径版本=%s，应为 %s"
                     % (t.group(1) if t else "?", cl_vers, ver))

    print("-" * 56)
    if fails:
        print("  RESULT: FAIL - 发现不一致，修完再推：")
        for x in fails:
            print("     - %s" % x)
        print("=" * 56)
        return 1

    print("  RESULT: PASS - 4 处版本号全部一致")
    print("=" * 56)
    return 0


if __name__ == "__main__":
    sys.exit(main())
