/*
 * Copyright (c) 2024 Auxio Project
 * PlaybackServiceFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.service

import android.content.Context
import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.oxycblt.auxio.AuxioService.Companion.INTENT_KEY_START_ID
import org.oxycblt.auxio.ForegroundListener
import org.oxycblt.auxio.ForegroundServiceNotification
import org.oxycblt.auxio.IntegerTable
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.state.DeferredPlayback
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.widgets.WidgetComponent
import org.oxycblt.auxio.playback.audio.LectureAudio
import org.oxycblt.auxio.playback.progress.LectureProgress
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

class PlaybackServiceFragment
private constructor(
    context: Context,
    private val foregroundListener: ForegroundListener,
    private val playbackManager: PlaybackStateManager,
    private val playbackSettings: PlaybackSettings,
    exoHolderFactory: ExoPlaybackStateHolder.Factory,
    sessionHolderFactory: MediaSessionHolder.Factory,
    widgetComponentFactory: WidgetComponent.Factory,
    systemReceiverFactory: SystemPlaybackReceiver.Factory,
) : PlaybackStateManager.Listener {
    class Factory
    @Inject
    constructor(
        private val playbackManager: PlaybackStateManager,
        private val playbackSettings: PlaybackSettings,
        private val exoHolderFactory: ExoPlaybackStateHolder.Factory,
        private val sessionHolderFactory: MediaSessionHolder.Factory,
        private val widgetComponentFactory: WidgetComponent.Factory,
        private val systemReceiverFactory: SystemPlaybackReceiver.Factory,
    ) {
        fun create(context: Context, foregroundListener: ForegroundListener) =
            PlaybackServiceFragment(
                context,
                foregroundListener,
                playbackManager,
                playbackSettings,
                exoHolderFactory,
                sessionHolderFactory,
                widgetComponentFactory,
                systemReceiverFactory,
            )
    }

    private val waitJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + waitJob)
    private var autoStopJob: Job? = null
    private val exoHolder = exoHolderFactory.create()
    private val sessionHolder = sessionHolderFactory.create(context, foregroundListener)
    private val widgetComponent = widgetComponentFactory.create(context)
    private val systemReceiver =
        systemReceiverFactory.create(
            context,
            widgetComponent,
            onExitRequested = { playbackManager.endSession() },
        )

    private fun scheduleAutoStop() {
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            delay(AUTO_STOP_DELAY_MS.milliseconds)
            L.d("Auto-stop timer expired after ${AUTO_STOP_DELAY_MS / 60000} minutes of inactivity")
            playbackManager.endSession()
        }
    }

    private fun cancelAutoStop() {
        autoStopJob?.cancel()
        autoStopJob = null
    }

    private fun updateAutoStopTimer(isPlaying: Boolean) {
        if (isPlaying) {
            cancelAutoStop()
        } else if (exoHolder.sessionOngoing) {
            scheduleAutoStop()
        }
    }

    // --- MEDIASESSION CALLBACKS ---

    fun attach(): MediaSessionCompat.Token {
        exoHolder.attach()
        sessionHolder.attach()
        widgetComponent.attach()
        systemReceiver.attach()
        playbackManager.addListener(this)
        updateAutoStopTimer(playbackManager.progression.isPlaying)
        // Lectures fork: listening controls and the sleep timer, set from the UI.
        scope.launch { LectureAudio.settingsVersion.collect { applyListening() } }
        scope.launch { LectureAudio.sleep.collect(::onSleepChanged) }
        return sessionHolder.token
    }

    fun handleTaskRemoved() {
        if (!playbackManager.progression.isPlaying || playbackSettings.exitOnTaskRemoval) {
            playbackManager.endSession()
        }
    }

    fun start(intent: Intent?) {
        // At minimum we want to ensure an active playback state.
        // TODO: Possibly also force to go foreground?
        val startId = intent?.getIntExtra(INTENT_KEY_START_ID, -1)
        val action =
            when (startId) {
                IntegerTable.START_ID_ACTIVITY -> null
                IntegerTable.START_ID_TASKER ->
                    DeferredPlayback.RestoreState(
                        play = true,
                        fallback = DeferredPlayback.ShuffleAll,
                    )
                IntegerTable.START_ID_MEDIA_BUTTON -> {
                    if (!sessionHolder.tryMediaButtonIntent(intent)) {
                        // Malformed intent, need to restore state immediately
                        DeferredPlayback.RestoreState(
                            play = true,
                            fallback = DeferredPlayback.ShuffleAll,
                        )
                    } else {
                        null
                    }
                }
                else -> {
                    L.d("Handling non-native start.")
                    if (intent != null && sessionHolder.tryMediaButtonIntent(intent)) {
                        // Just a media button intent, move on.
                        return
                    }
                    // External services using Auxio better know what they are doing.
                    DeferredPlayback.RestoreState(play = false)
                }
            }
        if (action != null) {
            L.d("Initing service fragment using action $action")
            playbackManager.playDeferred(action)
        }
    }

    val notification: ForegroundServiceNotification?
        get() = if (exoHolder.sessionOngoing) sessionHolder.notification else null

    fun release() {
        saveLectureProgress()
        stopProgressTicker()
        stopClipMonitor()
        sleepJob?.cancel()
        autoStopJob?.cancel()
        waitJob.cancel()
        playbackManager.removeListener(this)
        systemReceiver.release()
        widgetComponent.release()
        sessionHolder.release()
        exoHolder.release()
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        cancelAutoStop()
        onLectureMaybeChanged()
    }

    override fun onIndexMoved(index: Int) {
        onLectureMaybeChanged()
    }

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) {
        onLectureMaybeChanged()
    }

    override fun onProgressionChanged(progression: Progression) {
        // Update timer whenever play/pause state changes
        updateAutoStopTimer(progression.isPlaying)
        // Lectures fork: remember where we are (pauses and seeks), and keep saving while playing.
        saveLectureProgress()
        if (progression.isPlaying) startProgressTicker() else stopProgressTicker()
        if (progression.isPlaying) startClipMonitor() else stopClipMonitor()
    }

    // --- LECTURE PROGRESS (audiobook-style resume) ---

    private var trackedSong: Song? = null
    private var progressJob: Job? = null

    private fun saveLectureProgress() {
        val song = playbackManager.currentSong ?: return
        if (song != trackedSong) return
        LectureProgress.save(song, playbackManager.progression.calculateElapsedPositionMs())
    }

    private fun startProgressTicker() {
        if (progressJob?.isActive == true) return
        lastStatsTick = android.os.SystemClock.elapsedRealtime()
        progressJob = scope.launch {
            while (true) {
                delay(PROGRESS_SAVE_INTERVAL_MS.milliseconds)
                saveLectureProgress()
                recordListening()
            }
        }
    }

    private fun stopProgressTicker() {
        if (progressJob?.isActive == true) recordListening()
        lastStatsTick = 0
        progressJob?.cancel()
        progressJob = null
    }

    private var lastStatsTick = 0L

    /** Add the real time listened since the last tick to the stats (wall time, not audio time). */
    private fun recordListening() {
        val now = android.os.SystemClock.elapsedRealtime()
        val song = playbackManager.currentSong
        if (lastStatsTick > 0 && song != null) {
            org.oxycblt.auxio.playback.progress.ListeningStats.record(
                song, (now - lastStatsTick).coerceAtMost(STATS_MAX_GAP_MS))
        }
        lastStatsTick = now
    }

    // --- CHAPTER PLAYLISTS (clips) ---

    private var trackedIndex = -1
    private var clipJob: Job? = null
    /** The queue position whose clip end was last acted on, so it's only handled once. */
    private var clipEndedAt = -1

    /** The clip the current queue position should play, if a chapter playlist is playing. */
    private fun currentClip(): org.oxycblt.auxio.playback.clips.PlaylistEntry? {
        val session = org.oxycblt.auxio.playback.clips.ClipSession
        if (session.entries.isEmpty()) return null
        val queue = playbackManager.queue
        if (queue.isEmpty()) return null
        if (!session.matches(queue)) {
            // The queue was replaced or edited: this is no longer that playlist.
            session.clear()
            return null
        }
        return session.clipAt(playbackManager.index)
    }

    private fun startClipMonitor() {
        if (clipJob?.isActive == true) return
        clipJob = scope.launch {
            while (true) {
                delay(CLIP_CHECK_INTERVAL_MS.milliseconds)
                val clip = currentClip() ?: continue
                val end = clip.endMs ?: continue
                val position = playbackManager.progression.calculateElapsedPositionMs()
                val index = playbackManager.index
                // Seeking back into the clip re-arms its end.
                if (index == clipEndedAt && position < end - CLIP_REARM_MS) clipEndedAt = -1
                if (position >= clip.startMs && position >= end - CLIP_END_SLACK_MS &&
                    index != clipEndedAt) {
                    clipEndedAt = index
                    if (index < playbackManager.queue.lastIndex) {
                        L.d("Chapter clip ended, moving to the next playlist item")
                        playbackManager.next()
                    } else {
                        L.d("Last chapter clip ended, pausing")
                        playbackManager.playing(false)
                    }
                }
            }
        }
    }

    private fun stopClipMonitor() {
        clipJob?.cancel()
        clipJob = null
    }

    // --- LISTENING CONTROLS AND SLEEP TIMER ---

    private var speedAppliedFor: Song? = null
    private var sleepJob: Job? = null

    /** Speed (this lecture's own, or the last used), skip silence and voice boost. */
    private fun applyListening() {
        speedAppliedFor = playbackManager.currentSong
        exoHolder.setSpeed(LectureAudio.speedFor(speedAppliedFor))
        exoHolder.setSkipSilence(LectureAudio.skipSilence)
        exoHolder.setVoiceBoost(LectureAudio.voiceBoost)
    }

    private fun onSleepChanged(sleep: LectureAudio.Sleep) {
        sleepJob?.cancel()
        exoHolder.setVolume(1f)
        if (sleep == LectureAudio.Sleep.Off) return
        val song = playbackManager.currentSong
        val position = playbackManager.progression.calculateElapsedPositionMs()
        // Where "end of chapter" / "end of lecture" falls, fixed when the timer is set.
        val targetMs =
            when (sleep) {
                LectureAudio.Sleep.EndOfChapter -> song?.let { LectureAudio.chapterEndMs(it, position) }
                LectureAudio.Sleep.EndOfLecture -> song?.durationMs
                else -> null
            }
        sleepJob = scope.launch {
            while (true) {
                delay(SLEEP_TICK_MS.milliseconds)
                val remaining =
                    when {
                        sleep is LectureAudio.Sleep.Timer -> sleep.remainingMs
                        // The lecture moved on (it ended, or the user skipped): stop now.
                        playbackManager.currentSong != song || targetMs == null -> 0L
                        else ->
                            targetMs - playbackManager.progression.calculateElapsedPositionMs()
                    }
                // Fade out over the last seconds instead of cutting off mid-sentence.
                exoHolder.setVolume((remaining.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f))
                if (remaining <= 0) {
                    playbackManager.playing(false)
                    exoHolder.setVolume(1f)
                    LectureAudio.setSleep(LectureAudio.Sleep.Off)
                    break
                }
            }
        }
    }

    /** When a different lecture starts: close out the old one, and resume the new one. */
    private fun onLectureMaybeChanged() {
        val song = playbackManager.currentSong
        val index = playbackManager.index
        if (song != speedAppliedFor) applyListening()
        // Chapter playlists: each queue position may be a clip of its episode (even when two
        // clips of the same episode follow each other).
        if (index != trackedIndex || song != trackedSong) {
            trackedIndex = index
            clipEndedAt = -1
            val clip = currentClip()
            if (clip != null) {
                LectureProgress.skipNextResume = false
                val current = playbackManager.progression.calculateElapsedPositionMs()
                trackedSong = song
                // Only jump when outside the chapter: restoring the app mid-chapter keeps its place.
                if (!org.oxycblt.musikr.isWithinSpan(
                    current, clip.startMs, clip.endMs, CLIP_SEEK_TOLERANCE_MS)) {
                    L.d("Starting chapter clip at ${clip.startMs} ms")
                    playbackManager.seekTo(clip.startMs)
                }
                return
            }
        }
        if (song == trackedSong) return
        trackedSong?.let { previous ->
            // Played through to the end (auto-advance): the last save was just before the end.
            val entry = LectureProgress.get(previous)
            if (entry != null && !entry.finished &&
                entry.positionMs >= previous.durationMs - FINISHED_ON_ADVANCE_MS) {
                LectureProgress.markFinished(previous)
            }
        }
        trackedSong = song
        if (song == null) return
        if (LectureProgress.skipNextResume) {
            LectureProgress.skipNextResume = false
            return
        }
        val entry = LectureProgress.get(song) ?: return
        if (entry.finished || entry.positionMs < RESUME_MIN_MS) return
        val current = playbackManager.progression.calculateElapsedPositionMs()
        // Restoring the app's own saved state already put us there.
        if (kotlin.math.abs(current - entry.positionMs) < RESUME_MIN_MS) return
        L.d("Resuming $song at ${entry.positionMs} ms")
        playbackManager.seekTo((entry.positionMs - RESUME_REWIND_MS).coerceAtLeast(0))
    }

    override fun onSessionEnded() {
        foregroundListener.updateForeground(ForegroundListener.Change.MEDIA_SESSION)
    }

    private companion object {
        private const val AUTO_STOP_DELAY_MS = 30L * 60L * 1000L
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        /** Saved positions closer to the start than this aren't resumed. */
        private const val RESUME_MIN_MS = 15_000L
        /** Rewind a little on resume so the listener can pick up the thread. */
        private const val RESUME_REWIND_MS = 5_000L
        /** If a lecture moves on with the last save this close to the end, it was finished. */
        private const val FINISHED_ON_ADVANCE_MS = 60_000L
        private const val CLIP_CHECK_INTERVAL_MS = 300L
        /** Move on this close to the clip's end (the next chapter's first words aren't heard). */
        private const val CLIP_END_SLACK_MS = 250L
        private const val CLIP_SEEK_TOLERANCE_MS = 2_000L
        private const val CLIP_REARM_MS = 5_000L
        private const val SLEEP_TICK_MS = 250L
        /** Never count more than this per tick (guards against a stalled ticker). */
        private const val STATS_MAX_GAP_MS = 15_000L
        private const val SLEEP_FADE_MS = 10_000f
    }
}
