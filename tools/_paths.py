# -*- coding: utf-8 -*-
"""构建工具共用路径配置。支持 MMBMH_PROJ、MMBMH_BUILD、MMBMH_JDK 和 MMBMH_KEYSTORE 环境变量。"""
import os
import sys

# ---------- 两个根 ----------
PROJ = os.environ.get("MMBMH_PROJ") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = os.environ.get("MMBMH_BUILD") or os.path.join(PROJ, "build-work")

# ---------- 仓库内 ----------
TOOLS = os.path.dirname(os.path.abspath(__file__))
SRC_WORK = os.path.join(PROJ, "LotteryLedger")
APK_DIR = os.path.join(PROJ, "apk")
VERSION_FILE = os.path.join(SRC_WORK, "version.properties")
CHECKLIST = os.path.join(APK_DIR, "真机验收清单.md")
CHECKLIST_HIST = os.path.join(APK_DIR, "验收清单-历史.md")

# ---------- 编译工作区内 ----------
SRC_BUILD = os.path.join(BUILD, "LotteryLedger")
JDK = os.environ.get("MMBMH_JDK") or os.path.join(BUILD, "jdk17", "jdk")
SDK = os.path.join(BUILD, "android-sdk")
GRADLE = os.path.join(BUILD, "gradle-dist", "gradle-8.7", "bin", "gradle.bat")
GHOME = os.path.join(BUILD, "gradle-home")
BUILD_TOOLS = os.path.join(SDK, "build-tools", "34.0.0")
AAPT2 = os.path.join(BUILD_TOOLS, "aapt2.exe")
LAST_PACK_VERSION = os.path.join(BUILD, ".last_pack_version")

# ---------- 签名 ----------
# ⚠️ 这把密钥**必须**跟上一版是同一把。换了就装不上（签名不符）→
# 只能卸载重装 → 手机上的账本数据全部丢失。请保管好原签名文件。
KEYSTORE = os.environ.get("MMBMH_KEYSTORE") or os.path.expanduser(os.path.join("~", ".android", "debug.keystore"))
KS_ALIAS = "androiddebugkey"
KS_PASS = "android"      # Android 调试密钥的固定口令，不是秘密（Android 官方约定）
KEY_PASS = "android"

# 本机那把密钥的 SHA1 指纹（公开信息，不是秘密）。
# 作用是**验明正身**：重装后如果放回来的是另一把，`doctor.py` 会当场报出来 ——
# 否则等打完包、装不上、才发现，那时已经白忙一场。
EXPECTED_KS_SHA1 = "0D:3F:58:11:86:98:3F:A1:F6:7F:83:5D:71:4F:5C:1A:E5:3B:9D:D7"

# ---------- 其它 ----------
PY = sys.executable
REPO_OWNER = "CuuuuteBrainGoo"
REPO_NAME = "MMBMH"
REPO_URL = "https://github.com/%s/%s.git" % (REPO_OWNER, REPO_NAME)


# ---------- 自检 ----------
_REQUIRED = [
    ("仓库根", PROJ),
    ("源码工程", SRC_WORK),
    ("版本号文件", VERSION_FILE),
    ("编译工作区", BUILD),
    ("JDK 17", JDK),
    ("Android SDK", SDK),
    ("Gradle 8.7", GRADLE),
    ("build-tools 34", BUILD_TOOLS),
    ("APK 输出目录", APK_DIR),
]


def problems(with_keystore=True):
    """返回缺失项列表 [(名称, 路径), ...]。空列表 = 环境齐了。"""
    missing = [(n, p) for n, p in _REQUIRED if not os.path.exists(p)]
    if with_keystore and not os.path.exists(KEYSTORE):
        missing.append(("签名密钥", KEYSTORE))
    return missing


def require(with_keystore=True):
    """环境不全就**硬失败**，绝不带着病往下跑。

    宁可当场炸，也不要打出一个签名不对的包让人装不上 ——
    那才是真正浪费时间的那种"成功"。
    """
    miss = problems(with_keystore)
    if not miss:
        return
    print("=" * 66)
    print("  [X] 构建环境不完整，已中止")
    print("=" * 66)
    for name, path in miss:
        print("   缺 %-14s %s" % (name, path))
    print("-" * 66)
    print("  按仓库根目录 tools/README.md 装齐这些东西；")
    print("  或者设环境变量 MMBMH_PROJ / MMBMH_BUILD 指向你实际的路径。")
    if any(n == "签名密钥" for n, _ in miss):
        print()
        print("  !! 签名密钥不是「装上就有」的东西 ——")
        print("  !! 你以前打的包用的是这一把，换成新的就**装不上去**（只能卸载，账本数据会丢）。")
        print("  !! 请指定与旧版相同的签名文件后重试。")
    print("=" * 66)
    raise SystemExit(3)


# ---------- 子进程环境 ----------
def build_env(extra=None):
    """给 gradle / apksigner 用的环境变量。"""
    env = dict(os.environ)
    env["JAVA_HOME"] = JDK
    env["ANDROID_HOME"] = SDK
    env["ANDROID_SDK_ROOT"] = SDK
    env["PATH"] = os.path.join(JDK, "bin") + os.pathsep + env.get("PATH", "")
    if extra:
        env.update(extra)
    return env
