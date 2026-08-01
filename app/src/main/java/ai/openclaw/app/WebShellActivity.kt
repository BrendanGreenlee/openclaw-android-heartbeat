package ai.openclaw.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * v1 Web shell: a full-screen WebView pointed at the OpenClaw Gateway web chat.
 *
 * The official app's native UI (MainActivity) and WebSocket node connection
 * infrastructure (NodeRuntime, NodeForegroundService, ...) remain intact in
 * this fork — v2 will reuse them for the heartbeat watchdog.
 */
class WebShellActivity : AppCompatActivity() {

  companion object {
    /** Same Gateway the node WebSocket connects to; the web chat lives at its root. */
    const val DEFAULT_GATEWAY_URL = "https://tech.greenlee.website/"

    private const val EXTRA_GATEWAY_URL = "ai.openclaw.app.extra.GATEWAY_URL"

    /** Launch the web shell, optionally overriding the default gateway URL. */
    fun start(context: Context, gatewayUrl: String = DEFAULT_GATEWAY_URL) {
      context.startActivity(
        Intent(context, WebShellActivity::class.java)
          .putExtra(EXTRA_GATEWAY_URL, gatewayUrl),
      )
    }
  }

  private var webView: WebView? = null
  private var filePathCallback: ValueCallback<Array<Uri>>? = null

  private val fileChooserLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val results =
        if (result.resultCode == RESULT_OK && result.data != null) {
          val clipData = result.data!!.clipData
          if (clipData != null) {
            Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
          } else {
            result.data!!.data?.let { arrayOf(it) }
          }
        } else {
          null
        }
      filePathCallback?.onReceiveValue(results)
      filePathCallback = null
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Full screen, edge-to-edge, no browser chrome.
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).apply {
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      hide(WindowInsetsCompat.Type.systemBars())
    }

    val gatewayUrl = intent?.getStringExtra(EXTRA_GATEWAY_URL) ?: DEFAULT_GATEWAY_URL

    webView =
      WebView(this).apply {
        configureWebView(this, gatewayUrl)
      }
    setContentView(webView)

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          if (webView?.canGoBack() == true) {
            webView?.goBack()
          } else {
            finish()
          }
        }
      },
    )
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun configureWebView(view: WebView, gatewayUrl: String) {
    view.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      databaseEnabled = true
      setSupportMultipleWindows(false)
      mediaPlaybackRequiresUserGesture = false
      // Proper mobile viewport: let the page's viewport meta drive layout.
      useWideViewPort = true
      loadWithOverviewMode = true
      builtInZoomControls = false
      displayZoomControls = false
      cacheMode = WebSettings.LOAD_DEFAULT
    }

    // Persistent session: cookies + DOM storage survive app restarts.
    CookieManager.getInstance().apply {
      setAcceptCookie(true)
      setAcceptThirdPartyCookies(view, true)
    }

    view.webViewClient =
      object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
          view: WebView?,
          request: WebResourceRequest?,
        ): Boolean {
          val url = request?.url ?: return false
          val gatewayHost = Uri.parse(DEFAULT_GATEWAY_URL).host
          // Keep the gateway chat (and its assets) inside the WebView; open
          // external links in the system browser.
          if (url.host == gatewayHost) return false
          return runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, url))
            true
          }.getOrDefault(false)
        }
      }

    view.webChromeClient =
      object : WebChromeClient() {
        override fun onShowFileChooser(
          webView: WebView?,
          filePathCallback: ValueCallback<Array<Uri>>?,
          fileChooserParams: FileChooserParams?,
        ): Boolean {
          this@WebShellActivity.filePathCallback?.onReceiveValue(null)
          this@WebShellActivity.filePathCallback = filePathCallback
          val intent =
            fileChooserParams?.createIntent()
              ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                  addCategory(Intent.CATEGORY_OPENABLE)
                  type = "*/*"
                }
          return try {
            fileChooserLauncher.launch(intent)
            true
          } catch (e: ActivityNotFoundException) {
            this@WebShellActivity.filePathCallback = null
            false
          }
        }
      }

    view.setDownloadListener(
      DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
        runCatching {
          val request =
            DownloadManager.Request(Uri.parse(url))
              .setMimeType(mimetype)
              .addRequestHeader("User-Agent", userAgent)
              .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
              )
              .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                URLUtil.guessFileName(url, contentDisposition, mimetype),
              )
          (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
          Toast.makeText(this@WebShellActivity, "Download started", Toast.LENGTH_SHORT).show()
        }.onFailure {
          Toast.makeText(this@WebShellActivity, "Download failed", Toast.LENGTH_SHORT).show()
        }
      },
    )

    view.loadUrl(gatewayUrl)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    webView?.saveState(outState)
    super.onSaveInstanceState(outState)
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    webView?.restoreState(savedInstanceState)
  }

  override fun onPause() {
    webView?.onPause()
    super.onPause()
  }

  override fun onResume() {
    super.onResume()
    webView?.onResume()
  }

  override fun onDestroy() {
    webView?.destroy()
    super.onDestroy()
  }
}
