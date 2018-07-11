package com.yasha.exhentai

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity;
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.Toast

import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import okhttp3.OkHttpClient
import java.io.FileOutputStream
import java.net.URL
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import kotlin.collections.HashSet

class MainActivity : AppCompatActivity() {
    var cookieManager = CookieManager.getInstance()
    lateinit var myClipboard: ClipboardManager
    val loginUrl = "https://forums.e-hentai.org/index.php?act=Login&CODE=00";
    val homepageUrl = "https://exhentai.org"
    val fullColorUrl = "https://exhentai.org/?f_doujinshi=1&f_manga=1&f_artistcg=1&f_gamecg=1&f_western=0&f_non-h=0&f_imageset=1&f_cosplay=1&f_asianporn=1&f_misc=0&f_search=full+color&f_apply=Apply+Filter"

    val galleryRegex = Regex("https://exhentai\\.org/g/\\w+/\\w+")
    val galleryPageRegex = Regex("https://exhentai\\.org/s/\\w+/\\w+")

    var exhentaiCookie = "ipb_member_id=1601063;ipb_pass_hash=9f4567fb2741f37900a0054d4706a7d2;yay=louder;igneous=ace6704ed;s=7f5a98a89;sk=6a67o8lsurapoheqnzvqwo5g29xu"

    lateinit var downloadManager: HentaiDownloadManager

    companion object {
        var globalActivity: Activity? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        globalActivity = this
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        myClipboard = this.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        var initUrl: String
        if (checkCookie()) {
            initUrl = homepageUrl
        } else {
            initUrl = loginUrl
            Toast.makeText(this, "未设置Cookie，需要登录", Toast.LENGTH_LONG)
        }

        val cookies = HashMap<String, String>()
        for (str in exhentaiCookie.split(";")) {
            val s = str.split("=")
            cookies[s[0]] = s[1]
        }
        downloadManager = HentaiDownloadManager(applicationContext.getExternalFilesDir("").toString(), cookies)

        webv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress == 100) {
                    progressBar.visibility = View.GONE//加载完网页进度条消失
                } else {
                    progressBar.visibility = View.VISIBLE//开始加载网页时显示进度条
                    progressBar.progress = newProgress//设置进度值
                }
            }
        }

        webv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i("webv", "finished")
                super.onPageFinished(view, url)
            }
        }
        val settings = webv.settings
        settings.javaScriptEnabled = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        Log.i("UA", settings.userAgentString)

        initUrl = fullColorUrl
        webv.loadUrl(initUrl)

        fab.setOnClickListener { view ->
            //                        Snackbar.make(view, "cookie", Snackbar.LENGTH_LONG)
            //                    .setAction("Action", null).show()
            Log.i("dir", applicationContext.getExternalFilesDir("").toString())
            val url = webv.url
            if (url.contains(galleryRegex)) {
                //取得图集第一页的地址
                webv.evaluateJavascript("(function() { return document.getElementsByClassName('gdtl')[0].firstChild.href; })();") { str ->
                    var page = str?.trim('"')
                    downloadManager.add(page)
                    Log.i("page1", page + "")

                }
            } else if (url.contains(galleryPageRegex)) {
                Log.i("page", url)
                downloadManager.add(url)
            } else {
                Toast.makeText(this, "未能识别地址：地址不是一个图集或图集的一页", Toast.LENGTH_SHORT)
            }
        }
    }

    // 处理返回键
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webv.canGoBack())
                webv.goBack()
            else
                moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun checkCookie(): Boolean {
        if (cookieManager.getCookie("e-hentai.org") != null) {
            val hh= cookieManager.getCookie("exhentai.org")
            return true
            val ehentai = "ipb_pass_hash=9f4567fb2741f37900a0054d4706a7d2;ipb_member_id=1601063;ipb_session_id=cdc64a9c2c84b121fe8f203f7fe790e4"
            ehentai.split(';').forEach { str -> cookieManager.setCookie("e-hentai.org", str) }
            if (cookieManager.getCookie("exhentai.org") == null) {
                val time = Date().time / 1000
                val timeStr = time.toString() + "-" + (time + 3 * 60).toString()
                val exhentai = "ipb_member_id=1601063; ipb_pass_hash=9f4567fb2741f37900a0054d4706a7d2; yay=louder; igneous=ace6704ed; s=7f5a98a89; sk=6a67o8lsurapoheqnzvqwo5g29xu; " + timeStr
                exhentai.split(';').forEach { str -> cookieManager.setCookie("exhentai.org", str) }
            }
        }
        return false
    }

    fun onRefreshClick(menuItem: MenuItem) {
        webv.reload()
    }

    fun onFavClick(menuItem: MenuItem) {
        webv.loadUrl(fullColorUrl)
    }

    fun onLoadClipboardClick(menuItem: MenuItem) {
        val clip = myClipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val str = clip.getItemAt(0).text.toString()
            Log.i("clip", str + "")
            if (str != null && str.contains("http")) {
                webv.loadUrl(str)
            }
        }
    }

    fun onResetClick(menuItem: MenuItem) {
        downloadManager.reset()
        val cookies = HashMap<String, String>()
        for (str in exhentaiCookie.split(";")) {
            val s = str.split("=")
            cookies[s[0]] = s[1]
        }
        downloadManager = HentaiDownloadManager(applicationContext.getExternalFilesDir("").toString(), cookies)    }
}
