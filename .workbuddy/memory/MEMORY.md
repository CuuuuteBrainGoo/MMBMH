# 彩票账本 App — 项目长期约定

> 面向"下次打开这个项目时的我"。日常流水在 `YYYY-MM-DD.md`，踩坑过程在日志和技能库里。
> **这里只留跨会话必须遵守的规则本身。**

## ⛔ 推送 GitHub 前必须先跑密钥哨兵 ★★★

```bash
python F:\LottBuild\_secret_sentinel.py     # 必须看到 RESULT: CLEAN
```
- 扫**全部 git 历史 + 工作区**，用**形状正则**（不是人工前缀清单）
- 摔过三次（真 Key → 保持形状的占位符 → 我自己的工作日志抄真 Key）。
  统一根因：**靠"记得要查什么"防守，而不是靠机制。列清单必漏清单。**
- `.workbuddy/memory/*.md` **在仓库里** → 写复盘文档不许抄真实凭据
- **绝不点 GitHub 的 unblock 按钮**；改本地历史（全链路）才是唯一正解
- 远端 `https://github.com/CuuuuteBrainGoo/MMBMH.git`
- ⚠️ **沙箱里推不上去 GitHub** —— 网络隔离：直连 443 超时，也**看不到宿主机的 mihomo**
  （沙箱的 `127.0.0.1` 不是宿主机的，10090 在这边无监听）。
  **别反复重试、别去扫端口，纯浪费轮次。**
  **推送这最后一步由少爷在本机双击 `推送到GitHub.bat`** ——
  那个脚本会自动探测本机代理端口并写进 git 配置。
  AI 的职责到「**提交到本地 git** + 告诉少爷可以推了」为止。
- 详见技能 `pre-push-secret-scrub`

## 版本号散落 4 处，升版必须同步改 ★

唯一来源 `LotteryLedger/version.properties`（`app/build.gradle.kts` 读它，别写回 gradle）。
`versionCode` 每次交付 **+1**；`versionName` 加功能升 minor、只修 bug 升 patch。

| 位置 | 改什么 |
|---|---|
| `version.properties` | 权威值 |
| `.gitignore` | `!apk/LotteryLedger-v<版本>-debug-signed.apk` + `-release-` 两行 |
| `README.md` | 下载表 APK 名 + 「版本号怎么管」示例块 |
| `apk/真机验收清单.md` | 标题 + 安装路径 |

**校验**：`python F:\LottBuild\_release_guard.py`（不一致会 FAIL 并指出该改哪里）

## 工程结构：两个目录，不是符号链接 ★

- 权威源码：`F:\Projects\MMBMH-mony mony back my home\LotteryLedger`
- 编译副本：`F:\LottBuild\LotteryLedger`（ASCII 路径，避开中文路径 + aapt2 的坑）

**改了权威源码 ≠ 改了编译副本。** 编译前 `run_build.py` 会同步并打印
`sync: N copied, M removed` —— **必须看这一行**。详见技能 `android-dual-dir-build-guard`。

## 交付流程（每次都一样）

1. 改权威源码
2. `_build_and_test.py`（同步 + 编译 + 单测 + 报告新鲜度校验）
3. **问少爷要不要打包**（例外见下）
4. `_pack.py`（版本守卫 → 编译两变体 → 打包 → 签名）
5. `_dexprobe2.py`（拆 dex 证实新代码真进包）
6. `_release_guard.py`（版本号跨文件一致性）
7. 更新 `apk\真机验收清单.md`
8. 写入 `memory\YYYY-MM-DD.md`
9. **提交到本地 git**（跑哨兵 → `git add -A` → `git commit`）；
   **推送由少爷双击 `推送到GitHub.bat`**（沙箱推不上去，见上）

- **第 3 步是少爷亲定的**：修完 bug **不要默认打包**，先反馈「改了什么/怎么验证的」再问他。
  - 例外：能论证「打包对接下来工作有实质帮助」（典型：只有真机能验）可自行决定，**要说明为什么**
  - 他说「打包 / 给我 APK / 装一下」时**直接打，不要再反问**
  - 第 2 步照常跑（那是验证不是交付）
- **第 5、6 步不能省**：编译成功 ≠ 新代码进包；`sync: 0 copied` + `1 executed` 是假成功信号

## 验收清单：只放当前版本，历史必须归档 ★

红线：`真机验收清单.md` **≤ 25 KB**（曾长到 115 KB / 612 表格行，常驻预览导致打字都卡）；
历史组剪到 `验收清单-历史.md`（平时不许打开，**`present_files` 也不许推它**）。
升版动作：① 上一版那组剪到历史末尾 ② 主文件写本版 ③ 超 25 KB 说明忘了 ①

## 沙箱/工具铁律

- **长任务绝不接管道**（`| tail` / `| head`）：管道断裂 → python 收 SIGPIPE **被中途杀掉**，
  退出码显示 127，看不出任务半途而废
