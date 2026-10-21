"""编译两个变体 + 打包 + 签名，一气呵成。

## 为什么 build 步骤要带重试

release 的 `dexBuilderRelease` 偶发 `java.nio.file.AccessDeniedException`，
报的是某个 `ComposableSingletons$XxxKt$lambda-N$1.dex` 写不进去。
**这不是代码问题也不是配置问题** —— 是刚生成的 dex 被 Windows 上的
杀毒软件实时扫描短暂占住了文件句柄。实测重跑一次必过（已经中了两次）。

## ⚠️ 判断重试时必须读结果文件，不能只看 stdout

`run_build.py` 把 gradle 的完整输出写进结果文件（debug_result.txt 等），
**stdout 上只有几行摘要**。所以只看 stdout 的话，
`AccessDeniedException` 这个关键字根本匹配不到 —— 重试逻辑形同虚设。

## 只对「文件占用」重试

编译错误、测试失败不重试 —— 那些重跑一万次也一样，只是浪费三分钟。

## 版本号守卫（两道）

**第一道：有没有涨**（对照 `BUILD/.last_pack_version`）。
少爷 2026-09-27 指出：连着迭代七八个版本，APK 里的版本号一直停在 1.1.0。
现在版本号只在 `LotteryLedger/version.properties` 里定义一处。

**第二道：4 处是不是一致**（调 `_release_guard.py`）。
2026-09-28 审计发现：`version.properties` 已到 1.6.0，但 `.gitignore` 的
APK 放行行还停在 1.5.0、README 下载表也还是 1.5.0 —— 结果是
**推上 GitHub 的会是旧包**。所以把一致性校验焊进打包流程，
**不一致直接中断打包**（不要等到推完才发现）。

用法：python _pack.py
"""
import os
import subprocess

from _paths import PY, TOOLS, BUILD, VERSION_FILE, LAST_PACK_VERSION, require

require()  # 环境不全直接中止（含签名密钥）

RUN_BUILD = os.path.join(TOOLS, "run_build.py")
PACK = os.path.join(TOOLS, "pack_ledger.py")
SIGN = os.path.join(TOOLS, "sign_ledger.py")
GUARD = os.path.join(TOOLS, "_release_guard.py")


def read_version():
    """读 version.properties，返回 (versionCode, versionName)。"""
    if not os.path.exists(VERSION_FILE):
        return (-1, "?")
    code, name = -1, "?"
    for line in open(VERSION_FILE, encoding="utf-8"):
        s = line.strip()
        if s.startswith("versionCode="):
            code = int(s.split("=", 1)[1].strip())
        elif s.startswith("versionName="):
            name = s.split("=", 1)[1].strip()
    return (code, name)


def check_version_guard():
    """打包前检查版本号有没有涨。没涨就醒目警告（不中断，方便调试性打包）。"""
    code, name = read_version()
    prev = None
    if os.path.exists(LAST_PACK_VERSION):
        try:
            prev = int(open(LAST_PACK_VERSION, encoding="utf-8").read().strip())
        except Exception:
            prev = None

    print("=" * 62)
    print("  本次打包版本：%s (versionCode=%d)" % (name, code))
    if prev is None:
        print("  （首次记录，无历史可比较）")
    elif code < prev:
        print()
        print("  " + "!" * 58)
        print("  !! 版本号变小了！上次是 %d，这次是 %d" % (prev, code))
        print("  !! 装到手机上系统会**拒绝**安装（或当成降级）。请检查 version.properties。")
        print("  " + "!" * 58)
    elif code == prev:
        print()
        print("  ~ 跟上次打包是同一个版本号（%d）。" % code)
        print("  ~ 如果是修完 bug 重打同一版，忽略即可；")
        print("  ~ 如果是**新一轮交付**，请改 LotteryLedger/version.properties。")
    else:
        print("  版本号已递增：%d → %d  ✓" % (prev, code))
    print("=" * 62)

    with open(LAST_PACK_VERSION, "w", encoding="utf-8") as f:
        f.write(str(code))
    return code, name


def check_cross_file_consistency():
    """调 _release_guard.py 校验 4 处版本号一致。不一致 → 中断打包。

    不自己重写一遍逻辑 —— 那个脚本也能单独跑（想手动检查时），
    两处逻辑不该各长一份。
    """
    if not os.path.exists(GUARD):
        print("  ~ 没找到 _release_guard.py，跳过一致性校验。")
        return True

    print()
    r = subprocess.run([PY, GUARD], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    out = (r.stdout or "") + (r.stderr or "")
    print(out.rstrip())
    if r.returncode != 0:
        print()
        print("  " + "!" * 58)
        print("  !! 版本号在多个文件之间不一致，已中断打包。")
        print("  !! 按上面的 FAIL 提示改完，再重新跑 _pack.py。")
        print("  " + "!" * 58)
        return False
    return True


code, name = check_version_guard()

if not check_cross_file_consistency():
    raise SystemExit(1)

# (步骤名, 命令, 详细日志落盘的文件名或 None)
steps = [
    ("BUILD-DEBUG", [PY, RUN_BUILD, "debug_result.txt", "assembleDebug"], "debug_result.txt"),
    ("BUILD-RELEASE", [PY, RUN_BUILD, "release_result.txt", "assembleRelease"], "release_result.txt"),
    ("PACK", [PY, PACK, "debug", "release"], None),
    ("SIGN", [PY, SIGN], None),
]

RETRYABLE = (
    "AccessDeniedException",
    "Failed to delete",
    "being used by another process",
    "The process cannot access the file",
)


def looks_like_file_lock(stdout, stderr, result_file):
    blob = stdout + stderr
    # run_build.py 的完整 gradle 输出落在结果文件里，光看 stdout 匹配不到
    if result_file:
        p = os.path.join(BUILD, result_file)
        if os.path.exists(p):
            blob += open(p, encoding="utf-8", errors="replace").read()
    return any(k in blob for k in RETRYABLE)


log = []
for name, cmd, result_file in steps:
    r = None
    for attempt in (1, 2):
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        log.append("===== %s rc=%d (attempt %d) =====\n%s\n%s"
                   % (name, r.returncode, attempt, r.stdout, r.stderr))
        if r.returncode == 0:
            break
        if attempt == 1 and looks_like_file_lock(r.stdout, r.stderr, result_file):
            print("[%s] 疑似 dex 文件被占用（杀毒扫描），重试一次…" % name)
            continue
        break
    if r is not None and r.returncode != 0:
        break

out = "\n".join(log)
open(os.path.join(BUILD, "_pack_out.txt"), "w", encoding="utf-8").write(out)
print(out[-6000:])

# 汇总各步骤的退出码。特别标注 PACK 的 2（有产物比源码旧）——
# 那种包不会被签名（sign_ledger 会跳过 -STALE），用户最终拿不到它，
# 所以必须显眼地报出来。
codes = [line for line in log if line.startswith("=====")]
print("\n===== 步骤汇总 =====")
for c in codes:
    print("  " + c.split("\n")[0])
if any("PACK rc=2" in c for c in codes):
    print(
        "\n!! PACK 报了「有 APK 比最新源码旧」。那种包已被标成 -STALE 且**不会被签名**。\n"
        "!! 看一看是不是编译没跟上（重新跑一次 _pack.py 通常就好）。"
    )
failed = [c for c in codes if " rc=0 " not in c]
raise SystemExit(1 if failed else 0)
