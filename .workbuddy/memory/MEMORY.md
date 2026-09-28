# 彩票账本 App — 项目长期约定

> 面向"下次打开这个项目时的我"。日常流水在 `YYYY-MM-DD.md`，踩坑过程在日志和技能库里。
> **这里只留跨会话必须遵守的规则本身。**

## ⛔ 推送 GitHub 前必须先跑密钥哨兵 ★★★

```bash
python F:/LottBuild/_secret_sentinel.py "F:/Projects/MMBMH-mony mony back my home"
# 必须看到两行：RESULT: CLEAN + scanned: N commits + M files
```

- ⚠️ **必须显式传仓库路径**。脚本本体住在 `F:\LottBuild`，**那里不是 git 仓库**：
  在脚本目录裸跑会扫错地方。以前它会「静默降级成只扫工作区 + 照样打印 CLEAN」，
  **2026-09-28 已修成硬失败**（`RESULT: ABORT` + 退出码 2），
  顺带把统计写进 CLEAN 行（`scanned: 14 commits + 143 files`）——
  **「干净」必须自带证据，不能只吐一个结论**。
- 退出码：`0`=干净可推 / `1`=发现疑似密钥别推 / **`2`=扫描没跑成，结果无意义，必须重跑**
- 扫**全部 git 历史 + 工作区**，用**形状正则**（不是人工前缀清单）
- 摔过三次（真 Key → 保持形状的占位符 → 我自己的工作日志抄真 Key）。
  统一根因：**靠"记得要查什么"防守，而不是靠机制。列清单必漏清单。**
- `.workbuddy/memory/*.md` **在仓库里** → 写复盘文档不许抄真实凭据
- **绝不点 GitHub 的 unblock 按钮**；改本地历史（全链路）才是唯一正解
- `https://github.com/CuuuuteBrainGoo/MMBMH.git`（远端 `main`）
- ⚠️ **沙箱能不能推上去 GitHub？能 —— 条件是少爷先跑过一次 bat。**
  - 沙箱网络隔离：**看不到宿主机的 mihomo**（`127.0.0.1` 不是同一个）。
    **首次、且没跑过 bat 时**，直连 443 超时 —— 这时别重试、别扫端口，纯浪费轮次。
  - **但只要少爷在本机双击过一次 `推送到GitHub.bat`**，那个脚本会把探测到的
    代理端口写进**本仓库的 git config**（`http.proxy` / `https.proxy`）→
    **之后沙箱里的 git 走同一个 config，就能直连成功**（2026-09-28 实测推成功）。
  - **AI 的正确流程**：先试 `git push`（输出重定向到文件，别接管道）。
    成功就完事；**失败再让少爷双击 bat**。
- 脚本三处副本必须同步（改一处必拷另外两处，比 md5）：
  `F:/LottBuild/_secret_sentinel.py`、`~/.workbuddy/tools/secret_sentinel.py`、
  `~/.workbuddy/skills/pre-push-secret-scrub/scripts/secret_sentinel.py`
- 详见技能 `pre-push-secret-scrub`

## ⛔ 重写历史后：必须 force push，且 tag 会悬空 ★★

`filter-branch` / `rebase` 一旦重写历史，远端会出现「本地没有的提交」→ 普通 push 被拒：

```
! [rejected]  main -> main (fetch first)
```

**别慌，这不是故障，是历史分叉的必然结果。** 正确处置：

1. **先 `git ls-remote origin refs/heads/main` 拿远端真实值** ——
   本地 `origin/main` 可能是**陈旧缓存**，据此判断"能不能 fast-forward"会误判（踩过）。
2. `git fetch origin`（**输出重定向到文件，绝不接管道** —— 管道断裂会 SIGTERM 杀掉 git）
3. `git push --force-with-lease -u origin main`
   （**用 `--force-with-lease` 不用 `--force`**：远端被别人动过会自动中止，不静默覆盖）
4. **tag 会全部悬空**（指向被剔除的旧提交，点进去 404）→ 逐个重指：
   `git tag -f v1.5.0 <新历史里的对应提交>`，再 `git push --force origin v1.5.0 v1.6.0 ...`
5. **补建当前版本 tag**（历史重写容易漏掉最新版没打 tag）

### 剔历史文件的正确粒度 ★

- ❌ `git rm -r --cached apk` —— **剔的是「apk/ 下的一切」**，
  连要保留的 `apk/真机验收清单.md` 一起剔掉，**且工作区文件同步消失**
  （index-filter 跑在工作区取文件之前）。**2026-09-28 就是这么翻车的。**
- ✅ 要剔什么就**精确写什么路径**，一次一个文件；剔完立刻 `git checkout` 回工作区确认
- ✅ 动手前**必须先做整仓备份**（`.git` 全拷一份到仓库外），这次全靠备份救回来的

### force push 后服务端不会立刻瘦身 ★

- **客户端 `.git` 干净 ≠ 服务端干净**。force push 后旧提交变"孤儿"，
  但服务端对象库里的旧 blob 要等 **GitHub 定期 gc**（几小时~几天，不由我们控制）。
