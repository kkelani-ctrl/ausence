/*
 * Copyright (c) 2026 Auxio Project
 * LecturePlaylistUi.kt is part of Auxio.
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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BackportBottomSheetBehavior
import com.google.android.material.bottomsheet.BackportBottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogAddToPlaylistBinding
import org.oxycblt.auxio.databinding.DialogLecturePlaylistBinding
import org.oxycblt.auxio.databinding.FragmentLecturePlaylistsBinding
import org.oxycblt.auxio.databinding.ItemHomeHeaderBinding
import org.oxycblt.auxio.databinding.ItemPlaylistChoiceBinding
import org.oxycblt.auxio.databinding.ItemSongBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song

/** "1 h 12 min" / "12 min". */
internal fun formatLength(ms: Long): String {
    val min = (ms / 60_000L).coerceAtLeast(1)
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

/** Ask for a name and create a playlist (optionally with a first entry). */
fun promptNewLecturePlaylist(
    context: Context,
    first: List<PlaylistEntry> = emptyList(),
    onCreated: (LecturePlaylist) -> Unit = {},
) {
    val input =
        EditText(context).apply {
            hint = context.getString(R.string.lbl_lp_name_hint)
            isSingleLine = true
        }
    val container =
        FrameLayout(context).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.lbl_lp_new)
        .setView(container)
        .setPositiveButton(R.string.lbl_lp_create) { _, _ ->
            val name = input.text.toString().ifBlank { context.getString(R.string.lbl_lp_new) }
            val playlist = LecturePlaylists.create(name, first)
            if (first.isNotEmpty()) {
                Toast.makeText(
                        context, context.getString(R.string.fmt_lp_added, playlist.name),
                        Toast.LENGTH_SHORT)
                    .show()
            }
            onCreated(playlist)
        }
        .setNegativeButton(R.string.lbl_lp_cancel, null)
        .show()
    input.requestFocus()
}

/** Look up the episodes behind playlist entries, from the loaded library. */
internal fun songIndex(homeModel: HomeViewModel): Map<String, Song> =
    homeModel.songList.value.associateBy { it.uid.toString() }

internal fun entryLengthMs(entry: PlaylistEntry, song: Song?): Long =
    if (entry.isChapter) (entry.endMs ?: song?.durationMs ?: 0) - entry.startMs
    else song?.durationMs ?: 0

/** Bind a playlist entry into a song row: cover, title, "Chapter · episode · 12 min". */
internal fun bindEntryRow(b: ItemSongBinding, entry: PlaylistEntry, song: Song?) {
    val context = b.root.context
    if (song == null) {
        b.songName.text = entry.title ?: context.getString(R.string.lbl_lp_unavailable)
        b.songInfo.text = context.getString(R.string.lbl_lp_unavailable)
        b.songProgress.isVisible = false
        return
    }
    b.songAlbumCover.bind(song)
    val length = formatLength(entryLengthMs(entry, song))
    if (entry.isChapter) {
        b.songName.text = entry.title
        b.songInfo.text =
            context.getString(R.string.fmt_chapter_hit_info, song.name.resolve(context), length)
        b.songProgress.isVisible = false
    } else {
        b.songName.text = song.name.resolve(context)
        b.songInfo.text =
            context.getString(R.string.fmt_episode_info, song.artists.resolveNames(context), length)
        b.songProgress.bind(song)
    }
}

// --- ADD TO PLAYLIST ---

