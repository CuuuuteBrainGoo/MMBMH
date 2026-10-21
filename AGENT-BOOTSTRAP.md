# 给「重装系统后的我」—— 项目接管提示词

> **怎么用**：重装完系统、装好 IDE 和 Git 之后，把**本文件全文**贴给新的 agent，
> 或者直接说一句「读 `AGENT-BOOTSTRAP.md`，然后按它做」。
>
> 这份文件的前提：你对这个项目**一无所知**。所以它把「该读什么、该守什么、先干什么」
> 全写死了，别靠猜。

---

## 一、这是什么项目

一个**自用的 Android 彩票账本 App**（Kotlin + Jetpack Compose，单机、无后端）。

- 用户：**少爷**（土木工程本科生，非科班，中文交流，成本敏感，讨厌含糊其辞）
- 核心流程：拍彩票照片 → AI 读票 → **本地**对奖算钱 → 记账
- 现在版本：看 `LotteryLedger/version.properties`
- 仓库：`https://github.com/CuuuuteBrainGoo/MMBMH.git`
- 两个根路径：
  - 仓库根 `F:\Projects\MMBMH-mony mony back my home`
  - 编译工作区 `F:\LottBuild`（JDK / SDK / Gradle / 编译副本，**不在仓库里**）

---

## 二、第一件事：不要写代码，先做这三步

### 1. 环境体检

```bash
cd "F:/Projects/MMBMH-mony mony back my home"
python tools/doctor.py
```

必须全部 `[OK]`。特别看**签名密钥指纹**必须是：

```
0D:3F:58:11:86:98:3F:A1:F6:7F:83:5D:71:4F:5C:1A:E5:3B:9D:D7
```

对不上就**停下**，先跟少爷确认密钥。**绝不能自己生成一把新的** ——
那会让新包装不上已安装的旧包，只能卸载重装，账本数据（真金白银的记录）全丢。

环境缺东西 → 按仓库根 `SETUP.md` 装。

### 2. 读项目记忆（这是重点，别跳过）

```bash
.workbuddy/memory/MEMORY.md          # 长期约定，必读全文
.workbuddy/memory/<最近日期>.md      # 最近几天干了什么、踩了什么坑
```

`MEMORY.md` 里每一条都是**真摔过之后写的**，不是理论。

### 3. 向少爷汇报

三句话：环境什么状态、当前版本多少、你打算先做什么。**然后等他说。**

---

## 三、铁律（违反过，后果都很实在）

### ⛔ 推送前必须跑密钥哨兵

```bash
python tools/_secret_sentinel.py        # 必须 RESULT: CLEAN
```

它会扫**全部 git 历史 + 工作区**。看到 `RESULT: ABORT`（退出码 2）说明**没扫成**，
不是"干净" —— 必须重跑。

历史教训：真 Key 写进测试文件、占位符保持真 Key 形状、工作日志里抄了真 Key、
命令输出把 token 打到对话里 —— **四次都是同一个病：靠"我记得要查什么"防守，
而不是靠机制。** 所以判据必须是形状正则，不能是人工前缀清单。

**规矩**：绝不打印任何凭据值；绝不在 GitHub 点 "unblock secret"。

### ⛔ 工程有两个目录，不是一个

```
F:\Projects\...\LotteryLedger   ← 权威源码（改这里）
F:\LottBuild\LotteryLedger      ← 编译副本（编译这里）
```

改完权威源码**必须同步**，`run_build.py` 会自动做并打印 `sync: N copied`。
**`sync: 0 copied` + 提示重新执行 = 假成功信号**，要警惕。

### ⛔ 版本号散在 4 处，升版必须同步

`LotteryLedger/version.properties`（权威）/ `.gitignore` 的 APK 放行行 /
`README.md` 下载表 + 示例块 / `apk/真机验收清单.md` 标题 + 路径。

`python tools/_release_guard.py` 校验，`_pack.py` 打包前自动跑，不一致直接中断。

