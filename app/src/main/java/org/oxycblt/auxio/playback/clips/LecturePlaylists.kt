/*
 * Copyright (c) 2026 Auxio Project
 * LecturePlaylists.kt is part of Auxio.
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

package org.oxycblt.auxio.playback.clips

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.musikr.Chapter
import org.oxycblt.musikr.Song

/**
 * One entry of a lecture playlist: a whole episode, or just one chapter of it. No audio is copied;
 * a chapter is played by starting at [startMs] and moving on at [endMs].
 *
 * @param songUid The episode's UID (as a string).
 * @param startMs Where the clip starts (0 for a whole episode).
 * @param endMs Where the clip ends, or null to play to the end of the episode.
 * @param title The chapter title, or null for a whole episode.
 */
data class PlaylistEntry(
    val songUid: String,
    val startMs: Long,
    val endMs: Long?,
    val title: String?,
) {
    val isChapter: Boolean
        get() = title != null

    fun toJson() =
        JSONObject().apply {
            put("song", songUid)
            put("start", startMs)
            endMs?.let { put("end", it) }
            title?.let { put("title", it) }
        }

    companion object {
        fun fromJson(o: JSONObject) =
            PlaylistEntry(
                songUid = o.getString("song"),
                startMs = o.optLong("start", 0),
                endMs = if (o.has("end")) o.getLong("end") else null,
                title = o.optString("title").ifBlank { null },
            )

        fun episode(song: Song) = PlaylistEntry(song.uid.toString(), 0, null, null)

        /** A chapter of [song]; it ends where the next chapter starts (or the episode ends). */
        fun chapter(song: Song, chapter: Chapter): PlaylistEntry {
            val chapters = song.chapters
            val next = chapters.getOrNull(chapters.indexOf(chapter) + 1)?.startMs
            val end = chapter.endMs ?: next ?: song.durationMs
            return PlaylistEntry(song.uid.toString(), chapter.startMs, end, chapter.title)
        }
    }
}

data class LecturePlaylist(val id: String, val name: String, val entries: List<PlaylistEntry>)

/**
 * Ausence playlists, which (unlike Auxio's) can hold single chapters as well as whole episodes.
 * Stored on the device as JSON.
 */
object LecturePlaylists {
    private var prefs: SharedPreferences? = null
    private var cache = listOf<LecturePlaylist>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("lecture_playlists", Context.MODE_PRIVATE)
        prefs = p
        cache =
            runCatching {
                    val array = JSONArray(p.getString(KEY, "[]"))
                    (0 until array.length()).map { i ->
                        val o = array.getJSONObject(i)
                        val entries = o.getJSONArray("entries")
                        LecturePlaylist(
                            o.getString("id"),
                            o.getString("name"),
                            (0 until entries.length()).map {
                                PlaylistEntry.fromJson(entries.getJSONObject(it))
                            })
                    }
                }
                .getOrDefault(emptyList())
    }

    val all: List<LecturePlaylist>
        get() = cache

    fun get(id: String) = cache.find { it.id == id }

    fun create(name: String, entries: List<PlaylistEntry> = emptyList()): LecturePlaylist {
        val playlist = LecturePlaylist(UUID.randomUUID().toString(), name.trim(), entries)
        update(cache + playlist)
        return playlist
    }

    fun add(id: String, entry: PlaylistEntry) = edit(id) { it + entry }

    fun removeAt(id: String, index: Int) =
        edit(id) { list -> list.filterIndexed { i, _ -> i != index } }

    fun move(id: String, from: Int, to: Int) =
        edit(id) { list ->
            list.toMutableList().apply { add(to.coerceIn(0, size - 1), removeAt(from)) }
        }

    fun rename(id: String, name: String) =
        update(cache.map { if (it.id == id) it.copy(name = name.trim()) else it })

    fun delete(id: String) = update(cache.filter { it.id != id })

    private fun edit(id: String, block: (List<PlaylistEntry>) -> List<PlaylistEntry>) =
        update(cache.map { if (it.id == id) it.copy(entries = block(it.entries)) else it })

    private fun update(playlists: List<LecturePlaylist>) {
        cache = playlists
        val array = JSONArray()
        for (p in playlists) {
            array.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("entries", JSONArray().apply { p.entries.forEach { put(it.toJson()) } })
                })
        }
        prefs?.edit { putString(KEY, array.toString()) }
        main.post { listeners.forEach { it() } }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private const val KEY = "playlists"
}

/**
 * The playlist currently playing, so the player knows which part of each queued episode to play.
 * Only applies while the queue still matches the playlist; it is remembered across restarts.
 */
object ClipSession {
    private var prefs: SharedPreferences? = null

    /** The clips, in queue order. */
    @Volatile var entries: List<PlaylistEntry> = emptyList()
        private set

    /** Queue positions the listener chose to hear in full ("Play full episode"). */
    private val fullEpisode = mutableSetOf<Int>()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("lecture_clip_session", Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val array = JSONArray(p.getString("entries", "[]"))
            entries = (0 until array.length()).map { PlaylistEntry.fromJson(array.getJSONObject(it)) }
            val full = JSONArray(p.getString("full", "[]"))
            (0 until full.length()).forEach { fullEpisode.add(full.getInt(it)) }
        }
    }

    @Synchronized
    fun start(entries: List<PlaylistEntry>) {
        this.entries = entries
        fullEpisode.clear()
        save()
    }

    @Synchronized
    fun clear() {
        if (entries.isEmpty()) return
        entries = emptyList()
        fullEpisode.clear()
        save()
    }

    @Synchronized
    fun playFull(index: Int) {
        fullEpisode.add(index)
        save()
    }

    /** Whether the session describes this queue (same episodes, same order). */
    fun matches(queue: List<Song>) =
        entries.isNotEmpty() &&
            queue.size == entries.size &&
            queue.indices.all { queue[it].uid.toString() == entries[it].songUid }

    /** The clip for queue position [index], or null when it should play in full. */
    @Synchronized
    fun clipAt(index: Int): PlaylistEntry? {
        val entry = entries.getOrNull(index) ?: return null
        if (index in fullEpisode || !entry.isChapter) return null
        return entry
    }

    private fun save() {
        prefs?.edit {
            putString("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } }.toString())
            putString("full", JSONArray(fullEpisode.toList()).toString())
        }
    }
}
