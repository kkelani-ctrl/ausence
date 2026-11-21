/*
 * Copyright (c) 2026 Auxio Project
 * LectureSheets.kt is part of Auxio.
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

import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BackportBottomSheetBehavior
import com.google.android.material.bottomsheet.BackportBottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogLectureListBinding
import org.oxycblt.auxio.databinding.ItemChapterBinding
import org.oxycblt.auxio.databinding.ItemTranscriptLineBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.musikr.Chapter
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.TranscriptLine
import org.oxycblt.musikr.indexAt
import org.oxycblt.musikr.lineAt

/**
 * Shared base for the lecture bottom sheets: shows a list tied to the playing song and keeps the
 * current item highlighted (and in view) as playback moves.
 */
abstract class LectureListDialog : ViewBindingBottomSheetDialogFragment<DialogLectureListBinding>() {
    protected val playbackModel: PlaybackViewModel by activityViewModels()
    private var lastUserScrollMs = 0L
    private var lastAutoIndex = -1

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogLectureListBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogLectureListBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.root.setOnApplyWindowInsetsListener { v, insets ->
            v.updatePadding(bottom = insets.systemBarInsetsCompat.bottom)
            insets
        }
        binding.lectureListRecycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        lastUserScrollMs = SystemClock.elapsedRealtime()
                    }
                }
            })
        collectImmediately(playbackModel.song, ::onSong)
        collectImmediately(playbackModel.positionDs) { onPosition(it.dsToMs()) }
    }

    override fun onDestroyBinding(binding: DialogLectureListBinding) {
        super.onDestroyBinding(binding)
        binding.lectureListRecycler.adapter = null
    }

    private fun onSong(song: Song?) {
        val binding = requireBinding()
        lastAutoIndex = -1
        binding.lectureListSubtitle.text = song?.name?.resolve(requireContext())
        val count = bindSong(song)
        binding.lectureListEmpty.isVisible = count == 0
        binding.lectureListRecycler.isVisible = count > 0
        // Jump straight to where we are, without animation.
        onPosition(playbackModel.positionDs.value.dsToMs(), smooth = false)
    }

    private fun onPosition(positionMs: Long, smooth: Boolean = true) {
        val binding = binding ?: return
        val index = highlightAt(positionMs)
        if (index < 0 || index == lastAutoIndex) return
        lastAutoIndex = index
        // Don't fight the user: stop following for a few seconds after they scroll.
        if (SystemClock.elapsedRealtime() - lastUserScrollMs < FOLLOW_PAUSE_MS) return
        val recycler = binding.lectureListRecycler
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        if (smooth) {
            recycler.post { lm.scrollToPositionWithOffset(index, recycler.height / 3) }
        } else {
            lm.scrollToPositionWithOffset(index, recycler.height / 3)
        }
    }

    /** Seek in the current song. */
    protected fun seekToMs(ms: Long) {
        lastUserScrollMs = 0
        playbackModel.seekTo(ms.msToDs())
    }

    /** Bind the list for [song]. Returns the number of rows. */
    protected abstract fun bindSong(song: Song?): Int

    /** Highlight the row at [positionMs]. Returns its index, or -1. */
    protected abstract fun highlightAt(positionMs: Long): Int

    private companion object {
        const val FOLLOW_PAUSE_MS = 4000L
    }
}

/** Chapter list for the playing lecture. Tap a chapter to jump to it. */
@AndroidEntryPoint
class ChaptersDialog : LectureListDialog() {
    private val adapter = ChapterAdapter { seekToMs(it.startMs) }

    override fun onBindingCreated(binding: DialogLectureListBinding, savedInstanceState: Bundle?) {
        binding.lectureListTitle.setText(R.string.lbl_chapters)
        binding.lectureListEmpty.setText(R.string.lbl_no_chapters)
        binding.lectureListRecycler.adapter = adapter
        super.onBindingCreated(binding, savedInstanceState)
    }

    override fun bindSong(song: Song?): Int {
        adapter.submit(song?.chapters ?: emptyList())
        return adapter.itemCount
    }

    override fun highlightAt(positionMs: Long) = adapter.setCurrent(positionMs)

    companion object {
        const val TAG = "lecture_chapters"
    }
}

/** Time-synced transcript for the playing lecture. Tap a line to jump to it. */
@AndroidEntryPoint
class TranscriptDialog : LectureListDialog() {
    private val adapter = TranscriptAdapter { seekToMs(it.startMs) }

    override fun onBindingCreated(binding: DialogLectureListBinding, savedInstanceState: Bundle?) {
        binding.lectureListTitle.setText(R.string.lbl_transcript)
        binding.lectureListEmpty.setText(R.string.lbl_no_transcript)
        binding.lectureListRecycler.adapter = adapter
        super.onBindingCreated(binding, savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        // Cinematic: the transcript takes the whole screen.
        val dialog = dialog as? BackportBottomSheetDialog ?: return
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            it.layoutParams = it.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
        }
        dialog.behavior.apply {
            setSkipCollapsed(true)
            setState(BackportBottomSheetBehavior.STATE_EXPANDED)
        }
    }

