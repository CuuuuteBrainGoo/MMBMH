"""把工作区工程同步到编译目录，再跑 Gradle，结果落盘。

为什么需要同步：
  工作区 `PROJ/LotteryLedger`（中文路径，权威源码）
  编译目录 `BUILD/LotteryLedger`（ASCII 路径，避开 Gradle/aapt2 中文路径问题）
  **两个是独立的副本，不是一个目录**。改完工作区必须同步过来，否则编译的是旧代码。

路径全部来自 `_paths.py`，换机器不用改这个文件。

用法：
  python run_build.py <结果输出文件> <gradle 任务...>
  例：python run_build.py debug_result.txt assembleDebug
      python run_build.py test_result.txt testDebugUnitTest --rerun-tasks
"""
import os
import re
import shutil
import subprocess
import sys
import time

from _paths import SRC_WORK, SRC_BUILD, GRADLE, GHOME, BUILD, build_env

# 同步时跳过的目录（编译产物 / 缓存 / 大体积无关文件）
SKIP_DIRS = {"build", ".gradle", ".idea", "gradle-home"}


def sync(verbose=True):
    """把源工程同步到编译目录。返回 (新增/更新, 删除) 数量。"""
    copied = 0
    deleted = 0

    # 1) 正向同步：工作区 → 编译目录
    for root, dirs, files in os.walk(SRC_WORK):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        rel = os.path.relpath(root, SRC_WORK)
        dst_root = SRC_BUILD if rel == "." else os.path.join(SRC_BUILD, rel)
        os.makedirs(dst_root, exist_ok=True)

        for f in files:
            sp = os.path.join(root, f)
            dp = os.path.join(dst_root, f)
            need = True
            if os.path.isfile(dp):
                try:
                    if os.path.getsize(sp) == os.path.getsize(dp):
                        with open(sp, "rb") as a, open(dp, "rb") as b:
                            if a.read() == b.read():
                                need = False
                except Exception:
                    need = True
            if need:
                shutil.copy2(sp, dp)
                copied += 1

    # 2) 反向清理：编译目录里存在但工作区已删的源文件（只清源码，不碰 build/）
    for root, dirs, files in os.walk(SRC_BUILD):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        rel = os.path.relpath(root, SRC_BUILD)
        src_root = SRC_WORK if rel == "." else os.path.join(SRC_WORK, rel)
        for f in files:
            dp = os.path.join(root, f)
            sp = os.path.join(src_root, f)
            if not os.path.isfile(sp):
                try:
                    os.remove(dp)
                    deleted += 1
                except Exception:
                    pass

    if verbose:
        # ⚠️ `sync: 0 copied` 不一定是好事 —— 说明改了源码但没同步成功。
        # 交付前必须看这一行（见技能 android-dual-dir-build-guard）。
        print("sync: %d copied, %d removed" % (copied, deleted))
    return copied, deleted


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2

    out = sys.argv[1]
    if not os.path.isabs(out):
        # 相对路径落进编译工作区，别污染仓库
        out = os.path.join(BUILD, out)
    tasks = sys.argv[2:]

    sync()

    cmd = [GRADLE, "-p", SRC_BUILD, "-g", GHOME, "--console=plain"] + tasks
    t0 = time.time()
    r = subprocess.run(cmd, capture_output=True, cwd=SRC_BUILD, env=build_env())
    el = time.time() - t0

    stdout = r.stdout.decode("utf-8", "replace")
    stderr = r.stderr.decode("utf-8", "replace")

    lines = ["rc=%d  elapsed=%.1fs" % (r.returncode, el),
             "cmd=" + " ".join(tasks), ""]
    lines.append("================ STDOUT ================")
    lines.append(stdout)
    if stderr.strip():
        lines.append("================ STDERR ================")
        lines.append(stderr)

    with open(out, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))

    print("rc=%d elapsed=%.1fs" % (r.returncode, el))
    for ln in stdout.split("\n"):
        if re.search(r"^e: |error:|FAILURE|BUILD |What went wrong|actionable tasks|> Task .*FAILED", ln):
            print(ln.rstrip())
    return r.returncode


if __name__ == "__main__":
    sys.exit(main())
