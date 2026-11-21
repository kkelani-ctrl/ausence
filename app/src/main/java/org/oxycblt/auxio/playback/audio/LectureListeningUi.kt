/*
 * Copyright (c) 2026 Auxio Project
 * LectureListeningUi.kt is part of Auxio.
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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogListeningBinding
import org.oxycblt.auxio.databinding.DialogSleepBinding
import org.oxycblt.auxio.databinding.ItemChapterBinding
import org.oxycblt.auxio.databinding.ItemHomeHeaderBinding
import org.oxycblt.auxio.databinding.ItemPlaylistChoiceBinding
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment
import org.oxycblt.musikr.Song

/** "1×", "1.25×", "0.8×". */
fun formatSpeed(context: Context, speed: Float): String =
    context.getString(
        R.string.fmt_speed,
        "%.2f".format(java.util.Locale.US, speed).trimEnd('0').trimEnd('.'))

/** A one-field text dialog (playlist-style), multi-line for notes. */
fun promptText(
    context: Context,
    title: Int,
    initial: String?,
    multiLine: Boolean,
    onSave: (String) -> Unit,
) {
    val input =
        EditText(context).apply {
            setText(initial.orEmpty())
            isSingleLine = !multiLine
            if (multiLine) minLines = 3
            setSelection(text.length)
        }
    val container =
        FrameLayout(context).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
    MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setView(container)
        .setPositiveButton(R.string.lbl_lp_save) { _, _ -> onSave(input.text.toString()) }
        .setNegativeButton(R.string.lbl_lp_cancel, null)
        .show()
    input.requestFocus()
}

/** A small row of selectable text buttons (speed presets, skip lengths). */
private fun presetRow(
    row: ViewGroup,
    labels: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
) {
    row.removeAllViews()
    labels.forEachIndexed { i, label ->
        val button =
            MaterialButton(
                    row.context,
                    null,
                    if (i == selected) com.google.android.material.R.attr.materialButtonTonalStyle
                    else androidx.appcompat.R.attr.borderlessButtonStyle)
                .apply {
                    text = label
                    minWidth = 0
                    setOnClickListener { onPick(i) }
                }
        row.addView(button)
    }
}

// --- SPEED AND LISTENING ---

/** Speed (remembered per lecture), skip silence, voice boost and the double-tap skip length. */
@AndroidEntryPoint
class ListeningSheet : ViewBindingBottomSheetDialogFragment<DialogListeningBinding>() {
    private val playbackModel: PlaybackViewModel by activityViewModels()

    override fun onCreateBinding(inflater: LayoutInflater) = DialogListeningBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogListeningBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        val song = playbackModel.song.value
        binding.listeningSpeedSlider.apply {
            value = snap(LectureAudio.speedFor(song))
            setLabelFormatter { formatSpeed(context, it) }
            addOnChangeListener { _, v, fromUser ->
                binding.listeningSpeedValue.text = formatSpeed(context, v)
                if (fromUser) LectureAudio.setSpeed(song, v)
                refreshPresets(binding, song)
            }
        }
        binding.listeningSpeedValue.text = formatSpeed(requireContext(), LectureAudio.speedFor(song))
        refreshPresets(binding, song)

        binding.listeningSkipSilence.apply {
            isChecked = LectureAudio.skipSilence
            setOnCheckedChangeListener { _, on -> LectureAudio.skipSilence = on }
        }
        binding.listeningVoiceBoost.apply {
            isChecked = LectureAudio.voiceBoost
            setOnCheckedChangeListener { _, on -> LectureAudio.voiceBoost = on }
        }
        refreshSteps(binding)
    }

    private fun snap(speed: Float) = (Math.round(speed * 20) / 20f).coerceIn(0.5f, 3f)

    private fun refreshPresets(binding: DialogListeningBinding, song: Song?) {
        val current = snap(LectureAudio.speedFor(song))
        presetRow(
            binding.listeningSpeedPresets,
            SPEEDS.map { formatSpeed(requireContext(), it) },
            SPEEDS.indexOfFirst { it == current }) {
                LectureAudio.setSpeed(song, SPEEDS[it])
                binding.listeningSpeedSlider.value = SPEEDS[it]
            }
    }

    private fun refreshSteps(binding: DialogListeningBinding) {
        presetRow(
            binding.listeningStepPresets,
            STEPS.map { getString(R.string.fmt_seconds_short, it) },
            STEPS.indexOf(LectureAudio.stepSeconds)) {
                LectureAudio.stepSeconds = STEPS[it]
                refreshSteps(binding)
            }
    }

    companion object {
        const val TAG = "ausence_listening"
        private val SPEEDS = listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        private val STEPS = listOf(5, 10, 15, 30)
    }
}

// --- SLEEP TIMER ---

/** Sleep timer: presets, end of chapter / lecture, and a bar that drains while it runs. */
@AndroidEntryPoint
class SleepSheet : ViewBindingBottomSheetDialogFragment<DialogSleepBinding>() {
    private val playbackModel: PlaybackViewModel by activityViewModels()

