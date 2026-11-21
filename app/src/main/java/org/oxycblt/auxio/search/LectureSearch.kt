/*
 * Copyright (c) 2026 Auxio Project
 * LectureSearch.kt is part of Auxio.
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

package org.oxycblt.auxio.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.text.Normalizer
import org.oxycblt.auxio.databinding.ItemLectureHitBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.TranscriptLine

/**
 * A search match inside a lecture: either a chapter or a transcript line.
 *
 * @param song The lecture the match is in.
 * @param startMs Where to start playing.
 * @param text The chapter title, or the matching transcript excerpt.
 * @param detail Extra context (the chapter summary), if any.
 */
data class LectureHit(
    val song: Song,
    val startMs: Long,
    val text: String,
    val detail: String?,
    val isChapter: Boolean,
)

/** Finds [LectureHit]s for a query. All words of the query must be present (in any order). */
object LectureSearch {
    private const val MAX_CHAPTER_HITS = 60
    private const val MAX_TRANSCRIPT_HITS = 100
    private const val MIN_TRANSCRIPT_QUERY = 3
    private val COMBINING = Regex("\\p{InCombiningDiacriticalMarks}+")

    private fun fold(s: String) =
        COMBINING.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase()

    private fun terms(query: String) = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun matches(haystack: String, terms: List<String>) = terms.all { haystack.contains(it) }

    fun chapters(songs: Collection<Song>, query: String): List<LectureHit> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        val hits = mutableListOf<LectureHit>()
        for (song in songs) {
            for (chapter in song.chapters) {
                val haystack =
                    fold(
                        listOfNotNull(chapter.title, chapter.summary, chapter.part)
                            .joinToString(" "))
                if (matches(haystack, terms)) {
                    hits.add(LectureHit(song, chapter.startMs, chapter.title, chapter.summary, true))
                }
            }
        }
        // Matches in the title rank above matches only in the summary.
        return hits
            .sortedByDescending { matches(fold(it.text), terms) }
            .take(MAX_CHAPTER_HITS)
    }

    /**
     * Search the raw LRC transcript text. Works directly on the stored string (no parsing, no
     * per-line allocation) so it stays fast over hundreds of hour-long lectures.
     */
    fun transcripts(songs: Collection<Song>, query: String): List<LectureHit> {
        if (query.trim().length < MIN_TRANSCRIPT_QUERY) return emptyList()
        // Plain case-insensitive matching (no accent folding) keeps this allocation-free.
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        // Find candidates by the longest term, then check the rest in the surrounding lines.
        val anchor = terms.maxBy { it.length }
        val hits = mutableListOf<LectureHit>()
        for (song in songs) {
            val raw = song.transcriptText ?: continue
            var from = 0
            var lastLineStart = -1
            while (true) {
                val at = raw.indexOf(anchor, from, ignoreCase = true)
                if (at < 0) break
                val lineStart = raw.lastIndexOf('\n', at).let { if (it < 0) 0 else it + 1 }
                val lineEnd = raw.indexOf('\n', at).let { if (it < 0) raw.length else it }
                // Include the following line so phrases split across caption lines still match.
                val windowEnd =
                    if (lineEnd < raw.length) {
                        raw.indexOf('\n', lineEnd + 1).let { if (it < 0) raw.length else it }
                    } else {
                        lineEnd
                    }
                from = lineEnd + 1
                if (lineStart == lastLineStart) continue
                val window = raw.substring(lineStart, windowEnd)
                if (terms.all { window.contains(it, ignoreCase = true) }) {
                    lastLineStart = lineStart
                    val parsed = TranscriptLine.parse(window)
                    val startMs = parsed.firstOrNull()?.startMs ?: continue
                    hits.add(
                        LectureHit(
                            song, startMs, parsed.joinToString(" ") { it.text }, null, false))
                    if (hits.size >= MAX_TRANSCRIPT_HITS) return hits
                    // Skip past the window so the same passage isn't reported twice.
                    from = windowEnd + 1
                }
                if (from >= raw.length) break
            }
        }
        return hits
    }
}

class LectureHitViewHolder private constructor(private val binding: ItemLectureHitBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(hit: LectureHit, onClick: (LectureHit) -> Unit) {
        val context = binding.root.context
        binding.hitTime.text = hit.startMs.formatDurationMs(true)
        binding.hitText.text = if (hit.isChapter) hit.text else "“${hit.text}”"
        binding.hitSong.text =
            "${hit.song.name.resolve(context)} · ${hit.song.artists.resolveNames(context)}"
        binding.root.setOnClickListener { onClick(hit) }
    }

    companion object {
        const val VIEW_TYPE = 0xA0F0

        fun from(parent: ViewGroup) =
            LectureHitViewHolder(
                ItemLectureHitBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }
}
