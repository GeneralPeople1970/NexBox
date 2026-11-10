package com.nexbox.app.data

/** Release bodies are plain display text here; checksums, attachment instructions and HTML stay out. */
internal object ReleaseNotes {
    private const val MAX_BODY_LENGTH = 50_000
    private const val MAX_NOTES_LENGTH = 24_000
    private val comments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val heading = Regex("^#{1,6}\\s+(.+?)\\s*#*\\s*$")
    private val checksum = Regex("(?i)^(?:[`*_-]*\\s*)?(?:sha[- ]?256(?:sums)?|checksums?|校验和|校驗和|文件校验|文件校驗).*$")
    private val digest = Regex("(?i)^\\s*[`*_-]*\\s*[0-9a-f]{64}(?:\\s|$).*")
    private val attachment = Regex("(?i)(?:https?://\\S*/releases/download/|\\]\\([^)]*\\.apk(?:[?#][^)]*)?\\))")
    private val downloadHeading = Regex("(?i)^(?:下载|下載|安装|安裝|downloads?|installation|assets|attachments)(?:\\s.*)?$")
    private val link = Regex("!?\\[([^]]*)]\\([^)]*\\)")
    private val escapes = Regex("\\\\([\\\\`*_{}\\[\\]()#+.!<>-])")
    private val bullet = Regex("^\\s*[-*+]\\s+")
    private val title = Regex("(?i)^NexBox(?:\\s+v?[0-9].*)?$")

    fun parse(body: String): Pair<String, String> {
        val clean = comments.replace(body.take(MAX_BODY_LENGTH).replace("\r\n", "\n").replace('\r', '\n'), "")
        val chinese = mutableListOf<String>()
        val english = mutableListOf<String>()
        val fallback = mutableListOf<String>()
        var section: String? = null
        var skipSection = false
        var inCode = false
        for (line in clean.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCode = !inCode
                continue
            }
            if (inCode) continue
            val header = heading.matchEntire(trimmed)?.groupValues?.get(1)
            val label = (header ?: trimmed).replace("**", "").replace("`", "").trim()
            if (checksum.matches(label)) break
            if (header != null) {
                when (label.lowercase().replace('’', '\'')) {
                    "更新内容", "更新內容", "更新日志", "更新日誌", "更新说明", "更新說明" -> {
                        section = "zh"
                        skipSection = false
                        continue
                    }
                    "what's new", "whats new", "changelog", "release notes", "changes" -> {
                        section = "en"
                        skipSection = false
                        continue
                    }
                }
                if (downloadHeading.matches(label)) {
                    skipSection = true
                    section = null
                    continue
                }
                if (title.matches(label)) continue
            }
            if (skipSection || digest.matches(trimmed) || attachment.containsMatchIn(trimmed)) continue
            val text = plainText(header ?: line)
            fallback += text
            when (section) {
                "zh" -> chinese += text
                "en" -> english += text
            }
        }
        val zh = join(chinese)
        val en = join(english)
        if (zh.isNotBlank() || en.isNotBlank()) return zh to en
        val legacy = join(fallback)
        return legacy to legacy
    }

    private fun plainText(line: String): String {
        var value = link.replace(line) { it.groupValues[1] }
        value = escapes.replace(value) { it.groupValues[1] }
        value = value.replace("**", "").replace("__", "").replace("`", "").replace("\u200b", "")
        return bullet.replace(value.trim(), "• ").trimEnd()
    }

    private fun join(lines: List<String>): String = lines.joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n").trim().take(MAX_NOTES_LENGTH)
}
