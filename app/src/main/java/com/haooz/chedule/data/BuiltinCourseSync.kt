package com.haooz.chedule.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 单人自用版：真·刷新课表。
 *
 * 手机直连学校教务接口抓课表（不再依赖路由器中转），聚合成 Course 后写进本地仓库。
 * 成功/失败都会把真实结果返回给调用方，不做任何「假装成功」的兜底；
 * 直连失败时明确报错且保留现有课表，绝不用旧数据覆盖。
 */
object BuiltinCourseSync {

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
            // 首选手机直连教务（国内公网可达，无需校园网），拿到的是最新数据。
            //
            // 注意：这里必须用 try-catch「表达式」一次性解构出结果，
            // 不能先声明 val 再在 try 里赋值——Kotlin 不允许在 try 块内给外部 val 赋值
            // （'val' cannot be reassigned），那样编译不过。
            val pair: Pair<String, String> = try {
                DirectKebiaoFetcher.fetchAsWeeksCacheJson(context) to "教务直连"
            } catch (e: Exception) {
                // 直连失败时是否回落 GitHub，取决于本地有没有课表：
                //  - 本地已有课表：绝不覆盖，直接报真实错误。
                //    兜底源更新频率低于教务，拿旧数据盖掉在用的课表比不刷新更有害。
                //  - 本地为空（全新安装 / 清过数据）：回落 GitHub，先让课表有内容可用。
                val repoNow = CourseRepository.getInstance(context.applicationContext)
                if (repoNow.getAllCourses().isNotEmpty()) {
                    return@withContext SyncResult(false, DirectKebiaoFetcher.hintOf(e))
                }
                val gh = fetchFromGithub()
                    ?: return@withContext SyncResult(false, DirectKebiaoFetcher.hintOf(e))
                gh to "GitHub 兜底"
            }
            val textSure = pair.first
            val src = pair.second

            val root = JSONObject(textSure)
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
                val online: Boolean = false,
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
                    // 教务系统将来给周六补课加"线上上课"标识时，这里直接读到
                    val online = o.optBoolean("online", false)
                    val key = o.optInt("day", 1).toString() + "|" +
                        secs.joinToString(",") + "|" + name + "|" + teacher + "|" + room
                    val g = groups.getOrPut(key) { Group() }
                    g.wks.add(w)
                    val row = Row(
                        o.optInt("day", 1), secs, name, teacher,
                        // 线上课把教室标成「线上」，保留原教室便于对照
                        if (online && room.isNotBlank()) "线上 · $room" else if (online) "线上" else room,
                        st, et, online,
                    )
                    if (g.row == null || online) {
                        g.row = row
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

            val tag = DirectKebiaoFetcher.BUILD_TAG
            SyncResult(
                ok = true,
                message = if (changed) {
                    "[$src] 已更新 ${courses.size} 门课（原始 $rawCount 条）\n构建 $tag"
                } else {
                    "[$src] 已是最新：${courses.size} 门课，无变化\n构建 $tag"
                },
                rawCount = rawCount,
                courseCount = courses.size,
                changed = changed,
            )
        } catch (e: Exception) {
            SyncResult(false, "刷新失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * 兜底数据源：手机直连教务失败时的退路。
     * raw.githubusercontent.com 在国内常连不上，所以先走 jsDelivr CDN，
     * 再走 GitHub Pages，最后才是 raw。任一源拿到有效数据就停。
     */
    private val GH_SOURCES = listOf(
        "https://cdn.jsdelivr.net/gh/WoShiYiGeManHuaJia/kebiao-page@main/weeks_cache.json",
        "https://woshiyigemanhuajia.github.io/kebiao-page/weeks_cache.json",
        "https://raw.githubusercontent.com/WoShiYiGeManHuaJia/kebiao-page/main/weeks_cache.json",
    )

    private fun fetchFromGithub(): String? {
        for (u in GH_SOURCES) {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = (java.net.URL(u).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                if (code !in 200..299) continue
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                if (text.isBlank()) continue
                // 必须真的带 weeks 字段，否则当成没拿到
                if (org.json.JSONObject(text).optJSONObject("weeks") == null) continue
                return text
            } catch (_: Exception) {
                // 换下一个源
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        return null
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
