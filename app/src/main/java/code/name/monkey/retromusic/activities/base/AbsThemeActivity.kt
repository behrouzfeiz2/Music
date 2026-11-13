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
package code.name.monkey.retromusic.activities.base

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode
import androidx.core.os.LocaleListCompat
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.appthemehelper.common.ATHToolbarActivity
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.exitFullscreen
import code.name.monkey.retromusic.extensions.hideStatusBar
import code.name.monkey.retromusic.extensions.installSplitCompat
import code.name.monkey.retromusic.extensions.maybeSetScreenOn
import code.name.monkey.retromusic.extensions.maybeShowWhenLocked
import code.name.monkey.retromusic.extensions.setEdgeToEdgeOrImmersive
import code.name.monkey.retromusic.extensions.setImmersiveFullscreen
import code.name.monkey.retromusic.extensions.setLightNavigationBarAuto
import code.name.monkey.retromusic.extensions.setLightStatusBarAuto
import code.name.monkey.retromusic.extensions.surfaceColor
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.theme.getNightMode
import code.name.monkey.retromusic.util.theme.getThemeResValue
import com.google.android.material.button.MaterialButton

abstract class AbsThemeActivity : ATHToolbarActivity(), Runnable {

    private val handler = Handler(Looper.getMainLooper())
    private var accentTreeObserver: ViewTreeObserver? = null
    private var accentLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    override fun onContentChanged() {
        super.onContentChanged()
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        accentLayoutListener?.let { listener ->
            accentTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
        }
        val observer = content.viewTreeObserver
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            applyAccentToButtons(content)
        }
        accentTreeObserver = observer
        accentLayoutListener = listener
        observer.addOnGlobalLayoutListener(listener)
        content.post { applyAccentToButtons(content) }
    }

    private fun applyAccentToButtons(view: View) {
        val accentColor = ThemeStore.accentColor(this)
        if (view is MaterialButton) {
            val values = view.context.obtainStyledAttributes(
                intArrayOf(androidx.appcompat.R.attr.colorPrimary)
            )
            val primaryColor = try {
                values.getColor(0, Int.MIN_VALUE)
            } finally {
                values.recycle()
            }
            if (primaryColor != Int.MIN_VALUE) {
                val accentTint = ColorStateList.valueOf(accentColor)
                if (view.backgroundTintList?.defaultColor == primaryColor) {
                    view.backgroundTintList = accentTint
                }
                if (view.strokeColor?.defaultColor == primaryColor) {
                    view.strokeColor = accentTint
                }
                if (view.iconTint?.defaultColor == primaryColor) {
                    view.iconTint = accentTint
                }
                if (view.textColors.defaultColor == primaryColor) {
                    view.setTextColor(accentTint)
                }
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyAccentToButtons(view.getChildAt(index))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        updateLocale()
        updateTheme()
        hideStatusBar()
        super.onCreate(savedInstanceState)
        setEdgeToEdgeOrImmersive()
        maybeSetScreenOn()
        maybeShowWhenLocked()
        setLightNavigationBarAuto()
        setLightStatusBarAuto(surfaceColor())
        if (VersionUtils.hasQ()) {
            window.decorView.isForceDarkAllowed = false
        }
    }

    private fun updateTheme() {
        setTheme(getThemeResValue())
        if (PreferenceUtil.materialYou) {
            setDefaultNightMode(getNightMode())
        }

        if (PreferenceUtil.isCustomFont) {
            setTheme(R.style.FontThemeOverlay)
        }
    }

    private fun updateLocale() {
        val localeCode = PreferenceUtil.languageCode
        if (PreferenceUtil.isLocaleAutoStorageEnabled) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeCode))
            PreferenceUtil.isLocaleAutoStorageEnabled = true
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideStatusBar()
            handler.removeCallbacks(this)
            handler.postDelayed(this, 300)
        } else {
            handler.removeCallbacks(this)
        }
    }

    override fun run() {
        setImmersiveFullscreen()
    }

    override fun onStop() {
        handler.removeCallbacks(this)
        super.onStop()
    }

    public override fun onDestroy() {
        accentLayoutListener?.let { listener ->
            accentTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
        }
        accentLayoutListener = null
        accentTreeObserver = null
        super.onDestroy()
        exitFullscreen()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            handler.removeCallbacks(this)
            handler.postDelayed(this, 500)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase)
        installSplitCompat()
    }
}
