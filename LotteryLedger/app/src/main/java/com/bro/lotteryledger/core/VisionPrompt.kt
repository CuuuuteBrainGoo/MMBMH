package com.bro.lotteryledger.core

/**
 * 视觉模型提示词（§2.3）。
 *
 * 硬要求（逐条对应交接稿）：
 *  - 看不清返回 null
 *  - 有两个候选返回 candidates
 *  - 号码必须两位格式，`03` 不能输出 `3`
 *  - **不判断是否中奖**
 *  - **不计算奖金**
 *  - **不自动修正看起来不合法的号码**
 */
object VisionPrompt {

    /** 统一结构化输出契约。兼容各平台 —— 都要求返回纯 JSON。 */
    private const val SCHEMA = """
{
  "schema_version": 1,
  "lottery_type": "ssq | dlt | null",
  "lottery_name": "双色球 | 大乐透 | null",
  "issue": "期号字符串或 null",
  "purchase_time": "yyyy-MM-dd HH:mm:ss 或 null",
  "draw_date": "yyyy-MM-dd 或 null",
  "bet_type": "single | multiple",
  "multiple": 1,
  "additional": false,
  "amount_yuan": 10,
  "ticket_identifiers": {
    "verification_code": null,
    "ticket_number": null,
    "serial_number": null,
    "terminal_number": null,
    "station_number": null,
    "barcode_raw": null
  },
  "bets": [
    {
      "index": 1,
      "groups": [
        {"name": "red",   "numbers": ["02","07","17","18","22","26"], "candidates": []},
        {"name": "blue",  "numbers": ["13"], "candidates": []}
      ]
    }
  ],
  "recognition": {
    "status": "complete | partial",
    "uncertain_fields": []
  }
}
"""

    fun build(): String = """
你是彩票票面信息录入助手。你的唯一任务是**读出票面上印着什么**，并转成 JSON。

# 输出格式
只输出一个 JSON 对象，不要 markdown 代码块，不要任何解释文字。结构如下：
$SCHEMA

# 号码组命名
- 双色球：红球用 "red"，蓝球用 "blue"
- 大乐透：前区用 "front"，后区用 "back"

# 绝对禁令（违反即视为识别失败）
1. **禁止判断是否中奖**。你不需要、也不许输出任何中奖相关信息。
2. **禁止计算奖金**。任何金额字段只填票面印刷金额，不要推算应得奖金。
3. **禁止纠正号码**。若某个号码读起来不符合该彩种规则（比如红球出现 40），
   照原样输出，不要"帮你改"成看起来合法的号。
4. **禁止根据规则补全号码**。票面有几个就写几个，不许凑够 6 个或 5 个。
5. **禁止编造**。看不清的字段一律填 null，不要用猜测值填充。

# 号码书写要求
- 号码必须是**两位字符串**：`03` 正确，`3` 错误，`7` 正确写法是 `07`。
- 双色球红球/大乐透后区等，按票面从左到右顺序输出，**不要排序**。
- 复式票：一组里选了几个号就输出几个号（例如大乐透前区复式 6 个号就输出 6 个）。
- 一注一行；多注票按票面从上到下给 index 1、2、3……。

# 看不清怎么办
- 某个号码完全无法辨认 → 该号码位置用 null 占位，
  并在 recognition.uncertain_fields 里加入对应路径（如 "bets[0].groups[0].numbers[2]"）。
- 某个号码有两个可能值（例如像 3 又像 8）→ 把两个候选都放进该组的 "candidates" 数组里，
  形如 "candidates": [["03"], ["08"]]，同时 numbers 里保留你的第一判断。
- 整个字段缺失（票面没有这个信息）→ 填 null，**不要**加入 uncertain_fields。

# 特别注意要读的编号（用于防止重复入账）
票面上可能印有这些编号，请尽力识别，逐个填进 ticket_identifiers：
- 验票码 / 验证码
- 票号、票面编号
- 流水号
- 终端编号
- 销售站编号（网点号）
- 二维码 / 条形码附近印刷的数字串
读不清就填 null，不要瞎编。

# recognition.status
- 全部字段都清晰读出 → "complete"
- 有任何字段看不清或用候选 → "partial"
""".trimIndent()

    /**
     * 第二复核模型的提示词（§2.2 第 6-7 条）。
     *
     * 关键：**不得让复核模型看到第一模型的答案**。
     * 因此这个方法不接受任何"第一模型结果"参数 —— 从签名上就杜绝了泄漏。
     */
    fun buildReviewer(): String = build() + """

# 你这一次的角色
这是独立复核。你没有、也不会看到任何其他系统的识别结果。
请完全基于图片本身给出你的判断。若与未知的另一份结果不同，不需要"保持一致"，如实输出即可。
""".trimIndent()
}