/** Pick (or create) a playlist for a chapter or a whole episode. */
@AndroidEntryPoint
class AddToLecturePlaylistSheet : ViewBindingBottomSheetDialogFragment<DialogAddToPlaylistBinding>() {
    private val entries: List<PlaylistEntry> by lazy {
        val array = org.json.JSONArray(requireArguments().getString(ARG_ENTRIES)!!)
        (0 until array.length()).map { PlaylistEntry.fromJson(array.getJSONObject(it)) }
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogAddToPlaylistBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogAddToPlaylistBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        val single = entries.singleOrNull()
        binding.addSubject.text =
            when {
                single?.title != null -> single.title
                single != null -> getString(R.string.lbl_lp_add_episode)
                else -> getString(R.string.fmt_lp_add_episodes, entries.size)
            }
        val inflater = LayoutInflater.from(requireContext())

        // "New playlist" first, then the existing ones.
        ItemPlaylistChoiceBinding.inflate(inflater, binding.addList, true).apply {
            choiceIcon.setImageResource(R.drawable.ic_add_24)
            choiceTitle.text = getString(R.string.lbl_lp_new)
            choiceInfo.isVisible = false
            root.setOnClickListener {
                val context = requireContext()
                dismiss()
                promptNewLecturePlaylist(context, entries)
            }
        }
        for (playlist in LecturePlaylists.all.reversed()) {
            ItemPlaylistChoiceBinding.inflate(inflater, binding.addList, true).apply {
                choiceTitle.text = playlist.name
                choiceInfo.text =
                    resources.getQuantityString(
                        R.plurals.fmt_song_count, playlist.entries.size, playlist.entries.size)
                root.setOnClickListener {
                    LecturePlaylists.add(playlist.id, entries)
                    Toast.makeText(
                            requireContext(),
                            getString(R.string.fmt_lp_added, playlist.name),
                            Toast.LENGTH_SHORT)
                        .show()
                    dismiss()
                }
            }
        }
    }

    companion object {
        private const val TAG = "ausence_add_to_playlist"
        private const val ARG_ENTRIES = "entries"

        fun show(fragmentManager: FragmentManager, entry: PlaylistEntry) =
            show(fragmentManager, listOf(entry))

        fun show(fragmentManager: FragmentManager, entries: List<PlaylistEntry>) {
            if (entries.isEmpty()) return
            val json = org.json.JSONArray().apply { entries.forEach { put(it.toJson()) } }
            AddToLecturePlaylistSheet()
                .apply { arguments = Bundle().apply { putString(ARG_ENTRIES, json.toString()) } }
                .show(fragmentManager, TAG)
        }

        /** Whole episodes, for adding a series, a teacher or a selection. */
        fun showEpisodes(fragmentManager: FragmentManager, songs: Collection<Song>) =
            show(fragmentManager, songs.map { PlaylistEntry.episode(it) })
    }
}

// --- LIBRARY: PLAYLISTS TAB ---

private sealed interface PlaylistsItem {
    data class Header(val title: String) : PlaylistsItem

    data object New : PlaylistsItem

    data class Lecture(val playlist: LecturePlaylist) : PlaylistsItem

    data class Imported(val playlist: Playlist) : PlaylistsItem
}

/**
 * The Playlists tab of Your Library: Ausence playlists (chapters and episodes), then any
 * playlists imported into Auxio.
 */
