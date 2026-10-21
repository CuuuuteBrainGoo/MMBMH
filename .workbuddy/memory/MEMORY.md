# 彩票账本 · 项目长期约定

> 面向「下次打开这个项目时的我」。只写**必须遵守的规则本身**，踩坑过程在 `YYYY-MM-DD.md`。
> 换机器 / 重装系统 → 看仓库根 `SETUP.md`；接手的 agent → `AGENT-BOOTSTRAP.md`。

---

## 一、工具链：全部在 `tools/`，别再回 `F:\LottBuild` 找脚本

2026-10-21 起，构建/打包/发版脚本**已进仓库**，路径统一由 `tools/_paths.py` 提供
（`MMBMH_PROJ` / `MMBMH_BUILD` 两个环境变量可覆盖）。

| 命令 | 作用 |
|---|---|
| `python tools/doctor.py` | 环境体检（**换机后第一件事**，含密钥指纹比对） |
| `python tools/_build_and_test.py` | 同步 + 编译 + 单测 |
| `python tools/_pack.py` | 打包总控（版本守卫 → 两变体 → 签名） |
| `python tools/_dexprobe2.py` | 拆 dex 证实新代码进包 |
| `python tools/_release_guard.py` | 4 处版本号一致 |
| `python tools/_secret_sentinel.py` | 密钥哨兵（**推送前必须 CLEAN**） |
| `python tools/_make_release.py --notes "…"` | 建 Release + 传附件 |

- `F:\LottBuild` 现在只是**编译工作区**（JDK/SDK/Gradle/编译副本），
  里面残留的 `_fix_*.py` 是一次性脚本，**别用**
- 重构验收标准：改造后跑 `_pack.py`，**产出的 APK md5 必须与改造前一致**（本次已验）

## ⛔ 签名密钥：丢了就丢数据 ★★★

- 位置 `~/.android/debug.keystore`，**不在仓库**（正确），备份在少爷邮箱
- **绝不能自动生成新的**：换密钥 → 新包装不上旧包 → 只能卸载 → **账本全丢**
- `sign_ledger.py` 缺失时硬失败（退出码 3）；`doctor.py` 用 SHA1 验身：
  `0D:3F:58:11:86:98:3F:A1:F6:7F:83:5D:71:4F:5C:1A:E5:3B:9D:D7`
- 手机上重装 App 前**必须**先导出 JSON 备份

## ⛔ 推送前必须先跑密钥哨兵 ★★★

```bash
python tools/_secret_sentinel.py     # 必须看到 RESULT: CLEAN + scanned: N commits + M files
```

- 扫**全部 git 历史 + 工作区**，用**形状正则**（不是人工前缀清单）
- 退出码：`0` 干净 / `1` 有疑似密钥 / **`2` 扫描没跑成，结果无意义，必须重跑**
- CLEAN 行必须自带 `scanned: …` 证据 —— 「干净」不能只吐结论
- 摔过 5 次：真 Key 进测试文件 → 占位符保持真 Key 形状 → 工作日志抄真 Key →
  **命令输出打印 token** → yt-dlp 报错回显 cookie。
  统一根因：**靠"我记得要查什么"防守，而不是靠机制。列清单必漏清单。**
- 探测凭据只输出"有没有 / 长度 / 状态码"，**绝不打印值**
- 带 `--cookies` / `--token` / `-u user:pass` 的命令，一律先落盘再脱敏读
- **绝不点 GitHub 的 unblock 按钮**；改本地历史才是唯一正解
- `.workbuddy/memory/*.md` **在仓库里** → 写复盘文档不许抄真实凭据
- 脚本三处副本必须同步（md5 一致）：`F:/LottBuild/_secret_sentinel.py`、
  `~/.workbuddy/tools/secret_sentinel.py`、`~/.workbuddy/skills/pre-push-secret-scrub/scripts/`
  （仓库存量是 `tools/_secret_sentinel.py`，改了记得同步这三处）

## 推送 / 历史重写

- 远端 `https://github.com/CuuuuteBrainGoo/MMBMH.git`，分支 `main`
- **推送前先 `git ls-remote origin refs/heads/main` 拿远端真实值** ——
  本地 `origin/main` 可能是陈旧缓存，据此判断"能否 fast-forward"会误判（踩过）
- ⚠️ **仓库级 `http.proxy` 会变成陷阱**：少爷跑过 bat 后它指向 `127.0.0.1:10090`，
  代理一关就所有 git 操作失败（`ls-remote` 直接空输出）。
  **症状**：`Failed to connect ... over proxy 127.0.0.1`。
  **处置**：先试 `git -c http.proxy= push`（直连）——2026-10-21 直连可用；
  不行再让少爷双击 `推送到GitHub.bat`。确认直连稳定后 `git config --unset-all http.proxy`
