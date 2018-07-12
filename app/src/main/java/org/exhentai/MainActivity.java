package org.exhentai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.*;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.*;
import java.util.regex.Pattern;


public class MainActivity extends AppCompatActivity {
    private final CookieManager cookieManager = CookieManager.getInstance();
    private ClipboardManager myClipboard;
    private final String loginUrl = "https://forums.e-hentai.org/index.php?act=Login&CODE=00";
    private final String homepageUrl = "https://exhentai.org";
    private final String fullColorUrl = "https://exhentai.org/?f_doujinshi=1&f_manga=1&f_artistcg=1&f_gamecg=1&f_western=0&f_non-h=0&f_imageset=1&f_cosplay=1&f_asianporn=1&f_misc=0&f_search=full+color&f_apply=Apply+Filter";

    private final Pattern galleryRegex = Pattern.compile("https://exhentai\\.org/g/\\w+/\\w+");
    private final Pattern galleryPageRegex = Pattern.compile("https://exhentai\\.org/s/\\w+/\\w+");

    private final String exhentaiCookie = "ipb_member_id=1601063;ipb_pass_hash=9f4567fb2741f37900a0054d4706a7d2;yay=louder;igneous=ace6704ed;s=7f5a98a89;sk=6a67o8lsurapoheqnzvqwo5g29xu";

    private HentaiDownloadManager downloadManager;
    private WebView webv;

    public static MainActivity globalActivity;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            // Get link-URL.
            String url = (String) msg.getData().get("url");
            downloadManager.add(url);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        globalActivity = this;
        setContentView(R.layout.activity_main);
        myClipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        webv = findViewById(R.id.webv);

        String initUrl;
        if (checkCookie()) {
            initUrl = homepageUrl;
        } else {
            initUrl = loginUrl;
            Toast.makeText(this, "未设置Cookie，需要登录", Toast.LENGTH_LONG);
        }

        createDownloadManager();
        final ProgressBar progressBar = findViewById(R.id.progressBar);
        webv.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);//加载完网页进度条消失
                } else {
                    progressBar.setVisibility(View.VISIBLE);//开始加载网页时显示进度条
                    progressBar.setProgress(newProgress);//设置进度值
                }
            }
        });
        webv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                WebView.HitTestResult result = ((WebView) v).getHitTestResult();
                if (result == null) return false;
                int type = result.getType();
                switch (type) {
                    case WebView.HitTestResult.SRC_ANCHOR_TYPE:
                        downloadManager.add(result.getExtra());
                        break;
                    case WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE:
                        Message msg = mHandler.obtainMessage();
                        ((WebView) v).requestFocusNodeHref(msg);
                        break;
                }
                return false;
            }
        });
        webv.setWebViewClient(new WebViewClient());
        WebSettings settings = webv.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        initUrl = fullColorUrl;
        webv.loadUrl(initUrl);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadManager.add(webv.getUrl());
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webv.canGoBack())
                webv.goBack();
            else
                moveTaskToBack(false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    Boolean checkCookie() {
        if (cookieManager.getCookie("exhentai.org") != null) {
            String exhentai = cookieManager.getCookie("e-hentai.org");
            return true;
        } else {
            for (String cookie : exhentaiCookie.split(";")) {
                cookieManager.setCookie("exhentai.org", cookie);
            }
            return true;
        }
    }

    public void onRefreshClick(MenuItem menuItem) {
        webv.reload();
    }

    public void onFavClick(MenuItem menuItem) {
        webv.loadUrl(fullColorUrl);
    }

    public void onLoadClipboardClick(MenuItem menuItem) {
        ClipData clip = myClipboard.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence str = clip.getItemAt(0).getText();
            if (str == null) return;
            Log.i("clip", str.toString());
            if (str != null && str.toString().contains("http")) {
                webv.loadUrl(str.toString());
            }
        }
    }

    public void onResetClick(MenuItem menuItem) {
        downloadManager.reset();
        createDownloadManager();
    }

    void createDownloadManager() {
        HashMap cookies = new HashMap<String, String>();
        for (String str : exhentaiCookie.split(";")) {
            String[] s = str.split("=");
            cookies.put(s[0], s[1]);
        }
        downloadManager = new HentaiDownloadManager(this, getApplicationContext().getExternalFilesDir("").toString(), cookies);
    }

    public enum UrlType {
        OTHER,
        EXHENTAI_GALLERY,
        EXHENTAI_GALLERY_PAGE
    }

    UrlType getUrlType(String url) {
        if (galleryRegex.matcher(url).find())
            return UrlType.EXHENTAI_GALLERY;
        else if (galleryPageRegex.matcher(url).find())
            return UrlType.EXHENTAI_GALLERY_PAGE;
        else return UrlType.OTHER;
    }
}