- 症状：远端仓库 `size` API 仍报 64 MB、旧 commit 仍可访问 → **这是正常的，不是瘦身失败**。
- **别为了立即瘦身去删库重建**，代价远超收益。

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
**已焊进 `_pack.py`** → 打包前自动跑，不一致直接中断（不用再记着手动跑）。

## ⛔ 「代码 push 了」≠「Release 存在」★★（2026-09-28 少爷踩过）

**README 顶部的版本徽章读的是 GitHub 的 Release，不是仓库里的 README 文件。**

```markdown
![Release](https://img.shields.io/github/v/release/CuuuuteBrainGoo/MMBMH)
```
→ 走 `GET /repos/{o}/{r}/releases/latest`，**没有任何 Release 时永远显示最旧的那个**。

**症状**：代码 push 成功、bat 报 `[SUCCESS]`，但 Releases 页面不更新、徽章还显示旧版本。

**每次发版必须三步齐全**（缺一步就出现上述症状）：

| 步骤 | 命令/操作 | 作用 |
|---|---|---|
| ① 提交 | `git commit` | 代码进本地历史 |
| ② 推送 | `git push`（或 bat） | 代码进远端 |
| ③ **建 Release** | REST API `POST /repos/{o}/{r}/releases` + 传 APK 附件 | **徽章 / Releases 页面读这个** |

建 Release 的姿势（沙箱可直连时）：
- token 从 git 凭据取，**不落盘**：`git credential fill` + `printf "protocol=https\nhost=github.com\n\n"`
- 建完还要 `POST https://uploads.github.com/repos/{o}/{r}/releases/{id}/assets?name=X` 传 APK
  （**大文件必须走 `uploads.github.com` 域**，走 `api.github.com` 会失败）
- **随时用 `/releases/latest` 接口验证徽章会显示什么**，别靠肉眼看页面

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
- **跨工具传字符串，别用反斜杠字面量**（Windows 路径、`\n` 之类）：
  「工具 → bash heredoc → python」这条链会**吃掉一层反斜杠**，同一段文本经
  不同工具写进去，落地的层数不一样（2026-09-28 改哨兵 docstring 踩到：
  `F:\LottBuild` 触发 `SyntaxWarning: invalid escape sequence`，Edit 改两轮才对）。
  → 用**正斜杠**或 `chr(92)` 构造。**输出和输入都是失真通道，中间那层也在改数据。**

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

### 开奖时刻判定：只能有一处判断 ★（v1.6.2）

`core/DrawSchedule.kt` 是**唯一**的「开奖时刻」口径 —— 核验 / 查询 / 展示三层都必须调它：

| 函数 | 用途 | 关键点 |
|---|---|---|
| `drawTimePassed(drawDate, today, now)` | 能不能去查了 | 当天 **22:00 后**才算过点 |
| `checkCutoff(today, now)` | 给 SQL `draw_date <= :cutoff` 用 | **22:00 前返回昨天** → 今天开奖的票不会被提前捞出来 |
| `effectiveStatus(stored, drawDate, …)` | 列表 / 详情页徽章 | 存量票动态修正，**不写库** |

**摔过一次（2026-09-28）**：核验层看**时间**（22:00），查询层只看**日期**
（`draw_date <= today`）→ 大乐透开奖日当天下午 18:12 就弹「已过开奖日期」，
而当晚 21:30 才开奖。**两套逻辑各自看都对，合起来就错。**

**通用规则**：凡「同一个业务判断会被多个消费者用到」（UI / 查询 / 核验），
必须抽成**纯函数**（now 可注入、能单测）+ 加一条**「各消费者等价」的测试** ——
只测单个函数挡不住漂移（`DrawScheduleTest` 里那条遍历 24 小时的对拍测试就是干这个的）。

### 判据抽成一份还不够，**必须接上所有消费者** ★（v1.6.3）

上面那条讲的是「一个判断别写两遍」；这条是它的**背面** ——
判断确实只有一份，但**只接了一个调用方**。

**真实反例（2026-09-28）**：`BatchTriage.classify` 判据只有一份、也有测试，
但**只接了「批量导入」**。单张扫描那条路径压根没调用它 ⇒
同样干净的票，批量能一键入账、单张必须手点确认。
少爷一眼看出不对（「为什么不直接入账」），代码里却毫无报错。

**通用规则**：抽出一个判据/策略之后，**列出所有该用它的路径，逐条接上**；
每接一条**补一条守护测试**（`ScanAutoCommitGuardTest` 那种读源码的检查），
否则下次重构会静默摘掉某一处，行为和判据悄悄脱节、编译测试全绿。

### 别用文件大小判断「包是不是新的」★

v1.6.1/1.6.2/1.6.3 的 APK 字节数**完全相同**（zip 压缩 + 对齐 padding 吸收差异），
看着像打包脚本复制了旧文件。**要看内容**：比 md5 + 在 dex 里数新符号
（`commitCleanScan` 在 1.6.2 里 0 次、1.6.3 里 4 次）。
跟「盯产物不盯日志」同源：**别信侧面指标，信内容本身。**

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