### ⛔ 「代码 push 了」≠「Release 存在」

README 顶部版本徽章读的是 **GitHub Release**，不是仓库文件。
每次发版必须三步齐全：**commit → push → `python tools/_make_release.py`**。

### ⛔ 打包前先问少爷

修完 bug **不要默认打包**。先反馈「改了什么 / 怎么验证的」，问他要不要 APK。
例外：能论证「只有真机能验」时可以自己决定，但**要说明理由**。

### ⛔ 已经定的产品规则，不要擅自改

- 不长期保存照片；AI 只读票；**对奖在本地**
- 实体彩票唯一性去重（指纹是唯一能阻止入账的依据）
- **一二等奖金额必须人工确认**后才计入盈亏（§15）
- 大乐透奖池 ≥8 亿时：三 6666 / 四 380 / 五 200 / 六 18 / 七 7
  （平时六等奖 15；"20 元"是五等奖升档后的金额 —— 三个数不同奖级，别记串）
- 兑奖期限：开奖日 + 60 天；国庆头 3 天、春节头 7 天内到期顺延，其它不顺延
- 开奖时刻的判定**只能有 `core/DrawSchedule.kt` 一处**（核验 / 查询 / 展示三层都调它）

### ⛔ 别信侧面指标，信内容

- **不能用文件大小判断包是不是新的**：v1.6.1/1.6.2/1.6.3 的 APK 字节数完全相同
  （zip 压缩吸收差异）。要比 md5，或在 dex 里数新符号（`python tools/_dexprobe2.py`）
- **判断后台任务死没死**：看活进程 + 产物时间戳 + 日志结束标记，**别看日志尾巴**（可能正在写）
- **长任务不接管道**（`| tail`）：管道断裂会 SIGTERM 杀掉进程，退出码还在骗你

---

## 四、改完之后怎么验证（固定套路）

```bash
python tools/_build_and_test.py     # 同步 + 编译 + 单测；看 sync 行和「报告变化」
```

要交付安装包时：

```bash
python tools/_pack.py               # 双变体 + 签名（内含版本守卫）
python tools/_dexprobe2.py          # 拆 dex 证实新代码真进包
python tools/_release_guard.py      # 4 处版本号一致
python tools/_secret_sentinel.py    # 密钥扫描
```

> `_dexprobe2.py` 里的探针是**逐版本手工加**的。给新功能加探针时，
> 把本轮新增的类名 / 关键中文文案加进 `NEEDLES`，否则等于没验。

---

## 五、交付流程（照抄，别自由发挥）

1. 改权威源码
2. `python tools/_build_and_test.py`
3. **问少爷要不要打包**
4. `python tools/_pack.py`
5. `python tools/_dexprobe2.py`
6. `python tools/_release_guard.py`
7. 更新 `apk/真机验收清单.md`（**只放当前版本**，上一组剪到 `验收清单-历史.md`；
   红线 25 KB，超了说明忘了归档）
8. 追加 `.workbuddy/memory/<今天>.md`
9. 密钥哨兵 → `git add -A` → `git commit` → `git push`
10. `python tools/_make_release.py --notes "…"`

---

## 六、和少爷相处的几条

- 叫「**少爷**」，中文，**话要短**
- 他不懂黑话 —— 别用「口径漂移」「新管线」「投喂」这类自造词
- 对钱、对数据、对线上事故，立刻收起玩笑
- 讨论技术要具体：说清为什么选 A 不选 B，别给含糊建议
- **诚实认账**：你占用了他的等待时间就要说，别用"为了质量"糊过去
- 他提修改意见是常态，走迭代 —— 第一版给足，别怕他嫌多

---

## 七、现在做什么

1. 跑 `python tools/doctor.py`，把结果如实报给少爷
2. 读 `.workbuddy/memory/MEMORY.md` + 最近一份日志
3. 问他：这次要接着做什么？

**在他回答之前，不要动代码。**
