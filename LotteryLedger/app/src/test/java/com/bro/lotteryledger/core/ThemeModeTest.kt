package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主题模式（少爷 2026-09-27 第 6 条）。
 *
 * 关键点：**默认必须是 SYSTEM**。老用户升级上来时行为要跟以前一致
 * （以前就是纯跟随系统），不能因为加了设置项就把人家的深色变浅色。
 * 存储里读出来的字符串认不出来时，也必须回落 SYSTEM，不能崩。
 */
class ThemeModeTest {

    @Test
    fun `默认是跟随系统`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.from(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.from(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.from("乱七八糟"))
    }

    @Test
    fun `三个值都能还原`() {
        ThemeMode.entries.forEach { m ->
            assertEquals(m, ThemeMode.from(m.code))
        }
    }

    @Test
    fun `code 唯一且非空`() {
        val codes = ThemeMode.entries.map { it.code }
        assertEquals("code 不能重复", codes.size, codes.toSet().size)
        assertEquals(3, codes.size)
    }
}
