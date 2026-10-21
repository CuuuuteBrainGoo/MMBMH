# tools/ —— 构建与交付脚本

全部**只用 Python 标准库**（不装任何第三方包）。路径统一从 `_paths.py` 取，
换机器只改那一处（或用 `MMBMH_PROJ` / `MMBMH_BUILD` 环境变量）。

## 先跑这个

```bash
python tools/doctor.py
```

环境自检：仓库结构、JDK/SDK/Gradle、**签名密钥指纹**、git 远端。
换机器或重装系统后第一件事。全绿才能开工。

## 脚本一览

| 脚本 | 干什么 | 什么时候用 |
|---|---|---|
| `doctor.py` | 环境体检 | 换机 / 重装后第一步 |
| `_build_and_test.py` | 同步 + 编译 + 单测（分两次调 gradle，验证报告新鲜度） | 改完代码必跑 |
| `run_build.py` | 底层：同步工作区→编译副本，跑 gradle，输出落盘 | 被上面那个调，也可单独用 |
| `_pack.py` | 打包总控：版本守卫 → 编译两变体 → 拷出 → 签名 | 要出安装包时 |
| `pack_ledger.py` | 把编译产物拷到 `apk/`（带 `-STALE` 检测） | 被 `_pack.py` 调 |
| `sign_ledger.py` | zipalign + apksigner（**密钥缺失时硬失败，绝不自动生成**） | 被 `_pack.py` 调 |
| `_dexprobe2.py` | 拆 dex 数符号，证实新代码真进包 | 打包后 |
| `_release_guard.py` | 4 处版本号一致性 | 打包前（已焊进 `_pack.py`） |
| `_secret_sentinel.py` | 密钥哨兵：扫全历史 + 工作区 | **推送前必须 CLEAN** |
| `_make_release.py` | 建 GitHub Release + 传 APK + 验徽章 | 每次发版 |

## 三个容易搞错的约定

1. **`sync: 0 copied` 不一定是好事** —— 说改了源码但没同步成功，编译的是旧代码。
2. **`_pack.py` 的退出码 2 不是"成功"** —— 那是"有 APK 比源码旧"，包被标 `-STALE` 且不签名。
3. **`_secret_sentinel.py` 退出码 2 是"没扫成"**，不是干净。必须重跑。

## 没搬进仓库的脚本

`F:\LottBuild` 里还剩一批 `_fix_*.py`、`_upgrade_checklist_15x.py`、`ls*.py` 之类
—— 都是当时一次性用的，故意不搬。别被它们误导成"还要用"。

完整重建步骤见仓库根的 **`SETUP.md`**；接手项目的 agent 见 **`AGENT-BOOTSTRAP.md`**。
