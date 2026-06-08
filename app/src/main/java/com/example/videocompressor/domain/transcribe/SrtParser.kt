package com.example.videocompressor.domain.transcribe

/** 一条字幕：起止时间（毫秒）+ 文本（可多行）。序号在保存时按顺序重排，不存入。 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * 极简 SRT 解析/序列化。够用即可：按空行分块，每块首行序号(忽略)、次行时间轴、其余为文本。
 * 时间轴 `HH:MM:SS,mmm --> HH:MM:SS,mmm`（也兼容用 `.` 作毫秒分隔）。
 */
object SrtParser {

    private val TIME_LINE = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )

    fun parse(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        // 统一换行后按空行分块
        val blocks = content.replace("\r\n", "\n").replace("\r", "\n").split(Regex("\n[ \t]*\n"))
        for (block in blocks) {
            val lines = block.split("\n").map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            // 时间轴可能在第 1 行（无序号）或第 2 行（有序号）
            val timeIdx = lines.indexOfFirst { TIME_LINE.containsMatchIn(it) }
            if (timeIdx < 0) continue
            val m = TIME_LINE.find(lines[timeIdx]) ?: continue
            val start = toMs(m.groupValues, 1)
            val end = toMs(m.groupValues, 5)
            val text = lines.drop(timeIdx + 1).joinToString("\n").trim()
            cues.add(SubtitleCue(start, end, text))
        }
        return cues
    }

    fun format(cues: List<SubtitleCue>): String {
        val sb = StringBuilder()
        cues.forEachIndexed { i, cue ->
            sb.append(i + 1).append('\n')
            sb.append(formatTs(cue.startMs)).append(" --> ").append(formatTs(cue.endMs)).append('\n')
            sb.append(cue.text.trim()).append("\n\n")
        }
        return sb.toString()
    }

    private fun toMs(g: List<String>, base: Int): Long {
        val h = g[base].toLong()
        val m = g[base + 1].toLong()
        val s = g[base + 2].toLong()
        val msRaw = g[base + 3]
        // 兼容 1~3 位毫秒
        val ms = msRaw.padEnd(3, '0').take(3).toLong()
        return ((h * 3600 + m * 60 + s) * 1000) + ms
    }

    fun formatTs(totalMs: Long): String {
        val ms = totalMs % 1000
        val totalSec = totalMs / 1000
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        val h = totalSec / 3600
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms)
    }

    /** 列表里给用户看的简短时间 mm:ss。 */
    fun shortTs(totalMs: Long): String {
        val totalSec = totalMs / 1000
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        val h = totalSec / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
