# -*- coding: utf-8 -*-
"""环境体检 —— 换机器 / 重装系统后**第一件事**跑这个。

    python tools/doctor.py

它会逐项检查：仓库结构、编译工具链、签名密钥（含指纹比对）、git 远端。
全绿 = 可以直接 `python tools/_build_and_test.py`。

**为什么密钥要比对指纹**：
Android 只认真实签名。换了一把新密钥打出来的包**装不上旧包**
（只能卸载重装，账本数据全丢）。而"文件存在"不代表"是那一把" ——
所以这里比 SHA1，不放心的文件当场挑出来。
"""
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import _paths as P  # noqa: E402

OK = "  [OK]   "
NO = "  [X]    "
WARN = "  [!]    "

fails = 0
warns = 0


def line(mark, name, detail=""):
    global fails, warns
    if mark is NO:
        fails += 1
    elif mark is WARN:
        warns += 1
    print("%s%-22s %s" % (mark, name, detail))


def path_item(name, path):
    if os.path.exists(path):
        line(OK, name, path)
        return True
    line(NO, name, "缺失 -> " + path)
    return False


def keystore_sha1():
    keytool = os.path.join(P.JDK, "bin", "keytool.exe")
    if not os.path.exists(keytool):
        return None, "找不到 keytool（JDK 没装好？）"
    r = subprocess.run(
        [keytool, "-list", "-v", "-keystore", P.KEYSTORE, "-storepass", P.KS_PASS],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = (r.stdout or "") + (r.stderr or "")
    m = re.search(r"SHA1:\s*([0-9A-Fa-f:]{30,})", out)
    if not m:
        return None, out.strip()[:120]
    return m.group(1).upper(), None


def git(*args):
    r = subprocess.run(["git"] + list(args), cwd=P.PROJ,
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    return (r.stdout or "").strip(), r.returncode


def main():
    print("=" * 70)
    print("  彩票账本 · 环境体检")
    print("=" * 70)

    print("\n-- 解释器 --")
    line(OK, "Python", sys.version.split()[0] + "  (" + sys.executable + ")")

    print("\n-- 仓库 --")
    path_item("仓库根", P.PROJ)
    path_item("源码工程", P.SRC_WORK)
    path_item("app 目录", os.path.join(P.SRC_WORK, "app"))
    path_item("版本号文件", P.VERSION_FILE)
    path_item("APK 目录", P.APK_DIR)
    path_item("验收清单", P.CHECKLIST)

    if os.path.exists(P.VERSION_FILE):
        for ln in open(P.VERSION_FILE, encoding="utf-8"):
            s = ln.strip()
            if s.startswith("versionCode=") or s.startswith("versionName="):
                print("         " + s)

    print("\n-- 编译工具链（可重新下载，不在仓库里）--")
    path_item("JDK 17", P.JDK)
    path_item("Android SDK", P.SDK)
    path_item("Gradle 8.7", P.GRADLE)
    path_item("build-tools 34", P.BUILD_TOOLS)
    print("         MMBMH_PROJ  = %s" % P.PROJ)
    print("         MMBMH_BUILD = %s" % P.BUILD)

    print("\n-- 签名密钥（最要紧的一项）--")
    if not os.path.exists(P.KEYSTORE):
        line(NO, "debug.keystore", "缺失 -> " + P.KEYSTORE)
        print("         这不是「重新生成一个就行」的东西：换密钥 = 新包装不上旧包 =")
        print("         只能卸载重装 = 账本数据全丢。去备份邮件里把它取出来放回上面的路径。")
    else:
        line(OK, "debug.keystore", P.KEYSTORE)
        got, err = keystore_sha1()
        if got is None:
            line(WARN, "密钥指纹", "读不出来：" + str(err))
        elif got == P.EXPECTED_KS_SHA1.upper():
            line(OK, "密钥指纹", got)
        else:
            line(NO, "密钥指纹", "对不上！")
            print("         期望 %s" % P.EXPECTED_KS_SHA1)
            print("         实际 %s" % got)
            print("         这把不是当初打包用的那把 —— 别用它打包，先找回正确的。")

    print("\n-- git --")
    out, rc = git("rev-parse", "--abbrev-ref", "HEAD")
    line(OK if rc == 0 else NO, "当前分支", out or "(不在 git 仓库里)")
    out, rc = git("remote", "get-url", "origin")
    line(OK if rc == 0 else WARN, "远端 origin", out or "(未配置)")
    out, _ = git("status", "--porcelain")
    if out:
        line(WARN, "工作区", "有 %d 个未提交改动" % len(out.splitlines()))
    else:
        line(OK, "工作区", "干净")

    out, rc = git("log", "--oneline", "-1")
    if rc == 0:
        line(OK, "最新提交", out)

    print("\n" + "=" * 70)
    if fails:
        print("  结论：%d 项不合格 —— 按 SETUP.md 补齐再动手。" % fails)
        if warns:
            print("       另有 %d 项提醒。" % warns)
        print("=" * 70)
        return 1
    print("  结论：环境齐了，可以开工。")
    print("        下一步：python tools/_build_and_test.py  （同步 + 编译 + 单测）")
    if warns:
        print("        （%d 项提醒，不影响开工）" % warns)
    print("=" * 70)
    return 0


if __name__ == "__main__":
    sys.exit(main())
