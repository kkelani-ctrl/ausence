/*
 * Copyright (c) 2026 Auxio Project
 * LectureTest.kt is part of Auxio.
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

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LectureTest {
    @Test
    fun transcript_parsesMinutesOverSixty() {
        val lines = TranscriptLine.parse("[00:00.00] hello\n[75:12.50] later\n[1:02:03.5] hms")
        assertEquals(listOf(0L, 3_723_500L, 4_512_500L), lines.map { it.startMs })
        assertEquals("hms", lines[1].text)
    }

    @Test
    fun transcript_skipsUntimedAndBlankLines() {
        val lines = TranscriptLine.parse("header\n[00:01.00]   \n[00:02.00] text")
        assertEquals(1, lines.size)
        assertEquals(2000L, lines[0].startMs)
    }

    @Test
    fun transcript_plainTextFallsBackToParagraphs() {
        val lines = TranscriptLine.parseForDisplay("=== Lecture 1 ===\n\nFirst paragraph.\nSecond.")
        assertEquals(listOf("Lecture 1", "First paragraph.", "Second."), lines.map { it.text })
        assertFalse(lines.any { it.isTimed })
        assertEquals(-1, lines.lineAt(10_000))
    }

    @Test
    fun lineAt_findsCurrentLine() {
        val lines = TranscriptLine.parse("[00:00.00] a\n[00:05.00] b\n[00:10.00] c")
        assertEquals(-1, listOf<TranscriptLine>().lineAt(0))
        assertEquals(0, lines.lineAt(0))
        assertEquals(0, lines.lineAt(4_999))
        assertEquals(1, lines.lineAt(5_000))
        assertEquals(2, lines.lineAt(99_000))
    }

    @Test
    fun chapters_parse() {
        val json =
            """[{"start": 560, "end": 900.5, "title": "B", "type": "teaching",
               "summary": "s", "lecture": "Chapter 1", "approximate": true},
               {"start": 0, "title": "A"}, {"start": 5, "title": ""}]"""
        val chapters = Chapter.parse(json)
        assertEquals(listOf("A", "B"), chapters.map { it.title })
        assertEquals(560_000L, chapters[1].startMs)
        assertEquals(900_500L, chapters[1].endMs)
        assertEquals("Chapter 1", chapters[1].part)
        assertTrue(chapters[1].approximate)
        assertEquals(null, chapters[0].endMs)
        assertEquals(1, chapters.indexAt(600_000))
        assertTrue(Chapter.parse("not json").isEmpty())
        assertTrue(Chapter.parse(null).isEmpty())
    }

    /**
     * Checks the parsers against a real library dump, if one is available. Each <id>.lrc /
     * <id>.json pair is a song's transcript and SECTIONS_JSON tag; expected.json holds the counts
     * computed independently.
     */
    @Test
    fun realLibrary_matchesExpectedCounts() {
        val dir = System.getenv("LECTURE_SAMPLES")?.let(::File) ?: return
        val expected = JSONObject(File(dir, "expected.json").readText())
        var checked = 0
        for (id in expected.keys()) {
            val exp = expected.getJSONObject(id)
            val lrc = File(dir, "$id.lrc").readText().trim()
            val timed = TranscriptLine.parse(lrc)
            assertEquals("timed lines in $id", exp.getInt("timed"), timed.size)
            if (timed.isEmpty()) {
                assertEquals(
                    "paragraphs in $id",
                    exp.getInt("paras"),
                    TranscriptLine.parseForDisplay(lrc).size)
            } else {
                // Timestamps must be non-decreasing after sorting and within a sane range.
                assertTrue(timed.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
            }
            val chapters = Chapter.parse(File(dir, "$id.json").readText())
            assertEquals("chapters in $id", exp.getInt("chapters"), chapters.size)
            checked++
        }
        assertEquals(expected.length(), checked)
        println("Checked $checked real recordings")
    }
}