    override fun onCreateBinding(inflater: LayoutInflater) = DialogSleepBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogSleepBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        val inflater = LayoutInflater.from(requireContext())
        val options =
            MINUTES.map { m -> getString(R.string.fmt_sleep_minutes, m) to { LectureAudio.sleepIn(m) } } +
                listOf(
                    getString(R.string.lbl_sleep_end_chapter) to
                        { LectureAudio.setSleep(LectureAudio.Sleep.EndOfChapter) },
                    getString(R.string.lbl_sleep_end_lecture) to
                        { LectureAudio.setSleep(LectureAudio.Sleep.EndOfLecture) })
        for ((label, action) in options) {
            ItemPlaylistChoiceBinding.inflate(inflater, binding.sleepOptions, true).apply {
                choiceIcon.setImageResource(R.drawable.ic_sleep_24)
                choiceTitle.text = label
                choiceInfo.isVisible = false
                root.setOnClickListener {
                    action()
                    dismiss()
                }
            }
        }
        binding.sleepOff.setOnClickListener { LectureAudio.setSleep(LectureAudio.Sleep.Off) }
        binding.sleepPlus5.setOnClickListener {
            val timer = LectureAudio.sleep.value as? LectureAudio.Sleep.Timer
            val ms = (timer?.remainingMs ?: 0) + 5 * 60_000L
            LectureAudio.setSleep(
                LectureAudio.Sleep.Timer(
                    android.os.SystemClock.elapsedRealtime() + ms,
                    maxOf(ms, timer?.totalMs ?: 0)))
        }
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                refresh(binding)
                delay(1000)
            }
        }
    }

    private fun refresh(binding: DialogSleepBinding) {
        val (remaining, total, text) = sleepRemaining(playbackModel) ?: run {
            binding.sleepActive.isVisible = false
            return
        }
        binding.sleepActive.isVisible = true
        binding.sleepPlus5.isVisible = LectureAudio.sleep.value is LectureAudio.Sleep.Timer
        binding.sleepRemaining.text = getString(text, remaining.formatDurationMs(true))
        binding.sleepBar.setProgressCompat(
            if (total > 0) (remaining * 1000 / total).toInt() else 0, true)
    }

    companion object {
        const val TAG = "ausence_sleep"
        private val MINUTES = listOf(15, 30, 45, 60)
    }
}

/**
 * Time left on the sleep timer, the span it drains over, and the message for it. Null when off.
 */
fun sleepRemaining(playbackModel: PlaybackViewModel): Triple<Long, Long, Int>? {
    val song = playbackModel.song.value
    val position = playbackModel.positionDs.value * 100
    return when (val sleep = LectureAudio.sleep.value) {
        LectureAudio.Sleep.Off -> null
        is LectureAudio.Sleep.Timer -> Triple(sleep.remainingMs, sleep.totalMs, R.string.fmt_sleep_left)
        LectureAudio.Sleep.EndOfChapter -> {
            song ?: return null
            val end = LectureAudio.chapterEndMs(song, position)
            val start = song.chapters.lastOrNull { it.startMs <= position }?.startMs ?: 0
            Triple((end - position).coerceAtLeast(0), end - start, R.string.fmt_sleep_chapter)
        }
        LectureAudio.Sleep.EndOfLecture -> {
            song ?: return null
            Triple(
                (song.durationMs - position).coerceAtLeast(0), song.durationMs,
                R.string.fmt_sleep_lecture)
        }
    }
}

// --- BOOKMARKS AND NOTES (above the chapter list) ---

/**
 * The top of the chapters sheet: a heading, this lecture's notes, and its bookmarks. Tap a
 * bookmark to jump there; its menu edits the note or deletes it.
 */
class BookmarksAdapter(private val onSeek: (Long) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var song: Song? = null
    private var bookmarks = emptyList<Bookmark>()

    fun submit(song: Song?) {
        this.song = song
        bookmarks = song?.let { LectureBookmarks.of(it) } ?: emptyList()
        @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
    }

    // Heading + note row + bookmarks (nothing at all without a lecture).
    override fun getItemCount() = if (song == null) 0 else 2 + bookmarks.size

    override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view =
            if (viewType == 0) ItemHomeHeaderBinding.inflate(inflater, parent, false).root
            else ItemChapterBinding.inflate(inflater, parent, false).root
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val song = song ?: return
        val context = holder.itemView.context
        if (position == 0) {
            ItemHomeHeaderBinding.bind(holder.itemView).homeHeaderTitle.setText(
                R.string.lbl_bookmarks_notes)
            return
        }
        val b = ItemChapterBinding.bind(holder.itemView)
        b.chapterPart.isVisible = false
        b.chapterAdd.isVisible = true
        if (position == 1) {
            // The lecture's own notes.
            val note = LectureBookmarks.noteOf(song)
            b.chapterTime.text = ""
            b.chapterTitle.text =
                context.getString(
                    if (note == null) R.string.lng_add_lecture_note else R.string.lbl_lecture_note)
            b.chapterSummary.isVisible = note != null
            b.chapterSummary.text = note
            b.chapterAdd.setImageResource(R.drawable.ic_note_24)
            val edit = {
                promptText(context, R.string.lbl_lecture_note, note, multiLine = true) {
                    LectureBookmarks.setLectureNote(song, it)
                }
            }
            b.root.setOnClickListener { edit() }
            b.chapterAdd.setOnClickListener { edit() }
            return
        }
        val bookmark = bookmarks[position - 2]
        b.chapterTime.text = bookmark.positionMs.formatDurationMs(true)
        b.chapterTitle.text = bookmark.note ?: context.getString(R.string.lbl_bookmark)
        b.chapterSummary.isVisible = false
        b.chapterAdd.setImageResource(R.drawable.ic_more_24)
        b.root.setOnClickListener { onSeek(bookmark.positionMs) }
        b.chapterAdd.setOnClickListener { anchor ->
            PopupMenu(context, anchor).apply {
                menu.add(0, 1, 0, R.string.lbl_edit_note)
                menu.add(0, 2, 1, R.string.lbl_delete)
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 ->
                            promptText(context, R.string.lbl_edit_note, bookmark.note, true) { n ->
                                LectureBookmarks.setNote(song, bookmark, n)
                            }
                        2 -> LectureBookmarks.remove(song, bookmark)
                    }
                    true
                }
                show()
            }
        }
    }
}
