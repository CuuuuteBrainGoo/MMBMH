# SETUP —— 从零重建这个项目（格式化硬盘 / 换电脑专用）

> 目标：**只靠 GitHub + 一份签名密钥**，把「能编译、能打包、能发布」的环境重新搭起来。
> 签名密钥不在仓库里（故意的，见第 3 步），其余全部在仓库里。

预计耗时：**30~45 分钟**，其中大部分是下载。

---

## 0. 先看一眼这东西长什么样

| 目录 | 是什么 | 在仓库里？ |
|---|---|---|
| `LotteryLedger/` | Android 工程（Kotlin + Compose） | ✅ 是 |
| `apk/` | 交付的安装包（只放当前版本）+ 真机验收清单 | ✅ 是 |
| `tools/` | 构建 / 打包 / 签名 / 发版 / 体检脚本 | ✅ 是 |
| `UI Design/` | 网页原型（不进 App 打包） | ✅ 是 |
| `.workbuddy/memory/` | 项目记忆：约定、踩过的坑、每日工作日志 | ✅ 是 |
| **JDK / Android SDK / Gradle** | 编译工具，1 GB 以上 | ❌ **没有**，重新下载（第 2 步） |
| **`~/.android/debug.keystore`** | 签名密钥 | ❌ **没有**，从备份邮件取回（第 3 步） |

**两个根路径**（后面反复用到，建议一路照抄）：

```
仓库根   MMBMH_PROJ   = F:\Projects\MMBMH-mony mony back my home
编译工作区 MMBMH_BUILD = F:\LottBuild
```

> 想换位置完全可以，第 4 步改两个环境变量就行。**但仓库根别放进更深的含中文/空格路径** ——
> `aapt2` 对这类路径不稳定，这正是当初搞出「编译工作区」的原因。

---

## 1. 装 Git + Python，克隆仓库

```bash
git clone https://github.com/CuuuuteBrainGoo/MMBMH.git "F:/Projects/MMBMH-mony mony back my home"
```

- Git for Windows（带 Git Bash）
- Python 3.10+（脚本只用标准库，不装任何第三方包）
- 确认：`python --version`、`git --version`

---

## 2. 装编译工具链

三个东西，全部解压到 `F:\LottBuild\`，**目录名要一模一样**（脚本按固定路径找）。

### 2.1 JDK 17

```bash
# Temurin 17 (Adoptium) 的 zip 版，解压成：
F:\LottBuild\jdk17\jdk\bin\java.exe
```

> 版本要求来自工程：`sourceCompatibility = 17`、`jvmTarget = "17"`。**JDK 21 不行**（AGP 8.x 会抱怨）。

### 2.2 Android SDK（compileSdk 34 / build-tools 34.0.0）

```bash
# 下载 commandlinetools-win-*.zip，解压出 cmdline-tools，然后：
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

最终要有：

```
F:\LottBuild\android-sdk\platforms\android-34
F:\LottBuild\android-sdk\build-tools\34.0.0\aapt2.exe
F:\LottBuild\android-sdk\build-tools\34.0.0\apksigner.bat
F:\LottBuild\android-sdk\build-tools\34.0.0\zipalign.exe
```

> `apksigner.bat` 和 `zipalign.exe` 是签名脚本直接调的，缺一个就打不出能装的包。

### 2.3 Gradle 8.7

```bash
# gradle-8.7-bin.zip 解压成：
F:\LottBuild\gradle-dist\gradle-8.7\bin\gradle.bat
```

> 工程里**没有** `gradlew`（wrapper），所以脚本直接调这份独立 Gradle。
>
> 顺手建两个空目录：`F:\LottBuild\gradle-home`（Gradle 缓存）、`F:\LottBuild\LotteryLedger`（编译副本，首次编译自动生成）。

---

## 3. 恢复签名密钥 ★ 最要紧的一步

密钥文件很小（2.6 KB），但它决定了**新包能不能覆盖安装旧包**。

```bash
# 把邮件里的 debug.keystore 放到：
C:\Users\<你的用户名>\.android\debug.keystore
```

**为什么不能「让它自动生成一个新的」**：Android 校验的是签名证书。
换一把新密钥 → 新包和已装版本的签名不一致 → 系统拒绝覆盖安装 →
只能卸载重装 → **卸载会清掉 App 数据，账本全丢**。

验证指纹对不对：

```bash
python tools/doctor.py
```

应看到：

```
[OK]   密钥指纹    0D:3F:58:11:86:98:3F:A1:F6:7F:83:5D:71:4F:5C:1A:E5:3B:9D:D7
```

指纹对不上会直接报 `[X]` 并停下 —— 那时候**别打包**，先找回正确的密钥。

---

## 4. 配路径（只有你想换位置时才需要）

脚本全部从 `tools/_paths.py` 取路径，那里有两个默认值。两种改法：

**A. 用环境变量（推荐，不动代码）**

