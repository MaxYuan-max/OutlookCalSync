package com.maxyuan.outlookcalsync

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [needsUser] = true: 重试没有意义, 需要用户处理 (token 失效、权限、配置缺失)。
 * false: 网络/服务端的暂时性问题, 会自己恢复。
 */
class GraphException(message: String, val needsUser: Boolean = false) : Exception(message)

data class OutlookEvent(
    val id: String,
    val iCalUId: String,
    val subject: String,
    val startMillis: Long,
    val endMillis: Long,
    val isAllDay: Boolean,
    val location: String,
    val description: String,
    val reminderMinutes: Int?,
    val showAs: String,
    val response: String,
    val lastModified: String,
    val changeKey: String,
)

class GraphClient(
    private val store: SecureStore,
    private val checkCancelled: () -> Unit = {},
) {

    /** Exchanges the stored refresh token; persists the rotated one if the server returns it. */
    fun accessToken(): String {
        val tenant = store.tenantId
        val client = store.clientId
        val rt = store.refreshToken
        if (tenant.isEmpty() || client.isEmpty() || rt.isEmpty()) {
            throw GraphException("Tenant ID / Client ID / refresh token 未填写完整", needsUser = true)
        }
        val body = form(
            "client_id" to client,
            "grant_type" to "refresh_token",
            "refresh_token" to rt,
            "scope" to "https://graph.microsoft.com/Calendars.Read offline_access",
        )
        val resp = request(
            "https://login.microsoftonline.com/$tenant/oauth2/v2.0/token",
            method = "POST", body = body,
            contentType = "application/x-www-form-urlencoded",
        )
        val code = resp.code
        val json = runCatching { JSONObject(resp.body) }.getOrNull()
        if (code != 200 || json == null) {
            // 4xx 是凭据/配置被拒 (invalid_grant 等), 需要用户处理; 429/5xx 是服务端暂时问题
            throw GraphException("换取 token 失败 (HTTP $code): ${failureDetail(resp)}",
                needsUser = code in 400..499 && code != 429 && code != 408)
        }
        val rotated = json.optString("refresh_token").takeIf { it.isNotEmpty() }
        rotated?.let { store.refreshToken = it }
        return json.getString("access_token")
    }

    private companion object {
        const val PAGE_SIZE = 50
    }

    fun fetchEvents(token: String, backDays: Int, aheadDays: Int): List<OutlookEvent> {
        val today = LocalDate.now(ZoneOffset.UTC)
        val start = today.minusDays(backDays.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = today.plusDays(aheadDays.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC)
        val select = "id,iCalUId,subject,start,end,isAllDay,isCancelled,location," +
            "isReminderOn,reminderMinutesBeforeStart,showAs,responseStatus,lastModifiedDateTime,changeKey,organizer,onlineMeeting"
        var url: String? = "https://graph.microsoft.com/v1.0/me/calendarView" +
            "?startDateTime=${enc(start.toString())}&endDateTime=${enc(end.toString())}" +
            "&\$select=${enc(select)}&\$top=$PAGE_SIZE"

        val out = ArrayList<OutlookEvent>()
        var pages = 0
        while (url != null) {
            if (++pages > 200) throw GraphException("分页异常: 超过 200 页")
            val resp = request(url, headers = mapOf("Prefer" to "outlook.timezone=\"UTC\""),
                authToken = token)
            if (resp.code != 200) throw GraphException(
                "读取日历失败 (HTTP ${resp.code}) 第 $pages 页: ${failureDetail(resp)}",
                needsUser = resp.code == 401 || resp.code == 403)
            val json = JSONObject(resp.body)
            val arr: JSONArray = json.getJSONArray("value")
            for (i in 0 until arr.length()) parse(arr.getJSONObject(i))?.let(out::add)
            url = json.optString("@odata.nextLink").takeIf { it.isNotEmpty() }
        }
        return out
    }

    private fun parse(o: JSONObject): OutlookEvent? {
        if (o.optBoolean("isCancelled")) return null
        val allDay = o.optBoolean("isAllDay")
        val desc = buildList {
            o.optJSONObject("organizer")?.optJSONObject("emailAddress")?.optString("name")
                ?.takeIf { it.isNotEmpty() }?.let { add("Organizer: $it") }
            o.optJSONObject("onlineMeeting")?.optString("joinUrl")
                ?.takeIf { it.isNotEmpty() }?.let { add("Join: $it") }
        }.joinToString("\n")
        return OutlookEvent(
            id = o.getString("id"),
            iCalUId = o.optString("iCalUId").ifEmpty { o.getString("id") },
            subject = o.optString("subject").ifEmpty { "(无标题)" },
            startMillis = utcMillis(o.getJSONObject("start").getString("dateTime")),
            endMillis = utcMillis(o.getJSONObject("end").getString("dateTime")),
            isAllDay = allDay,
            location = o.optJSONObject("location")?.optString("displayName") ?: "",
            description = desc,
            reminderMinutes = if (o.optBoolean("isReminderOn") && !o.isNull("reminderMinutesBeforeStart"))
                o.getInt("reminderMinutesBeforeStart") else null,
            showAs = o.optString("showAs"),
            response = o.optJSONObject("responseStatus")?.optString("response") ?: "",
            lastModified = o.optString("lastModifiedDateTime"),
            changeKey = o.optString("changeKey"),
        )
    }

    private fun utcMillis(dateTime: String): Long =
        Instant.parse(dateTime.substringBefore('.').let { if (it.endsWith("Z")) it else "${it}Z" }).toEpochMilli()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun form(vararg p: Pair<String, String>) = p.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }

    /**
     * 带重试的请求: 502/503/504/429 和超时、连接类 IO 错误最多重试 [RetryPolicy.MAX_RETRIES_PER_REQUEST] 次。
     * 其它状态码(含 4xx、500)原样返回, 由调用方判断。
     */
    private fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        authToken: String? = null,
    ): HttpResult {
        // 只对 GET 做请求级重试，换 token 的 POST 失败交给上层处理。
        val maxRetries = if (method == "GET") RetryPolicy.MAX_RETRIES_PER_REQUEST else 0
        var attempt = 0
        while (true) {
            checkCancelled()
            val outcome = runCatching { requestOnce(url, method, body, contentType, headers, authToken) }
            val result = outcome.getOrNull()
            val error = outcome.exceptionOrNull()

            val retryable = if (result != null) RetryPolicy.isRetryableStatus(result.code)
            else RetryPolicy.isRetryableError(error!!)
            if (!retryable || attempt >= maxRetries) {
                return result ?: throw error!!
            }

            attempt++
            val wait = RetryPolicy.delayMs(attempt, result?.retryAfterSeconds, Math.random())
            checkCancelled()
            Thread.sleep(wait)
        }
    }

    /** 发一次请求，不记录 URL、响应体或令牌。 */
    private fun requestOnce(
        url: String,
        method: String,
        body: String?,
        contentType: String?,
        headers: Map<String, String>,
        authToken: String?,
    ): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            authToken?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            contentType?.let { c.setRequestProperty("Content-Type", it) }
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) c.doOutput = true

            c.connect()

            if (body != null) {
                c.outputStream.use { it.write(body.toByteArray()) }
            }

            val code = c.responseCode

            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

            // Graph 用 request-id, 微软登录接口用 x-ms-request-id
            val requestId = c.getHeaderField("request-id") ?: c.getHeaderField("x-ms-request-id")
                ?: c.getHeaderField("client-request-id") ?: ""
            val retryAfter = RetryPolicy.parseRetryAfter(c.getHeaderField("Retry-After"))
            return HttpResult(code, text, requestId, retryAfter)
        } finally {
            c.disconnect()
        }
    }
}

data class HttpResult(val code: Int, val body: String, val requestId: String, val retryAfterSeconds: Long? = null)

/** 只保留结构化错误码和 request-id；不把原始响应、错误描述或用户资料写进状态栏和通知。 */
internal fun failureDetail(r: HttpResult): String {
    val code = runCatching {
        val error = JSONObject(r.body).opt("error")
        when (error) {
            is JSONObject -> error.optString("code")
            is String -> error
            else -> null
        }
    }.getOrNull()?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,99}")) }
    val requestId = r.requestId.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) }
    val parts = buildList {
        if (code != null) add(code)
        if (requestId != null) add("request-id=$requestId")
    }
    return parts.joinToString(" | ")
}
