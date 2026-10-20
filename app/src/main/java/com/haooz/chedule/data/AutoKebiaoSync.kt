package com.haooz.chedule.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动自动同步课表。
 *
 * 设计目的：用户不需要点任何按钮。App 一启动就在后台静默拉一次最新课表，
 * 成功即写本地（界面直接就是最新的）；同时把抓到的原始数据回传到 GitHub，
 * 这样服务端（以及重装/其他设备）也拿得到最新课表。
 *
 * 三条自律约束，避免它变成负担：
 *  1. 节流：距上次尝试不足 20 分钟不再发起。
 *  2. 回传更节流：无变化且距上次回传不足 6 小时不发，省电省流量。
 *  3. 全程静默：失败不弹窗、不打断，只写日志偏好，诊断页可查。
 */
object AutoKebiaoSync {

    private const val PREFS = "kb_auto"
    private const val K_LAST_TRY = "last_try"
    private const val K_LAST_UP = "last_up"
    private const val K_LAST_RESULT = "last_result"

    private const val TRY_GAP_MS = 20 * 60 * 1000L
    private const val UP_GAP_MS = 6 * 60 * 60 * 1000L

    // 回传目标：kebiao-page（私有库），App 的 GitHub 兜底也读这里的 weeks_cache.json
    private const val UP_REPO = "WoShiYiGeManHuaJia/kebiao-page"
    private const val UP_PATH = "weeks_cache.json"
    private const val UP_BRANCH = "main"

    fun lastResult(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_LAST_RESULT, "").orEmpty()

    /** 启动调用一次即可；内部自行节流。 */
    fun kick(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong(K_LAST_TRY, 0L) < TRY_GAP_MS) return
        p.edit().putLong(K_LAST_TRY, now).apply()

        CoroutineScope(Dispatchers.IO).launch {
            val res = runCatching { BuiltinCourseSync.sync(context) }
                .getOrElse {
                    BuiltinCourseSync.SyncResult(
                        false, "自动同步异常：${it.javaClass.simpleName}: ${it.message?.take(150)}"
                    )
                }
            val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(now))
            var log = "[$stamp] ${if (res.ok) "成功" else "失败"} · ${res.message.take(150)}"

            if (res.ok && !res.rawJson.isNullOrBlank()) {
                // 无变化时只在超过节流窗口才回传，省流量
                val needUp = res.changed || now - p.getLong(K_LAST_UP, 0L) > UP_GAP_MS
                if (needUp) {
                    val ok = upload(res.rawJson)
                    log += "\n回传 ${if (ok) "成功" else "失败（不影响本地课表）"}"
                    if (ok) p.edit().putLong(K_LAST_UP, now).apply()
                } else {
                    log += "\n无变化，跳过回传"
                }
            }
            p.edit().putString(K_LAST_RESULT, log).apply()
        }
    }

    /** 诊断页「立即同步」：跳过节流，同步并把结果日志直接返回。 */
    suspend fun force(context: Context): String = withContext(Dispatchers.IO) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val res = runCatching { BuiltinCourseSync.sync(context) }
            .getOrElse {
                BuiltinCourseSync.SyncResult(
                    false, "同步异常：${it.javaClass.simpleName}: ${it.message?.take(150)}"
                )
            }
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(now))
        var log = "[$stamp] ${if (res.ok) "成功" else "失败"} · ${res.message.take(200)}"
        if (res.ok && !res.rawJson.isNullOrBlank()) {
            val ok = upload(res.rawJson)
            log += "\n回传 ${if (ok) "成功" else "失败（不影响本地课表）"}"
            if (ok) p.edit().putLong(K_LAST_UP, now).apply()
        }
        p.edit()
            .putLong(K_LAST_TRY, now)
            .putString(K_LAST_RESULT, log)
            .apply()
        log
    }

    /**
     * 用 GitHub Contents API 覆盖写 weeks_cache.json。
     * 需要先 GET 拿 sha，否则 PUT 会被拒（409）。
     */
    private fun upload(json: String): Boolean {
        val token = BuildConfig.GH_TOKEN
        if (token.isBlank()) return false
        val api = "https://api.github.com/repos/$UP_REPO/contents/$UP_PATH"
        return try {
            var sha: String? = null
            try {
                val g = (URL("$api?ref=$UP_BRANCH").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "NexioSchedule")
                }
                if (g.responseCode in 200..299) {
                    val txt = BufferedReader(InputStreamReader(g.inputStream)).use { it.readText() }
                    sha = JSONObject(txt).optString("sha").takeIf { it.isNotBlank() }
                }
                g.disconnect()
            } catch (_: Exception) { /* 首次创建时没有 sha，正常 */ }

            val body = JSONObject().apply {
                put("message", "auto: 手机端同步课表 " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
                put("content", Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                put("branch", UP_BRANCH)
                if (sha != null) put("sha", sha)
            }
            val conn = (URL(api).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "NexioSchedule")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching {
                    BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
                }.getOrNull()?.take(150)
                android.util.Log.w("AutoKebiaoSync", "upload HTTP $code $err")
            }
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            android.util.Log.w("AutoKebiaoSync", "upload fail: ${e.message}")
            false
        }
    }
}
