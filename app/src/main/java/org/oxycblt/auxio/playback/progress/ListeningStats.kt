/*
 * Copyright (c) 2026 Auxio Project
 * ListeningStats.kt is part of Auxio.
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
import androidx.core.content.edit
import java.time.LocalDate
import org.oxycblt.musikr.Song

/**
 * A private, on-device log of listening time: per day and per lecture. Fed by the playback
 * service while audio plays.
 */
object ListeningStats {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("listening_stats", Context.MODE_PRIVATE)
    }

    /** Add [ms] of listening to [song], on [day] (today by default). */
    fun record(song: Song, ms: Long, day: LocalDate = LocalDate.now()) {
        if (ms <= 0) return
        val p = prefs ?: return
        val dayKey = "d:$day"
        val songKey = "s:" + song.uid
        p.edit {
            putLong(dayKey, p.getLong(dayKey, 0) + ms)
            putLong(songKey, p.getLong(songKey, 0) + ms)
        }
    }

    fun dayMs(day: LocalDate): Long = prefs?.getLong("d:$day", 0) ?: 0

    /** The last [days] days, oldest first, ending today. */
    fun recentDays(days: Int, today: LocalDate = LocalDate.now()): List<Pair<LocalDate, Long>> =
        (days - 1 downTo 0).map { today.minusDays(it.toLong()) }.map { it to dayMs(it) }

    fun totalMs(): Long =
        prefs?.all?.entries?.filter { it.key.startsWith("d:") }?.sumOf { it.value as Long } ?: 0

    /** Listening time per lecture UID (string). */
    fun perLecture(): Map<String, Long> =
        prefs?.all?.entries
            ?.filter { it.key.startsWith("s:") }
            ?.associate { it.key.removePrefix("s:") to (it.value as Long) }
            .orEmpty()
}