    override fun bindSong(song: Song?): Int {
        adapter.submit(song?.transcript ?: emptyList())
        return adapter.itemCount
    }

    override fun highlightAt(positionMs: Long) = adapter.setCurrent(positionMs)

    companion object {
        const val TAG = "lecture_transcript"
    }
}

class ChapterAdapter(private val onClick: (Chapter) -> Unit) :
    RecyclerView.Adapter<ChapterAdapter.Holder>() {
    private var chapters = emptyList<Chapter>()
    private var current = -1

    fun submit(list: List<Chapter>) {
        chapters = list
        current = -1
        @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
    }

    fun setCurrent(positionMs: Long): Int {
        val index = chapters.indexAt(positionMs)
        if (index != current) {
            val old = current
            current = index
            if (old >= 0) notifyItemChanged(old)
            if (index >= 0) notifyItemChanged(index)
        }
        return index
    }

    override fun getItemCount() = chapters.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemChapterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val chapter = chapters[position]
        val b = holder.binding
        val context = b.root.context
        val isCurrent = position == current
        // Only show the part (talk / book chapter) heading when it changes.
        val showPart =
            chapter.part != null && chapter.part != chapters.getOrNull(position - 1)?.part
        b.chapterPart.isVisible = showPart
        b.chapterPart.text = chapter.part
        b.chapterTime.text =
            (if (chapter.approximate) "~" else "") + chapter.startMs.formatDurationMs(true)
        b.chapterTitle.text = chapter.title
        b.chapterTitle.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
        b.chapterTitle.setTextColor(
            context.getAttrColorCompat(
                if (isCurrent) androidx.appcompat.R.attr.colorPrimary
                else android.R.attr.textColorPrimary))
        b.chapterSummary.isVisible = chapter.summary != null
        b.chapterSummary.text = chapter.summary
        b.root.isActivated = isCurrent
        b.root.setOnClickListener { onClick(chapter) }
    }

    class Holder(val binding: ItemChapterBinding) : RecyclerView.ViewHolder(binding.root)
}

class TranscriptAdapter(private val onClick: (TranscriptLine) -> Unit) :
    RecyclerView.Adapter<TranscriptAdapter.Holder>() {
    private var lines = emptyList<TranscriptLine>()
    private var current = -1

    fun submit(list: List<TranscriptLine>) {
        lines = list
        current = -1
        @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
    }

    fun setCurrent(positionMs: Long): Int {
        val index = lines.lineAt(positionMs)
        if (index != current) {
            val old = current
            current = index
            if (old >= 0) notifyItemChanged(old, PAYLOAD_CURRENT)
            if (index >= 0) notifyItemChanged(index, PAYLOAD_CURRENT)
        }
        return index
    }

    override fun getItemCount() = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(
            ItemTranscriptLineBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: List<Any>) {
        if (payloads.contains(PAYLOAD_CURRENT)) {
            // Only the highlight changed: fade instead of rebinding.
            fade(holder, position == current, animate = true)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val line = lines[position]
        val b = holder.binding
        b.lineTime.isVisible = false
        b.lineText.text = line.text
        if (line.isTimed) {
            b.lineText.setTextAppearance(
                b.root.context.resolveAttr(
                    com.google.android.material.R.attr.textAppearanceHeadlineSmallEmphasized))
            fade(holder, position == current, animate = false)
        } else {
            // Untimed text (an audiobook's source text) reads as calm, normal-sized paragraphs.
            b.lineText.setTextAppearance(
                b.root.context.resolveAttr(com.google.android.material.R.attr.textAppearanceBodyLarge))
            b.lineText.alpha = 1f
        }
        b.lineText.setTextColor(
            b.root.context.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurface))
        if (line.isTimed) {
            b.root.setOnClickListener { onClick(line) }
        } else {
            b.root.setOnClickListener(null)
            b.root.isClickable = false
        }
    }

    private fun fade(holder: Holder, isCurrent: Boolean, animate: Boolean) {
        val view = holder.binding.lineText
        val target = if (isCurrent) 1f else DIM_ALPHA
        view.animate().cancel()
        if (animate) view.animate().alpha(target).setDuration(300L).start() else view.alpha = target
    }

    class Holder(val binding: ItemTranscriptLineBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val PAYLOAD_CURRENT = "current"
        const val DIM_ALPHA = 0.32f
    }
}

/** Resolve a theme attribute that points at a style (e.g. a text appearance). */
private fun android.content.Context.resolveAttr(attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return value.resourceId
}
