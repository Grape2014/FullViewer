package com.grape.fullviewer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * URL入力 → 全画面WebViewで表示するシンプルなビューア。
 *
 * - 起動時:                       URL入力画面のみ
 * - URLを開いた後:               入力画面を隠してWebViewを全画面（Immersive Sticky）で表示
 * - 戻るキー:                     ページ履歴があれば戻る、なければ入力画面へ
 */
class MainActivity : Activity() {

    private lateinit var inputScreen: View
    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var openButton: Button
    private lateinit var errorText: TextView

    /** 現在WebViewを表示中かどうか（フルスクリーン状態の切替判定に使用） */
    private var viewing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputScreen = findViewById(R.id.inputScreen)
        webView = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        openButton = findViewById(R.id.openButton)
        errorText = findViewById(R.id.errorText)

        setupWebView()
        restoreLastUrl()

        openButton.setOnClickListener { openFromInput() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openFromInput()
                true
            } else {
                false
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
        }

        // 全てのナビゲーションをアプリ内WebViewで処理する
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val scheme = request.url.scheme
                return when (scheme) {
                    "http", "https" -> false // アプリ内で開く
                    else -> {
                        // tel: / mailto: などは外部アプリへ任せる
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        }
                        true
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                // 手動入力時のキャッシュ無効化を解除し、以降の遷移では通常のキャッシュを使う
                view.settings.cacheMode = WebSettings.LOAD_DEFAULT
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                // メインフレームの読み込み失敗のみ通知
                if (request.isForMainFrame) {
                    val desc = error.description?.toString().orEmpty()
                    errorText.text = if (desc.isBlank()) {
                        getString(R.string.load_error)
                    } else {
                        getString(R.string.load_error) + "\n" + desc
                    }
                    errorText.visibility = View.VISIBLE
                }
            }
        }

        // ノッチ（ディスプレイカットアウト）領域にも描画する（API 28+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /** 入力欄の文字列を整形して全画面表示へ切り替える */
    private fun openFromInput() {
        val raw = urlInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            urlInput.error = getString(R.string.url_empty)
            return
        }
        val url = normalizeUrl(raw)
        saveLastUrl(url)
        loadUrl(url)
    }

    /** 最後に入力したURLを復元する */
    private fun restoreLastUrl() {
        val lastUrl = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_LAST_URL, null)
        if (!lastUrl.isNullOrBlank()) {
            urlInput.setText(lastUrl)
            urlInput.setSelection(lastUrl.length)
        }
    }

    private fun saveLastUrl(url: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_URL, url)
            .apply()
    }

    private fun normalizeUrl(raw: String): String =
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            "https://$raw"
        }

    private fun loadUrl(url: String) {
        errorText.visibility = View.GONE
        inputScreen.visibility = View.GONE
        webView.visibility = View.VISIBLE
        // キャッシュが原因で最初のページが古い内容になるのを防ぐ
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.clearCache(true)
        webView.loadUrl(url)
        viewing = true
        enterImmersive()
    }

    /** 入力画面へ戻る */
    private fun showInputScreen() {
        viewing = false
        webView.visibility = View.GONE
        inputScreen.visibility = View.VISIBLE
        exitImmersive()
        urlInput.requestFocus()
    }

    /* ---------- フルスクリーン制御 ---------- */

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun exitImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 他アプリやホームからの復帰時にフルスクリーンを再適用
        if (hasFocus && viewing) {
            enterImmersive()
        }
    }

    /* ---------- 戻るキー ---------- */

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (viewing) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                showInputScreen()
            }
        } else {
            super.onBackPressed()
        }
    }

    /* ---------- WebViewのライフサイクル ---------- */

    override fun onResume() {
        webView.onResume()
        super.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "fullviewer"
        private const val KEY_LAST_URL = "last_url"
    }
}
