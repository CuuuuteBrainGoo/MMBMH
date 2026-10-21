"""编译 + 测试 wrapper：分两次调用 gradle，逐次验证报告新鲜度。

为什么不用 run_build.py 一次传两个任务：
  上次实测 `assembleDebug testDebugUnitTest` 出现 `1 executed, 46 up-to-date`，
  测试任务是否真的重跑无法从日志判断。分两次调用可以分别确认。

用法：python _build_and_test.py
"""
import glob
import os
import subprocess
import sys
import time

from _paths import PY, TOOLS, SRC_BUILD, BUILD, require

TEST_DIR = os.path.join(SRC_BUILD, "app", "build", "test-results", "testDebugUnitTest")
RUN_BUILD = os.path.join(TOOLS, "run_build.py")


def report_state():
    files = glob.glob(os.path.join(TEST_DIR, "TEST-*.xml"))
    return {os.path.basename(f): os.path.getmtime(f) for f in files}


def run(label, outfile, tasks):
    r = subprocess.run(
        [PY, RUN_BUILD, os.path.join(BUILD, outfile)] + tasks,
        capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    print("\n########## %s rc=%d ##########" % (label, r.returncode))
    print(r.stdout)
    if r.stderr.strip():
        print("--stderr--")
        print(r.stderr[-1500:])
    return r.returncode


def main():
    require(with_keystore=False)

    before = report_state()
    print("测试报告（跑之前）：")
    for k, v in sorted(before.items()):
        print("  %s  %s" % (time.strftime("%H:%M:%S", time.localtime(v)), k))

    rc1 = run("TEST", "test_result.txt", ["testDebugUnitTest"])
    after = report_state()
    print("\n测试报告（跑之后）：")
    changed = []
    for k, v in sorted(after.items()):
        old = before.get(k)
        mark = "  <== 新/更新" if old != v else ""
        if old != v:
            changed.append(k)
        print("  %s  %s%s" % (time.strftime("%H:%M:%S", time.localtime(v)), k, mark))
    print("变化文件数: %d" % len(changed))

    rc2 = run("BUILD", "build_result.txt", ["assembleDebug"])

    print("\n===== 汇总 =====")
    print("test rc=%d  build rc=%d  报告变化=%d" % (rc1, rc2, len(changed)))
    return 0 if (rc1 == 0 and rc2 == 0) else 1


if __name__ == "__main__":
    raise SystemExit(main())
