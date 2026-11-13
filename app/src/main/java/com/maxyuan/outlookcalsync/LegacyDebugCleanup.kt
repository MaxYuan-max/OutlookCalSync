package com.maxyuan.outlookcalsync

import java.io.File

/** 只清理本应用旧版产生的诊断文件，不递归删除目录，也不接触凭据。 */
object LegacyDebugCleanup {
    fun deleteLog(filesDir: File): Boolean {
        val log = File(filesDir, "debug.log")
        return !log.exists() || (log.isFile && log.delete())
    }

    /** 旧状态可能包含完整或截断的服务端 JSON；保留时间、HTTP 状态、页码和 request-id。 */
    fun compactLastResult(value: String): String {
        val header = Regex(
            "^(.*?失败:\\s*(?:读取日历失败|换取 token 失败)\\s*\\(HTTP \\d{3}\\)(?:\\s*第 \\d+ 页)?).*",
            RegexOption.DOT_MATCHES_ALL,
        ).matchEntire(value)?.groupValues?.get(1) ?: return value
        val code = Regex("\"code\"\\s*:\\s*\"([A-Za-z][A-Za-z0-9_.-]{0,99})\"")
            .find(value)?.groupValues?.get(1)
            ?: Regex("\\(HTTP \\d{3}\\)\\s+([A-Za-z][A-Za-z0-9_.-]{0,99}):")
                .find(value)?.groupValues?.get(1)
            ?: Regex("^\\s*:\\s*([A-Za-z][A-Za-z0-9_.-]{0,99})(?=\\s*(?:\\||$))")
                .find(value.removePrefix(header))?.groupValues?.get(1)
        val requestId = Regex("(?:request-id=|\"request-id\"\\s*:\\s*\")([A-Za-z0-9_.:-]{1,128})")
            .find(value)?.groupValues?.get(1)
        val details = listOfNotNull(code, requestId?.let { "request-id=$it" }).joinToString(" | ")
        return if (details.isEmpty()) header else "$header: $details"
    }
}
