/*
 * Copyright (c) 2026 Auxio Project
 * CreateSheet.kt is part of Auxio.
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

package org.oxycblt.auxio.home

import android.os.Bundle
import android.view.LayoutInflater
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.databinding.DialogCreateBinding
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment

/**
 * The "Create" sheet from the bottom bar, as in Spotify: start a playlist, import one, or add a
 * folder of lectures.
 *
 * Uses Auxio's own bottom sheet base: the app replaces Material's bottom sheet dialog layout with
 * its backported behavior, so a stock BottomSheetDialogFragment crashes on open.
 */
@AndroidEntryPoint
class CreateSheet : ViewBindingBottomSheetDialogFragment<DialogCreateBinding>() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val homeModel: HomeViewModel by activityViewModels()

    override fun onCreateBinding(inflater: LayoutInflater) = DialogCreateBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogCreateBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.createPlaylist.setOnClickListener {
            val context = requireContext()
            dismiss()
            org.oxycblt.auxio.playback.clips.promptNewLecturePlaylist(context)
        }
        binding.createImport.setOnClickListener {
            dismiss()
            musicModel.importPlaylist()
        }
        binding.createFolder.setOnClickListener {
            // The folder picker opens from the library screen, so go there first.
            runCatching {
                androidx.navigation.Navigation.findNavController(
                        requireActivity(), org.oxycblt.auxio.R.id.explore_nav_host)
                    .popBackStack(org.oxycblt.auxio.R.id.home_fragment, false)
            }
            dismiss()
            homeModel.startChooseMusicLocations()
        }
    }

    companion object {
        const val TAG = "ausence_create"
    }
}
