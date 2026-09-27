package com.bro.lotteryledger.repo

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「拜拜财神」清空范围的守护测试。
 *
 * ## 为什么要检查源码，而不是跑一遍看结果
 *
 * 清空逻辑要真跑得起 Room + 内存数据库，那是仪器测试的范围（这台机器跑不了）。
 * 但**真正的风险不在运行时，而在改动时**：
 *
 *  ① 有人给设置表加了「整表清空」→ 用户的平台配置全没了，
 *     而少爷明确要求「不得改动软件的任何设置项」
 *  ② 有人图省事改用 Room 的 `clearAllTables()` → 一次清掉全部 8 张表
 *  ③ 新增了业务表但忘了加清空 → **看起来清空了，实际有残留**
 *
 * ## 三个踩过的坑（都写在这里，免得下次重犯）
 *
 * 1. **Room 的 `@Query` 是 `RetentionPolicy.CLASS`**（编译期注解，为 KSP 服务），
 *    运行时反射**读不到**。第一版用注解反射，一条 SQL 都没拿到。
 * 2. **不能把「有 DELETE」等同于「会清空」**。`DELETE FROM ai_providers WHERE id=:id`
 *    是「删掉这一个平台配置」，完全正当；要禁的是**没有 WHERE 的整表清空**。
 *    第二版就栽在这，把合法的删单条判成了违规。
 * 3. **注释里也会出现关键字**。我在仓储层写了「绝不能改成 clearAllTables()」
 *    这句**警告注释**，结果被自己的检查抓成违规。检查前必须先剥注释。
 */
class WipeScopeGuardTest {

    /** 允许被**整表清空**的表。只有业务数据，没有任何设置表。 */
    private val allowedTables = setOf(
        "tickets",          // 票
        "ticket_bets",      // 投注号码
        "recognition_runs", // AI 调用记录
        "drafts",           // 核对草稿
        "edit_logs",        // 修改留痕
        "draw_results"      // 开奖结果缓存
    )

    private val daoSource: String by lazy {
        readSource("src/main/java/com/bro/lotteryledger/db/Daos.kt")
    }
    private val repoSource: String by lazy {
        readSource("src/main/java/com/bro/lotteryledger/repo/LedgerRepository.kt")
    }

    /**
     * 读源码文件。
     *
     * Gradle 跑单元测试时工作目录是模块目录（`app/`），但不同环境可能不同，
     * 所以试几个常见位置。**都找不到就断言失败** —— 静默跳过等于这个测试白写。
     */
    private fun readSource(rel: String): String {
        val candidates = listOf(File(rel), File("app/$rel"), File("../app/$rel"))
        val f = candidates.firstOrNull { it.exists() }
        assertNotNull(
            "找不到 $rel。试过：${candidates.joinToString { it.absolutePath }}",
            f
        )
        return f!!.readText(Charsets.UTF_8)
    }

    /**
     * 剥掉块注释和整行注释。
     *
     * 必须做这一步：源码里「不要用 clearAllTables」这类**警告注释**
     * 会被关键字检查误判成违规（真踩过）。
     * 只删「行首可选空白 + //」的行注释，不碰 URL 里的 `//`。
     */
    private fun stripComments(src: String): String =
        src.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""^[ \t]*//.*$""", RegexOption.MULTILINE), "")

    /** 一条 DELETE 语句：表名、`FROM xxx` 之后的原文、是否带 WHERE。 */
    private data class DeleteStmt(val table: String, val rest: String) {
        /** 没有 WHERE = 整表清空。 */
        val wipesWholeTable: Boolean get() = !rest.contains("WHERE", ignoreCase = true)
    }

    /** 抽出源码里所有 DELETE 语句。 */
    private fun deleteStatements(src: String): List<DeleteStmt> =
        Regex("""DELETE\s+FROM\s+(\w+)([^\n]*)""", RegexOption.IGNORE_CASE)
            .findAll(stripComments(src))
            .map { m ->
                DeleteStmt(
                    table = m.groupValues[1].lowercase(),
                    rest = m.groupValues[2].trim()
                )
            }
            .toList()

    @Test
    fun `整表清空的表必须全部在白名单里`() {
        val wiping = deleteStatements(daoSource).filter { it.wipesWholeTable }
        assertTrue(
            "一条「整表清空」语句都没找到 —— 说明检查本身失效了（源文件读错了？）",
            wiping.isNotEmpty()
        )
        val offenders = wiping.map { it.table }.toSet() - allowedTables
        assertTrue(
            "这些表被整表清空了，但不在白名单里（很可能清到了用户的设置）：$offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `每张业务表都有整表清空语句`() {
        // 防「漏清一张表」：那种情况界面看着清空了，数据还躺在库里，
        // 下次统计或导出时又冒出来，很难查。
        val wiping = deleteStatements(daoSource)
            .filter { it.wipesWholeTable }
            .map { it.table }
            .toSet()
        val missing = allowedTables - wiping
        assertTrue(
            "这些业务表没有整表清空语句 —— 清空台账会漏掉它们，留下孤儿数据：$missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `设置表上只允许删单条 不允许整表清空`() {
        // 删掉某一个平台配置是正当功能（AiProviderDao 就有）；
        // 但**整表清空**意味着用户的全部配置一次性消失 —— 这条必须拦住。
        val settingStmts = deleteStatements(daoSource)
            .filter { it.table.contains("provider") || it.table.contains("setting") }

        assertTrue(
            "没找到设置表上的任何 DELETE（结构变了？那这条检查要重写）",
            settingStmts.isNotEmpty()
        )
        val bad = settingStmts.filter { it.wipesWholeTable }
        assertTrue(
            "设置表被整表清空了：${bad.map { it.table }} —— " +
                "这不只是删一条，是把用户配好的东西全清了",
            bad.isEmpty()
        )
    }

    @Test
    fun `仓储层不能调用 clearAllTables`() {
        // Room 的便利 API，一次清掉全部 8 张表 —— 包括平台配置和设置项。
        // 它内部由 Room 生成 SQL，源码里没有 DELETE 字符串，
        // 所以上面几条检查抓不到，必须单独盯。
        assertTrue(
            "LedgerRepository 里调用了 clearAllTables —— 它会清掉全部表（含设置），" +
                "直接违反「不得改动软件的任何设置项」。清空必须逐表删。",
            !stripComments(repoSource).contains("clearAllTables")
        )
    }

    @Test
    fun `白名单本身不能含设置表`() {
        // 防「有人图省事往白名单里加表名」—— 白名单是这道防线的根
        val banned = listOf("provider", "setting", "secure", "key")
        val bad = allowedTables.filter { t -> banned.any { t.contains(it, ignoreCase = true) } }
        assertTrue("白名单里混进了疑似设置表的表名：$bad", bad.isEmpty())
    }
}
