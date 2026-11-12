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

class GraphClient(private val store: SecureStore) {

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
        val (code, text) = request(
            "https://login.microsoftonline.com/$tenant/oauth2/v2.0/token",
            method = "POST", body = body,
            contentType = "application/x-www-form-urlencoded",
        )
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (code != 200 || json == null) {
            val err = json?.optString("error")
            val desc = json?.optString("error_description")?.lineSequence()?.firstOrNull()
            // 4xx 是凭据/配置被拒 (invalid_grant 等), 需要用户处理; 429/5xx 是服务端暂时问题
            throw GraphException("换取 token 失败 (HTTP $code) $err: $desc",
                needsUser = code in 400..499 && code != 429 && code != 408)
        }
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { store.refreshToken = it }
        return json.getString("access_token")
    }

    fun fetchEvents(token: String, backDays: Int, aheadDays: Int): List<OutlookEvent> {
        val today = LocalDate.now(ZoneOffset.UTC)
        val start = today.minusDays(backDays.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = today.plusDays(aheadDays.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC)
        val select = "id,iCalUId,subject,start,end,isAllDay,isCancelled,location," +
            "isReminderOn,reminderMinutesBeforeStart,showAs,responseStatus,lastModifiedDateTime,changeKey,organizer,onlineMeeting"
        var url: String? = "https://graph.microsoft.com/v1.0/me/calendarView" +
            "?startDateTime=${enc(start.toString())}&endDateTime=${enc(end.toString())}" +
            "&\$select=${enc(select)}&\$top=100"

        val out = ArrayList<OutlookEvent>()
        var pages = 0
        while (url != null) {
            if (++pages > 200) throw GraphException("分页异常: 超过 200 页")
            val (code, text) = request(url, headers = mapOf("Prefer" to "outlook.timezone=\"UTC\""),
                authToken = token)
            if (code != 200) throw GraphException("读取日历失败 (HTTP $code): ${errorMessage(text)}",
                needsUser = code == 401 || code == 403)
            val json = JSONObject(text)
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

    private fun errorMessage(text: String): String =
        runCatching { JSONObject(text).getJSONObject("error").let { "${it.optString("code")}: ${it.optString("message")}" } }
            .getOrDefault(text.take(200))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun form(vararg p: Pair<String, String>) = p.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }

    private fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        authToken: String? = null,
    ): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        authToken?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        contentType?.let { c.setRequestProperty("Content-Type", it) }
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }
}
