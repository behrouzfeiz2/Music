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
package code.name.monkey.retromusic.fragments.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.Preference
import androidx.lifecycle.lifecycleScope
import code.name.monkey.appthemehelper.common.prefs.supportv7.ATEListPreference
import code.name.monkey.retromusic.LANGUAGE_NAME
import code.name.monkey.retromusic.LAST_ADDED_CUTOFF
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.installLanguageAndRecreate
import code.name.monkey.retromusic.fragments.LibraryViewModel
import code.name.monkey.retromusic.fragments.ReloadType.HomeSections
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.network.LastFmCatalogApi
import code.name.monkey.retromusic.util.CustomArtistImageUtil
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

/**
 * @author Hemanth S (h4h13).
 */

class OtherSettingsFragment : AbsSettingsFragment() {
    private val libraryViewModel by activityViewModel<LibraryViewModel>()

    override fun invalidateSettings() {
        val languagePreference: ATEListPreference? = findPreference(LANGUAGE_NAME)
        languagePreference?.setOnPreferenceChangeListener { _, _ ->
            restartActivity()
            return@setOnPreferenceChangeListener true
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        PreferenceUtil.languageCode =
            AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { "auto" }
        addPreferencesFromResource(R.xml.pref_advanced)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val preference: Preference? = findPreference(LAST_ADDED_CUTOFF)
        preference?.setOnPreferenceChangeListener { lastAdded, newValue ->
            setSummary(lastAdded, newValue)
            libraryViewModel.forceReload(HomeSections)
            true
        }
        val rebuildPreference: Preference? = findPreference("lastfm_rebuild_archive")
        rebuildPreference?.setOnPreferenceClickListener { clickedPreference ->
            val appContext = requireContext().applicationContext
            clickedPreference.isEnabled = false
            clickedPreference.summary = appContext.getString(R.string.lastfm_rebuild_starting)
            val imageUtil = CustomArtistImageUtil.getInstance(appContext)
            viewLifecycleOwner.lifecycleScope.launch {
                var checked = 0
                var saved = 0
                var notFound = 0
                var saveFailed = 0
                try {
                    val artists = withContext(Dispatchers.IO) {
                        libraryViewModel.getArtistsForArtworkRebuild()
                    }.distinctBy { it.id }
                        .filter {
                            it.name.isNotBlank() &&
                                it.name != "Unknown Artist" &&
                                it.name != "Various Artists" &&
                                !imageUtil.hasCustomArtistImage(it)
                        }

                    val total = artists.size
                    artists.forEachIndexed { index, artist ->
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        var requestFailed = false
                        val imageUrl = try {
                            LastFmCatalogApi.findArtistImage(artist.name)
                        } catch (error: java.io.IOException) {
                            // A timeout for one artist must not stop the entire archive scan.
                            requestFailed = true
                            saveFailed++
                            null
                        }
                        val wasSaved = if (imageUrl != null) {
                            imageUtil.cacheLastFmArtistImage(artist, imageUrl)
                        } else {
                            if (!requestFailed) notFound++
                            false
                        }
                        checked++
                        if (wasSaved) saved++ else if (imageUrl != null) saveFailed++
                        if (isAdded && view != null) {
                            clickedPreference.summary = appContext.getString(
                                R.string.lastfm_rebuild_progress, checked, total
                            )
                        }
                        if (index < artists.lastIndex) kotlinx.coroutines.delay(1100L)
                    }

                    if (isAdded && view != null) {
                        clickedPreference.summary = appContext.getString(R.string.lastfm_rebuild_summary)
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.lastfm_rebuild_complete, checked, saved, notFound, saveFailed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (isAdded && view != null) {
                        clickedPreference.summary = appContext.getString(R.string.lastfm_rebuild_summary)
                        val detail = error.message?.take(160).orEmpty()
                        val message = if (detail.isNotBlank()) {
                            "Last.fm update failed: $detail"
                        } else {
                            appContext.getString(R.string.lastfm_rebuild_failed)
                        }
                        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                    }
                } finally {
                    if (isAdded && view != null) {
                        clickedPreference.isEnabled = true
                    }
                }
            }
            true
        }
        val languagePreference: Preference? = findPreference(LANGUAGE_NAME)
        languagePreference?.setOnPreferenceChangeListener { prefs, newValue ->
            setSummary(prefs, newValue)
            if (newValue as? String == "auto") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            } else {
                // Install the languages from Play Store first and then set the application locale
                requireActivity().installLanguageAndRecreate(newValue.toString()) {
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(
                            newValue as? String
                        )
                    )
                }
            }
            true
        }
    }
}
