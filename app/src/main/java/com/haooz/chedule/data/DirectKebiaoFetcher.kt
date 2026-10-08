package com.haooz.chedule.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 单人自用版：手机直连学校教务接口抓课表，不再依赖路由器中转。
 *
 * 接口是教育网内网地址（222.243.161.213:81），境外连不上，所以只能由手机
 *（连校园网 WiFi 时）或路由器这类在国内网内的设备来抓。这里在 App 内
 * 用原生 HttpURLConnection 实现，不引入 Python/Chaquopy，包体与启动都不受影响。
 */
object DirectKebiaoFetcher {

    private const val BASE = "http://222.243.161.213:81/hnrjzyxyhd"

    /**
     * UA 必须伪装成正常浏览器。
     * 若这里露出「Dalvik/okhttp」这类一眼机器人的指纹，检测系统会把它当成
     *「第 N 个设备」从而判定多终端共享，直接拒绝登录。
     */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val MAX_WEEK = 20
    private const val PREFS = "kb_direct"
    private const val K_USER = "user_no"
    private const val K_PWD = "pwd_enc"
    private const val K_SCH = "school_code"

    // 内置默认账号（单人自用，免配置）。若学校改了加密串，用 saveCredential 覆盖即可。
    private const val DEFAULT_USER = "202405190231"
    private const val DEFAULT_PWD = "QlRZY0NDcTBDRG5uQnZ4TDROSWFzZz09"
    private const val DEFAULT_SCH = "4711"

    data class Credential(
        val userNo: String,
        val pwdEnc: String,
        val schoolCode: String,
    )

