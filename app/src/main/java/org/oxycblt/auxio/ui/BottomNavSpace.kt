/*
 * Copyright (c) 2026 Auxio Project
 * BottomNavSpace.kt is part of Auxio.
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

package org.oxycblt.auxio.ui

/**
 * Lectures fork: the height of the bottom navigation bar (including the system navigation inset
 * it pads for), so the mini player can rest on top of it and content can scroll clear of both.
 * Zero when there is no bottom bar (e.g. wide layouts).
 */
object BottomNavSpace {
    @Volatile var height: Int = 0
}
