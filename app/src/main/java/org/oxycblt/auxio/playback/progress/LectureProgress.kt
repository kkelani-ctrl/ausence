/*
 * Copyright (c) 2026 Auxio Project
 * LectureProgress.kt is part of Auxio.
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

package org.oxycblt.auxio.playback.progress

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import org.oxycblt.musikr.Song

/**
 * Remembers how far into each lecture the user has listened, like an audiobook app. Stored on the
 * device in its own preferences file, so it survives closing the app, reboots and updates.
 */
object LectureProgress {
    /**
     * @param positionMs Where the listener stopped.
     * @param durationMs The recording's length when saved.
     * @param finished Whether the lecture was listened to the end (or marked as such).
     */
    data class Entry(
        val positionMs: Long,
        val durationMs: Long,
        val finished: Boolean,
        /** When this was last listened to (or marked), for "continue listening" ordering. */
        val updatedAt: Long = 0,
    ) {
        val fraction: Float
            get() =
                when {
                    finished -> 1f
                    durationMs <= 0 -> 0f
                    else -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                }

        val remainingMs: Long
            get() = if (finished) 0 else (durationMs - positionMs).coerceAtLeast(0)
    }

    /** Positions below this aren't worth remembering (the listener barely started). */
    private const val MIN_SAVE_MS = 10_000L
    /** Within this of the end counts as finished. */
    private const val FINISH_MARGIN_MS = 45_000L

    private var prefs: SharedPreferences? = null
    private val cache = ConcurrentHashMap<String, Entry>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Set when playback was started at an exact point (a search hit), so the next new lecture
     * shouldn't jump to its saved position.
     */
    @Volatile var skipNextResume = false

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("lecture_progress", Context.MODE_PRIVATE)
        prefs = p
        for ((key, value) in p.all) {
            decode(value as? String)?.let { cache[key] = it }
        }
    }

    private fun key(song: Song) = song.uid.toString()

    private fun decode(value: String?): Entry? {
        val parts = value?.split('|') ?: return null
        if (parts.size < 3) return null
        return Entry(
            parts[0].toLongOrNull() ?: return null,
            parts[1].toLongOrNull() ?: return null,
            parts[2] == "1",
            parts.getOrNull(3)?.toLongOrNull() ?: 0,
        )
    }

    private fun write(song: Song, entry: Entry?) {
        val k = key(song)
        if (entry == null) cache.remove(k) else cache[k] = entry
        prefs?.edit {
            if (entry == null) remove(k)
            else
                putString(
                    k,
                    "${entry.positionMs}|${entry.durationMs}|${if (entry.finished) 1 else 0}|${entry.updatedAt}")
        }
        notifyChanged()
    }

    fun get(song: Song): Entry? = cache[key(song)]

    /** Record that playback of [song] is at [positionMs]. */
    fun save(song: Song, positionMs: Long) {
        val duration = song.durationMs
        val old = get(song)
        if (positionMs < MIN_SAVE_MS) {
            // Barely started: don't create an entry, and keep a "finished" mark on a replay.
            return
        }
        val finished = duration > 0 && positionMs >= duration - FINISH_MARGIN_MS
        val entry = Entry(positionMs, duration, finished, System.currentTimeMillis())
        if (old != null && old.positionMs == positionMs && old.finished == finished) return
        // Avoid rewriting the file for tiny moves of the same entry.
        if (old != null && !finished && old.finished == finished &&
            kotlin.math.abs(old.positionMs - positionMs) < 1000) return
        write(song, entry)
    }

    fun markFinished(song: Song) =
        write(song, Entry(song.durationMs, song.durationMs, true, System.currentTimeMillis()))

    fun reset(song: Song) = write(song, null)

    /** Finished lectures among [songs], for "12 of 36" summaries. */
    fun finishedCount(songs: Collection<Song>) = songs.count { get(it)?.finished == true }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { it() }
        } else {
            main.post { listeners.forEach { it() } }
        }
    }

    /** "1 h 12 min left", "8 min left", "Finished". */
    fun describe(context: Context, entry: Entry): String {
        if (entry.finished) return context.getString(org.oxycblt.auxio.R.string.lbl_progress_finished)
        val totalMin = (entry.remainingMs / 60_000L).toInt()
        return when {
            totalMin < 1 -> context.getString(org.oxycblt.auxio.R.string.fmt_progress_left_lt_min)
            totalMin < 60 ->
                context.getString(org.oxycblt.auxio.R.string.fmt_progress_left_min, totalMin)
            else ->
                context.getString(
                    org.oxycblt.auxio.R.string.fmt_progress_left_h_min, totalMin / 60, totalMin % 60)
        }
    }
}
