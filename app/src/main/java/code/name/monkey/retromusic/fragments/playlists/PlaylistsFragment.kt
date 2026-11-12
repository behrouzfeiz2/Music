/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.fragments.playlists

import android.os.Bundle
import android.view.*
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.core.view.MenuCompat
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import code.name.monkey.retromusic.EXTRA_PLAYLIST_ID
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.playlist.PlaylistAdapter
import code.name.monkey.retromusic.db.PlaylistWithSongs
import code.name.monkey.retromusic.extensions.setUpMediaRouteButton
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.fragments.ReloadType
import code.name.monkey.retromusic.fragments.base.AbsRecyclerViewCustomGridSizeFragment
import code.name.monkey.retromusic.helper.SortOrder.PlaylistSortOrder
import code.name.monkey.retromusic.interfaces.IPlaylistClickListener
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.PlaylistPinStore
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.launch

class PlaylistsFragment :
    AbsRecyclerViewCustomGridSizeFragment<PlaylistAdapter, GridLayoutManager>(),
    IPlaylistClickListener {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        libraryViewModel.getPlaylists().observe(viewLifecycleOwner) { playlists ->
            PlaylistPinStore.retainExisting(
                requireContext(),
                playlists.map { it.playlistEntity.playListId }
            )
            adapter?.setPinnedPlaylistIds(PlaylistPinStore.getPinnedIds(requireContext()))
            adapter?.swapDataSet(playlists)
        }
    }

    override val titleRes: Int
        get() = R.string.playlists

    override val emptyMessage: Int
        get() = R.string.no_playlists

    override val isShuffleVisible: Boolean
        get() = false

    override fun createLayoutManager(): GridLayoutManager {
        // Keep the Spotify-inspired library grid consistent in portrait and landscape.
        return GridLayoutManager(requireContext(), 3)
    }

    override fun createAdapter(): PlaylistAdapter {
        val dataSet = if (adapter == null) mutableListOf() else adapter!!.dataSet
        return PlaylistAdapter(
            requireActivity(),
            dataSet,
            itemLayoutRes(),
            this
        ) { playlist ->
            when (PlaylistPinStore.toggle(
                requireContext(),
                playlist.playlistEntity.playListId
            )) {
                PlaylistPinStore.ToggleResult.PINNED,
                PlaylistPinStore.ToggleResult.UNPINNED -> {
                    adapter?.setPinnedPlaylistIds(PlaylistPinStore.getPinnedIds(requireContext()))
                }
                PlaylistPinStore.ToggleResult.LIMIT_REACHED -> {
                    showToast(R.string.playlist_pin_limit_reached)
                }
            }
        }.also {
            it.setPinnedPlaylistIds(PlaylistPinStore.getPinnedIds(requireContext()))
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateMenu(menu, inflater)
        // Playlist tiles use a fixed three-column layout; hide the generic grid-size control.
        menu.removeItem(R.id.action_grid_size)
        menu.removeItem(R.id.action_layout_type)
        menu.add(0, R.id.action_add_to_playlist, 0, R.string.new_playlist_title)
        menu.add(0, R.id.action_import_playlist, 0, R.string.import_playlist)
        menu.findItem(R.id.action_settings).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        setUpSortOrderMenu(menu.findItem(R.id.action_sort_order).subMenu!!)
        MenuCompat.setGroupDividerEnabled(menu, true)
        //Setting up cast button
        requireContext().setUpMediaRouteButton(menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (handleSortOrderMenuItem(item)) {
            return true
        }
        return super.onMenuItemSelected(item)
    }

    private fun setUpSortOrderMenu(subMenu: SubMenu) {
        val order: String? = getSortOrder()
        subMenu.clear()
        createId(
            subMenu,
            R.id.action_song_sort_order_asc,
            R.string.sort_order_a_z,
            order == PlaylistSortOrder.PLAYLIST_A_Z
        )
        createId(
            subMenu,
            R.id.action_song_sort_order_desc,
            R.string.sort_order_z_a,
            order == PlaylistSortOrder.PLAYLIST_Z_A
        )
        createId(
            subMenu,
            R.id.action_playlist_sort_order,
            R.string.sort_order_num_songs,
            order == PlaylistSortOrder.PLAYLIST_SONG_COUNT
        )
        createId(
            subMenu,
            R.id.action_playlist_sort_order_desc,
            R.string.sort_order_num_songs_desc,
            order == PlaylistSortOrder.PLAYLIST_SONG_COUNT_DESC
        )
        subMenu.setGroupCheckable(0, true, true)
    }

    private fun handleSortOrderMenuItem(item: MenuItem): Boolean {
        val sortOrder: String = when (item.itemId) {
            R.id.action_song_sort_order_asc -> PlaylistSortOrder.PLAYLIST_A_Z
            R.id.action_song_sort_order_desc -> PlaylistSortOrder.PLAYLIST_Z_A
            R.id.action_playlist_sort_order -> PlaylistSortOrder.PLAYLIST_SONG_COUNT
            R.id.action_playlist_sort_order_desc -> PlaylistSortOrder.PLAYLIST_SONG_COUNT_DESC
            else -> PreferenceUtil.playlistSortOrder
        }
        if (sortOrder != PreferenceUtil.playlistSortOrder) {
            item.isChecked = true
            setAndSaveSortOrder(sortOrder)
            return true
        }
        return false
    }

    private fun handleGridSizeMenuItem(item: MenuItem): Boolean {
        val gridSize = when (item.itemId) {
            R.id.action_grid_size_1 -> 1
            R.id.action_grid_size_2 -> 2
            R.id.action_grid_size_3 -> 3
            R.id.action_grid_size_4 -> 4
            R.id.action_grid_size_5 -> 5
            R.id.action_grid_size_6 -> 6
            R.id.action_grid_size_7 -> 7
            R.id.action_grid_size_8 -> 8
            else -> 0
        }
        if (gridSize > 0) {
            item.isChecked = true
            setAndSaveGridSize(gridSize)
            return true
        }
        return false
    }

    private fun createId(menu: SubMenu, id: Int, title: Int, checked: Boolean) {
        menu.add(0, id, 0, title).isChecked = checked
    }

    override fun setGridSize(gridSize: Int) {
        adapter?.notifyDataSetChanged()
    }

    override fun setSortOrder(sortOrder: String) {
        libraryViewModel.forceReload(ReloadType.Playlists)
    }

    override fun loadSortOrder(): String {
        return PreferenceUtil.playlistSortOrder
    }

    override fun saveSortOrder(sortOrder: String) {
        PreferenceUtil.playlistSortOrder = sortOrder
    }

    override fun loadGridSize(): Int = 3

    override fun saveGridSize(gridColumns: Int) = Unit

    override fun loadGridSizeLand(): Int = 3

    override fun saveGridSizeLand(gridColumns: Int) = Unit

    override fun loadLayoutRes(): Int = R.layout.item_playlist_library

    override fun saveLayoutRes(layoutRes: Int) {
        //Save layout
    }




    override fun onPlaylistClick(playlistWithSongs: PlaylistWithSongs, view: View) {
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).addTarget(requireView())
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
        findNavController().navigate(
            R.id.playlistDetailsFragment,
            bundleOf(EXTRA_PLAYLIST_ID to playlistWithSongs.playlistEntity.playListId)
        )
    }
}