- `taskkill` 必须写 `MSYS_NO_PATHCONV=1 taskkill /PID N /F`
- 需要设 PATH 时在 **Python 脚本内部** `os.environ`（Git Bash 会改写传给 .exe 的 PATH）
- 批量删除 > 50 文件/轮会被拦；先试 `mv` 改名
- `res/` 里**只能放 .xml 和 .png**（`.bak` 会让 aapt2 直接报错）
- **判断后台任务死没死**：看①活进程 ②产物时间戳 ③日志结束标记。**别看日志尾巴**（正在写）

## 产品规则（不可擅改）

- 不长期保存照片；AI 只读票；**程序本地对奖**
- 实体彩票唯一性去重（票身份指纹是唯一可阻止入账的依据）
- 一二等奖金额**必须人工确认**后才计入盈亏（§15）
- 大乐透奖池 ≥8 亿：三 6666 / 四 380 / 五 200 / 六 18 / 七 7
  （**平时六等奖 15**；"20 元"是**五等奖**升档后的金额 —— 三个数不同奖级，曾记串）
- 兑奖期限：开奖日 + 60 天；**国庆头 3 天、春节头 7 天**内到期顺延，其它不顺延
- 春节日期硬编码表（2025~2035），表外年份**不顺延**（宁可保守）
- 对奖：开奖日 **22:30**；没结果 → 次日 **08:30** 再跑；没有待核验的票就不跑
- 入账时若已过开奖时间（当天 22:00 后 / 往日），**当场核验一次**
- 官方接口每次拉 **200 期**（约 15 个月）。曾 50 期 → 4 个月前的票永远卡"等待开奖结果"（已修）

### 「号码撞大奖」是**玩笑**，不是中奖提示 ★

指「**往期买过的号码恰好等于本期开奖号**」→ 弹一句损话（"就差一期"）；**不是**真中奖庆祝。
1. **绝不碰账目**（彩蛋跑失败也不能影响任何功能）
2. **文案不许出现「中了 / 恭喜 / 中奖 / 发财」**（否则玩笑变假好消息，比不弹还糟）
3. **排除「本期自己买的票」**（那是真中奖，走 `PrizeRules` + 奖金确认）

`core/JackpotTease.kt` + `MainActivity.TeaseOverlay`（紫 `LedgerColors.Tease`）。
冷启动查一次；同一（彩种+期号）只调侃一次，**没撞上不记录**。
对比范围只用「最新一期」vs「本机全部记录」（少爷原话：没必要看官方 200 期）。
浮层**必须点按钮 3 次**才关；文案池 20 条。单测 `JackpotTeaseTest` 扫全池禁用词。

## 展示编号（`core/TicketCode.kt`）★（v1.6.0）

**规则：编号 = 列表位置**（列表按 `draw_date DESC`），即 `编号 = 总数 − 下标`：
最下面（最旧）= `1`，最上面（最新）= `总张数`。

1. **必须用 `tickets`（全量）算，不能用 `visible`** —— 少爷明确「筛选时编号不变」
2. **`TicketCode.join` 内部 `sortedDescending()`** —— 各查询 ORDER BY 不一致
   （`byStatus` ASC、`all()` DESC），**不能靠调用方保证顺序**
3. **编号进不了 core 纯函数**（`Fingerprints` / `PrizeNotice`）。
   core 负责「说什么」，ViewModel 负责「挂在谁头上」：
   `withCode(id, notice)` 加前缀、`duplicateReasonText()` 把 `#数据库id` 换成 `#展示编号`

**取舍**：删中间一张 → 它上面的编号各减 1；改开奖日期也变编号。
换来「连续 + 越大越新永远成立」——比"永不变但乱序"更好找。

## 首页滚动位置：state 必须提到 `LedgerRootContent` ★（v1.6.0）

**根因不在列表在路由**：详情页不是 `Route`，是 `detail != null` 独立状态，
在 `when` 里优先级**高于 `route`** → `else -> HomeScreen(...)` 分支被移出组合树 →
内部 `rememberLazyListState()` 销毁 → 返回时重建，位置归零。

**修法**：`val homeListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }`
放在 `LedgerRootContent`（组合树里**始终存在**那层），传参给 `HomeScreen`。
顺带白捡：Activity 重建（切深浅色/旋转屏）、切设置页再回首页都保住位置。

**通用教训**：**Compose 里任何「切页后要保住」的状态，都不能放在会被移出组合树的页面里。**

## 主题模式（`core/ThemeMode.kt`）

LIGHT / DARK / **SYSTEM（默认）**，存 `SettingsStore.KEY_THEME_MODE`。
默认**必须** SYSTEM（老用户升级后行为要跟以前一致）。`LedgerRoot` 结合
`isSystemInDarkTheme()` 算出最终深浅再包 `LedgerTheme`。

## 过期票：紫色 + 负号

- `LedgerColors.Expired` = **紫 `0xFF7B3FA0`**（改这一处全站生效：首页 chip、
  卡片金额、详情页、`statusColor()`）
- 过期金额**必须带 `−` 号** —— 那是**永远拿不到的钱**，不带符号会被当成资产

