/*
 * Copyright (c) 2026 Auxio Project
 * Lecture.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.musikr

import org.json.JSONArray

/**
 * A chapter (topic section) of a lecture, read from the SECTIONS_JSON tag.
 *
 * @param startMs Where the chapter starts, in milliseconds.
 * @param endMs Where the chapter ends, in milliseconds. Null if unknown (then it runs until the
 *   next chapter or the end of the recording).
 * @param title The chapter title.
 * @param summary A short summary of what is said in the chapter, if any.
 * @param type The kind of section, e.g. "teaching", "Q&A", "story/example".
 * @param part For recordings that combine several talks or book chapters, the name of the part
 *   this chapter belongs to.
 * @param approximate Whether [startMs] was estimated rather than taken from the recording.
 */
data class Chapter(
    val startMs: Long,
    val endMs: Long?,
    val title: String,
    val summary: String?,
    val type: String?,
    val part: String?,
    val approximate: Boolean,
) {
    companion object {
        /** Parse the SECTIONS_JSON tag. Returns an empty list on missing or malformed data. */
        fun parse(json: String?): List<Chapter> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(json)
                (0 until array.length())
                    .mapNotNull { i ->
                        val o = array.optJSONObject(i) ?: return@mapNotNull null
                        val title = o.optString("title").ifBlank { return@mapNotNull null }
                        Chapter(
                            startMs = (o.optDouble("start", 0.0) * 1000).toLong(),
                            endMs =
                                if (o.has("end") && !o.isNull("end")) {
                                    (o.optDouble("end") * 1000).toLong()
                                } else {
                                    null
                                },
                            title = title,
                            summary = o.optString("summary").ifBlank { null },
                            type = o.optString("type").ifBlank { null },
                            part = o.optString("lecture").ifBlank { null },
                            approximate = o.optBoolean("approximate", false),
                        )
                    }
                    .sortedBy { it.startMs }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * One timed line of a lecture transcript.
 *
 * @param startMs When the line is spoken, in milliseconds.
 * @param text What is said.
 */
data class TranscriptLine(val startMs: Long, val text: String) {
    /** Whether this line has a real timestamp. Untimed lines come from plain-text transcripts. */
    val isTimed: Boolean
        get() = startMs >= 0

    companion object {
        /**
         * Parse a transcript for display: timed LRC lines if there are any, otherwise the plain
         * text split into untimed paragraphs (e.g. an audiobook's source text).
         */
        fun parseForDisplay(lyrics: String?): List<TranscriptLine> {
            val timed = parse(lyrics)
            if (timed.isNotEmpty() || lyrics.isNullOrBlank()) return timed
            return lyrics
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { TranscriptLine(-1, it.removePrefix("=== ").removeSuffix(" ===")) }
                .toList()
        }

        // [mm:ss.xx] or [hh:mm:ss.xx]; minutes may exceed 59 for long lectures.
        private val LRC_LINE = Regex("""^\s*\[(\d+):(\d{1,2})(?::(\d{1,2}))?(?:[.,](\d{1,3}))?]\s*(.*)$""")

        /**
         * Parse LRC-formatted lyrics into transcript lines. Lines without a timestamp are
         * ignored; if the text has no timestamps at all, an empty list is returned.
         */
        fun parse(lyrics: String?): List<TranscriptLine> {
            if (lyrics.isNullOrBlank()) return emptyList()
            return lyrics
                .lineSequence()
                .mapNotNull { line ->
                    val m = LRC_LINE.matchEntire(line) ?: return@mapNotNull null
                    val (a, b, c, frac, text) = m.destructured
                    if (text.isBlank()) return@mapNotNull null
                    val seconds =
                        if (c.isNotEmpty()) {
                            a.toLong() * 3600 + b.toLong() * 60 + c.toLong()
                        } else {
                            a.toLong() * 60 + b.toLong()
                        }
                    val fracMs =
                        when (frac.length) {
                            0 -> 0L
                            1 -> frac.toLong() * 100
                            2 -> frac.toLong() * 10
                            else -> frac.toLong()
                        }
                    TranscriptLine(seconds * 1000 + fracMs, text.trim())
                }
                .sortedBy { it.startMs }
                .toList()
        }
    }
}

/** The index of the [Chapter] playing at [positionMs], or -1 if none. */
fun List<Chapter>.indexAt(positionMs: Long): Int = indexOfLast { it.startMs <= positionMs }

/** The index of the [TranscriptLine] being spoken at [positionMs], or -1 if none. */
fun List<TranscriptLine>.lineAt(positionMs: Long): Int {
    if (isEmpty() || !first().isTimed) return -1
    // Binary search for the last line that starts at or before the position.
    var lo = 0
    var hi = size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startMs <= positionMs) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}
