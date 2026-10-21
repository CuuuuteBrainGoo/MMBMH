"""给 APK 签名（debug keystore），否则真机装不上。

输入：pack_ledger.py 产出的 `*-unsigned.apk`
输出：`*-signed.apk` + `.idsig`，并自动删掉未签名中间产物（避免点错装错）。

## ⚠️ 密钥找不到时**直接失败**，绝不自动生成新的

Android 的包安装校验是"签名必须和已装的一致"。换成一把新密钥，
新包就**装不上去** —— 只能卸载重装，而卸载会清掉 App 数据（账本全丢）。
所以这里宁可停下报错，也不自作主张生成。
"""
import glob
import os
import subprocess
import sys

from _paths import (
    APK_DIR, AAPT2, BUILD_TOOLS, KEYSTORE, KS_ALIAS, KS_PASS, KEY_PASS, build_env,
)

APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner.bat")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe")
env = build_env()


def main():
    for label, p in (("apksigner", APKSIGNER), ("zipalign", ZIPALIGN), ("build-tools", BUILD_TOOLS)):
        if not os.path.exists(p):
            print("[X] 找不到 %s：%s" % (label, p))
            return 3

    if not os.path.exists(KEYSTORE):
        print("=" * 66)
        print("  [X] 找不到签名密钥：%s" % KEYSTORE)
        print("=" * 66)
        print("  不能用新生成的密钥代替 —— 那会让新包装不上已安装的旧包，")
        print("  只能卸载重装，而卸载会清掉账本数据。")
        print("  去重装前的备份邮件里取出 debug.keystore，放回上面的路径。")
        print("=" * 66)
        return 3

    sources = sorted(glob.glob(os.path.join(APK_DIR, "*-unsigned.apk")))
    if not sources:
        print("no *-unsigned.apk found in", APK_DIR)
        return 1

    signed_ok = []
    for src in sources:
        name = os.path.basename(src)
        if "-STALE" in name:
            print("skip stale:", name)
            continue
        dst_name = name.replace("-unsigned.apk", "-signed.apk")
        dst = os.path.join(APK_DIR, dst_name)

        aligned = os.path.join(APK_DIR, "_aligned_" + name)
        ra = subprocess.run([ZIPALIGN, "-f", "-p", "4", src, aligned],
                            capture_output=True, env=env)
        use = aligned if ra.returncode == 0 and os.path.exists(aligned) else src
        if ra.returncode != 0:
            print("  zipalign failed, sign as-is:", ra.stderr.decode("utf-8", "replace")[:200])

        rs = subprocess.run(
            [APKSIGNER, "sign",
             "--ks", KEYSTORE, "--ks-pass", "pass:" + KS_PASS,
             "--ks-key-alias", KS_ALIAS,
             "--key-pass", "pass:" + KEY_PASS,
             "--out", dst, use],
            capture_output=True, env=env
        )
        ok = rs.returncode == 0 and os.path.exists(dst)
        if ok:
            signed_ok.append(dst)
        print("%-52s -> %s  rc=%d %s" % (
            name, dst_name, rs.returncode,
            "OK %.2fMB" % (os.path.getsize(dst) / 1048576) if ok else "FAILED"))
        if not ok:
            print("   ", rs.stdout.decode("utf-8", "replace")[:400])
            print("   ", rs.stderr.decode("utf-8", "replace")[:400])
        if os.path.exists(aligned):
            os.remove(aligned)

        if ok:
            rv = subprocess.run([APKSIGNER, "verify", "--print-certs", dst],
                                capture_output=True, env=env)
            first = rv.stdout.decode("utf-8", "replace").split("\n")[0] if rv.stdout else ""
            print("    verify rc=%d  %s" % (rv.returncode, first[:110]))

    # 清掉未签名中间产物，避免真机上装错
    for src in sources:
        if "-STALE" in os.path.basename(src):
            try:
                os.remove(src)
                print("removed stale:", os.path.basename(src))
            except Exception:
                pass
        elif os.path.basename(src).replace("-unsigned.apk", "-signed.apk") in \
                [os.path.basename(d) for d in signed_ok]:
            try:
                os.remove(src)
                print("removed intermediate:", os.path.basename(src))
            except Exception:
                pass

    print("\nsigned: %d" % len(signed_ok))
    return 0 if signed_ok else 1


if __name__ == "__main__":
    sys.exit(main())
