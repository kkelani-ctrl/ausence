/*
 * Copyright (c) 2026 Auxio Project
 * ChaptersSheetFragment.kt is part of Auxio.
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

package org.oxycblt.auxio.playback

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.databinding.FragmentChaptersSheetBinding
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Song

/**
 * The sheet under the player (where Auxio shows its queue): the playing lecture's chapters, like
 * the chapter list in an audiobook player. The current chapter is highlighted and kept in view;
 * tap one to jump to it.
 */
@AndroidEntryPoint
class ChaptersSheetFragment : ViewBindingFragment<FragmentChaptersSheetBinding>() {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val adapter =
        ChapterAdapter(
            onClick = { playbackModel.seekTo(it.startMs.msToDs()) },
            onAdd = { chapter ->
                playbackModel.song.value?.let { song ->
                    org.oxycblt.auxio.playback.clips.AddToLecturePlaylistSheet.show(
                        childFragmentManager,
                        org.oxycblt.auxio.playback.clips.PlaylistEntry.chapter(song, chapter))
                }
            })
    private val bookmarksAdapter =
        org.oxycblt.auxio.playback.audio.BookmarksAdapter { playbackModel.seekTo(it.msToDs()) }
    private val onBookmarksChanged: () -> Unit = {
        bookmarksAdapter.submit(playbackModel.song.value)
    }
    private var lastUserScrollMs = 0L
    private var lastIndex = -1

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentChaptersSheetBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentChaptersSheetBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)
        // Bookmarks and notes on top, then the chapters.
        binding.chaptersSheetRecycler.adapter =
            androidx.recyclerview.widget.ConcatAdapter(bookmarksAdapter, adapter)
        org.oxycblt.auxio.playback.audio.LectureBookmarks.addListener(onBookmarksChanged)
        binding.chaptersSheetRecycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        lastUserScrollMs = SystemClock.elapsedRealtime()
                    }
                }
            })
        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(playbackModel.positionDs) { updatePosition(it.dsToMs()) }
    }

    override fun onDestroyBinding(binding: FragmentChaptersSheetBinding) {
        super.onDestroyBinding(binding)
        org.oxycblt.auxio.playback.audio.LectureBookmarks.removeListener(onBookmarksChanged)
        binding.chaptersSheetRecycler.adapter = null
    }

    private fun updateSong(song: Song?) {
        val binding = requireBinding()
        adapter.submit(song?.chapters ?: emptyList())
        bookmarksAdapter.submit(song)
        lastIndex = -1
        updatePosition(playbackModel.positionDs.value.dsToMs())
    }

    private fun updatePosition(positionMs: Long) {
        val binding = binding ?: return
        val index = adapter.setCurrent(positionMs)
        if (index < 0 || index == lastIndex) return
        lastIndex = index
        if (SystemClock.elapsedRealtime() - lastUserScrollMs < FOLLOW_PAUSE_MS) return
        val lm = binding.chaptersSheetRecycler.layoutManager as? LinearLayoutManager ?: return
        // Chapters come after the bookmarks section.
        val row = index + bookmarksAdapter.itemCount
        binding.chaptersSheetRecycler.post { lm.scrollToPositionWithOffset(row, 0) }
    }

    private companion object {
        const val FOLLOW_PAUSE_MS = 4000L
    }
}
