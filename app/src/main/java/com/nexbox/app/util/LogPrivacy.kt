package com.nexbox.app.util

/** Applied before memory, IPC, disk, Logcat and again when exporting older files. */
internal object LogPrivacy {
    const val MAX_MESSAGE_CHARS = 8192
    private val uri = Regex("(?i)\\b(?:https?|content|file|sasdk|intent)://[^\\s\"']+")
    private val secret = Regex(
        "(?i)([\"']?\\b(?:access[_ -]?token|refresh[_ -]?token|id[_ -]?token|token|password|passwd|" +
            "authorization|cookie|android[_ -]?id|imei\\d?|imsi|oaid|serial(?:number)?|" +
            "iccid|eid|subscriber[_ -]?id|line1number|phone(?:number)?|accountname|" +
            "latitude|longitude|lat|lng|ssid|bssid|mac(?:address)?)[\"']?\\s*[:=]\\s*)" +
            "(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;)}\\]\\r\\n]+)"
    )
    private val mac = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
    // Bounded tokens and a start boundary prevent quadratic scans of long stack/message lines.
    private val email = Regex("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,63}")
    private val authorization = Regex("(?i)(\\bauthorization\\s*[:=]\\s*)(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+")
    private val cookie = Regex("(?i)(\\b(?:set-cookie|cookie)\\s*[:=]\\s*)[^\\r\\n]+")
    private val location = Regex("\\bLocation\\[[^\\]\\r\\n]*\\]")
    private val invalidTag = Regex("[^A-Za-z0-9_.:-]")
    private val account = Regex("Account\\s*\\{[^}\\r\\n]*\\}")
    private val privatePath = Regex("((?:/storage/emulated/\\d+|/sdcard|/data/(?:user/\\d+|data)/[A-Za-z0-9_.-]+))/[^\\s\"',;)}]+")
    private val coordinates = Regex("(?i)(\\b(?:current|new|coordinates?|location)\\s*[:=]\\s*)-?\\d{1,3}\\.\\d+\\s*[,/]\\s*-?\\d{1,3}\\.\\d+")

    fun clean(message: String): String {
        var result = message.take(MAX_MESSAGE_CHARS * 2)
        result = uri.replace(result) { match ->
            val raw = match.value
            val schemeEnd = raw.indexOf("://") + 3
            val authorityEnd = raw.indexOfAny(charArrayOf('/', '?', '#'), schemeEnd).let { if (it < 0) raw.length else it }
            val authority = raw.substring(schemeEnd, authorityEnd).substringAfterLast('@')
            raw.substring(0, schemeEnd) + authority + if (authorityEnd < raw.length) "/<redacted>" else ""
        }
        result = authorization.replace(result) { it.groupValues[1] + "<redacted>" }
        result = cookie.replace(result) { it.groupValues[1] + "<redacted>" }
        result = secret.replace(result) { it.groupValues[1] + "<redacted>" }
        result = mac.replace(result, "<mac>")
        result = email.replace(result, "<email>")
        result = account.replace(result, "Account{<redacted>}")
        result = location.replace(result, "Location[<redacted>]")
        result = privatePath.replace(result) { it.groupValues[1] + "/<path>" }
        result = coordinates.replace(result) { it.groupValues[1] + "<coordinates>" }
        return if (result.length > MAX_MESSAGE_CHARS || message.length > MAX_MESSAGE_CHARS * 2)
            result.take(MAX_MESSAGE_CHARS - 24) + "\n[message truncated]" else result
    }

    fun tag(value: String): String = value.take(80).replace(invalidTag, "_")
    fun source(value: String): String = when {
        value.startsWith("content:", true) -> "content-uri"
        value.startsWith("file:", true) || value.contains('/') || value.contains('\\') -> "local-file"
        else -> "installed-package"
    }
}
