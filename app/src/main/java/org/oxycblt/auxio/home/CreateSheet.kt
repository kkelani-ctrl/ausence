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
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.databinding.DialogCreateBinding
import org.oxycblt.auxio.music.MusicViewModel

/**
 * The "Create" sheet from the bottom bar, as in Spotify: start a playlist, import one, or add a
 * folder of lectures.
 */
@AndroidEntryPoint
class CreateSheet : BottomSheetDialogFragment() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val homeModel: HomeViewModel by activityViewModels()
    private var binding: DialogCreateBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = DialogCreateBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)
        binding.createPlaylist.setOnClickListener {
            dismiss()
            musicModel.createPlaylist()
        }
        binding.createImport.setOnClickListener {
            dismiss()
            musicModel.importPlaylist()
        }
        binding.createFolder.setOnClickListener {
            dismiss()
            // Show the library first so the folder picker (opened from there) can appear.
            homeModel.startChooseMusicLocations()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        const val TAG = "ausence_create"
    }
}