@AndroidEntryPoint
class LecturePlaylistsFragment : ViewBindingFragment<FragmentLecturePlaylistsBinding>() {
    private val homeModel: HomeViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val adapter = PlaylistsAdapter()
    private val onChanged: () -> Unit = { rebuild() }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentLecturePlaylistsBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentLecturePlaylistsBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.homePlaylistRecycler.adapter = adapter
        LecturePlaylists.addListener(onChanged)
        collectImmediately(homeModel.playlistList) { rebuild() }
        collectImmediately(homeModel.songList) { rebuild() }
    }

    override fun onDestroyBinding(binding: FragmentLecturePlaylistsBinding) {
        super.onDestroyBinding(binding)
        LecturePlaylists.removeListener(onChanged)
        binding.homePlaylistRecycler.adapter = null
    }

    private fun rebuild() {
        if (binding == null) return
        val items = mutableListOf<PlaylistsItem>(PlaylistsItem.New)
        items += LecturePlaylists.all.reversed().map { PlaylistsItem.Lecture(it) }
        val imported = homeModel.playlistList.value
        if (imported.isNotEmpty()) {
            items += PlaylistsItem.Header(getString(R.string.lbl_lp_imported))
            items += imported.map { PlaylistsItem.Imported(it) }
        }
        adapter.submit(items, songIndex(homeModel))
    }

    private inner class PlaylistsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items = emptyList<PlaylistsItem>()
        private var songs = emptyMap<String, Song>()

        fun submit(list: List<PlaylistsItem>, songs: Map<String, Song>) {
            items = list
            this.songs = songs
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) =
            when (items[position]) {
                is PlaylistsItem.Header -> 0
                else -> 1
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                object :
                    RecyclerView.ViewHolder(
                        ItemHomeHeaderBinding.inflate(inflater, parent, false).root) {}
            } else {
                object :
                    RecyclerView.ViewHolder(
                        ItemPlaylistChoiceBinding.inflate(inflater, parent, false).root) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val context = holder.itemView.context
            when (val item = items[position]) {
                is PlaylistsItem.Header ->
                    ItemHomeHeaderBinding.bind(holder.itemView).homeHeaderTitle.text = item.title
                PlaylistsItem.New ->
                    ItemPlaylistChoiceBinding.bind(holder.itemView).apply {
                        choiceIcon.setImageResource(R.drawable.ic_add_24)
                        choiceTitle.text = context.getString(R.string.lbl_lp_new)
                        choiceInfo.isVisible = false
                        root.setOnClickListener { promptNewLecturePlaylist(context) }
                    }
                is PlaylistsItem.Lecture ->
                    ItemPlaylistChoiceBinding.bind(holder.itemView).apply {
                        val p = item.playlist
                        choiceIcon.setImageResource(R.drawable.ic_playlist_24)
                        choiceTitle.text = p.name
                        choiceInfo.isVisible = true
                        val total = p.entries.sumOf { entryLengthMs(it, songs[it.songUid]) }
                        choiceInfo.text =
                            context.getString(R.string.fmt_lp_info, p.entries.size, formatLength(total))
                        root.setOnClickListener {
                            LecturePlaylistSheet.show(childFragmentManager, p.id)
                        }
                    }
                is PlaylistsItem.Imported ->
                    ItemPlaylistChoiceBinding.bind(holder.itemView).apply {
                        val p = item.playlist
                        choiceIcon.setImageResource(R.drawable.ic_playlist_24)
                        choiceTitle.text = p.name.resolve(context)
                        choiceInfo.isVisible = true
                        choiceInfo.text =
                            context.resources.getQuantityString(
                                R.plurals.fmt_song_count, p.songs.size, p.songs.size)
                        root.setOnClickListener { detailModel.showPlaylist(p) }
                    }
            }
        }
    }
}

// --- PLAYLIST PAGE ---

/** A playlist's page: its chapters and episodes, with Play and per-item options. */
@AndroidEntryPoint
class LecturePlaylistSheet : ViewBindingBottomSheetDialogFragment<DialogLecturePlaylistBinding>() {
    private val homeModel: HomeViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val playlistId: String by lazy { requireArguments().getString(ARG_ID)!! }
    private val adapter = EntryAdapter()
    private val onChanged: () -> Unit = { refresh() }

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogLecturePlaylistBinding.inflate(inflater)

