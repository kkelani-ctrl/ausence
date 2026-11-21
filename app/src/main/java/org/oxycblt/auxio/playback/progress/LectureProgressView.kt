/*
 * Copyright (c) 2026 Auxio Project
 * LectureProgressView.kt is part of Auxio.
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
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.oxycblt.musikr.Song

/**
 * A thin progress bar with "N min left" under a lecture in a list. Hidden for lectures that
 * haven't been started. Updates itself while visible, so the playing lecture's bar moves.
 */
class LectureProgressView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    LinearLayout(context, attrs, defStyleAttr) {
    private val bar =
        LinearProgressIndicator(context).apply {
            max = 1000
            trackThickness = dp(3)
            trackCornerRadius = dp(2)
            setIndicatorColor(attrColor(androidx.appcompat.R.attr.colorPrimary))
            trackColor = attrColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)
            // No gap/stop indicator: keep it a plain thin line.
            indicatorTrackGapSize = 0
            trackStopIndicatorSize = 0
        }
    private val label =
        TextView(context).apply {
            setTextAppearance(
                resolveStyle(com.google.android.material.R.attr.textAppearanceLabelSmall))
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            maxLines = 1
        }
    private var song: Song? = null
    private val onChange: () -> Unit = { refresh() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(bar, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(
            label,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
        isVisible = false
    }

    fun bind(song: Song) {
        this.song = song
        refresh()
    }

    private fun refresh() {
        val song = song ?: return
        val entry = LectureProgress.get(song)
        isVisible = entry != null
        if (entry == null) return
        bar.setProgressCompat((entry.fraction * 1000).toInt(), false)
        label.text = LectureProgress.describe(context, entry)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LectureProgress.addListener(onChange)
        refresh()
    }

    override fun onDetachedFromWindow() {
        LectureProgress.removeListener(onChange)
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)
            .toInt()

    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    }

    private fun resolveStyle(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.resourceId
    }

}