- 重写历史后**必须 force push**：`! [rejected] main -> main (fetch first)` 是正常现象，别慌
  - `git push --force-with-lease -u origin main`（**别用 `--force`**）
  - **tag 会全部悬空**（指向被剔除的旧提交）→ 逐个 `git tag -f vX.Y.Z <新提交>` 再
    `git push --force origin <tags>`
- 剔历史文件：**精确写路径，一次一个**。`git rm -r --cached apk` 会连文档一起剔、
  工作区文件同步消失（翻过车）。动手前先整仓备份 `.git`
- force push 后**服务端不立刻瘦身**（要等 GitHub 定期 gc）→ 别为立即瘦身删库重建

## 版本号 / 发布

版本号**唯一来源** `LotteryLedger/version.properties`（`app/build.gradle.kts` 读它）。
`versionCode` 每次交付 **+1**；加功能升 minor、只修 bug 升 patch。

**散落 4 处，升版必须同步改**：`version.properties` / `.gitignore`（`!apk/LotteryLedger-v<版本>-*`）/
`README.md`（下载表 + 示例块）/ `apk/真机验收清单.md`（标题 + 路径）。
`_release_guard.py` 校验，**已焊进 `_pack.py`**，不一致直接中断。

### 「代码 push 了」≠「Release 存在」★★

README 顶部徽章读的是 **GitHub Release**，不是仓库文件。缺 Release 就永远显示旧版本。
每次发版三步：**commit → push → `_make_release.py`**。
随时用 `/releases/latest` 接口验证徽章会显示什么，别肉眼看页面。
传 APK 附件**必须走 `uploads.github.com`**（走 api 域会失败）。

## 工程结构

- 权威源码 `F:\Projects\MMBMH-mony mony back my home\LotteryLedger`
- 编译副本 `F:\LottBuild\LotteryLedger`（纯 ASCII 路径，避开 aapt2 的坑）
- **改了权威源码 ≠ 改了编译副本**。`run_build.py` 会自动同步并打印
  `sync: N copied, M removed` —— **`0 copied` 不一定是好事**（可能没同步成功）

## 交付流程（每次都一样）

1. 改权威源码
2. `python tools/_build_and_test.py`（看 `sync` 行 + 「报告变化」数）
3. **问少爷要不要打包**
4. `python tools/_pack.py`
5. `python tools/_dexprobe2.py`
6. `python tools/_release_guard.py`
7. 更新 `apk/真机验收清单.md`（≤ 25 KB，上一组剪到 `验收清单-历史.md`）
8. 追加 `.workbuddy/memory/<今天>.md`
9. 哨兵 → `git add -A` → `git commit` → `git push`（旧版 APK 要 `git rm --cached`）
10. `python tools/_make_release.py --notes "…"`

- **第 3 步是少爷亲定的**：修完 bug 不要默认打包，先说「改了什么/怎么验的」再问。
  - 例外：能论证「打包对接下来工作有实质帮助」可自行决定，**要说明理由**
  - 他说「打包 / 给我 APK」时**直接打，别再反问**
- 第 5、6 步不能省：编译成功 ≠ 新代码进包

## 沙箱 / 工具铁律

- **长任务绝不接管道**（`| tail` / `| head`）：管道断裂 → SIGTERM 杀进程，退出码 127 看不出半途而废
- **跨工具传字符串别用反斜杠字面量**：「工具 → bash heredoc → python」会吃掉一层反斜杠，
  同一段文本经不同工具落地层数不同。→ 用**正斜杠**或 `chr(92)` 构造
- `taskkill` 必须写 `MSYS_NO_PATHCONV=1 taskkill /PID N /F`
- 需要设 PATH 时在 **Python 脚本内部** `os.environ`（Git Bash 会改写传给 .exe 的 PATH）
- 批量删除 > 50 文件/轮会被拦；先试 `mv` 改名
- `res/` 里**只能放 .xml 和 .png**（`.bak` 会让 aapt2 直接报错）
- **判断后台任务死没死**：看 ①活进程 ②产物时间戳 ③日志结束标记。**别看日志尾巴**（正在写）
- Windows bat：纯 ASCII + CRLF + 无 BOM，中文外移（技能 `windows-script-encoding`）

## 产品规则（不可擅改）

