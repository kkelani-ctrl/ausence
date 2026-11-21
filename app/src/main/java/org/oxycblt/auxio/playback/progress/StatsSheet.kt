/*
 * Copyright (c) 2026 Auxio Project
 * StatsSheet.kt is part of Auxio.
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

package org.oxycblt.auxio.playback.progress

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BackportBottomSheetBehavior
import com.google.android.material.bottomsheet.BackportBottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogStatsBinding
import org.oxycblt.auxio.databinding.ItemPlaylistChoiceBinding
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.clips.formatLength
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment

/** Listening stats: today, this week, all time, the last 14 days and the most listened. */
@AndroidEntryPoint
class StatsSheet : ViewBindingBottomSheetDialogFragment<DialogStatsBinding>() {
    private val homeModel: HomeViewModel by activityViewModels()

    override fun onCreateBinding(inflater: LayoutInflater) = DialogStatsBinding.inflate(inflater)

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BackportBottomSheetDialog ?: return
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            it.layoutParams = it.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
        }
        dialog.behavior.apply {
            setSkipCollapsed(true)
            setState(BackportBottomSheetBehavior.STATE_EXPANDED)
        }
    }

    override fun onBindingCreated(binding: DialogStatsBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        val today = LocalDate.now()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val week =
            generateSequence(weekStart) { it.plusDays(1) }
                .takeWhile { !it.isAfter(today) }
                .sumOf { ListeningStats.dayMs(it) }
        val total = ListeningStats.totalMs()
        binding.statsToday.text = formatMs(ListeningStats.dayMs(today))
        binding.statsWeek.text = formatMs(week)
        binding.statsTotal.text = formatMs(total)
        binding.statsChart.days = ListeningStats.recentDays(14, today).map { it.second }

        val songs = homeModel.songList.value.associateBy { it.uid.toString() }
        val top =
            ListeningStats.perLecture().entries
                .mapNotNull { (uid, ms) -> songs[uid]?.let { it to ms } }
                .sortedByDescending { it.second }
                .take(TOP)
        val inflater = LayoutInflater.from(requireContext())
        for ((song, ms) in top) {
            ItemPlaylistChoiceBinding.inflate(inflater, binding.statsTop, true).apply {
                choiceIcon.setImageResource(R.drawable.ic_stats_24)
                choiceTitle.text = song.name.resolve(requireContext())
                choiceInfo.text = formatMs(ms)
            }
        }
        binding.statsEmpty.isVisible = total == 0L
        binding.statsTopHeader.isVisible = top.isNotEmpty()
    }

    private fun formatMs(ms: Long) = if (ms < 60_000L) "0 min" else formatLength(ms)

    companion object {
        const val TAG = "ausence_stats"
        private const val TOP = 5
    }
}

/** A plain bar chart of daily listening, oldest on the left, today highlighted. */
class DailyBarsView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var days: List<Long> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    }

    private val barPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = attrColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)
        }
    private val todayPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = attrColor(androidx.appcompat.R.attr.colorPrimary) }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val max = days.max().coerceAtLeast(1)
        val gap = 6 * resources.displayMetrics.density
        val barWidth = (width - gap * (days.size - 1)) / days.size
        val minBar = 3 * resources.displayMetrics.density
        days.forEachIndexed { i, ms ->
            val h = (height * ms.toFloat() / max).coerceAtLeast(minBar)
            val x = i * (barWidth + gap)
            rect.set(x, height - h, x + barWidth, height.toFloat())
            canvas.drawRoundRect(
                rect, barWidth / 3, barWidth / 3, if (i == days.lastIndex) todayPaint else barPaint)
        }
    }
}