## 统计报表口径（`core/LedgerReports.kt`）

`purchase` 照算、`prize` **排除 `EXPIRED_UNCLAIMED`**、`expired` 单列。
**必须与首页总账完全一致**；单测钉了不变量「各桶净收支之和 = 总账净收支」。
改口径两边一起改。UI 在 `ui/ReportsScreen.kt`，入口是首页右上柱状图。

## ⛔ 预填值里绝不能出现真实账号资源标识 ★

`ProviderDefaults` 的预填值**会发给每一个装了 App 的人**。
曾误填少爷本人的接入点 ID（不是密钥，但属于**别人的账号资源**）。
**占位符要一眼假又能看出格式**（如 `ep-xxxxxxxxxxxxxxxx`）；**注释里也不许写真实 ID**。

## UI 硬规矩

1. **手画 SVG 图标不要凭印象拼 path** —— 我曾把「设置」画成太阳，少爷一眼看出。
   只用两类：**抄官方 path**（Material/Lucide）或**文字标签**。
   **图形语义画反比画丑严重得多。**
2. **每个输入框下方都要有说明**（这是什么 / 去哪抄 / 常见坑）；说明行走私有 `Hint()`
   组件，别裸写 `Text`。帮助页链接走 `Intent.ACTION_VIEW`，**不在 App 内开 WebView**。
3. `UI Design/` 是**网页原型**（与 gradle 工程平级，**不进 App 打包**）——
   **原型里的错会误导决策**，图标语义 / 颜色 / 交互要跟 App 一致。
   改完质检：`**` 星号、`<div>` 开闭数是否相等。

## 仓库边界

- **git 根** = `F:\Projects\MMBMH-mony mony back my home`，分支 `main`
- 提交身份 `311624123+CuuuuteBrainGoo@users.noreply.github.com`（仓库级 config）
- **APK 只留当前版本**（`.gitignore` 排 `apk/*.apk` + `apk/*.idsig`，再 `!` 放行当前两个）
- `version.properties`、`.workbuddy/memory/` 进仓库
- ⚠️ **历史里仍压着旧版 APK 的 blob**（删文件删不掉 `.git` 对象）——
  真要瘦身只能 rewrite history + force push，**需少爷同意**

## ⛔ 崩溃可观测性：固定设施，别删 ★

教训：**「进不去的 App，其内置日志功能等于不存在」**。（曾审 2000 行没找到 v1.5.0 闪退根因。）

| 文件 | 作用 |
|---|---|
| `core/CrashBeacon.kt` | 崩溃时**直接写文件**（不走线程池 / sink / 锁），下次启动读出来 |
| `filesDir/crash/last_crash.txt` | 堆栈（cause 链最多 4 层、机型、Android 版本、**版本号**） |
| `filesDir/crash/last_launch` | 启动标记 —— 与 crash 文件**分开存**，区分「真崩了」和「被低内存杀了」 |
| `MainActivity.showLastCrashIfAny()` | **原生** AlertDialog（不用 Compose），可复制 / 分享 |

1. **崩溃屏必须用原生 View，且在 `setContent` 之前弹**（若崩溃跟 Compose 主题有关，
   用 Compose 写的崩溃屏会跟着崩，等于没做）
2. **崩溃处理器里只允许「拼字符串 + 直接 writeText」** —— 不许调 `LedgerLog`、不走线程池
   （`LedgerLog.log()` 的 `sink?.invoke` 已包 try-catch，否则 sink 抛异常 → 原始堆栈彻底丢失）
3. `setCancelable(false)` —— 唯一的线索不能被手滑点掉

**再遇「闪退」的正确顺序**：**先确认有没有崩溃日志通路**；没有就**先建通路**，别急着猜。
**审 500 行还找不到 → 立刻转去建通路。**

### 崩溃记录必须带版本号比对 ★（v1.5.3）

症状：修好后升级到新版，**每次打开还是弹**（`last_crash.txt` 是上次残留，
逻辑只看「有没有」、没看「哪个版本写的」）。

1. 落盘**必须写版本号**（`版本：X.Y.Z`，`VERSION_LINE` 常量）
2. 启动比对 `versionOf(record)` vs `BuildConfig.VERSION_NAME`：
   相等 → 正常弹；**不等 → 不弹**，但写 `LedgerLog.w("Crash", …内容备查")` 留证据
3. **抽不到版本号返回 null → 当作新崩溃弹**（宁可多弹，不能漏真崩溃）
4. 弹窗一眼分清：旧记录标题「旧版本的崩溃记录」、按钮「知道了，不再提示」

**通用教训**：**任何「上次留下的状态」，跨版本读时都要能判断「这是哪个版本写的」。**

### 探针报「缺失」先怀疑探针自己 ★

`_dexprobe2.py` 的探针是**硬编码 UI 文案**；源码改了措辞后探针照样报缺失 ——
看着像代码丢了，其实只是**探针过期**。
**顺序**：探针报缺失 → **先 `grep` 源码实际文案** → 对上了就**改探针**，
不要「修」一个根本没坏的实现。
