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
import android.view.View\nimport android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.Preference\nimport androidx.lifecycle.lifecycleScope
import code.name.monkey.appthemehelper.common.prefs.supportv7.ATEListPreference
import code.name.monkey.retromusic.LANGUAGE_NAME
import code.name.monkey.retromusic.LAST_ADDED_CUTOFF
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.installLanguageAndRecreate
import code.name.monkey.retromusic.fragments.LibraryViewModel
import code.name.monkey.retromusic.fragments.ReloadType.HomeSections
import code.name.monkey.retromusic.util.PreferenceUtil\nimport code.name.monkey.retromusic.network.SpotifyCatalogApi\nimport code.name.monkey.retromusic.util.CustomArtistImageUtil
import org.koin.androidx.viewmodel.ext.android.activityViewModel\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\nimport kotlinx.coroutines.launch

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
        val rebuildPreference: Preference? = findPreference("spotify_rebuild_archive")\n        rebuildPreference?.setOnPreferenceClickListener { preference ->\n            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())\n            val hasCredentials = !prefs.getString("spotify_client_id", "").isNullOrBlank() &&\n                !prefs.getString("spotify_client_secret", "").isNullOrBlank()\n            if (!hasCredentials) {\n                Toast.makeText(requireContext(), R.string.spotify_credentials_required, Toast.LENGTH_LONG).show()\n                return@setOnPreferenceClickListener true\n            }\n            preference.isEnabled = false\n            preference.summary = getString(R.string.spotify_rebuild_starting)\n            viewLifecycleOwner.lifecycleScope.launch {\n                val artists = withContext(Dispatchers.IO) { libraryViewModel.getArtistsForSpotifyRebuild() }\n                    .distinctBy { it.name.trim().lowercase() }\n                    .filter { it.name.isNotBlank() && it.name != "Unknown Artist" && it.name != "Various Artists" }\n                var saved = 0\n                var checked = 0\n                val imageUtil = CustomArtistImageUtil.getInstance(requireContext())\n                for (artist in artists) {\n                    checked++\n                    val imageUrl = runCatching { SpotifyCatalogApi.findArtistImage(artist.name) }.getOrNull()\n                    if (imageUrl != null && !imageUtil.hasCustomArtistImage(artist) &&\n                        imageUtil.cacheSpotifyArtistImage(artist, imageUrl)) saved++\n                    preference.summary = getString(R.string.spotify_rebuild_progress, checked, artists.size)\n                }\n                preference.isEnabled = true\n                preference.summary = getString(R.string.spotify_rebuild_summary)\n                Toast.makeText(requireContext(), getString(R.string.spotify_rebuild_complete, checked, saved), Toast.LENGTH_LONG).show()\n            }\n            true\n        }\n        val languagePreference: Preference? = findPreference(LANGUAGE_NAME)
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
