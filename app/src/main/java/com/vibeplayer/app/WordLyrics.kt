package com.vibeplayer.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** One word of a lyric line, with the moment it starts and ends (milliseconds from the start of the song). */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

/**
 * Word-by-word timing inside "enhanced LRC" lyrics, written like:
 *   [00:12.00]<00:12.00>Hello <00:12.50>there <00:13.10>
 * Each <mm:ss.xx> marks the moment the word after it starts. A marker after the last word marks its end.
 */
object WordLyrics {
    private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    private fun timeOf(tag: MatchResult): Long {
        val minutes = tag.groupValues[1].toLong()
        val seconds = tag.groupValues[2].toLong()
        val fraction = tag.groupValues[3].padEnd(3, '0').take(3).toLong()
        return minutes * 60_000L + seconds * 1000L + fraction
    }

    /** The words of one line, or an empty list if the line has no word timing. */
    fun parseWords(raw: String, lineStartMs: Long): List<LyricWord> {
        val tags = wordTag.findAll(raw).toList()
        if (tags.isEmpty()) return emptyList()

        val starts = ArrayList<Long>()
        val ends = ArrayList<Long>()
        val texts = ArrayList<String>()

        // Any text before the first marker starts with the line itself.
        val lead = raw.substring(0, tags.first().range.first)
        if (lead.isNotBlank()) {
            starts.add(lineStartMs)
            ends.add(timeOf(tags.first()))
            texts.add(lead)
        }

        for (i in tags.indices) {
            val tag = tags[i]
            val segmentEnd = if (i + 1 < tags.size) tags[i + 1].range.first else raw.length
            val segment = raw.substring(tag.range.last + 1, segmentEnd)
            val start = timeOf(tag)
            val end = if (i + 1 < tags.size) timeOf(tags[i + 1]) else start + 600L
            if (segment.isBlank()) {
                // Just a space (or nothing): keep the spacing by attaching it to the word before.
                if (texts.isNotEmpty()) texts[texts.lastIndex] = texts.last() + segment
            } else {
                starts.add(start)
                ends.add(end)
                texts.add(segment)
            }
        }

        val words = ArrayList<LyricWord>()
        for (i in texts.indices) {
            words.add(LyricWord(starts[i], maxOf(ends[i], starts[i] + 1), texts[i]))
        }
        return words
    }
}

/**
 * Apple-style lyrics, stored as TTML (an XML text). Lines are <p begin="..." end="..."> and,
 * when the lyrics are timed word by word, each word is a <span begin="..." end="...">.
 */
object TtmlLyrics {

    fun looksLikeTtml(text: String): Boolean {
        val start = text.trimStart()
        return (start.startsWith("<?xml") || start.startsWith("<tt")) && text.contains("<p")
    }

    /** Turns a time like "1:23.456", "00:01:23.456", "83.456s" or "83456ms" into milliseconds, or -1. */
    private fun toMs(value: String?): Long {
        if (value.isNullOrBlank()) return -1L
        val v = value.trim()
        return try {
            when {
                v.endsWith("ms") -> v.removeSuffix("ms").toDouble().toLong()
                v.endsWith("s") -> (v.removeSuffix("s").toDouble() * 1000).toLong()
                else -> {
                    var seconds = 0.0
                    for (part in v.split(':')) seconds = seconds * 60 + part.toDouble()
                    (seconds * 1000).toLong()
                }
            }
        } catch (e: Exception) {
            -1L
        }
    }

    private fun attribute(parser: XmlPullParser, ending: String): String? {
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)
            if (name == ending || name.endsWith(":$ending")) return parser.getAttributeValue(i)
        }
        return null
    }

    /** Reads TTML lyrics. Returns null if it can't be read or has no lines. */
    fun parse(text: String): Lyrics? {
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(text))

            val lines = ArrayList<LyricLine>()
            var inLine = false
            var lineBegin = -1L
            var lineEnd = -1L
            var words = ArrayList<LyricWord>()
            var lineText = StringBuilder()

            var inWord = false
            var wordBegin = -1L
            var wordEnd = -1L
            var wordText = StringBuilder()
            var skipDepth = 0 // inside a translation or romanization, which we leave out

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val role = attribute(parser, "role").orEmpty()
                        if (skipDepth > 0) {
                            skipDepth++
                        } else if (role.contains("translation") || role.contains("roman")) {
                            skipDepth = 1
                        } else if (parser.name == "p") {
                            inLine = true
                            lineBegin = toMs(attribute(parser, "begin"))
                            lineEnd = toMs(attribute(parser, "end"))
                            words = ArrayList()
                            lineText = StringBuilder()
                        } else if (parser.name == "span" && inLine) {
                            val begin = toMs(attribute(parser, "begin"))
                            if (begin >= 0) {
                                inWord = true
                                wordBegin = begin
                                wordEnd = toMs(attribute(parser, "end"))
                                wordText = StringBuilder()
                            }
                        } else if (parser.name == "br" && inLine) {
                            lineText.append(' ')
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inLine && skipDepth == 0) {
                            if (inWord) {
                                wordText.append(parser.text)
                            } else {
                                // Text between words, usually just a space: keep it with the word before.
                                lineText.append(parser.text)
                                if (words.isNotEmpty()) {
                                    val last = words.removeAt(words.lastIndex)
                                    words.add(last.copy(text = last.text + parser.text))
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (skipDepth > 0) {
                            skipDepth--
                        } else if (parser.name == "span" && inWord) {
                            inWord = false
                            val word = wordText.toString()
                            if (word.isNotEmpty()) {
                                val end = if (wordEnd > wordBegin) wordEnd else wordBegin + 600L
                                words.add(LyricWord(wordBegin, end, word))
                                lineText.append(word)
                            }
                        } else if (parser.name == "p" && inLine) {
                            inLine = false
                            // Line breaks and indentation inside the XML count as a single space.
                            val spaces = Regex("\\s+")
                            val content = lineText.toString().replace(spaces, " ").trim()
                            val begin = if (lineBegin >= 0) lineBegin else words.firstOrNull()?.startMs ?: -1L
                            if (begin >= 0 && (content.isNotEmpty() || words.isNotEmpty())) {
                                val keptWords = words.map { it.copy(text = it.text.replace(spaces, " ")) }
                                lines.add(LyricLine(begin, content, keptWords))
                            }
                        }
                    }
                }
                event = parser.next()
            }
            if (lines.isEmpty()) return null
            return Lyrics(lines.sortedBy { it.timeMs }, synced = true)
        } catch (e: Exception) {
            return null
        }
    }
}
