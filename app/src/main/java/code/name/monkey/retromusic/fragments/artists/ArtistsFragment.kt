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
package code.name.monkey.retromusic.fragments.artists

import android.os.Bundle
import android.view.*
import androidx.core.os.bundleOf
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.lifecycle.lifecycleScope
import code.name.monkey.retromusic.EXTRA_ARTIST_ID
import code.name.monkey.retromusic.EXTRA_ARTIST_NAME
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.artist.ArtistAdapter
import code.name.monkey.retromusic.extensions.setUpMediaRouteButton
import code.name.monkey.retromusic.fragments.GridStyle
import code.name.monkey.retromusic.fragments.ReloadType
import code.name.monkey.retromusic.fragments.base.AbsRecyclerViewCustomGridSizeFragment
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.SortOrder.ArtistSortOrder
import code.name.monkey.retromusic.interfaces.IAlbumArtistClickListener
import code.name.monkey.retromusic.interfaces.IArtistClickListener
import code.name.monkey.retromusic.music.folders.FolderArtistRepository
import code.name.monkey.retromusic.music.smartplaylist.SmartPlaylistEngine
import code.name.monkey.retromusic.network.LastFMService
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.service.MusicService
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.RetroUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get

class ArtistsFragment : AbsRecyclerViewCustomGridSizeFragment<ArtistAdapter, GridLayoutManager>(),
    IArtistClickListener, IAlbumArtistClickListener {
    private var artistBuildJob: kotlinx.coroutines.Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        libraryViewModel.getSongs().observe(viewLifecycleOwner) { songs ->
            artistBuildJob?.cancel()
            artistBuildJob = viewLifecycleOwner.lifecycleScope.launch {
                val artists = withContext(Dispatchers.Default) {
                    FolderArtistRepository.artists(songs)
                }
                if (!isAdded) return@launch
                adapter?.apply {
                    swapDataSet(artists)
                    albumArtistsOnly = false
                }
            }
        }
    }


    override fun onDestroyView() {
        artistBuildJob?.cancel()
        artistBuildJob = null
        super.onDestroyView()
    }

    override val titleRes: Int
        get() = R.string.artists

    override val emptyMessage: Int
        get() = R.string.no_artists

    override val isShuffleVisible: Boolean
        get() = true

    override fun onShuffleClicked() {
        adapter?.dataSet?.let { artists ->
            val songs = artists.shuffled().flatMap { artist -> artist.songs }
            if (songs.isNotEmpty()) {
                MusicPlayerRemote.setShuffleMode(MusicService.SHUFFLE_MODE_NONE)
                MusicPlayerRemote.openQueue(queue = songs, startPosition = 0, startPlaying = true)
            }
        }
    }

    override fun setSortOrder(sortOrder: String) {
        libraryViewModel.forceReload(ReloadType.Songs)
    }

    override fun createLayoutManager(): GridLayoutManager {
        return GridLayoutManager(requireActivity(), getGridSize())
    }

    override fun createAdapter(): ArtistAdapter {
        val dataSet = if (adapter == null) ArrayList() else adapter!!.dataSet
        return ArtistAdapter(
            requireActivity(),
            dataSet,
            itemLayoutRes(),
            this,
            this,
            ::makeBestOf
        )
    }

    override fun loadGridSize(): Int {
        return PreferenceUtil.artistGridSize
    }

    override fun saveGridSize(gridColumns: Int) {
        PreferenceUtil.artistGridSize = gridColumns
    }

    override fun loadGridSizeLand(): Int {
        return PreferenceUtil.artistGridSizeLand
    }

    override fun saveGridSizeLand(gridColumns: Int) {
        PreferenceUtil.artistGridSizeLand = gridColumns
    }

    override fun setGridSize(gridSize: Int) {
        layoutManager?.spanCount = gridSize
        adapter?.notifyDataSetChanged()
    }

    override fun loadSortOrder(): String {
        return PreferenceUtil.artistSortOrder
    }

    override fun saveSortOrder(sortOrder: String) {
        PreferenceUtil.artistSortOrder = sortOrder
    }

    override fun loadLayoutRes(): Int {
        return PreferenceUtil.artistGridStyle.layoutResId
    }

    override fun saveLayoutRes(layoutRes: Int) {
        PreferenceUtil.artistGridStyle = GridStyle.values().first { gridStyle ->
            gridStyle.layoutResId == layoutRes
        }
    }

    companion object {

        fun newInstance(): ArtistsFragment {
            return ArtistsFragment()
        }
    }

    private fun makeBestOf(artist: code.name.monkey.retromusic.model.Artist) {
        val localSongs = artist.songs.distinctBy { it.id }
        if (localSongs.isEmpty()) {
            requireContext().showToast(getString(R.string.smart_playlist_empty))
            return
        }

        val playlistName = getString(R.string.best_of_playlist_name, artist.name)
        val lastFm = get<LastFMService>()
        requireContext().showToast(getString(R.string.best_of_building, artist.name))

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SmartPlaylistEngine.buildArtistBestOf(
                        localSongs,
                        artist.name,
                        lastFm
                    )
                }
            }

            if (!isAdded) return@launch
            val bestOf = result.getOrNull()
            when {
                bestOf == null -> {
                    requireContext().showToast(getString(R.string.smart_playlist_failed))
                }
                bestOf.isEmpty() -> {
                    requireContext().showToast(getString(R.string.best_of_empty, artist.name))
                }
                else -> {
                    runCatching {
                        libraryViewModel.replacePlaylistContents(
                            requireContext(),
                            playlistName,
                            bestOf
                        )
                    }.onFailure {
                        if (isAdded) {
                            requireContext().showToast(getString(R.string.smart_playlist_failed))
                        }
                    }
                }
            }
        }
    }

    override fun onArtist(artistId: Long, view: View) {
        val artist = adapter?.dataSet?.firstOrNull { it.id == artistId } ?: return
        val folderPath = artist.songs.firstOrNull()?.data
            ?.let { FolderArtistRepository.folderPathForSong(it) } ?: return
        val folderKey = FolderArtistRepository.keyForFolder(folderPath)
        findNavController().navigate(
            R.id.albumArtistDetailsFragment,
            bundleOf(EXTRA_ARTIST_NAME to folderKey)
        )
        reenterTransition = null
    }

    override fun onAlbumArtist(artistName: String, view: View) {
        findNavController().navigate(
            R.id.albumArtistDetailsFragment,
            bundleOf(EXTRA_ARTIST_NAME to artistName),
            null,
            FragmentNavigatorExtras(view to artistName)
        )
        reenterTransition = null
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateMenu(menu, inflater)
        val gridSizeItem: MenuItem = menu.findItem(R.id.action_grid_size)
        if (RetroUtil.isLandscape) {
            gridSizeItem.setTitle(R.string.action_grid_size_land)
        }
        setUpGridSizeMenu(gridSizeItem.subMenu!!)
        val layoutItem = menu.findItem(R.id.action_layout_type)
        setupLayoutMenu(layoutItem.subMenu!!)
        setUpSortOrderMenu(menu.findItem(R.id.action_sort_order).subMenu!!)
        //Setting up cast button
        requireContext().setUpMediaRouteButton(menu)
    }

    private fun setupAlbumArtistMenu(menu: Menu) {
        menu.add(0, R.id.action_album_artist, 0, R.string.show_album_artists).apply {
            isCheckable = true
            isChecked = PreferenceUtil.albumArtistsOnly
        }
    }

    private fun setUpSortOrderMenu(
        sortOrderMenu: SubMenu
    ) {
        val currentSortOrder: String? = getSortOrder()
        sortOrderMenu.clear()
        sortOrderMenu.add(
            0,
            R.id.action_artist_sort_order_asc,
            0,
            R.string.sort_order_a_z
        ).isChecked = currentSortOrder.equals(ArtistSortOrder.ARTIST_A_Z)
        sortOrderMenu.add(
            0,
            R.id.action_artist_sort_order_desc,
            1,
            R.string.sort_order_z_a
        ).isChecked = currentSortOrder.equals(ArtistSortOrder.ARTIST_Z_A)
        sortOrderMenu.setGroupCheckable(0, true, true)
    }

    private fun setupLayoutMenu(
        subMenu: SubMenu
    ) {
        when (itemLayoutRes()) {
            R.layout.item_card -> subMenu.findItem(R.id.action_layout_card).isChecked = true
            R.layout.item_grid -> subMenu.findItem(R.id.action_layout_normal).isChecked = true
            R.layout.item_card_color -> subMenu.findItem(R.id.action_layout_colored_card).isChecked =
                true
            R.layout.item_grid_circle -> subMenu.findItem(R.id.action_layout_circular).isChecked =
                true
            R.layout.image -> subMenu.findItem(R.id.action_layout_image).isChecked = true
            R.layout.item_image_gradient -> subMenu.findItem(R.id.action_layout_gradient_image).isChecked =
                true
        }
    }

    private fun setUpGridSizeMenu(
        gridSizeMenu: SubMenu
    ) {
        when (getGridSize()) {
            1 -> gridSizeMenu.findItem(R.id.action_grid_size_1).isChecked =
                true
            2 -> gridSizeMenu.findItem(R.id.action_grid_size_2).isChecked = true
            3 -> gridSizeMenu.findItem(R.id.action_grid_size_3).isChecked = true
            4 -> gridSizeMenu.findItem(R.id.action_grid_size_4).isChecked = true
            5 -> gridSizeMenu.findItem(R.id.action_grid_size_5).isChecked = true
            6 -> gridSizeMenu.findItem(R.id.action_grid_size_6).isChecked = true
            7 -> gridSizeMenu.findItem(R.id.action_grid_size_7).isChecked = true
            8 -> gridSizeMenu.findItem(R.id.action_grid_size_8).isChecked = true
        }
        val gridSize: Int = maxGridSize
        if (gridSize < 8) {
            gridSizeMenu.findItem(R.id.action_grid_size_8).isVisible = false
        }
        if (gridSize < 7) {
            gridSizeMenu.findItem(R.id.action_grid_size_7).isVisible = false
        }
        if (gridSize < 6) {
            gridSizeMenu.findItem(R.id.action_grid_size_6).isVisible = false
        }
        if (gridSize < 5) {
            gridSizeMenu.findItem(R.id.action_grid_size_5).isVisible = false
        }
        if (gridSize < 4) {
            gridSizeMenu.findItem(R.id.action_grid_size_4).isVisible = false
        }
        if (gridSize < 3) {
            gridSizeMenu.findItem(R.id.action_grid_size_3).isVisible = false
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (handleGridSizeMenuItem(item)) {
            return true
        }
        if (handleLayoutResType(item)) {
            return true
        }
        if (handleSortOrderMenuItem(item)) {
            return true
        }
        return super.onMenuItemSelected(item)
    }

    private fun handleAlbumArtistMenu(item: MenuItem): Boolean {
        return if (item.itemId == R.id.action_album_artist) {
            PreferenceUtil.albumArtistsOnly = !item.isChecked
            item.isChecked = !item.isChecked
            libraryViewModel.forceReload(ReloadType.Artists)
            true
        } else {
            false
        }
    }

    private fun handleSortOrderMenuItem(
        item: MenuItem
    ): Boolean {
        val sortOrder: String = when (item.itemId) {
            R.id.action_artist_sort_order_asc -> ArtistSortOrder.ARTIST_A_Z
            R.id.action_artist_sort_order_desc -> ArtistSortOrder.ARTIST_Z_A
            else -> PreferenceUtil.artistSortOrder
        }
        if (sortOrder != PreferenceUtil.artistSortOrder) {
            item.isChecked = true
            setAndSaveSortOrder(sortOrder)
            return true
        }
        return false
    }

    private fun handleLayoutResType(
        item: MenuItem
    ): Boolean {
        val layoutRes = when (item.itemId) {
            R.id.action_layout_normal -> R.layout.item_grid
            R.id.action_layout_card -> R.layout.item_card
            R.id.action_layout_colored_card -> R.layout.item_card_color
            R.id.action_layout_circular -> R.layout.item_grid_circle
            R.id.action_layout_image -> R.layout.image
            R.id.action_layout_gradient_image -> R.layout.item_image_gradient
            else -> PreferenceUtil.artistGridStyle.layoutResId
        }
        if (layoutRes != PreferenceUtil.artistGridStyle.layoutResId) {
            item.isChecked = true
            setAndSaveLayoutRes(layoutRes)
            return true
        }
        return false
    }

    private fun handleGridSizeMenuItem(
        item: MenuItem
    ): Boolean {
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

    override fun onResume() {
        super.onResume()
        libraryViewModel.forceReload(ReloadType.Songs)
    }
}