    fun loadCredential(context: Context): Credential {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Credential(
            p.getString(K_USER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_USER,
            p.getString(K_PWD, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_PWD,
            p.getString(K_SCH, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SCH,
        )
    }

    fun saveCredential(context: Context, c: Credential) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_USER, c.userNo.trim())
            .putString(K_PWD, c.pwdEnc.trim())
            .putString(K_SCH, c.schoolCode.trim())
            .apply()
    }

    private fun http(
        url: String,
        headers: Map<String, String>,
        method: String,
        timeoutMs: Int,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = method
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            if (method == "POST") {
                conn.doOutput = true
                conn.outputStream.use { it.write(ByteArray(0)) }
            }
            val code = conn.responseCode
            val stream = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?: throw RuntimeException("HTTP $code 无响应体")
            val text = BufferedReader(
                InputStreamReader(stream, Charsets.UTF_8)
            ).use { it.readText() }
            if (code !in 200..299) throw RuntimeException("HTTP $code ${text.take(150)}")
            text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 抓全部周次，返回与 weeks_cache.json 结构一致的 JSON 文本，
     * 交给 BuiltinCourseSync 复用既有解析（含线上标识识别）。
     */
    fun fetchAsWeeksCacheJson(context: Context): String {
        precheck()
        val c = loadCredential(context)
        val q = "userNo=${enc(c.userNo)}&pwd=${enc(c.pwdEnc)}" +
            "&encode=1&captchaData=&codeVal="
        val loginRaw = http("$BASE/login?$q", emptyMap(), "POST", 15_000)
        val lj = runCatching { JSONObject(loginRaw) }
            .getOrNull() ?: throw RuntimeException("登录返回不是 JSON：${loginRaw.take(120)}")
        if (lj.optString("code") != "1") {
            throw RuntimeException("登录失败：" + loginRaw.take(160))
        }
        val token = lj.optJSONObject("data")?.optString("token").orEmpty()
        if (token.isBlank()) throw RuntimeException("登录成功但无 token")

        val weeks = JSONObject()
        var semester = ""
        var currentWeek = 1
        for (w in 1..MAX_WEEK) {
            val arr = JSONArray()
            runCatching {
                val raw = http(
                    "$BASE/student/curriculum?week=$w&kbjcmsid=",
                    mapOf("Token" to token, "schoolCode" to c.schoolCode),
                    "GET", 15_000,
                )
                val j = JSONObject(raw)
                if (j.optString("code") == "1") {
                    val data = j.optJSONArray("data")?.optJSONObject(0)
                    val top = data?.optJSONArray("topInfo")?.optJSONObject(0)
                    if (w == 1) {
                        semester = top?.optString("semesterId").orEmpty()
                        currentWeek = top?.optString("week")?.toIntOrNull() ?: 1
                    }
                    val cs = data?.optJSONArray("courses")
                    if (cs != null) {
                        for (i in 0 until cs.length()) {
                            val co = cs.optJSONObject(i) ?: continue
                            arr.put(JSONObject().apply {
                                put("day", co.optInt("weekDay", 1))
                                put("secs", parseSecs(co.optString("classTime")))
                                put("name", co.optString("courseName"))
                                put("teacher", co.optString("teacherName"))
                                put("room", co.optString("classroomName"))
                                put("bld", co.optString("buildingName"))
                                put(
                                    "time",
                                    co.optString("startTime") + "-" + co.optString("endTIme")
                                )
                                put("online", detectOnline(co))
                            })
                        }
                    }
                }
            }
            weeks.put(w.toString(), arr)
        }
        if (weeks.length() == 0) throw RuntimeException("未拉到任何周次数据")
        return JSONObject().apply {
            put("weeks", weeks)
            put("semester", semester)
            put("current_week", currentWeek)
            put("ts", System.currentTimeMillis())
        }.toString()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * 把异常翻译成人话，直接显示在 Toast 上。
     * 用户最需要区分的是「没连校园网」和「账号被拒」，这两者处理方式完全不同。
     */
    fun hintOf(e: Exception): String {
        val m = e.message ?: ""
        val n = e.javaClass.simpleName ?: ""
        return when {
            // 连不上：超时 / 拒绝 / 无法解析 / 无路由
            "Timeout" in n || "SocketTimeout" in n || "ConnectException" in n ||
                "UnknownHost" in n || "NoRoute" in n || "ECONNREFUSED" in m ||
                "ETIMEDOUT" in m || "ENETUNREACH" in m || "timeout" in m.lowercase() ->
                "连不上教务服务器（$BASE）。这是教育网内网地址，请连校园网 WiFi 后再刷新"

            "UnknownHost" in n || "Unable to resolve host" in m ->
                "解析不到教务服务器地址，请检查网络"

            m.startsWith("登录失败") -> m

            m.contains("未拉到任何周次") -> "登录成功但没拉到课表，教务可能维护中"

            m.startsWith("HTTP ") -> "教务接口返回异常：$m"

            else -> "刷新失败：$m"
        }
    }

    /**
     * 网络预检：正式登录前先 TCP 探一下，连不上就快速失败。
     * 避免用户在 4G 下白等 15 秒超时才知道连不上。
     */
    private fun precheck() {
        val sock = java.net.Socket()
        try {
            sock.connect(java.net.InetSocketAddress("222.243.161.213", 81), 6000)
        } finally {
            runCatching { sock.close() }
        }
    }

    /** 与 Python 侧一致：classTime 每 2 位一节，取 s[i+1:i+3]，i 步长 2 */
    private fun parseSecs(s: String): JSONArray {
        val a = JSONArray()
        if (s.length > 1) {
            var i = 0
            while (i < s.length - 1) {
                val v = s.substring(i + 1, minOf(i + 3, s.length)).toIntOrNull()
                if (v != null) a.put(v)
                i += 2
            }
        }
        return a
    }

    private val ONLINE_KEYS = listOf(
        "online", "isOnline", "onlineFlag", "onlineTeach", "onLine",
        "teachMode", "teachingMode", "courseMode", "classMode", "teachType",
        "teachingType", "classType", "courseType", "teachForm", "studyMode",
    )
    private val ONLINE_WORDS = listOf(
        "线上", "网络授课", "网络教学", "远程", "直播", "腾讯会议", "钉钉", "云课堂",
    )
    private val ONLINE_OFF = setOf("0", "false", "no", "off", "none", "null", "")
    private val MODE_OFF = setOf(
        "0", "1", "2", "false", "none", "null", "", "线下", "面授", "正常", "教室",
    )

    /** 两层识别：先按字段名，再兜底扫全字段关键词 */
    private fun detectOnline(c: JSONObject): Boolean {
        for (k in ONLINE_KEYS) {
            if (!c.has(k)) continue
            val sv = c.optString(k, "").trim()
            val low = k.lowercase()
            if ("online" in low) {
                if (sv.lowercase() !in ONLINE_OFF) return true
                continue
            }
            if (sv.lowercase() in MODE_OFF) continue
            if (ONLINE_WORDS.any { it in sv }) return true
        }
        val it = c.keys()
        while (it.hasNext()) {
            val k = it.next()
            val sv = c.optString(k, "")
            if (ONLINE_WORDS.any { w -> w in sv }) return true
        }
        return false
    }
}
