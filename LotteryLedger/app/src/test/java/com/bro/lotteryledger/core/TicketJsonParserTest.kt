package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器测试（§2.3 / §4）。
 *
 * 重点验证：**模型给的脏数据不会被静默修正**。
 */
class TicketJsonParserTest {

    private val goodJson = """
    {
      "schema_version": 1,
      "lottery_type": "ssq",
      "lottery_name": "双色球",
      "issue": "2026111",
      "purchase_time": "2026-09-24 13:54:02",
      "draw_date": "2026-09-24",
      "bet_type": "single",
      "multiple": 1,
      "additional": false,
      "amount_yuan": 10,
      "ticket_identifiers": {
        "verification_code": null,
        "ticket_number": null,
        "serial_number": "0012",
        "terminal_number": "03",
        "station_number": "11001",
        "barcode_raw": null
      },
      "bets": [
        {
          "index": 1,
          "groups": [
            {"name": "red", "numbers": ["02","07","17","18","22","26"]},
            {"name": "blue", "numbers": ["13"]}
          ]
        }
      ],
      "recognition": {"status": "complete", "uncertain_fields": []}
    }
    """.trimIndent()

    @Test
    fun `解析标准输出`() {
        val r = TicketJsonParser.parse(goodJson)
        assertTrue("应解析成功: ${r.issues}", r.ok)
        val t = r.ticket!!
        assertEquals(LotteryType.SSQ, t.lotteryType)
        assertEquals("2026111", t.issue)
        assertEquals("2026-09-24", t.drawDate)
        assertEquals("2026-09-24 13:54:02", t.purchaseTime)
        assertEquals(1, t.bets.size)
        assertEquals(listOf("02", "07", "17", "18", "22", "26"), t.bets[0].reds())
        assertEquals(listOf("13"), t.bets[0].blues())
        assertEquals("11001", t.identifiers.stationNumber)
        assertEquals(RecognitionStatus.VERIFIED, t.recognitionStatus)
    }

    @Test
    fun `剥离 markdown 代码围栏`() {
        val wrapped = "```json\n$goodJson\n```"
        assertTrue(TicketJsonParser.parse(wrapped).ok)
    }

    @Test
    fun `剥离前后废话`() {
        val noisy = "好的，我识别到以下内容：\n$goodJson\n希望有帮助。"
        assertTrue(TicketJsonParser.parse(noisy).ok)
    }

    @Test
    fun `完全不是 JSON 应报致命错`() {
        val r = TicketJsonParser.parse("抱歉我无法识别这张图片")
        assertFalse(r.ok)
        assertNull(r.ticket)
        assertTrue(r.issues.any { it.fatal })
    }

    // ---------- §2.3 绝不猜测 ----------

    @Test
    fun `一位数号码不被自动补零且产生提示`() {
        val bad = goodJson.replace("\"02\",\"07\"", "\"2\",\"07\"")
        val r = TicketJsonParser.parse(bad)
        val nums = r.ticket!!.bets[0].reds()
        assertEquals("必须原样保留 '2'，不能补成 '02'", "2", nums[0])
        assertTrue("应产生格式提示", r.issues.any { it.message.contains("格式不规范") })
    }

    @Test
    fun `号码为 null 时记录为无法辨认`() {
        val bad = goodJson.replace("\"02\",", "null,")
        val r = TicketJsonParser.parse(bad)
        assertTrue("应报告该号码无法辨认", r.issues.any { it.message.contains("无法辨认") })
        assertEquals("null 位置应被剔除", 5, r.ticket!!.bets[0].reds().size)
    }

    @Test
    fun `候选号码导致 needs_review`() {
        val withCand = goodJson.replace(
            """{"name": "blue", "numbers": ["13"]}""",
            """{"name": "blue", "numbers": ["13"], "candidates": [["13"],["18"]]}"""
        )
        val r = TicketJsonParser.parse(withCand)
        assertTrue(r.ticket!!.bets[0].isAmbiguous)
        assertEquals(RecognitionStatus.NEEDS_REVIEW, r.ticket!!.recognitionStatus)
    }

    @Test
    fun `uncertain_fields 非空导致 needs_review`() {
        val bad = goodJson.replace("\"uncertain_fields\": []", "\"uncertain_fields\": [\"bets[0].groups[0]\"]")
        val r = TicketJsonParser.parse(bad)
        assertEquals(RecognitionStatus.NEEDS_REVIEW, r.ticket!!.recognitionStatus)
    }

    @Test
    fun `status 为 partial 导致 needs_review`() {
        val bad = goodJson.replace("\"status\": \"complete\"", "\"status\": \"partial\"")
        assertEquals(RecognitionStatus.NEEDS_REVIEW, TicketJsonParser.parse(bad).ticket!!.recognitionStatus)
    }

    @Test
    fun `未知彩种报致命错`() {
        val bad = goodJson.replace("\"ssq\"", "\"fc3d\"")
        val r = TicketJsonParser.parse(bad)
        assertTrue(r.issues.any { it.fatal })
    }

    @Test
    fun `大乐透前后区命名被正确识别`() {
        val dlt = goodJson
            .replace("\"ssq\"", "\"dlt\"")
            .replace("\"red\"", "\"front\"")
            .replace("\"blue\"", "\"back\"")
            .replace("[\"02\",\"07\",\"17\",\"18\",\"22\",\"26\"]", "[\"01\",\"05\",\"12\",\"23\",\"34\"]")
            .replace("[\"13\"]", "[\"04\",\"08\"]")
        val r = TicketJsonParser.parse(dlt)
        assertTrue(r.ok)
        assertEquals(LotteryType.DLT, r.ticket!!.lotteryType)
        assertEquals(listOf("01", "05", "12", "23", "34"), r.ticket!!.bets[0].fronts())
        assertEquals(listOf("04", "08"), r.ticket!!.bets[0].backs())
    }

    @Test
    fun `时间格式不规范时不猜测而是置空`() {
        val bad = goodJson.replace("2026-09-24 13:54:02", "昨天下午")
        val r = TicketJsonParser.parse(bad)
        assertNull("无法识别的时间必须置空", r.ticket!!.purchaseTime)
        assertTrue(r.issues.any { it.path == "purchase_time" })
    }

    @Test
    fun `提取 JSON 能处理嵌套花括号`() {
        val extracted = TicketJsonParser.extractJson(goodJson)
        assertNotNull(extracted)
        assertTrue(extracted!!.startsWith("{"))
        assertTrue(extracted.endsWith("}"))
    }
}
