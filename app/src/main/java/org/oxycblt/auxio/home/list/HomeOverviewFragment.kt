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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeOverviewBinding
import org.oxycblt.auxio.databinding.ItemHomeCardBinding
import org.oxycblt.auxio.databinding.ItemHomeCarouselBinding
import org.oxycblt.auxio.databinding.ItemHomeHeaderBinding
import org.oxycblt.auxio.databinding.ItemHomeShelfBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.progress.LectureProgress
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Song

/**
 * Home, laid out like a bookshelf: lectures in progress and recently finished as rows of large
 * covers, then every series on the shelf with a ring showing how much of it is listened.
 */
@AndroidEntryPoint
class HomeOverviewFragment : ViewBindingFragment<FragmentHomeOverviewBinding>() {
    private val homeModel: HomeViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val adapter =
        ShelfAdapter(
            onPlay = { playbackModel.play(it, PlaySong.FromAlbum) },
            onMenu = { listModel.openMenu(R.menu.song, it, PlaySong.FromAlbum) },
            onSeries = { detailModel.showAlbum(it) })
    private val onProgressChanged: () -> Unit = { rebuild() }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeOverviewBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentHomeOverviewBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.homeOverviewRecycler.layoutManager =
            GridLayoutManager(requireContext(), SHELF_COLUMNS).apply {
                spanSizeLookup =
                    object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int) =
                            if (adapter.isShelf(position)) 1 else SHELF_COLUMNS
                    }
            }
        binding.homeOverviewRecycler.adapter = adapter
        LectureProgress.addListener(onProgressChanged)
        collectImmediately(homeModel.songList, homeModel.albumList) { _, _ -> rebuild() }
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
        // Series you've touched most recently first, then the rest by name.
        val lastTouched =
            withProgress.groupBy { it.first.album }.mapValues { (_, v) ->
                v.maxOf { it.second.updatedAt }
            }
        val shelf =
            homeModel.albumList.value.sortedWith(
                compareByDescending<Album> { lastTouched[it] ?: 0L }
                    .thenBy { it.name.resolve(requireContext()) })

        val items = mutableListOf<HomeItem>()
        if (inProgress.isNotEmpty()) {
            items += HomeItem.Header(getString(R.string.lbl_continue_listening))
            items += HomeItem.Row(inProgress.map { it.first })
        }
        if (finished.isNotEmpty()) {
            items += HomeItem.Header(getString(R.string.lbl_recently_finished))
            items += HomeItem.Row(finished.take(RECENT_FINISHED).map { it.first })
        }
        if (shelf.isNotEmpty()) {
            items += HomeItem.Header(getString(R.string.lbl_your_shelf))
            items += shelf.map { HomeItem.Series(it) }
        }
        adapter.submit(items)
        binding.homeOverviewEmpty.isVisible = items.isEmpty()
    }

    private companion object {
        const val SHELF_COLUMNS = 3
        const val RECENT_FINISHED = 12
    }
}

private sealed interface HomeItem {
    data class Header(val title: String) : HomeItem

    data class Row(val songs: List<Song>) : HomeItem

    data class Series(val album: Album) : HomeItem
}

private class ShelfAdapter(
    private val onPlay: (Song) -> Unit,
    private val onMenu: (Song) -> Unit,
    private val onSeries: (Album) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items = emptyList<HomeItem>()

    fun submit(list: List<HomeItem>) {
        // Same makeup (e.g. just a progress tick): refresh rings and bars without rebinding
        // covers, so nothing flickers.
        if (list == items) {
            notifyItemRangeChanged(0, items.size, PAYLOAD_PROGRESS)
            return
        }
        items = list
        @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
    }

    fun isShelf(position: Int) = items.getOrNull(position) is HomeItem.Series

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) =
        when (items[position]) {
            is HomeItem.Header -> 0
            is HomeItem.Row -> 1
            is HomeItem.Series -> 2
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view =
            when (viewType) {
                0 -> ItemHomeHeaderBinding.inflate(inflater, parent, false).root
                1 ->
                    ItemHomeCarouselBinding.inflate(inflater, parent, false).root.apply {
                        layoutManager =
                            LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                    }
                else -> ItemHomeShelfBinding.inflate(inflater, parent, false).root
            }
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: List<Any>,
    ) {
        val item = items[position]
        if (PAYLOAD_PROGRESS in payloads && item is HomeItem.Series) {
            bindRing(ItemHomeShelfBinding.bind(holder.itemView), item.album)
        } else if (PAYLOAD_PROGRESS !in payloads) {
            onBindViewHolder(holder, position)
        }
        // Rows: their cards' progress bars update themselves.
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HomeItem.Header ->
                ItemHomeHeaderBinding.bind(holder.itemView).homeHeaderTitle.text = item.title
            is HomeItem.Row ->
                (holder.itemView as RecyclerView).adapter = CardAdapter(item.songs, onPlay, onMenu)
            is HomeItem.Series -> {
                val b = ItemHomeShelfBinding.bind(holder.itemView)
                val context = b.root.context
                b.shelfCover.bind(item.album)
                b.shelfTitle.text = item.album.name.resolve(context)
                b.root.setOnClickListener { onSeries(item.album) }
                bindRing(b, item.album)
            }
        }
    }

    /** The ring fills with how much of the series is listened (partial lectures count). */
    private fun bindRing(b: ItemHomeShelfBinding, album: Album) {
        val songs = album.songs
        val listened = songs.sumOf { (LectureProgress.get(it)?.fraction ?: 0f).toDouble() }
        val fraction = if (songs.isEmpty()) 0.0 else listened / songs.size
        b.shelfRingContainer.isVisible = fraction > 0
        b.shelfRing.setProgressCompat((fraction * 1000).toInt(), false)
        b.shelfInfo.text =
            b.root.context.getString(
                R.string.fmt_shelf_info, LectureProgress.finishedCount(songs), songs.size)
    }

    private companion object {
        const val PAYLOAD_PROGRESS = "progress"
    }
}

/** Large covers in a sideways row. Tap to resume; long-press for the lecture's menu. */
private class CardAdapter(
    private val songs: List<Song>,
    private val onPlay: (Song) -> Unit,
    private val onMenu: (Song) -> Unit,
) : RecyclerView.Adapter<CardAdapter.Holder>() {
    override fun getItemCount() = songs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemHomeCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val song = songs[position]
        val b = holder.binding
        val context = b.root.context
        b.cardCover.bind(song)
        b.cardTitle.text = song.name.resolve(context)
        b.cardArtist.text = song.artists.resolveNames(context)
        b.cardProgress.bind(song)
        b.root.setOnClickListener { onPlay(song) }
        b.root.setOnLongClickListener {
            onMenu(song)
            true
        }
    }

    class Holder(val binding: ItemHomeCardBinding) : RecyclerView.ViewHolder(binding.root)
}
