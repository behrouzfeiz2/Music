package code.name.monkey.retromusic.fragments.artists

import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Bundle
import android.text.Spanned
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.text.parseAsHtml
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.retromusic.EXTRA_ALBUM_ID
import code.name.monkey.retromusic.EXTRA_ARTIST_NAME
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.album.HorizontalAlbumAdapter
import code.name.monkey.retromusic.adapter.song.SimpleSongAdapter
import code.name.monkey.retromusic.databinding.FragmentArtistDetailsBinding
import code.name.monkey.retromusic.dialogs.AddToPlaylistDialog
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.artistImageOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.asBitmapPalette
import code.name.monkey.retromusic.glide.SingleColorTarget
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.SortOrder
import code.name.monkey.retromusic.interfaces.IAlbumClickListener
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.network.Result
import code.name.monkey.retromusic.network.SpotifyAlbum
import code.name.monkey.retromusic.network.SpotifyArtistProfile
import code.name.monkey.retromusic.network.SpotifyCatalogApi
import code.name.monkey.retromusic.network.SpotifyTrack
import code.name.monkey.retromusic.network.model.LastFmArtist
import code.name.monkey.retromusic.repository.RealRepository
import code.name.monkey.retromusic.util.*
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import java.util.*

abstract class AbsArtistDetailsFragment : AbsMainActivityFragment(R.layout.fragment_artist_details),
    IAlbumClickListener {
    private var _binding: FragmentArtistDetailsBinding? = null
    private val binding get() = _binding!!

    abstract val detailsViewModel: ArtistDetailsViewModel
    abstract val artistId: Long?
    abstract val artistName: String?
    private lateinit var artist: Artist
    private lateinit var songAdapter: SimpleSongAdapter
    private lateinit var albumAdapter: HorizontalAlbumAdapter
    private var forceDownload: Boolean = false
    private var lang: String? = null
    private var biography: Spanned? = null

    private val savedSongSortOrder: String
        get() = PreferenceUtil.artistDetailSongSortOrder
    private val savedAlbumSortOrder: String
        get() = PreferenceUtil.artistAlbumSortOrder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedElementEnterTransition = MaterialContainerTransform().apply {
            drawingViewId = R.id.fragment_container
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(surfaceColor())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentArtistDetailsBinding.bind(view)
        mainActivity.addMusicServiceEventListener(detailsViewModel)
        mainActivity.setSupportActionBar(binding.toolbar)
        binding.toolbar.title = null
        binding.artistCoverContainer.transitionName = (artistId ?: artistName).toString()
        postponeEnterTransition()
        detailsViewModel.getArtist().observe(viewLifecycleOwner) {
            view.doOnPreDraw {
                startPostponedEnterTransition()
            }
            showArtist(it)
        }
        setupRecyclerView()

        binding.fragmentArtistContent.playAction.apply {
            setOnClickListener { MusicPlayerRemote.openQueue(artist.sortedSongs, 0, true) }
        }
        binding.fragmentArtistContent.shuffleAction.apply {
            setOnClickListener { MusicPlayerRemote.openAndShuffleQueue(artist.songs, true) }
        }

        binding.fragmentArtistContent.biographyText.setOnClickListener {
            if (binding.fragmentArtistContent.biographyText.maxLines == 4) {
                binding.fragmentArtistContent.biographyText.maxLines = Integer.MAX_VALUE
            } else {
                binding.fragmentArtistContent.biographyText.maxLines = 4
            }
        }
        setupSongSortButton()
        setupAlbumSortButton()
        binding.appBarLayout?.statusBarForeground =
            MaterialShapeDrawable.createWithElevationOverlay(requireContext())
    }

    private fun setupRecyclerView() {
        albumAdapter = HorizontalAlbumAdapter(requireActivity(), ArrayList(), this)
        binding.fragmentArtistContent.albumRecyclerView.apply {
            itemAnimator = DefaultItemAnimator()
            layoutManager = GridLayoutManager(this.context, 1, GridLayoutManager.HORIZONTAL, false)
            adapter = albumAdapter
        }
        songAdapter = SimpleSongAdapter(requireActivity(), ArrayList(), R.layout.item_song)
        binding.fragmentArtistContent.recyclerView.apply {
            itemAnimator = DefaultItemAnimator()
            layoutManager = LinearLayoutManager(this.context)
            adapter = songAdapter
        }
    }

    private fun showArtist(artist: Artist) {
        if (artist.songCount == 0) {
            findNavController().navigateUp()
            return
        }
        this.artist = artist
        loadArtistImage(artist)
        loadSpotifyCatalog(artist)
        if (PreferenceUtil.isAllowedToDownloadMetadata(requireContext())) {
            loadBiography(artist.name)
        }
        binding.artistTitle.text = artist.name
        binding.text.text = String.format(
            "%s • %s",
            MusicUtil.getArtistInfoString(requireContext(), artist),
            MusicUtil.getReadableDurationString(MusicUtil.getTotalDuration(artist.songs))
        )
        val songText = resources.getQuantityString(
            R.plurals.albumSongs, artist.songCount, artist.songCount
        )
        val albumText = resources.getQuantityString(
            R.plurals.albums, artist.songCount, artist.songCount
        )
        binding.fragmentArtistContent.songTitle.text = songText
        binding.fragmentArtistContent.albumTitle.text = albumText
        songAdapter.swapDataSet(artist.sortedSongs)
        albumAdapter.swapDataSet(artist.albums)
    }


    private fun loadSpotifyCatalog(currentArtist: Artist) {
        binding.spotifyCatalog.removeAllViews()
        binding.spotifyCatalog.isVisible = false
        binding.spotifyLoading.isVisible =
            code.name.monkey.retromusic.BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank() &&
            code.name.monkey.retromusic.BuildConfig.SPOTIFY_CLIENT_SECRET.isNotBlank()
        if (!binding.spotifyLoading.isVisible) return

        viewLifecycleOwner.lifecycleScope.launch {
            val profile = runCatching { SpotifyCatalogApi.findArtist(currentArtist.name) }.getOrNull()
            if (_binding == null || !isAdded || artist.name != currentArtist.name) return@launch
            binding.spotifyLoading.isVisible = false
            if (profile != null) {
                renderSpotifyCatalog(profile, currentArtist)
                profile.imageUrl?.let { imageUrl ->
                    binding.image.scaleType = ImageView.ScaleType.FIT_CENTER
                    Glide.with(requireContext())
                        .load(imageUrl)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .dontTransform()
                        .into(binding.image)
                }
            }
        }
    }

    private fun renderSpotifyCatalog(profile: SpotifyArtistProfile, localArtist: Artist) {
        val container = binding.spotifyCatalog
        container.removeAllViews()
        container.isVisible = true

        val popularTitle = spotifySectionTitle("Popular tracks")
        container.addView(popularTitle)
        if (profile.tracks.isEmpty()) {
            container.addView(spotifyCaption("No popular tracks were returned by Spotify."))
        } else {
            profile.tracks.take(10).forEachIndexed { index, track ->
                container.addView(createSpotifyTrackRow(index + 1, track, localArtist))
            }
        }

        val albumsTitle = spotifySectionTitle("Albums & Singles")
        container.addView(albumsTitle)
        if (profile.albums.isEmpty()) {
            container.addView(spotifyCaption("No albums were found on Spotify."))
        } else {
            val horizontalScroll = android.widget.HorizontalScrollView(requireContext()).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
            }
            val albumRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(4), dp(16), dp(12))
            }
            profile.albums.forEach { album ->
                albumRow.addView(createSpotifyAlbumCard(album, localArtist))
            }
            horizontalScroll.addView(albumRow)
            container.addView(horizontalScroll)
        }

        if (profile.spotifyUrl.isNotBlank()) {
            val attribution = spotifyCaption("Spotify  •  View artist on Spotify").apply {
                gravity = Gravity.END
                setPadding(dp(16), dp(8), dp(16), dp(16))
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(profile.spotifyUrl)))
                }
            }
            container.addView(attribution)
        }
    }

    private fun spotifySectionTitle(title: String) = TextView(requireContext()).apply {
        text = title
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        setPadding(dp(16), dp(16), dp(16), dp(8))
    }

    private fun spotifyCaption(value: String) = TextView(requireContext()).apply {
        text = value
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setPadding(dp(16), dp(6), dp(16), dp(8))
    }

    private fun createSpotifyTrackRow(number: Int, track: SpotifyTrack, localArtist: Artist) =
        TextView(requireContext()).apply {
            text = "$number.  ${track.name}\n      ${track.album}"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setPadding(dp(20), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val localSong = localArtist.songs.firstOrNull {
                    normalizeSpotifyText(it.title) == normalizeSpotifyText(track.name)
                }
                if (localSong != null) {
                    MusicPlayerRemote.openQueue(listOf(localSong), 0, true)
                } else {
                    showToast("This track is not available in your local library.")
                }
            }
        }

    private fun createSpotifyAlbumCard(album: SpotifyAlbum, localArtist: Artist): View {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(12)
            }
            isClickable = true
            isFocusable = true
        }
        val cover = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(124), dp(124))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = album.name
        }
        album.imageUrl?.let { url ->
            Glide.with(requireContext())
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .dontTransform()
                .into(cover)
        }
        val title = TextView(requireContext()).apply {
            text = album.name
            maxLines = 2
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, dp(6), 0, 0)
        }
        val year = TextView(requireContext()).apply {
            text = album.releaseDate.take(4)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        card.addView(cover)
        card.addView(title)
        card.addView(year)
        card.setOnClickListener {
            val localAlbum = localArtist.albums.firstOrNull {
                normalizeSpotifyText(it.title) == normalizeSpotifyText(album.name)
            }
            if (localAlbum != null && localAlbum.songs.isNotEmpty()) {
                MusicPlayerRemote.openQueue(localAlbum.songs, 0, true)
            } else if (album.spotifyUrl.isNotBlank()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(album.spotifyUrl)))
            }
        }
        return card
    }

    private fun normalizeSpotifyText(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun loadBiography(
        name: String,
        lang: String? = Locale.getDefault().language,
    ) {
        biography = null
        this.lang = lang
        detailsViewModel.getArtistInfo(name, lang, null).observe(viewLifecycleOwner) { result ->
            when (result) {
                is Result.Loading -> logD("Loading")
                is Result.Error -> logE("Error")
                is Result.Success -> artistInfo(result.data)
            }
        }
    }

    private fun artistInfo(lastFmArtist: LastFmArtist?) {
        if (lastFmArtist != null && lastFmArtist.artist != null && lastFmArtist.artist.bio != null) {
            val bioContent = lastFmArtist.artist.bio.content
            if (bioContent != null && bioContent.trim().isNotEmpty()) {
                binding.fragmentArtistContent.run {
                    biographyText.isVisible = true
                    biographyTitle.isVisible = true
                    biography = bioContent.parseAsHtml()
                    biographyText.text = biography
                    if (lastFmArtist.artist.stats.listeners.isNotEmpty()) {
                        listeners.show()
                        listenersLabel.show()
                        scrobbles.show()
                        scrobblesLabel.show()
                        listeners.text =
                            RetroUtil.formatValue(lastFmArtist.artist.stats.listeners.toFloat())
                        scrobbles.text =
                            RetroUtil.formatValue(lastFmArtist.artist.stats.playcount.toFloat())
                    }
                }
            }
        }

        // If the "lang" parameter is set and no biography is given, retry with default language
        if (biography == null && lang != null) {
            loadBiography(artist.name, null)
        }
    }

    private fun loadArtistImage(artist: Artist) {
        Glide.with(requireContext()).asBitmapPalette().artistImageOptions(artist)
            .load(RetroGlideExtension.getArtistModel(artist)).dontAnimate()
            .into(object : SingleColorTarget(binding.image) {
                override fun onColorReady(color: Int) {
                    setColors(color)
                }
            })
    }

    private fun setColors(color: Int) {
        if (_binding != null) {
            binding.fragmentArtistContent.shuffleAction.applyColor(color)
            binding.fragmentArtistContent.playAction.applyOutlineColor(color)
        }
    }

    override fun onAlbumClick(albumId: Long, view: View) {
        if (albumId < 0L) {
            val selectedAlbum = artist.albums.firstOrNull { it.id == albumId } ?: return
            if (selectedAlbum.title.equals("Singles", ignoreCase = true)) {
                MusicPlayerRemote.openQueue(selectedAlbum.songs, 0, true)
                return
            }
            findNavController().navigate(
                R.id.albumDetailsFragment,
                bundleOf(
                    EXTRA_ALBUM_ID to albumId,
                    EXTRA_ARTIST_NAME to artistName
                ),
                null,
                FragmentNavigatorExtras(view to albumId.toString())
            )
            return
        }
        findNavController().navigate(
            R.id.albumDetailsFragment,
            bundleOf(EXTRA_ALBUM_ID to albumId),
            null,
            FragmentNavigatorExtras(
                view to albumId.toString()
            )
        )
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return handleSortOrderMenuItem(item)
    }

    private fun handleSortOrderMenuItem(item: MenuItem): Boolean {
        val songs = artist.songs
        when (item.itemId) {
            android.R.id.home -> findNavController().navigateUp()
            R.id.action_play_next -> {
                MusicPlayerRemote.playNext(songs)
                return true
            }

            R.id.action_add_to_current_playing -> {
                MusicPlayerRemote.enqueue(songs)
                return true
            }

            R.id.action_add_to_playlist -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    val playlists = get<RealRepository>().fetchPlaylists()
                    withContext(Dispatchers.Main) {
                        AddToPlaylistDialog.create(playlists, songs)
                            .show(childFragmentManager, "ADD_PLAYLIST")
                    }
                }
                return true
            }

            R.id.action_set_artist_image -> {
                selectImageLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
                return true
            }

            R.id.action_reset_artist_image -> {
                showToast(resources.getString(R.string.updating))
                lifecycleScope.launch {
                    CustomArtistImageUtil.getInstance(requireContext())
                        .resetCustomArtistImage(artist)
                    detailsViewModel.refreshArtistInfo()
                }
                forceDownload = true
                return true
            }
        }
        return true
    }

    private fun setupSongSortButton() {
        binding.fragmentArtistContent.songSortOrder.setOnClickListener {
            PopupMenu(requireContext(), binding.fragmentArtistContent.songSortOrder).apply {
                inflate(R.menu.menu_artist_song_sort_order)
                setUpSortOrderMenu(menu)
                setOnMenuItemClickListener { item ->
                    val sortOrder = when (item.itemId) {
                        R.id.action_sort_order_title -> SortOrder.ArtistSongSortOrder.SONG_A_Z
                        R.id.action_sort_order_title_desc -> SortOrder.ArtistSongSortOrder.SONG_Z_A
                        R.id.action_sort_order_album -> SortOrder.ArtistSongSortOrder.SONG_ALBUM
                        R.id.action_sort_order_year -> SortOrder.ArtistSongSortOrder.SONG_YEAR
                        R.id.action_sort_order_song_duration -> SortOrder.ArtistSongSortOrder.SONG_DURATION
                        else -> {
                            throw IllegalArgumentException("invalid ${item.title}")
                        }
                    }
                    item.isChecked = true
                    setSaveSortOrder(sortOrder)
                    return@setOnMenuItemClickListener true
                }
                show()
            }
        }
    }

    private fun setSaveSortOrder(sortOrder: String) {
        PreferenceUtil.artistDetailSongSortOrder = sortOrder
        songAdapter.swapDataSet(artist.sortedSongs)
    }

    private fun setupAlbumSortButton() {
        binding.fragmentArtistContent.albumSortOrder.setOnClickListener {
            PopupMenu(requireContext(), binding.fragmentArtistContent.albumSortOrder).apply {
                inflate(R.menu.menu_artist_album_sort_order)
                setUpAlbumSortOrderMenu(menu)
                setOnMenuItemClickListener { item ->
                    val sortOrder = when (item.itemId) {
                        R.id.action_sort_order_title -> SortOrder.ArtistAlbumSortOrder.ALBUM_A_Z
                        R.id.action_sort_order_title_desc -> SortOrder.ArtistAlbumSortOrder.ALBUM_Z_A
                        R.id.action_sort_order_year -> SortOrder.ArtistAlbumSortOrder.ALBUM_YEAR_ASC
                        R.id.action_sort_order_year_desc -> SortOrder.ArtistAlbumSortOrder.ALBUM_YEAR
                        else -> {
                            throw IllegalArgumentException("invalid ${item.title}")
                        }
                    }
                    item.isChecked = true
                    setSaveAlbumSortOrder(sortOrder)
                    return@setOnMenuItemClickListener true
                }
                show()
            }
        }
    }

    private fun setSaveAlbumSortOrder(sortOrder: String) {
        PreferenceUtil.artistAlbumSortOrder = sortOrder
        albumAdapter.swapDataSet(artist.sortedAlbums)
    }

    private fun setUpAlbumSortOrderMenu(sortOrder: Menu) {
        when (savedAlbumSortOrder) {
            SortOrder.ArtistAlbumSortOrder.ALBUM_A_Z -> sortOrder.findItem(R.id.action_sort_order_title).isChecked =
                true

            SortOrder.ArtistAlbumSortOrder.ALBUM_Z_A -> sortOrder.findItem(R.id.action_sort_order_title_desc).isChecked =
                true

            SortOrder.ArtistAlbumSortOrder.ALBUM_YEAR_ASC -> sortOrder.findItem(R.id.action_sort_order_year).isChecked =
                true

            SortOrder.ArtistAlbumSortOrder.ALBUM_YEAR -> sortOrder.findItem(R.id.action_sort_order_year_desc).isChecked =
                true

            else -> {
                throw IllegalArgumentException("invalid $savedAlbumSortOrder")
            }
        }
    }

    private fun setUpSortOrderMenu(sortOrder: Menu) {
        when (savedSongSortOrder) {
            SortOrder.ArtistSongSortOrder.SONG_A_Z -> sortOrder.findItem(R.id.action_sort_order_title).isChecked =
                true

            SortOrder.ArtistSongSortOrder.SONG_Z_A -> sortOrder.findItem(R.id.action_sort_order_title_desc).isChecked =
                true

            SortOrder.ArtistSongSortOrder.SONG_ALBUM -> sortOrder.findItem(R.id.action_sort_order_album).isChecked =
                true

            SortOrder.ArtistSongSortOrder.SONG_YEAR -> sortOrder.findItem(R.id.action_sort_order_year).isChecked =
                true

            SortOrder.ArtistSongSortOrder.SONG_DURATION -> sortOrder.findItem(R.id.action_sort_order_song_duration).isChecked =
                true

            else -> {
                throw IllegalArgumentException("invalid $savedSongSortOrder")
            }
        }
    }

    private val selectImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            lifecycleScope.launch {
                if (uri != null) {
                    CustomArtistImageUtil.getInstance(requireContext())
                        .setCustomArtistImage(artist, uri)

                    detailsViewModel.refreshArtistInfo()
                }
            }
        }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_artist_detail, menu)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}