package com.haooz.chedule.data

import java.time.LocalDate

/**
 * 智能节假日/调休解析器（单人自用版）
 *
 * 目标：一句话搞定，不要再点好几个日期选择器。
 * 例：
 *   "10月1日到10月7日放假"      -> 假期 10-01 ~ 10-07
 *   "10月11日补10月7日的课"     -> 调休：10-11 上 10-07 那天的课
 *   "9月28日上班，补10月8日的课" -> 同上
 */
object SmartHolidayParser {

    data class Parsed(
        val type: Int,                  // HolidayManager.TYPE_HOLIDAY / TYPE_WORKSWAP
        val startDate: String,          // yyyy-MM-dd
        val endDate: String,            // 假期用；调休等于 startDate
        val name: String,
        val followDate: String?,        // 调休跟随的那天
        val summary: String,            // 给用户的确认文案
    )

    // 10月1日 / 10月1号 / 10-01 / 10/1 / 2026-10-01
    private val DATE_RE = Regex(
        """(?:(\d{4})\s*[-/年.])?\s*(\d{1,2})\s*[-/月.]\s*(\d{1,2})\s*[日号]?"""
    )

    // 明显是"补/上/调"的动词
    private val SWAP_WORDS = listOf("补", "调休", "调课", "上班", "补课", "上.*?的课", "补.*?的课")
    private val HOLIDAY_WORDS = listOf("放假", "休息", "假期", "休", "假")

    fun parse(text: String, defaultYear: Int): Parsed? {
        val raw = text.trim()
        if (raw.isBlank()) return null

        val dates = extractDates(raw, defaultYear)
        if (dates.isEmpty()) return null

        val isSwap = SWAP_WORDS.any { raw.contains(it) } &&
            !raw.contains("不补") && !raw.contains("不调")
        val isHoliday = HOLIDAY_WORDS.any { raw.contains(it) } || raw.contains("放")

        return when {
            // 调休：出现两个及以上日期，且含"补/上/调"语义
            isSwap && dates.size >= 2 -> {
                val attend = dates[0]
                val follow = dates[1]
                Parsed(
                    type = com.haooz.chedule.data.HolidayManager.TYPE_WORKSWAP,
                    startDate = attend.toString(),
                    endDate = attend.toString(),
                    name = "调休（补${follow.monthValue}月${follow.dayOfMonth}日的课）",
                    followDate = follow.toString(),
                    summary = "${fmt(attend)} 上 ${fmt(follow)} 那天的课",
                )
            }

            // 假期：一个或两个日期（两个即区间）
            isHoliday || !isSwap -> {
                val start = dates[0]
                val end = if (dates.size >= 2 && hasRangeWord(raw)) dates[1] else start
                val realEnd = if (end.isBefore(start)) start else end
                val days = java.time.temporal.ChronoUnit.DAYS.between(start, realEnd).toInt() + 1
                Parsed(
                    type = com.haooz.chedule.data.HolidayManager.TYPE_HOLIDAY,
                    startDate = start.toString(),
                    endDate = realEnd.toString(),
                    name = if (days > 1) "节假日（${days}天）" else "节假日",
                    followDate = null,
                    summary = if (days > 1) "${fmt(start)} ~ ${fmt(realEnd)} 放假（共${days}天）"
                    else "${fmt(start)} 放假",
                )
            }

            else -> null
        }
    }

    private fun hasRangeWord(raw: String): Boolean =
        raw.contains("到") || raw.contains("至") || raw.contains("~") ||
            raw.contains("—") || raw.contains("-") || raw.contains("～")

    private fun extractDates(text: String, defaultYear: Int): List<LocalDate> {
        val out = LinkedHashMap<String, LocalDate>()
        // 优先匹配带年份的，避免把 "2026-10-01" 拆成两段
        for (m in DATE_RE.findAll(text)) {
            val y = m.groupValues[1].toIntOrNull() ?: defaultYear
            val mo = m.groupValues[2].toIntOrNull() ?: continue
            val d = m.groupValues[3].toIntOrNull() ?: continue
            val date = try {
                LocalDate.of(y, mo, d)
            } catch (_: Exception) {
                continue
            }
            out[date.toString()] = date
        }
        return out.values.toList()
    }

    private fun fmt(d: LocalDate) = "${d.monthValue}月${d.dayOfMonth}日"
}