    override fun onStart() {
        super.onStart()
        // Full height, like a page.
        val dialog = dialog as? BackportBottomSheetDialog ?: return
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            it.layoutParams = it.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
        }
        dialog.behavior.apply {
            setSkipCollapsed(true)
            setState(BackportBottomSheetBehavior.STATE_EXPANDED)
        }
    }

    override fun onBindingCreated(binding: DialogLecturePlaylistBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.lpRecycler.adapter = adapter
        binding.lpPlay.setOnClickListener { play(0, full = false) }
        binding.lpMore.setOnClickListener { showPlaylistMenu(it) }
        LecturePlaylists.addListener(onChanged)
        refresh()
    }

    override fun onDestroyBinding(binding: DialogLecturePlaylistBinding) {
        super.onDestroyBinding(binding)
        LecturePlaylists.removeListener(onChanged)
        binding.lpRecycler.adapter = null
    }

    private fun refresh() {
        val binding = binding ?: return
        val playlist = LecturePlaylists.get(playlistId)
        if (playlist == null) {
            dismiss()
            return
        }
        val songs = songIndex(homeModel)
        binding.lpName.text = playlist.name
        val total = playlist.entries.sumOf { entryLengthMs(it, songs[it.songUid]) }
        binding.lpInfo.text =
            getString(R.string.fmt_lp_info, playlist.entries.size, formatLength(total))
        binding.lpEmpty.isVisible = playlist.entries.isEmpty()
        binding.lpPlay.isEnabled = playlist.entries.isNotEmpty()
        adapter.submit(playlist.entries, songs)
    }

    /** Play from [index] (skipping entries whose episode is missing). */
    private fun play(index: Int, full: Boolean) {
        val playlist = LecturePlaylists.get(playlistId) ?: return
        val songs = songIndex(homeModel)
        val available = playlist.entries.withIndex().filter { songs[it.value.songUid] != null }
        if (available.isEmpty()) return
        val start = available.indexOfFirst { it.index >= index }.coerceAtLeast(0)
        playbackModel.playLecturePlaylist(
            available.map { it.value },
            available.map { songs.getValue(it.value.songUid) },
            start,
            full)
        playbackModel.openPlayback()
        dismiss()
    }

    private fun showPlaylistMenu(anchor: View) {
        val playlist = LecturePlaylists.get(playlistId) ?: return
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, R.string.lbl_lp_rename)
            menu.add(0, 2, 1, R.string.lbl_lp_delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> rename(playlist)
                    2 -> {
                        LecturePlaylists.delete(playlist.id)
                        dismiss()
                    }
                }
                true
            }
            show()
        }
    }

    private fun rename(playlist: LecturePlaylist) {
        val context = requireContext()
        val input =
            EditText(context).apply {
                setText(playlist.name)
                isSingleLine = true
                setSelection(text.length)
            }
        val container =
            FrameLayout(context).apply {
                val pad = (24 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lbl_lp_rename)
            .setView(container)
            .setPositiveButton(R.string.lbl_lp_save) { _, _ ->
                if (input.text.isNotBlank()) LecturePlaylists.rename(playlist.id, input.text.toString())
            }
            .setNegativeButton(R.string.lbl_lp_cancel, null)
            .show()
    }

    private fun showEntryMenu(anchor: View, index: Int, entry: PlaylistEntry, count: Int) {
        PopupMenu(requireContext(), anchor).apply {
            if (entry.isChapter) menu.add(0, 1, 0, R.string.lbl_lp_full_episode)
            if (index > 0) menu.add(0, 2, 1, R.string.lbl_lp_move_up)
            if (index < count - 1) menu.add(0, 3, 2, R.string.lbl_lp_move_down)
            menu.add(0, 4, 3, R.string.lbl_lp_remove)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> play(index, full = true)
                    2 -> LecturePlaylists.move(playlistId, index, index - 1)
                    3 -> LecturePlaylists.move(playlistId, index, index + 1)
                    4 -> LecturePlaylists.removeAt(playlistId, index)
                }
                true
            }
            show()
        }
    }

    private inner class EntryAdapter : RecyclerView.Adapter<EntryAdapter.Holder>() {
        private var entries = emptyList<PlaylistEntry>()
        private var songs = emptyMap<String, Song>()

        fun submit(list: List<PlaylistEntry>, songs: Map<String, Song>) {
            entries = list
            this.songs = songs
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            val b = holder.binding
            bindEntryRow(b, entry, songs[entry.songUid])
            b.root.setOnClickListener { play(position, full = false) }
            b.songMenu.setOnClickListener {
                showEntryMenu(it, position, entry, entries.size)
            }
        }

        inner class Holder(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        private const val TAG = "ausence_lecture_playlist"
        private const val ARG_ID = "id"

        fun show(fragmentManager: FragmentManager, id: String) {
            LecturePlaylistSheet()
                .apply { arguments = Bundle().apply { putString(ARG_ID, id) } }
                .show(fragmentManager, TAG)
        }
    }
}