```bat
setx MMBMH_PROJ  "F:\Projects\MMBMH-mony mony back my home"
setx MMBMH_BUILD "F:\LottBuild"
```

**B. 直接改 `tools/_paths.py` 的默认值**（两行）

```python
PROJ  = os.environ.get("MMBMH_PROJ")  or r"你的仓库根"
BUILD = os.environ.get("MMBMH_BUILD") or r"你的编译工作区"
```

---

## 5. 配 GitHub 凭据

发 Release（第 7 步）要用 API，脚本从 **git credential** 现取 token，不落盘、不打印。

```bash
git config --global credential.helper manager
git push   # 第一次会让你登录/授权，之后就有了
```

> 细节：`_make_release.py` 调的是 `git credential fill`。
> **只配 HTTPS 的仓库地址**，不要用 SSH —— 脚本读的是 https 那份凭据。

---

## 6. 体检 + 首次构建

```bash
cd "F:/Projects/MMBMH-mony mony back my home"

python tools/doctor.py             # 全绿才继续
python tools/_build_and_test.py    # 同步 + 编译 + 单测（约 1.5 分钟）
```

`_build_and_test.py` 会打印两行关键信息，**必须看**：

```
sync: N copied, M removed        ← N=0 说明源码没同步过来，编译的是旧代码
test rc=0  build rc=0  报告变化=K ← 报告变化=0 说明单测压根没重跑
```

---

## 7. 日常命令表

| 我要… | 命令 | 说明 |
|---|---|---|
| 环境自检 | `python tools/doctor.py` | 换机后第一件事 |
| 编译 + 单测 | `python tools/_build_and_test.py` | 改完代码必跑 |
| 打包（双变体 + 签名） | `python tools/_pack.py` | 版本守卫会自动跑，不一致直接中断 |
| 只校验版本号 | `python tools/_release_guard.py` | 4 处一致性 |
| 证实新代码真进包 | `python tools/_dexprobe2.py` | 拆 dex 数符号，**唯一可信证据** |
| 推送前密钥扫描 | `python tools/_secret_sentinel.py` | **必须 CLEAN 才能推** |
| 建 GitHub Release | `python tools/_make_release.py` | 徽章读这个 |
| 只同步 + 编译 | `python tools/run_build.py out.txt assembleDebug` | 底层脚本 |

### 发版的完整顺序（顺序不能乱）

```bash
# 1. 改版本号（4 处要一致）
#    LotteryLedger/version.properties     ← 权威值，versionCode 每次 +1
#    .gitignore                           ← !apk/LotteryLedger-v<新版本>-*.apk
#    README.md                            ← 下载表 + 示例块
#    apk/真机验收清单.md                    ← 标题 + 安装路径
python tools/_release_guard.py   # 校验 4 处，必须 PASS

# 2. 打包 + 签名
python tools/_pack.py

# 3. 拆包验证新代码真进去了
python tools/_dexprobe2.py

# 4. 密钥扫描
python tools/_secret_sentinel.py   # 必须看到 RESULT: CLEAN + scanned: N commits + M files

# 5. 提交推送（旧版 APK 要显式移出索引，.gitignore 对已跟踪文件无效）
git rm --cached apk/LotteryLedger-v<旧版本>-*.apk
git add -A && git commit && git push

# 6. 建 Release（代码 push ≠ Release 存在；徽章读的是 Release）
python tools/_make_release.py --notes "本次改动…"
```

---

## 8. 踩过的坑（不知道会白忙）

| 坑 | 真相 |
|---|---|
| 工程有两份 `LotteryLedger` | 工作区（中文路径，权威）vs 编译副本（ASCII 路径，被编译）。**改工作区必须同步**，`run_build.py` 会做，打印 `sync: N copied` |
| 三个版本的 APK **字节数完全相同** | zip 压缩 + 对齐 padding 吸收差异，**不代表复制了旧文件**。要比 md5、或在 dex 里数新符号 |
| 改了 `.gitignore` 但旧 APK 还在仓库 | `.gitignore` 对**已跟踪**文件无效，必须 `git rm --cached` |
| `aapt2` 报怪错 | 仓库放在含中文/空格路径且直接编译。要么用编译副本，要么把仓库放到纯 ASCII 路径 |
| 包名里出现 `v0.0.0` | 版本号读不到时静默降级。版本号**只从** `version.properties` 读 |
| 同一个业务判断写了两处 | 核验层看时间、查询层看日期 → 开奖日当天下午就误报「已过开奖时间」。判据必须收敛成 `core/DrawSchedule.kt` 一份 |

---

## 9. 还想更稳一点？

- 把 `F:\LottBuild` 里的一次性小脚本（`_fix_*.py`、`_upgrade_checklist_*.py`）**故意没搬进仓库** ——
  它们是当时的一次性工具，留着只会让下一个人误以为要用。
- 用户级技能库（`~/.workbuddy/skills/`，59 个）**不在这个仓库里**，属于另一条备份线。
