package com.haooz.chedule.data

import java.time.LocalDate

/**
 * 智能节假日/调休解析器（单人自用版 · 支持多天/多条）
 *
 * 目标：一句话搞定，不要再点好几个日期选择器。
 * 例：
 *   "10月1日到10月7日放假"              -> 假期 10-01 ~ 10-07（1 条）
 *   "10月1日、10月2日放假"              -> 假期 10-01、10-02（2 条）
 *   "10月11日补10月7日的课"             -> 调休：10-11 上 10-07 那天的课
 *   "9月17日上9月21日的课，9月18日上9月22日的课" -> 调休 2 条
 *   "10月1日、2日、3日放假"             -> 省略月份自动继承（3 条）
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

    // 裸日期：只有"2日""3号"，没有月份
    private val BARE_RE = Regex("""(\d{1,2})\s*[月/-]\s*(\d{1,2})\s*[日号]?|(\d{1,2})\s*[日号]""")

    // 明显是"补/上/调"的动词
    private val SWAP_WORDS = listOf("补", "调休", "调课", "上班", "补课", "上.*?的课", "补.*?的课")
    // 「上9月21日的课」这类没有"补"字的说法，需要用正则判断
    private val SWAP_RE = Regex("[上补调改][^，,。；;]{0,10}课|调休|上班|补课|上课|改上")
    private val HOLIDAY_WORDS = listOf("放假", "休息", "假期", "休", "假")
    private val RANGE_WORDS = listOf("到", "至", "~", "—", "～")

    /** 解析整段文本，返回所有识别出的条目 */
    fun parseMulti(text: String, defaultYear: Int): List<Parsed> {
        val raw = text.trim()
        if (raw.isBlank()) return emptyList()

        val normalized = normalizeBareDays(raw)
        // 按标点切句：逗号/句号/分号/换行
        val clauses = normalized.split(Regex("[，,。；;\\n]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val out = ArrayList<Parsed>()
        for (clause in clauses) {
            out.addAll(parseClause(clause, defaultYear))
        }
        // 单句场景兜底：整句只识别出一个日期时，再拿全句试一次区间
        if (out.isEmpty()) {
            parseClause(normalized, defaultYear).let { out.addAll(it) }
        }
        return out
    }

    /** 兼容旧调用：只返回第一条 */
    fun parse(text: String, defaultYear: Int): Parsed? =
        parseMulti(text, defaultYear).firstOrNull()

    private fun parseClause(clause: String, defaultYear: Int): List<Parsed> {
        val dates = extractDates(clause, defaultYear)
        if (dates.isEmpty()) return emptyList()

        val isSwap = (SWAP_WORDS.any { clause.contains(it) } || SWAP_RE.containsMatchIn(clause)) &&
            !clause.contains("不补") && !clause.contains("不调")
        val isHoliday = HOLIDAY_WORDS.any { clause.contains(it) } || clause.contains("放")
        val hasRange = RANGE_WORDS.any { clause.contains(it) }

        return if (isSwap && !isHoliday) {
            parseSwap(clause, dates)
        } else {
            parseHoliday(dates, hasRange)
        }
    }

    /** 调休：动词前的日期为"上课日"，动词后的日期为"被补的那天"；按顺序一一配对 */
    private fun parseSwap(clause: String, dates: List<LocalDate>): List<Parsed> {
        if (dates.size < 2) return emptyList()

        // 找到动词在句中的位置，用它把日期分成前后两组
        val verbIdx = clause.indexOfAny(listOf("上", "补", "调", "改"))
        val firstDatePos = clause.indexOf(dates[0].dayOfMonth.toString())

        val attend: List<LocalDate>
        val follow: List<LocalDate>
        if (verbIdx >= 0 && firstDatePos >= 0 && verbIdx > firstDatePos && dates.size >= 2) {
            // 形如「10月11日 补 10月7日、10月8日的课」：动词前 1 个上课日，动词后多个被补日
            attend = listOf(dates[0])
            follow = dates.drop(1)
        } else {
            // 日期数量对称时按前半/后半配对
            val half = dates.size / 2
            attend = dates.take(half)
            follow = dates.drop(half)
        }

        val n = minOf(attend.size, follow.size)
        val out = ArrayList<Parsed>()
        for (i in 0 until n) {
            val a = attend[i]
            val f = follow[i]
            out.add(
                Parsed(
                    type = com.haooz.chedule.data.HolidayManager.TYPE_WORKSWAP,
                    startDate = a.toString(),
                    endDate = a.toString(),
                    name = "调休（补${f.monthValue}月${f.dayOfMonth}日的课）",
                    followDate = f.toString(),
                    summary = "${fmt(a)} 上 ${fmt(f)} 那天的课",
                )
            )
        }
        return out
    }

    /** 放假：有"到/至"按区间一条；否则每个日期各一条 */
    private fun parseHoliday(dates: List<LocalDate>, hasRange: Boolean): List<Parsed> {
        val out = ArrayList<Parsed>()
        if (hasRange && dates.size >= 2) {
            val start = dates[0]
            val end = if (dates[1].isBefore(start)) start else dates[1]
            val days = java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt() + 1
            out.add(
                Parsed(
                    type = com.haooz.chedule.data.HolidayManager.TYPE_HOLIDAY,
                    startDate = start.toString(),
                    endDate = end.toString(),
                    name = if (days > 1) "节假日（${days}天）" else "节假日",
                    followDate = null,
                    summary = if (days > 1) "${fmt(start)} ~ ${fmt(end)} 放假（共${days}天）"
                    else "${fmt(start)} 放假",
                )
            )
            return out
        }
        for (d in dates) {
            out.add(
                Parsed(
                    type = com.haooz.chedule.data.HolidayManager.TYPE_HOLIDAY,
                    startDate = d.toString(),
                    endDate = d.toString(),
                    name = "节假日",
                    followDate = null,
                    summary = "${fmt(d)} 放假",
                )
            )
        }
        return out
    }

    /** 把"1日、2日、3日"这种省略月份的补成"10月1日、10月2日、10月3日" */
    private fun normalizeBareDays(text: String): String {
        var lastMonth = -1
        val out = StringBuilder()
        var idx = 0
        for (m in BARE_RE.findAll(text)) {
            out.append(text.substring(idx, m.range.first))
            val withMonth = m.groupValues[1]
            val bare = m.groupValues[3]
            if (withMonth.isNotBlank()) {
                lastMonth = withMonth.toIntOrNull() ?: lastMonth
                out.append(m.value)
            } else if (bare.isNotBlank() && lastMonth > 0) {
                out.append("${lastMonth}月${bare}日")
            } else {
                out.append(m.value)
            }
            idx = m.range.last + 1
        }
        out.append(text.substring(idx))
        return out.toString()
    }

    private fun extractDates(text: String, defaultYear: Int): List<LocalDate> {
        val out = LinkedHashMap<String, LocalDate>()
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
