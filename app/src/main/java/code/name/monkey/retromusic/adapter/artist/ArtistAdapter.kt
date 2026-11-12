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
package code.name.monkey.retromusic.adapter.artist

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.PopupMenu
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.base.AbsMultiSelectAdapter
import code.name.monkey.retromusic.adapter.base.MediaEntryViewHolder
import code.name.monkey.retromusic.extensions.hide
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.artistImageOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.asBitmapPalette
import code.name.monkey.retromusic.glide.RetroMusicColoredTarget
import code.name.monkey.retromusic.helper.menu.SongsMenuHelper
import code.name.monkey.retromusic.interfaces.IAlbumArtistClickListener
import code.name.monkey.retromusic.interfaces.IArtistClickListener
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.MusicUtil
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide
import me.zhanghai.android.fastscroll.PopupTextProvider
import java.util.concurrent.Executors

class ArtistAdapter(
    override val activity: FragmentActivity,
    var dataSet: List<Artist>,
    var itemLayoutRes: Int,
    val IArtistClickListener: IArtistClickListener,
    val IAlbumArtistClickListener: IAlbumArtistClickListener? = null,
    private val onMakeBestOf: (Artist) -> Unit = {}
) : AbsMultiSelectAdapter<ArtistAdapter.ViewHolder, Artist>(activity, R.menu.menu_media_selection),
    PopupTextProvider {

    var albumArtistsOnly = false

    init {
        this.setHasStableIds(true)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun swapDataSet(dataSet: List<Artist>) {
        this.dataSet = dataSet
        notifyDataSetChanged()
        albumArtistsOnly = PreferenceUtil.albumArtistsOnly
    }

    override fun getItemId(position: Int): Long {
        return dataSet[position].id
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
            try {
                LayoutInflater.from(activity).inflate(itemLayoutRes, parent, false)
            } catch (e: Resources.NotFoundException) {
                LayoutInflater.from(activity).inflate(R.layout.item_grid_circle, parent, false)
            }
        return createViewHolder(view)
    }

    private fun createViewHolder(view: View): ViewHolder {
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val artist = dataSet[position]
        val isChecked = isChecked(artist)
        holder.itemView.isActivated = isChecked
        holder.title?.text = artist.name
        holder.text?.hide()
        val transitionName =
            if (albumArtistsOnly) artist.name else artist.id.toString()
        if (holder.imageContainer != null) {
            holder.imageContainer?.transitionName = transitionName
        } else {
            holder.image?.transitionName = transitionName
        }
        loadArtistImage(artist, holder)
        holder.menu?.apply {
            isVisible = true
            setOnClickListener { anchor ->
                PopupMenu(activity, anchor).apply {
                    menu.add(0, R.id.action_add_to_playlist, 0, R.string.action_add_to_playlist)
                    menu.add(0, R.id.action_make_best_of, 1, R.string.make_best_of)
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            R.id.action_add_to_playlist -> SongsMenuHelper.handleMenuClick(
                                activity, artist.songs, item.itemId
                            )
                            R.id.action_make_best_of -> {
                                onMakeBestOf(artist)
                                true
                            }
                            else -> false
                        }
                    }
                    show()
                }
            }
        }
    }

    private fun setColors(processor: MediaNotificationProcessor, holder: ViewHolder) {
        holder.mask?.backgroundTintList = ColorStateList.valueOf(processor.primaryTextColor)
        if (holder.paletteColorContainer != null) {
            holder.paletteColorContainer?.setBackgroundColor(processor.backgroundColor)
            holder.title?.setTextColor(processor.primaryTextColor)
        }
        holder.imageContainerCard?.setCardBackgroundColor(processor.backgroundColor)
    }

    private fun loadArtistImage(artist: Artist, holder: ViewHolder) {
        if (holder.image == null) {
            return
        }
        Glide.with(activity)
            .asBitmapPalette()
            .artistImageOptions(artist)
            .load(RetroGlideExtension.getArtistModel(artist))
            .transition(RetroGlideExtension.getDefaultTransition())
            .into(object : RetroMusicColoredTarget(holder.image!!) {
                override fun onColorReady(colors: MediaNotificationProcessor) {
                    setColors(colors, holder)
                }
            })
        loadFolderCollage(artist, holder)
    }

    private fun loadFolderCollage(artist: Artist, holder: ViewHolder) {
        val imageView = holder.image ?: return
        imageView.tag = artist.id
        val albums = artist.albums.take(3)
        if (albums.size < 2) return
        coverExecutor.execute {
            val bitmaps = albums.mapNotNull { album ->
                val song = album.safeGetFirstSong()
                runCatching {
                    val target = Glide.with(activity)
                        .asBitmap()
                        .load(RetroGlideExtension.getSongModel(song))
                        .submit(256, 256)
                    try {
                        target.get()
                    } finally {
                        Glide.with(activity).clear(target)
                    }
                }.getOrNull()
            }
            if (bitmaps.size < 2) return@execute
            val size = 320
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val cardWidth = 224f
            val cardHeight = 224f
            val placements = listOf(
                Triple(112f, 164f, -13f),
                Triple(208f, 164f, 13f),
                Triple(160f, 144f, 0f)
            )
            bitmaps.take(3).forEachIndexed { index, bitmap ->
                val (centerX, centerY, rotation) = placements[index]
                val rect = RectF(
                    centerX - cardWidth / 2f,
                    centerY - cardHeight / 2f,
                    centerX + cardWidth / 2f,
                    centerY + cardHeight / 2f
                )
                canvas.save()
                canvas.rotate(rotation, centerX, centerY)
                paint.color = android.graphics.Color.WHITE
                paint.setShadowLayer(10f, 0f, 5f, 0x66000000)
                canvas.drawRoundRect(rect, 12f, 12f, paint)
                paint.clearShadowLayer()
                val clip = Path().apply { addRoundRect(rect, 9f, 9f, Path.Direction.CW) }
                canvas.save()
                canvas.clipPath(clip)
                canvas.drawBitmap(bitmap, null, rect, paint)
                canvas.restore()
                canvas.restore()
            }
            activity.runOnUiThread {
                if (imageView.tag == artist.id) {
                    imageView.setImageBitmap(output)
                } else {
                    output.recycle()
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    override fun getIdentifier(position: Int): Artist {
        return dataSet[position]
    }

    override fun getName(model: Artist): String {
        return model.name
    }

    override fun onMultipleItemAction(
        menuItem: MenuItem,
        selection: List<Artist>
    ) {
        SongsMenuHelper.handleMenuClick(activity, getSongList(selection), menuItem.itemId)
    }

    private fun getSongList(artists: List<Artist>): List<Song> {
        val songs = ArrayList<Song>()
        for (artist in artists) {
            songs.addAll(artist.songs) // maybe async in future?
        }
        return songs
    }

    override fun getPopupText(position: Int): String {
        return getSectionName(position)
    }

    private fun getSectionName(position: Int): String {
        return MusicUtil.getSectionName(dataSet[position].name)
    }

    inner class ViewHolder(itemView: View) : MediaEntryViewHolder(itemView) {

        init {
            menu?.isVisible = true
        }

        override fun onClick(v: View?) {
            super.onClick(v)
            if (isInQuickSelectMode) {
                toggleChecked(layoutPosition)
            } else {
                val artist = dataSet[layoutPosition]
                image?.let {
                    if (albumArtistsOnly && IAlbumArtistClickListener != null) {
                        IAlbumArtistClickListener.onAlbumArtist(artist.name, imageContainer ?: it)
                    } else {
                        IArtistClickListener.onArtist(artist.id, imageContainer ?: it)
                    }
                }
            }
        }

        override fun onLongClick(v: View?): Boolean {
            return toggleChecked(layoutPosition)
        }
    }
}
