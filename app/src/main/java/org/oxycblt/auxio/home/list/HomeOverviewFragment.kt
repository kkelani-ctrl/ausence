/*
 * Copyright (c) 2026 Auxio Project
 * HomeOverviewFragment.kt is part of Auxio.
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

package org.oxycblt.auxio.home.list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeOverviewBinding
import org.oxycblt.auxio.databinding.ItemHomeHeaderBinding
import org.oxycblt.auxio.databinding.ItemSongBinding
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.progress.LectureProgress
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Song

/**
 * The Home tab: lectures in progress (most recent first), then the finished ones. Tap one to pick
 * up where you left off.
 */
@AndroidEntryPoint
class HomeOverviewFragment : ViewBindingFragment<FragmentHomeOverviewBinding>() {
    private val homeModel: HomeViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val adapter =
        OverviewAdapter(
            onPlay = { playbackModel.play(it, PlaySong.FromAlbum) },
            onMenu = { listModel.openMenu(R.menu.song, it, PlaySong.FromAlbum) },
        )
    private val onProgressChanged: () -> Unit = { rebuild() }
    private var lastKey: List<Pair<String, Boolean>>? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeOverviewBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentHomeOverviewBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.homeOverviewRecycler.adapter = adapter
        LectureProgress.addListener(onProgressChanged)
        collectImmediately(homeModel.songList) {
            lastKey = null
            rebuild()
        }
    }

    override fun onDestroyBinding(binding: FragmentHomeOverviewBinding) {
        super.onDestroyBinding(binding)
        LectureProgress.removeListener(onProgressChanged)
        binding.homeOverviewRecycler.adapter = null
    }

    private fun rebuild() {
        val binding = binding ?: return
        val withProgress =
            homeModel.songList.value.mapNotNull { song ->
                LectureProgress.get(song)?.let { song to it }
            }
        val inProgress =
            withProgress.filter { !it.second.finished }.sortedByDescending { it.second.updatedAt }
        val finished =
            withProgress.filter { it.second.finished }.sortedByDescending { it.second.updatedAt }
        // Progress bars update themselves; only rebuild the list when its makeup changes.
        val key = (inProgress + finished).map { it.first.uid.toString() to it.second.finished }
        if (key == lastKey) return
        lastKey = key

        val items = mutableListOf<OverviewItem>()
        if (inProgress.isNotEmpty()) {
            items += OverviewItem.Header(getString(R.string.lbl_continue_listening))
            items += inProgress.map { OverviewItem.Row(it.first) }
        }
        if (finished.isNotEmpty()) {
            items += OverviewItem.Header(getString(R.string.lbl_finished_lectures))
            items += finished.map { OverviewItem.Row(it.first) }
        }
        adapter.submit(items)
        binding.homeOverviewEmpty.isVisible = items.isEmpty()
    }
}

private sealed interface OverviewItem {
    data class Header(val title: String) : OverviewItem

    data class Row(val song: Song) : OverviewItem
}

private class OverviewAdapter(
    private val onPlay: (Song) -> Unit,
    private val onMenu: (Song) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items = emptyList<OverviewItem>()

    fun submit(list: List<OverviewItem>) {
        items = list
        @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) =
        if (items[position] is OverviewItem.Header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemHomeHeaderBinding.inflate(inflater, parent, false))
        } else {
            RowHolder(ItemSongBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is OverviewItem.Header ->
                (holder as HeaderHolder).binding.homeHeaderTitle.text = item.title
            is OverviewItem.Row -> {
                val b = (holder as RowHolder).binding
                val context = b.root.context
                val song = item.song
                b.songAlbumCover.bind(song)
                b.songName.text = song.name.resolve(context)
                b.songInfo.text =
                    context.getString(
                        R.string.fmt_two,
                        song.artists.resolveNames(context),
                        song.album.name.resolve(context))
                b.songProgress.bind(song)
                // Always show the cover: the mini player already shows what's playing, and the
                // "now playing" tile would hide the artwork here.
                b.root.setOnClickListener { onPlay(song) }
                b.songMenu.setOnClickListener { onMenu(song) }
            }
        }
    }

    class HeaderHolder(val binding: ItemHomeHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    class RowHolder(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ROW = 1
    }
}
