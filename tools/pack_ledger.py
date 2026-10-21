"""打包：把编译好的 APK 拷到仓库的 apk/ 目录（待签名）。

三个假成功陷阱全部规避（见技能 android-apk-sandbox-build §7.2）：
  1. 版本号从 version.properties 读，不硬编码
  2. 产物必须晚于最新 .kt 源码，否则报警（返回 2）
  3. 缺产物直接退出，不静默

注意：**不回拷源码工程**。
  run_build.py 已经把工作区同步到编译目录（正向），工作区才是权威源码位置。
  再反向 rmtree + 拷回有风险：万一编译目录缺文件，工作区会被清空。

用法：python pack_ledger.py <变体> ...   例：python pack_ledger.py debug release
      不传参默认两个都打。
"""
import os
import shutil
import sys

from _paths import SRC_BUILD, APK_DIR, VERSION_FILE

VARIANTS = {
    "debug": os.path.join("app", "build", "outputs", "apk", "debug", "app-debug.apk"),
    "release": os.path.join("app", "build", "outputs", "apk", "release", "app-release-unsigned.apk"),
}


def read_version():
    """读 version.properties 的 versionName。"""
    if not os.path.exists(VERSION_FILE):
        print("!! 找不到 version.properties：", VERSION_FILE)
        return "0.0.0"
    name = None
    for line in open(VERSION_FILE, encoding="utf-8"):
        s = line.strip()
        if s.startswith("versionName="):
            name = s.split("=", 1)[1].strip()
    if not name:
        print("!! version.properties 里没有 versionName=")
        return "0.0.0"
    return name


def newest_source_ts():
    """
    最新的**会进 APK 的**源码时间。

    ⚠️ 只看 `src/main`。测试代码（`src/test`）**不进 APK**，
    改测试文件不该让产物"变旧"。

    2026-09-27 真踩过：改了一个测试文件之后，debug 包被判成 `-STALE`，
    PACK 返回 2，`_pack.py` 把后续的签名步骤整个跳过了 ——
    结果是"打包成功但用户拿不到签名包"。而且文件名里的 `-STALE`
    看起来像是编译出了问题，实际编译完全正常。
    """
    newest = 0
    for dp, dn, fn in os.walk(os.path.join(SRC_BUILD, "app", "src", "main")):
        for f in fn:
            if f.endswith((".kt", ".xml")):
                newest = max(newest, os.path.getmtime(os.path.join(dp, f)))
    # 版本号文件也算：改了 version.properties，APK 必须重打
    if os.path.exists(VERSION_FILE):
        newest = max(newest, os.path.getmtime(VERSION_FILE))
    return newest


def main():
    want = sys.argv[1:] or list(VARIANTS.keys())
    ver = read_version()
    newest_src = newest_source_ts()
    os.makedirs(APK_DIR, exist_ok=True)

    built = []
    for variant in want:
        sub = VARIANTS.get(variant)
        if sub is None:
            print("未知变体:", variant)
            continue
        p = os.path.join(SRC_BUILD, sub)
        if not os.path.exists(p):
            print("MISSING:", p)
            continue
        fresh = os.path.getmtime(p) > newest_src
        tag = "" if fresh else "-STALE"
        dst = os.path.join(APK_DIR, "LotteryLedger-v%s-%s-unsigned%s.apk" % (ver, variant, tag))
        shutil.copy2(p, dst)
        built.append((dst, os.path.getsize(dst), fresh))
        print("copied %-62s %8.2f MB  fresh=%s"
              % (os.path.basename(dst), os.path.getsize(dst) / 1048576, fresh))

    if not built:
        print("!!!! 没有产出任何 APK，检查编译日志 !!!!")
        return 1
    if any(not b[2] for b in built):
        print("!!!! WARNING: 有 APK 早于最新源码，可能是旧的 !!!!")
        return 2
    print("OK: %d 个 APK 已就绪（待签名）" % len(built))
    return 0


if __name__ == "__main__":
    sys.exit(main())
