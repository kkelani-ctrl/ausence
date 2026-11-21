/*
 * Copyright (c) 2026 Auxio Project
 * LectureBookmarks.kt is part of Auxio.
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
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import java.util.concurrent.CopyOnWriteArraySet
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.musikr.Song

/** A saved moment in a lecture, with an optional note. */
data class Bookmark(val positionMs: Long, val note: String?, val createdAt: Long)

/**
 * Bookmarks (moments with optional notes) and one free-text note per lecture. Stored on the
 * device, keyed by the lecture's UID.
 */
object LectureBookmarks {
    private var prefs: SharedPreferences? = null
    private val bookmarks = HashMap<String, List<Bookmark>>()
    private val notes = HashMap<String, String>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("lecture_bookmarks", Context.MODE_PRIVATE)
        prefs = p
        for ((key, value) in p.all) {
            val text = value as? String ?: continue
            when {
                key.startsWith("b:") ->
                    runCatching {
                        val a = JSONArray(text)
                        bookmarks[key.removePrefix("b:")] =
                            (0 until a.length()).map {
                                val o = a.getJSONObject(it)
                                Bookmark(
                                    o.getLong("pos"),
                                    o.optString("note").ifBlank { null },
                                    o.optLong("at"))
                            }
                    }
                key.startsWith("n:") -> notes[key.removePrefix("n:")] = text
            }
        }
    }

    fun of(song: Song): List<Bookmark> =
        bookmarks[song.uid.toString()].orEmpty().sortedBy { it.positionMs }

    fun noteOf(song: Song): String? = notes[song.uid.toString()]

    /** Every lecture's bookmarks and notes, keyed by UID string (for search). */
    fun allBookmarks(): Map<String, List<Bookmark>> = bookmarks

    fun allNotes(): Map<String, String> = notes

    fun add(song: Song, positionMs: Long, note: String? = null): Bookmark {
        val bookmark = Bookmark(positionMs, note?.ifBlank { null }, System.currentTimeMillis())
        save(song, of(song) + bookmark)
        return bookmark
    }

    fun setNote(song: Song, bookmark: Bookmark, note: String?) =
        save(song, of(song).map { if (it == bookmark) it.copy(note = note?.ifBlank { null }) else it })

    fun remove(song: Song, bookmark: Bookmark) = save(song, of(song) - bookmark)

    fun setLectureNote(song: Song, text: String?) {
        val key = song.uid.toString()
        val value = text?.trim()?.ifBlank { null }
        if (value == null) notes.remove(key) else notes[key] = value
        prefs?.edit { if (value == null) remove("n:$key") else putString("n:$key", value) }
        notifyChanged()
    }

    private fun save(song: Song, list: List<Bookmark>) {
        val key = song.uid.toString()
        if (list.isEmpty()) bookmarks.remove(key) else bookmarks[key] = list
        val json =
            JSONArray().apply {
                list.forEach {
                    put(
                        JSONObject().apply {
                            put("pos", it.positionMs)
                            it.note?.let { n -> put("note", n) }
                            put("at", it.createdAt)
                        })
                }
            }
        prefs?.edit { if (list.isEmpty()) remove("b:$key") else putString("b:$key", json.toString()) }
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        main.post { listeners.forEach { it() } }
    }
}