- 不长期保存照片；AI 只读票；**对奖在本地**
- 实体彩票唯一性去重（票身份指纹是唯一能阻止入账的依据）
- **一二等奖金额必须人工确认**后才计入盈亏（§15）
- 大乐透奖池 ≥8 亿：三 6666 / 四 380 / 五 200 / 六 18 / 七 7
  （平时六等奖 15；"20 元"是**五等奖**升档后的金额 —— 三个数不同奖级，曾记串）
- 兑奖期限：开奖日 + 60 天；**国庆头 3 天、春节头 7 天**内到期顺延，其它不顺延
- 春节日期硬编码表（2025~2035），表外年份**不顺延**（宁可保守）
- 对奖：开奖日 **22:30**；没结果 → 次日 **08:30** 再跑；没有待核验的票就不跑
- 入账时若已过开奖时间（当天 22:00 后 / 往日），**当场核验一次**
- 官方接口每次拉 **200 期**（约 15 个月）。曾 50 期 → 4 个月前的票永远卡"等待开奖结果"

### 开奖时刻判定：只能有一处 ★（v1.6.2）

`core/DrawSchedule.kt` 是**唯一**口径 —— 核验 / 查询 / 展示三层都必须调它：

| 函数 | 用途 | 关键点 |
|---|---|---|
| `drawTimePassed(drawDate, today, now)` | 能不能去查了 | 当天 **22:00 后**才算过点 |
| `checkCutoff(today, now)` | 给 SQL `draw_date <= :cutoff` 用 | **22:00 前返回昨天** |
| `effectiveStatus(stored, …)` | 列表/详情页徽章 | 存量票动态修正，**不写库** |

**摔过**：核验层看**时间**（22:00），查询层只看**日期**（`draw_date <= today`）→
开奖日当天下午就弹「已过开奖时间」，而当晚 21:30 才开奖。**两套逻辑各自看都对，合起来就错。**
（`PrizeService.isDrawTimePassed` 现在只是委托，API 没变，旧测试成了回归保护。）

### 判据抽成一份还不够，**必须接上所有消费者** ★（v1.6.3）

`BatchTriage.classify` 判据只有一份、也有测试，但**只接了「批量导入」** →
单张扫描同一张干净票却要手点确认（少爷一眼看出不对，代码毫无报错）。

**通用规则**：抽出判据后，**列出所有该用它的路径逐条接上**，
每接一条**补一条读源码的守护测试**（如 `ScanAutoCommitGuardTest`），
否则下次重构会静默摘掉某一处，行为和判据悄悄脱节、编译测试全绿。

### 别用文件大小判断「包是不是新的」★

v1.6.1/1.6.2/1.6.3 的 APK **字节数完全相同**（zip 压缩 + 对齐 padding 吸收差异）。
要**比 md5** + **在 dex 里数新符号**（`_dexprobe2.py`）。别信侧面指标，信内容。

### 探针报「缺失」先怀疑探针自己 ★

探针是硬编码 UI 文案，源码改了措辞后照样报缺失 —— 看着像代码丢了，其实探针过期。
顺序：**先 grep 源码实际文案** → 对上了就改探针，别去"修"一个没坏的实现。

### 「号码撞大奖」是**玩笑**，不是中奖提示 ★

指「往期买过的号码恰好等于本期开奖号」→ 弹一句损话（"就差一期"）；**不是**真中奖庆祝。
1. **绝不碰账目**（彩蛋跑失败也不能影响任何功能）
2. 文案**不许出现「中了 / 恭喜 / 中奖 / 发财」**（否则玩笑变假好消息，比不弹还糟）
3. **排除「本期自己买的票」**（那是真中奖，走 `PrizeRules` + 奖金确认）

`core/JackpotTease.kt` + `MainActivity.TeaseOverlay`（紫 `LedgerColors.Tease`）。
冷启动查一次；同一（彩种+期号）只调侃一次，**没撞上不记录**。浮层**必须点 3 次**才关。
单测 `JackpotTeaseTest` 扫全池禁用词。

## 展示编号（`core/TicketCode.kt`）★（v1.6.0）

**编号 = 列表位置**（列表按 `draw_date DESC`）：`编号 = 总数 − 下标`，最旧 = 1，最新 = 总数。

1. **必须用 `tickets`（全量）算，不能用 `visible`** —— 筛选时编号不变
2. `TicketCode.join` 内部 `sortedDescending()` —— 各查询 ORDER BY 不一致，不能靠调用方保证
3. 编号进不了 core 纯函数：`withCode(id, notice)` 加前缀、`duplicateReasonText()` 把 `#数据库id` 换成 `#展示编号`

**取舍**：删中间一张 → 上面的编号各减 1；换来「连续 + 越大越新永远成立」。

## 首页滚动位置：state 必须提到 `LedgerRootContent` ★（v1.6.0）

