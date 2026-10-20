package com.haooz.chedule.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 真·教务直连：抓 jw.hnsoftedu.com/jsxsd 的课表 HTML 并解析。
 *
 * 之前走的是 222.243.161.213:81/hnrjzyxyhd 那套 JSON 接口，
 * 实测 TCP 握手 48ms 通、HTTP 登录必超时，且解析出来的课表与网页不一致。
 * 浏览器实际访问的是 /jsxsd/ 这套强智页面，凭 JSESSIONID 即可取到课表，
 * 这里照搬同样请求，再把表格解析成 weeks_cache 结构。
 *
 * 全程只访问教务域名，不经过任何境外服务器。
 */
object JwKebiaoFetcher {

    private const val BASE = "http://jw.hnsoftedu.com"
    private const val URL_KB = "$BASE/jsxsd/xskb/xskb_list.do"
    private const val PREFS = "kb_jw"
    private const val K_COOKIE = "cookie"

    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 默认 Cookie：会话失效后在「关于 → 课表直连·诊断」里粘贴新的即可。 */
    private const val DEFAULT_COOKIE =
        "JSESSIONID=290D7DE3E32E87645E3E1FE50D862563; SERVERID=123"

    fun loadCookie(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(K_COOKIE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_COOKIE
    }

    fun saveCookie(context: Context, c: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_COOKIE, c.trim()).apply()
    }

    /** 抓课表并解析成 weeks_cache 结构（与 DirectKebiaoFetcher 返回一致，可直接复用解析）。 */
    fun fetchAsWeeksCacheJson(context: Context): String {
        val html = get(URL_KB, loadCookie(context))
        val table = Regex(
            "<table[^>]*id=\"timetable\"[^>]*>(.*?)</table>",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.get(1)
            ?: throw RuntimeException("课表页无 timetable 表格（Cookie 多半已失效，请重新登录网页版再复制 Cookie）")

        val trRe = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
        val tdRe = Regex("<t[dh][^>]*>(.*?)</t[dh]>", RegexOption.DOT_MATCHES_ALL)
        // 行首例：第一大节 (01,02小节) 08:00-09:40
        val secRe = Regex("第(.)大节.*?\\((\\d+),(\\d+)小节\\)\\s*(\\d\\d:\\d\\d)-(\\d\\d:\\d\\d)")
        // 单元格例：云存储技术 王飞 7-12(周)[03-04节] 【第二实训楼（老校区）】S2-403（信息）
        val courseRe = Regex(
            "(\\S+?)\\s+([\\u4e00-\\u9fa5]{2,4})\\s+(\\d+)-(\\d+)\\(周\\)\\[([0-9\\-]+)节\\]\\s*【([^】]+)】\\s*(\\S+)"
        )

        data class Item(
            val day: Int, val secs: List<Int>, val name: String, val teacher: String,
            val room: String, val bld: String, val time: String, val w1: Int, val w2: Int,
        )

        val items = ArrayList<Item>()
        for (tr in trRe.findAll(table)) {
            val tds = tdRe.findAll(tr.groupValues[1]).toList()
            if (tds.size < 8) continue
            val ms = secRe.find(plain(tds[0].groupValues[1])) ?: continue
            val tm = ms.groupValues[4] + "-" + ms.groupValues[5]
            for (day in 1..7) {
                val cell = plain(tds[day].groupValues[1])
                if (cell.isBlank()) continue
                for (seg in cell.split(Regex("-{4,}"))) {
                    val mc = courseRe.find(seg.trim()) ?: continue
                    val secs = mc.groupValues[5].split("-").mapNotNull { it.toIntOrNull() }
                    if (secs.isEmpty()) continue
                    items.add(
                        Item(
                            day, secs, mc.groupValues[1], mc.groupValues[2],
                            mc.groupValues[7], mc.groupValues[6], tm,
                            mc.groupValues[3].toIntOrNull() ?: 1,
                            mc.groupValues[4].toIntOrNull() ?: 20,
                        )
                    )
                }
            }
        }
        if (items.isEmpty()) throw RuntimeException("表格里没解析到课程（页面结构可能变了）")

        val weeks = JSONObject()
        for (w in 1..20) {
            val arr = JSONArray()
            for (it in items) {
                if (w < it.w1 || w > it.w2) continue
                arr.put(JSONObject().apply {
                    put("day", it.day)
                    put("secs", JSONArray().apply { it.secs.forEach { s -> put(s) } })
                    put("name", it.name)
                    put("teacher", it.teacher)
                    put("room", it.room)
                    put("bld", it.bld)
                    put("time", it.time)
                })
            }
            weeks.put(w.toString(), arr)
        }
        return JSONObject().apply {
            put("weeks", weeks)
            put("semester", "2026-2027-1")
            put("current_week", 1)
            put("ts", System.currentTimeMillis())
        }.toString()
    }

    private fun plain(s: String): String {
        var t = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        t = t.replace(Regex("<[^>]+>"), " ")
        t = t.replace("&nbsp;", " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun get(url: String, cookie: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        }
        return try {
            val code = conn.responseCode
            if (code == 302 || code == 301) {
                throw RuntimeException("被重定向到登录页，Cookie 已失效")
            }
            val stream = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?: throw RuntimeException("HTTP $code 无响应体")
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
