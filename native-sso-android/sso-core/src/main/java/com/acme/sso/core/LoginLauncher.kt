package com.acme.sso.core

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens the eSignet login UI. A Custom Tab (the system browser) is used whenever one is available,
 * as RFC 8252 recommends; bare emulator images without a browser fall back to [WebLoginActivity].
 * Either way the authorization response comes back to the app through its redirect URI.
 */
object LoginLauncher {
    fun open(context: Context, authorizeUrl: String, redirectUri: String) {
        val uri = Uri.parse(authorizeUrl)
        if (CustomTabsClient.getPackageName(context, null) != null) {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
        } else {
            context.startActivity(Intent(context, WebLoginActivity::class.java)
                .putExtra(WebLoginActivity.EXTRA_URL, authorizeUrl)
                .putExtra(WebLoginActivity.EXTRA_REDIRECT, redirectUri))
        }
    }
}

/** WebView fallback that hands the redirect back to the app as a VIEW intent, like a browser would. */
class WebLoginActivity : Activity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val redirect = intent.getStringExtra(EXTRA_REDIRECT).orEmpty()
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (redirect.isNotEmpty() && url.startsWith(redirect)) {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url).setPackage(packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                    return true
                }
                return false
            }
        }
        setContentView(web)
        web.loadUrl(intent.getStringExtra(EXTRA_URL).orEmpty())
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_REDIRECT = "redirect"
    }
}
