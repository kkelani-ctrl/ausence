/*
 * Copyright (c) 2026 Auxio Project
 * LectureAudio.kt is part of Auxio.
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

package org.oxycblt.auxio.playback.audio

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.indexAt

/**
 * Listening controls for lectures: speed (remembered per lecture), skip silence, voice boost, the
 * double-tap skip length and the sleep timer. The UI writes here; the playback service applies
 * it, so everything keeps working with the screen off.
 */
object LectureAudio {
    sealed interface Sleep {
        data object Off : Sleep

        /** Pause when [endAtElapsed] (SystemClock.elapsedRealtime) is reached. */
        data class Timer(val endAtElapsed: Long, val totalMs: Long) : Sleep {
            val remainingMs: Long
                get() = (endAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        }

        data object EndOfChapter : Sleep

        data object EndOfLecture : Sleep
    }

    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3.0f

    private var prefs: SharedPreferences? = null

    /** Bumped whenever speed, skip silence or voice boost change, so the service re-applies. */
    val settingsVersion: StateFlow<Int>
        field = MutableStateFlow(0)

    val sleep: StateFlow<Sleep>
        field = MutableStateFlow<Sleep>(Sleep.Off)

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("lecture_audio", Context.MODE_PRIVATE)
    }

    private fun bump() {
        settingsVersion.value += 1
    }

    /** The speed for [song]: its own, if set, else the last speed used. */
    fun speedFor(song: Song?): Float {
        val p = prefs ?: return 1f
        val own = song?.let { p.getFloat("speed:" + it.uid, 0f) } ?: 0f
        return (if (own > 0f) own else p.getFloat(KEY_LAST_SPEED, 1f)).coerceIn(MIN_SPEED, MAX_SPEED)
    }

    fun setSpeed(song: Song?, speed: Float) {
        val value = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        prefs?.edit {
            putFloat(KEY_LAST_SPEED, value)
            if (song != null) putFloat("speed:" + song.uid, value)
        }
        bump()
    }

    var skipSilence: Boolean
        get() = prefs?.getBoolean(KEY_SKIP_SILENCE, false) ?: false
        set(value) {
            prefs?.edit { putBoolean(KEY_SKIP_SILENCE, value) }
            bump()
        }

    var voiceBoost: Boolean
        get() = prefs?.getBoolean(KEY_VOICE_BOOST, false) ?: false
        set(value) {
            prefs?.edit { putBoolean(KEY_VOICE_BOOST, value) }
            bump()
        }

    /** How far a double-tap on the cover jumps, in seconds. */
    var stepSeconds: Int
        get() = prefs?.getInt(KEY_STEP, 10) ?: 10
        set(value) {
            prefs?.edit { putInt(KEY_STEP, value) }
        }

    fun setSleep(value: Sleep) {
        sleep.value = value
    }

    /** Start (or extend) a minutes-based timer. */
    fun sleepIn(minutes: Int) {
        val ms = minutes * 60_000L
        setSleep(Sleep.Timer(SystemClock.elapsedRealtime() + ms, ms))
    }

    /** Where the chapter playing at [positionMs] ends (the lecture's end if it has none). */
    fun chapterEndMs(song: Song, positionMs: Long): Long {
        val chapters = song.chapters
        val i = chapters.indexAt(positionMs)
        if (i < 0) return chapters.firstOrNull()?.startMs ?: song.durationMs
        return chapters[i].endMs ?: chapters.getOrNull(i + 1)?.startMs ?: song.durationMs
    }

    private const val KEY_LAST_SPEED = "last_speed"
    private const val KEY_SKIP_SILENCE = "skip_silence"
    private const val KEY_VOICE_BOOST = "voice_boost"
    private const val KEY_STEP = "step_seconds"
}