详情页不是 `Route`，是 `detail != null` 独立状态，`when` 里优先级**高于 `route`** →
`else -> HomeScreen(...)` 被移出组合树 → 内部 `rememberLazyListState()` 销毁 → 返回归零。

修法：`rememberSaveable(saver = LazyListState.Saver)` 放在**始终存在**那层，传参给 `HomeScreen`。
**通用**：Compose 里任何「切页后要保住」的状态，都不能放在会被移出组合树的页面里。

## 主题 / 颜色 / 口径

- 主题 `core/ThemeMode.kt`：LIGHT / DARK / **SYSTEM（默认，不可改）**，老用户升级后行为要一致
- 过期票：`LedgerColors.Expired` = 紫 `0xFF7B3FA0`（改这一处全站生效）；
  金额**必须带 `−` 号** —— 那是永远拿不到的钱
- 统计 `core/LedgerReports.kt`：`purchase` 照算、`prize` **排除 `EXPIRED_UNCLAIMED`**、`expired` 单列。
  **必须与首页总账完全一致**；单测钉了「各桶净收支之和 = 总账净收支」。改口径两边一起改

## ⛔ 预填值里绝不能出现真实账号资源标识 ★

`ProviderDefaults` 的预填值**会发给每一个装了 App 的人**。曾误填少爷本人的接入点 ID。
占位符要一眼假又能看出格式（`ep-xxxxxxxxxxxxxxxx`）；**注释里也不许写真实 ID**。

## UI 硬规矩

1. **手画 SVG 图标不要凭印象拼 path**（曾把「设置」画成太阳）。只用：**抄官方 path** 或**文字标签**。
   **图形语义画反比画丑严重得多。**
2. **每个输入框下方都要有说明**（是什么 / 去哪抄 / 常见坑），走私有 `Hint()` 组件。
   帮助页链接走 `Intent.ACTION_VIEW`，**不在 App 内开 WebView**
3. `UI Design/` 是网页原型（**不进 App 打包**）—— 原型里的错会误导决策，
   图标语义/颜色/交互要跟 App 一致。改完质检 `**` 星号、`<div>` 开闭数

## 仓库边界

- git 根 `F:\Projects\MMBMH-mony mony back my home`，分支 `main`
- 提交身份 `311624123+CuuuuteBrainGoo@users.noreply.github.com`（仓库级 config）
- **APK 只留当前版本**（`.gitignore` 排 `apk/*.apk`，再 `!` 放行当前两个）
- `version.properties`、`tools/`、`.workbuddy/memory/` 进仓库
- ⚠️ 历史里仍压着旧版 APK 的 blob —— 真要瘦身只能 rewrite + force push，**需少爷同意**

## ⛔ 崩溃可观测性：固定设施，别删 ★

教训：**「进不去的 App，其内置日志功能等于不存在」**（曾审 2000 行没找到闪退根因）。

| 文件 | 作用 |
|---|---|
| `core/CrashBeacon.kt` | 崩溃时**直接写文件**（不走线程池 / sink / 锁） |
| `filesDir/crash/last_crash.txt` | 堆栈（cause 链最多 4 层、机型、Android 版本、**版本号**） |
| `filesDir/crash/last_launch` | 启动标记，与 crash **分开存** → 区分「真崩了」和「被低内存杀了」 |
| `MainActivity.showLastCrashIfAny()` | **原生** AlertDialog（不用 Compose），可复制/分享 |

1. 崩溃屏必须**原生 View**，且在 `setContent` **之前**弹（Compose 主题相关崩溃时 Compose 写的屏也会崩）
2. 崩溃处理器里只允许「拼字符串 + 直接 writeText」—— 不许调 `LedgerLog`、不走线程池
3. `setCancelable(false)` —— 唯一线索不能被手滑点掉
4. **再遇闪退**：先确认**有没有崩溃日志通路**；没有就先建通路。**审 500 行找不到 → 立刻转去建通路**

### 崩溃记录必须带版本号比对 ★（v1.5.3）

症状：修好后升级到新版，**每次打开还弹**（`last_crash.txt` 是上次残留，逻辑只看「有没有」）。

1. 落盘**必须写版本号**（`VERSION_LINE` 常量）
2. 启动比对 `versionOf(record)` vs `BuildConfig.VERSION_NAME`：相等 → 弹；
   **不等 → 不弹**，但写 `LedgerLog.w("Crash", …)` 留证据
3. **抽不到版本号 → 当作新崩溃弹**（宁可多弹，不能漏真崩溃）

**通用**：任何「上次留下的状态」，跨版本读时都要能判断「这是哪个版本写的」。
