package com.haooz.chedule.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 单人自用版：真·刷新课表。
 *
 * 从公开仓库拉 weeks_cache.json（路由器定时跑出来的最新课表），
 * 聚合成 Course 后写进本地仓库。成功/失败都会把真实结果返回给调用方，
 * 不做任何「假装成功」的兜底。
 */
object BuiltinCourseSync {

    /**
     * 多源兜底：raw.githubusercontent.com 在国内常常连不上，
     * 所以先走 jsDelivr CDN，再走 GitHub Pages，最后才是 raw。
     * 任何一个源拿到有效数据就停，全部失败才报失败——绝不假装成功。
     */
    private val SOURCES = listOf(
        "https://cdn.jsdelivr.net/gh/WoShiYiGeManHuaJia/kebiao-page@main/weeks_cache.json",
        "https://woshiyigemanhuajia.github.io/kebiao-page/weeks_cache.json",
        "https://raw.githubusercontent.com/WoShiYiGeManHuaJia/kebiao-page/main/weeks_cache.json",
    )

    /** 拉取文本：逐源尝试，返回 (文本, 失败原因列表) */
    private fun fetchText(): Pair<String?, String> {
        val errs = ArrayList<String>()
        for (url in SOURCES) {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    errs.add("${hostOf(url)} HTTP $code")
                    conn.disconnect()
                    continue
                }
                val text = BufferedReader(
                    InputStreamReader(conn.inputStream, Charsets.UTF_8)
                ).use { it.readText() }
                conn.disconnect()
                if (text.isBlank() || !text.trimStart().startsWith("{")) {
                    errs.add("${hostOf(url)} 内容异常")
                    continue
                }
                return Pair(text, "")
            } catch (e: Exception) {
                errs.add("${hostOf(url)} ${e.javaClass.simpleName}")
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        return Pair(null, errs.joinToString("；"))
    }

    private fun hostOf(url: String): String =
        url.removePrefix("https://").takeWhile { it != '/' }

    data class SyncResult(
        val ok: Boolean,
        val message: String,
        val rawCount: Int = 0,
        val courseCount: Int = 0,
        val changed: Boolean = false,
    )

    private val COLORS = longArrayOf(
        0xFF4CAF50L, 0xFF2196F3L, 0xFFFF9800L, 0xFFF44336L, 0xFFE6B422L,
        0xFFE91E63L, 0xFF00BCD4L, 0xFF3F51B5L, 0xFFAB47BCL, 0xFF009688L, 0xFF673AB7L
    )

    suspend fun sync(context: Context): SyncResult = withContext(Dispatchers.IO) {
        try {
            val (text0, fetchErr) = fetchText()
            if (text0 == null) {
                return@withContext SyncResult(false, "全部源都取不到：$fetchErr")
            }
            val text = text0

            val root = JSONObject(text)
            val weeks = root.optJSONObject("weeks")
                ?: return@withContext SyncResult(false, "数据里没有 weeks 字段")

            data class Row(
                val day: Int,
                val secs: List<Int>,
                val name: String,
                val teacher: String,
                val room: String,
                val start: String,
                val end: String,
            )
            data class Group(
                val wks: MutableSet<Int> = java.util.TreeSet(),
                var row: Row? = null,
            )

            val groups = LinkedHashMap<String, Group>()
            var rawCount = 0

            val keys = weeks.keys()
            while (keys.hasNext()) {
                val wk = keys.next()
                val w = wk.toIntOrNull() ?: continue
                val arr = weeks.optJSONArray(wk) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    rawCount++
                    val secsArr = o.optJSONArray("secs")
                    val secs = ArrayList<Int>()
                    if (secsArr != null) {
                        for (k in 0 until secsArr.length()) {
                            val v = secsArr.optInt(k, -1)
                            if (v > 0) secs.add(v)
                        }
                    }
                    if (secs.isEmpty()) continue
                    val time = o.optString("time", "")
                    val st = if (time.contains("-")) time.substringBefore("-") else ""
                    val et = if (time.contains("-")) time.substringAfter("-") else ""
                    val name = o.optString("name", "")
                    val teacher = o.optString("teacher", "")
                    val room = o.optString("room", "")
                    val key = o.optInt("day", 1).toString() + "|" +
                        secs.joinToString(",") + "|" + name + "|" + teacher + "|" + room
                    val g = groups.getOrPut(key) { Group() }
                    g.wks.add(w)
                    if (g.row == null) {
                        g.row = Row(o.optInt("day", 1), secs, name, teacher, room, st, et)
                    }
                }
            }

            if (rawCount == 0) return@withContext SyncResult(false, "数据里没有课程")

            val sorted = groups.values.sortedWith(
                compareBy<Group> { it.row?.day ?: 1 }
                    .thenBy { it.row?.secs?.firstOrNull() ?: 1 }
                    .thenBy { it.row?.name ?: "" }
            )

            val courses = ArrayList<Course>(sorted.size)
            sorted.forEachIndexed { index, g ->
                val r = g.row ?: return@forEachIndexed
                val wks = ArrayList(g.wks)
                val color = COLORS[Math.floorMod(r.name.hashCode(), COLORS.size)]
                courses.add(
                    Course(
                        id = "kb_%03d".format(index + 1),
                        name = r.name,
                        classroom = r.room,
                        teacher = r.teacher,
                        dayOfWeek = r.day,
                        startSection = r.secs.first(),
                        endSection = r.secs.last(),
                        startWeek = wks.first(),
                        endWeek = wks.last(),
                        weekType = Course.WEEK_TYPE_ALL,
                        colorRes = color,
                        selectedWeeks = wks,
                        scheduleId = "",
                        lastModified = System.currentTimeMillis(),
                        isCustomTime = r.start.isNotBlank() && r.end.isNotBlank(),
                        customStartTime = r.start.ifBlank { null },
                        customEndTime = r.end.ifBlank { null },
                    )
                )
            }

            val repo = CourseRepository.getInstance(context.applicationContext)
            val oldFp = fingerprint(repo.getAllCourses())
            val newFp = fingerprint(courses)
            val changed = oldFp != newFp
            repo.saveCourses(courses)

            // 不覆盖当前周：数据源里的 current_week 是路由器跑的那天，
            // 用它会把周次拽回去。周次由 App 按开学日期自行推算。

            SyncResult(
                ok = true,
                message = if (changed) {
                    "已更新 ${courses.size} 门课（原始 $rawCount 条）"
                } else {
                    "已是最新：${courses.size} 门课，无变化"
                },
                rawCount = rawCount,
                courseCount = courses.size,
                changed = changed,
            )
        } catch (e: Exception) {
            SyncResult(false, "刷新失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun fingerprint(list: List<Course>): String = list
        .sortedBy { it.id }
        .joinToString(";") {
            listOf(
                it.name, it.classroom, it.teacher, it.dayOfWeek.toString(),
                it.startSection.toString(), it.endSection.toString(),
                it.selectedWeeks.joinToString(","),
            ).joinToString("|")
        }
}
