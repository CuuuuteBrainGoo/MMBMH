package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API Key 掩码展示（少爷 2026-09-27 要求：保留头尾各 3 字符，中间 3 个星号）。
 *
 * 这里最要紧的一条是 [ApiKeyMask.isUnchangedMask]：
 * 判错的后果是**真密钥被掩码串覆盖**，之后所有识别都 401，
 * 而且从界面上完全看不出原因（Key 那栏看着「有值」）。
 *
 * ## ⚠️ 测试数据一律用「不像密钥」的字符串
 *
 * 这个文件被 GitHub 的密钥扫描拦过**两次**，教训很深：
 *
 * - 第一次：这里直接放了真 Key，被扫出来（合理）。
 * - 第二次：换成占位符，但**保持了真 Key 的形状**
 *   （`ark-` + 8-4-4-4-12-5 的分段），结果**又被扫出来**。
 *
 * 关键认知：**扫描器是模式匹配，不看值是否有效。**
 * 所以「把真值换成同形状的假值」根本没用 —— 形状本身就是触发条件。
 *
 * 现在改用纯字母、不含任何密钥特征的字符串（`abcdef...`）。
 * 测试关心的是「头尾各留 3 个、中间 3 个星号」这条规则，
 * 用什么样的字符串完全不重要 —— 之前为了"像真的"而保留形状，
 * 是完全没有收益的风险。
 */
class ApiKeyMaskTest {

    @Test
    fun `正常长度的 key 保留头尾各 3 个字符`() {
        // 用中性字符串，刻意不带任何密钥前缀/分段特征
        val key = "abcdefghijklmnopqrstuvwxyz"
        assertEquals("abc***xyz", ApiKeyMask.mask(key))
    }

    @Test
    fun `中间固定 3 个星号 不暴露真实长度`() {
        val short = ApiKeyMask.mask("1234567")!!            // 长度 7
        val long = ApiKeyMask.mask("x".repeat(200))!!
        // 两者的星号数量必须一样，否则「星号个数」会泄露密钥长度
        assertEquals(
            short.count { it == '*' },
            long.count { it == '*' }
        )
        assertEquals(3, ApiKeyMask.MIDDLE.length)
    }

    @Test
    fun `太短的 key 全部打码 不露出大半`() {
        // 长度 6 时头尾各 3 会正好拼出完整密钥 —— 必须全部打码
        assertEquals("******", ApiKeyMask.mask("abc123"))
        assertEquals("******", ApiKeyMask.mask("abcd"))
        assertEquals("******", ApiKeyMask.mask("ab"))
    }

    @Test
    fun `刚好超过阈值时开始保留头尾`() {
        assertEquals(6, "abc123".length)
        assertEquals("******", ApiKeyMask.mask("abc123"))
        assertEquals("abc***234", ApiKeyMask.mask("abc1234"))
    }

    @Test
    fun `空值返回 null 表示未配置`() {
        assertNull(ApiKeyMask.mask(null))
        assertNull(ApiKeyMask.mask(""))
        assertNull(ApiKeyMask.mask("   "))
    }

    @Test
    fun `判定 - 框里就是掩码时视为未修改`() {
        val stored = "abcdefghijklmnopqrstuvwxyz"
        val shown = ApiKeyMask.mask(stored)
        assertTrue(ApiKeyMask.isUnchangedMask(shown, stored))
        // 前后有空格也算没改（输入法容易带空格）
        assertTrue(ApiKeyMask.isUnchangedMask(" $shown ", stored))
    }

    @Test
    fun `判定 - 用户真的换了 key 时不能误判成未修改`() {
        val stored = "abcdefghijklmnopqrstuvwxyz"
        assertFalse(ApiKeyMask.isUnchangedMask("zyxwvutsrqponmlkjihgfedcba", stored))
        assertFalse(ApiKeyMask.isUnchangedMask("", stored))
        assertFalse(ApiKeyMask.isUnchangedMask(null, stored))
    }

    @Test
    fun `判定 - 没有已存 key 时永远不是未修改`() {
        assertFalse(ApiKeyMask.isUnchangedMask("anything", null))
        assertFalse(ApiKeyMask.isUnchangedMask(null, null))
    }

    @Test
    fun `判定 - 值恰好长得像掩码时 视为未修改是可接受的`() {
        // 边界：真密钥碰巧写成 `abc***xyz` 这种形状时，掩码后与自身相同，
        // 于是被判成「未修改」→ 保存时不覆盖 → 保持原值。
        // 结论无害（值本来就没变），记录在这里免得以后有人当 bug 改。
        val weird = "abc***xyz"
        assertEquals(weird, ApiKeyMask.mask(weird))
        assertTrue(ApiKeyMask.isUnchangedMask(weird, weird))
    }
}
