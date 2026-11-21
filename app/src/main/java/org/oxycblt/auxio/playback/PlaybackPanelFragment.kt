/*
 * Copyright (c) 2021 Auxio Project
 * PlaybackPanelFragment.kt is part of Auxio.
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

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.abs
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentPlaybackPanelBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.queue.QueueViewModel
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.ui.StyledSeekBar
import org.oxycblt.auxio.playback.ui.stepper.Direction
import org.oxycblt.auxio.playback.ui.stepper.StepperOverlay
import org.oxycblt.auxio.playback.ui.swiper.CarouselTransformer
import org.oxycblt.auxio.playback.ui.swiper.CoverPagerAdapter
import org.oxycblt.auxio.playback.ui.swiper.UserAwarePagerCallback
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.dampen
import org.oxycblt.auxio.util.recycler
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.smoothScrollByPageTo
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.musikr.Chapter
import org.oxycblt.musikr.TranscriptLine
import org.oxycblt.musikr.indexAt
import org.oxycblt.musikr.lineAt
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A [ViewBindingFragment] more information about the currently playing song, alongside all
 * available controls.
 *
 * @author Alexander Capehart (OxygenCobalt)
 *
 * TODO: Improve flickering situation on play button
 */
@AndroidEntryPoint
class PlaybackPanelFragment :
    ViewBindingFragment<FragmentPlaybackPanelBinding>(),
    Toolbar.OnMenuItemClickListener,
    StyledSeekBar.Listener,
    StepperOverlay.Listener {
    private val coverPagerAdapter = CoverPagerAdapter(this)
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val queueModel: QueueViewModel by viewModels()
    private var equalizerLauncher: ActivityResultLauncher<Intent>? = null
    private var userAwarePagerCallback: UserAwarePagerCallback? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentPlaybackPanelBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentPlaybackPanelBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        // AudioEffect expects you to use startActivityForResult with the panel intent. There is no
        // contract analogue for this intent, so the generic contract is used instead.
        equalizerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                // Nothing to do
            }

        // --- UI SETUP ---
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsetsCompat
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        binding.playbackToolbar.apply {
            setNavigationOnClickListener { playbackModel.openMain() }
            setOnMenuItemClickListener(this@PlaybackPanelFragment)
        }

        binding.playbackPager?.apply {
            // intentional LTR override since thepager is a chronological element
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            adapter = coverPagerAdapter
            userAwarePagerCallback =
                UserAwarePagerCallback(this) {
                        // Posting the queue goto command prevents the seekbar pos from desyncing
                        // from the song's duration, which creates a visual flicker in the seekbar.
                        post { queueModel.goto(it) }
                    }
                    .also { it.attach() }
            setPageTransformer(CarouselTransformer())
            recycler().apply {
                // Make it possible to collapse the bottom sheet from the ViewPager's touch area.
                isNestedScrollingEnabled = false
                // Visual effect consistency
                // TODO: Custom overscroll?
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            // Make it easier to collapse the bottom sheet
            dampen()
            offscreenPageLimit = 1
        }

        // Set up fast seek overlay
        binding.playbackSong.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentSong() }
        }
        binding.playbackArtist.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentArtist() }
        }
        binding.playbackAlbum?.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentAlbum() }
        }

        binding.playbackSeekBar?.listener = this

        // Set up actions
        // TODO: Add better playback button accessibility
        binding.playbackRepeat.setOnClickListener { playbackModel.toggleRepeatMode() }
        binding.playbackSkipPrev.setOnClickListener { playbackModel.prev() }
        binding.playbackPlayPause.apply {
            @SuppressLint("RestrictedApi")
            setCornerSpringForce(
                SpringForce().apply {
                    stiffness = 700f
                    dampingRatio = 0.9f
                },
            )
            setOnClickListener { playbackModel.togglePlaying() }
        }
        binding.playbackSkipNext.setOnClickListener { playbackModel.next() }
        binding.playbackShuffle.setOnClickListener { playbackModel.toggleShuffled() }
        // Lectures fork: Apple Music-style bottom row (absent on short/landscape layouts, where
        // the same actions live in the toolbar).
        binding.playbackTranscript?.setOnClickListener { showTranscript() }
        binding.playbackLyricsCard?.setOnClickListener { showTranscript() }
        binding.playbackSpeed?.setOnClickListener {
            org.oxycblt.auxio.playback.audio.ListeningSheet().show(childFragmentManager, org.oxycblt.auxio.playback.audio.ListeningSheet.TAG)
        }
        binding.playbackSleep?.setOnClickListener {
            org.oxycblt.auxio.playback.audio.SleepSheet().show(childFragmentManager, org.oxycblt.auxio.playback.audio.SleepSheet.TAG)
        }
        // Tap: save this moment. Long-press: save it with a note.
        binding.playbackBookmark?.setOnClickListener { addBookmark(withNote = false) }
        binding.playbackBookmark?.setOnLongClickListener {
            addBookmark(withNote = true)
            true
        }
        // Like Spotify's queue button: slide up the chapters sheet under the player.
        binding.playbackChapters?.setOnClickListener { playbackModel.openQueue() }

        binding.playbackMore?.setOnClickListener {
            playbackModel.song.value?.let {
                listModel.openMenu(R.menu.playback_song, it, PlaySong.ByItself)
            }
        }

        // --- VIEWMODEL SETUP --
        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(playbackModel.parent, ::updateParent)
        collectImmediately(playbackModel.positionDs, ::updatePosition)
        collectImmediately(playbackModel.repeatMode, ::updateRepeat)
        collectImmediately(playbackModel.isPlaying, ::updatePlaying)
        collectImmediately(playbackModel.isShuffled, ::updateShuffled)
        collectImmediately(playbackModel.pagerQueue, ::updatePager)
        collectImmediately(org.oxycblt.auxio.playback.audio.LectureAudio.settingsVersion) { updateSpeedLabel() }
        collectImmediately(org.oxycblt.auxio.playback.audio.LectureAudio.sleep) { updateSleepLabel() }
        org.oxycblt.auxio.playback.audio.LectureBookmarks.addListener(onBookmarksChanged)
    }

    // FIXME: Old code!! Maybe not necessary anymore?
    //    override fun onStart() {
    //        super.onStart()
    //        playbackModel.song.value?.let { requireBinding().playbackCover.bind(it) }
    //        requireBinding().root.viewTreeObserver.addOnGlobalLayoutListener(this)
    //    }

    //    override fun onStop() {
    //        super.onStop()
    //        requireBinding().root.viewTreeObserver.removeOnGlobalLayoutListener(this)
    //    }

    //    override fun onGlobalLayout() {
    //        if (binding == null || lastCoverWidth < 0) {
    //            return
    //        }
    // Hacky workaround for cover radius not being preserved in between sizing changes
    // (i.e split screen or landscape mode)
    // For some reason ConstraintLayout does several passes on 1:1 elements that causes their
    // size to radically change, so we wait until it stabilizes and then force an image
    // reload if needed. Optimistically this is a no-op from coil caching, but when the cover
    // did accidentally load the wrong image (with weird corner radius intended for bigger
    // covers) we can force it to reload.
    // If this breaks, it's fine since we also started a load as we normally did w/state
    // updates, so the cover will not break.
    //        val binding = requireBinding()
    //        val coverWidth = binding.playbackCover.width
    //        if (lastCoverWidth != coverWidth) {
    //            lastCoverWidth = coverWidth
    //        } else {
    //            playbackModel.song.value?.let { binding.playbackCover.bind(it) }
    //            lastCoverWidth = -1
    //        }
    //    }

    private val onBookmarksChanged: () -> Unit = { updateBookmarkMarks() }

    private fun updateSpeedLabel() {
        val binding = binding ?: return
        binding.playbackSpeed?.text =
            org.oxycblt.auxio.playback.audio.formatSpeed(
                requireContext(), org.oxycblt.auxio.playback.audio.LectureAudio.speedFor(playbackModel.song.value))
    }

    /** The sleep button shows the minutes left while a timer runs. */
    private fun updateSleepLabel() {
        val button = binding?.playbackSleep ?: return
        val left = org.oxycblt.auxio.playback.audio.sleepRemaining(playbackModel)?.first
        button.text = left?.let { getString(R.string.fmt_sleep_button, (it / 60_000L) + 1) } ?: ""
    }

    private fun updateBookmarkMarks() {
        val binding = binding ?: return
        val song = playbackModel.song.value
        binding.playbackSeekBar?.bookmarkMarksDs =
            song?.let { org.oxycblt.auxio.playback.audio.LectureBookmarks.of(it).map { b -> b.positionMs.msToDs() } }
                ?: emptyList()
    }

    private fun addBookmark(withNote: Boolean) {
        val song = playbackModel.song.value ?: return
        val positionMs = playbackModel.positionDs.value.dsToMs()
        if (withNote) {
            org.oxycblt.auxio.playback.audio.promptText(
                requireContext(), R.string.lbl_bookmark_with_note, null, multiLine = true) {
                    org.oxycblt.auxio.playback.audio.LectureBookmarks.add(song, positionMs, it)
                }
        } else {
            org.oxycblt.auxio.playback.audio.LectureBookmarks.add(song, positionMs)
            android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.fmt_bookmark_saved, positionMs.formatDurationMs(true)),
                    android.widget.Toast.LENGTH_LONG)
                .show()
        }
    }

    override fun onDestroyBinding(binding: FragmentPlaybackPanelBinding) {
        org.oxycblt.auxio.playback.audio.LectureBookmarks.removeListener(onBookmarksChanged)
        equalizerLauncher = null
        coverPlaying = null
        binding.playbackPager?.animate()?.cancel()
        binding.playbackRepeat.clearPendingIcon()
        binding.playbackSong.isSelected = false
        binding.playbackArtist.isSelected = false
        binding.playbackAlbum?.isSelected = false
        binding.playbackToolbar.setOnMenuItemClickListener(null)
        userAwarePagerCallback?.release()
        binding.playbackPager?.adapter = null
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        // Lectures fork: chapter list and transcript sheets.
        if (item.itemId == R.id.action_show_chapters) {
            showChapters()
            return true
        }
        if (item.itemId == R.id.action_show_transcript) {
            showTranscript()
            return true
        }
        if (item.itemId == R.id.action_full_episode) {
            // Keep listening past the end of this playlist chapter; the playlist then carries on.
            org.oxycblt.auxio.playback.clips.ClipSession.playFull(queueModel.index.value)
            item.isVisible = false
            return true
        }
        if (item.itemId == R.id.action_open_equalizer) {
            // Launch the system equalizer app, if possible.
            L.d("Launching equalizer")
            val equalizerIntent =
                Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    // Provide audio session ID so the equalizer can show options for this app
                    // in particular.
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, playbackModel.currentAudioSessionId)
                    // Signal music type so that the equalizer settings are appropriate for
                    // music playback.
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            try {
                requireNotNull(equalizerLauncher) { "Equalizer panel launcher was not available" }
                    .launch(equalizerIntent)
            } catch (e: ActivityNotFoundException) {
                requireContext().showToast(R.string.err_no_app)
            }
            return true
        }

        return false
    }

    private fun showChapters() {
        if (playbackModel.song.value?.chapters.isNullOrEmpty()) return
        if (childFragmentManager.findFragmentByTag(ChaptersDialog.TAG) == null) {
            ChaptersDialog().show(childFragmentManager, ChaptersDialog.TAG)
        }
    }

    private fun showTranscript() {
        if (playbackModel.song.value?.transcriptText.isNullOrBlank()) return
        if (childFragmentManager.findFragmentByTag(TranscriptDialog.TAG) == null) {
            TranscriptDialog().show(childFragmentManager, TranscriptDialog.TAG)
        }
    }

    override fun onSeekConfirmed(positionDs: Long) {
        isScrubbing = false
        playbackModel.seekTo(positionDs)
    }

    private fun updateSong(song: Song?) {
        if (song == null) {
            // Nothing to do.
            return
        }

        val binding = requireBinding()
        val context = requireContext()
        L.d("Updating song display: $song")
        binding.playbackSong.text = song.name.resolve(context)
        binding.playbackArtist.text =
            if (binding.playbackAlbum == null) {
                // Compact layout: "Teacher · Series" on one line leaves more room for the cover.
                getString(
                    R.string.fmt_two,
                    song.artists.resolveNames(context),
                    song.album.name.resolve(context))
            } else {
                song.artists.resolveNames(context)
            }
        binding.playbackAlbum?.text = song.album.name.resolve(context)
        binding.playbackSeekBar?.durationDs = song.durationMs.msToDs()
        val hasChapters = song.chapters.isNotEmpty()
        val hasTranscript = !song.transcriptText.isNullOrBlank()
        // With the bottom row present, keep the top bar clean; otherwise use the toolbar.
        val hasBottomRow = binding.playbackLectureActions != null
        val session = org.oxycblt.auxio.playback.clips.ClipSession
        binding.playbackToolbar.menu.findItem(R.id.action_full_episode)?.isVisible =
            session.matches(queueModel.queue.value) && session.clipAt(queueModel.index.value) != null
        binding.playbackToolbar.menu.apply {
            findItem(R.id.action_show_chapters)?.isVisible = hasChapters && !hasBottomRow
            findItem(R.id.action_show_transcript)?.isVisible = hasTranscript && !hasBottomRow
        }
        // Live transcript card and chapter marks on the seek bar.
        lectureLines = TranscriptLine.parse(song.transcriptText)
        lectureChapters = song.chapters
        lastLineIndex = Int.MIN_VALUE
        binding.playbackLyricsCard?.isVisible = lectureLines.isNotEmpty()
        binding.playbackSeekBar?.chapterMarksDs = lectureChapters.map { it.startMs.msToDs() }
        updateSpeedLabel()
        updateBookmarkMarks()
        updateLectureDisplay(playbackModel.positionDs.value)

        // Like Apple Music's lyrics button: dimmed rather than hidden when unavailable.
        binding.playbackTranscript?.apply {
            isEnabled = hasTranscript
            alpha = if (hasTranscript) 1f else 0.38f
        }
        binding.playbackChapters?.apply {
            isEnabled = hasChapters
            alpha = if (hasChapters) 1f else 0.38f
        }
    }

    private fun updateParent(parent: MusicParent?) {
        val binding = requireBinding()
        val context = requireContext()
        binding.playbackToolbar.subtitle =
            parent?.run { name.resolve(context) } ?: context.getString(R.string.lbl_all_songs)
    }

    private fun updatePosition(positionDs: Long) {
        requireBinding().playbackSeekBar?.positionDs = positionDs
        updateLectureDisplay(positionDs)
    }

    private var lectureLines = emptyList<TranscriptLine>()
    private var lectureChapters = emptyList<Chapter>()
    private var lastLineIndex = Int.MIN_VALUE
    private var isScrubbing = false

    /** Keep the transcript card and the chapter name in step with playback. */
    private fun updateLectureDisplay(positionDs: Long) {
        val binding = binding ?: return
        val positionMs = positionDs.dsToMs()
        if (!isScrubbing) {
            val chapter = lectureChapters.getOrNull(lectureChapters.indexAt(positionMs))
            val song = playbackModel.song.value
            binding.playbackSeekBar?.chapterLabel =
                if (chapter == null || song == null) {
                    chapter?.title
                } else {
                    // "Chapter title · 6:12 left", like an audiobook player's chapter progress.
                    val left =
                        org.oxycblt.auxio.playback.audio.LectureAudio.chapterEndMs(song, positionMs) - positionMs
                    getString(
                        R.string.fmt_chapter_left_label,
                        chapter.title,
                        left.coerceAtLeast(0).formatDurationMs(false))
                }
        }
        updateSleepLabel()
        if (lectureLines.isEmpty()) return
        val index = lectureLines.lineAt(positionMs)
        if (index == lastLineIndex) return
        lastLineIndex = index
        val current = lectureLines.getOrNull(index) ?: lectureLines.first().takeIf { index < 0 }
        val next = lectureLines.getOrNull(if (index < 0) 1 else index + 1)
        binding.playbackLyricsCurrent?.text = current?.text
        binding.playbackLyricsNext?.apply {
            text = next?.text
            isVisible = next != null
        }
    }

    override fun onScrub(positionDs: Long) {
        // While dragging, show which chapter the finger is over.
        isScrubbing = true
        requireBinding().playbackSeekBar?.chapterLabel =
            lectureChapters.getOrNull(lectureChapters.indexAt(positionDs.dsToMs()))?.title
    }

    private fun updateRepeat(repeatMode: RepeatMode) {
        val repeatButton = requireBinding().playbackRepeat
        repeatButton.isChecked = repeatMode != RepeatMode.NONE
        repeatButton.setIconResource(repeatMode.icon)
    }

    private fun updatePlaying(isPlaying: Boolean) {
        requireBinding().playbackPlayPause.isChecked = isPlaying
        requireBinding().playbackSeekBar?.setWaveEnabled(isPlaying)
        animateCover(isPlaying)
    }

    private var coverPlaying: Boolean? = null

    /**
     * As in Apple Music, the artwork sits at full size while playing and recedes a little when
     * paused, with a soft spring.
     */
    private fun animateCover(isPlaying: Boolean) {
        val pager = requireBinding().playbackPager ?: return
        val target = if (isPlaying) 1f else PAUSED_COVER_SCALE
        val first = coverPlaying == null
        if (coverPlaying == isPlaying) return
        coverPlaying = isPlaying
        pager.animate().cancel()
        if (first) {
            pager.scaleX = target
            pager.scaleY = target
            return
        }
        pager
            .animate()
            .scaleX(target)
            .scaleY(target)
            .setDuration(if (isPlaying) 450L else 350L)
            .setInterpolator(
                if (isPlaying) android.view.animation.OvershootInterpolator(1.4f)
                else androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            .start()
    }

    private companion object {
        const val PAUSED_COVER_SCALE = 0.86f
    }

    private fun updateShuffled(isShuffled: Boolean) {
        requireBinding().playbackShuffle.isChecked = isShuffled
    }

    private fun updatePager(queue: PagerQueue) {
        // Right now there's easily 140ms of frame skipping when going next/prev. This is primarily
        // the fault of specifically the nested bottom sheet UI setup, which is intractable to
        // optimize. If I don't do multiple remeasures/relayouts on every slightest state
        // instability
        // I will suddenly encounter insane issues where the sheet fails to measure, appears below
        // the sidebar, flies away, not changing with ui scale, etc, often only on third-party OEM
        // ROMs that randomly mangle  SDK APIs and the SystemUI chrome for no reason.
        //
        // Historically this was not an issue, as I did not animate next/prev. Now I do, and it's
        // highly noticeable. So at least for plain next/prev I have to hack around it, do not
        // execute any transition until the state has fully adjudicated and laid out the UI. It's
        // not effective for swiping but there's nothing I can do there.
        //
        // Eventually one day Claude Fable 6.7 will probably be able to figure out that you need to
        // reflect into System.FoobaCrumbo::beegieConnector(GoolaUtils.PlubBud) and call it
        // specifically with 0x189B31FA alongside disabling the AndroidX Helpo SuperCharge by
        // manually clobbering `BottomSheetM2InternalBoogieCompat::scrimbloManager` to null for it
        // to not actually randomly mangle the sheets and do it in 1 clean layout, but for now I
        // must do this to keep my sanity.
        //
        // Actual snippet here was codex, just cleaned & adapted it / cognitive ownership
        requireBinding().playbackPager.apply {
            if (!isAttachedToWindow) {
                post { updatePagerImpl(queue) }
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isHardwareAccelerated) {
                // New version using post-Q frame hooks
                viewTreeObserver.registerFrameCommitCallback {
                    post { postOnAnimation { updatePagerImpl(queue) } }
                }
                postInvalidateOnAnimation()
            } else {
                // Let current layout happen, then wait for the next to conclude
                postOnAnimation { postOnAnimation { updatePagerImpl(queue) } }
            }
        }
    }

    private fun updatePagerImpl(queue: PagerQueue) {
        // Android insanity means this may be executed after view destruction
        // but only on some devices.
        val binding = binding ?: return

        val command = playbackModel.pagerCommand.consume()
        if (command == null) {
            // This probably shouldn't happen in practice, as QueueViewModel directly
            // attaches to PlaybackStateManager and will basically always initialize
            // with a command as a result.
            //
            // If it does happen we should just make sure the UI state is aligned. Don't
            // want broken UI.
            coverPagerAdapter.update(queue.queue, null)
            binding.playbackPager.setCurrentItem(queue.index, false)
            return
        }

        if (command.update != null) {
            // queue needs to be updated.
            coverPagerAdapter.update(queue.queue, command.update)
        }

        if (command.scroll != null) {
            // we need to scroll, however the smooth scroll only really looks best
            // when we are only doing next/prev due to various factors. better to
            // just not animate on outright gotos or queue updates
            val delta = binding.playbackPager.currentItem - command.scroll
            if (delta == 0) {
                // user scroll, carry on
                return
            }
            if (command.update == null && abs(delta) == 1) {
                binding.playbackPager.smoothScrollByPageTo(command.scroll)
            } else {
                binding.playbackPager.setCurrentItem(command.scroll, false)
            }
        }
    }

    private fun navigateToCurrentSong() {
        playbackModel.song.value?.let(detailModel::showAlbum)
    }

    private fun navigateToCurrentArtist() {
        playbackModel.song.value?.let(detailModel::showArtist)
    }

    private fun navigateToCurrentAlbum() {
        playbackModel.song.value?.let { detailModel.showAlbum(it.album) }
    }

    override fun seek(direction: Direction) {
        when (direction) {
            Direction.FORWARDS -> playbackModel.stepForward()
            Direction.BACKWARDS -> playbackModel.stepBackwards()
        }
    }
}
