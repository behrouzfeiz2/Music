package code.name.monkey.retromusic.fragments.online

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import code.name.monkey.retromusic.R

class OnlineFragment : Fragment(R.layout.fragment_online) {

    private var onlineWebView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val webView = view.findViewById<WebView>(R.id.onlineWebView)
        onlineWebView = webView

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://musify.club/en")
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    override fun onPause() {
        onlineWebView?.evaluateJavascript(
            "document.querySelectorAll('audio,video').forEach(function(media){media.pause();});",
            null
        )
        onlineWebView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        onlineWebView?.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        onlineWebView?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        onlineWebView?.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = null
            destroy()
        }
        onlineWebView = null
        super.onDestroyView()
    }
}
