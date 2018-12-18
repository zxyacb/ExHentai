package org.exhentai;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;

public class HentaiDownloadService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        Log.i("Service", "onBind");
        return null;
    }

    @Override
    public void onCreate() {
        Log.i("Service", "onCreate");
        super.onCreate();

        baseDirectory = this.getApplicationContext().getExternalFilesDir("").toString();
        HashMap cookies = new HashMap<String, String>();
        for (String str : MainActivity.exhentaiCookies.split(";")) {
            String[] s = str.split("=");
            cookies.put(s[0], s[1]);
        }
        connection = Jsoup.connect("http://example.com").cookies(cookies).userAgent(userAgent).timeout(5000);
        client = new OkHttpClient.Builder().writeTimeout(15, TimeUnit.SECONDS).build();
        notificationManager = (NotificationManager) this.getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(new NotificationChannel("exhentai", "exhentai", NotificationManager.IMPORTANCE_DEFAULT));
            builder = new Notification.Builder(MainActivity.globalActivity, "exhentai");
        } else {
            builder = new Notification.Builder(MainActivity.globalActivity);
        }
        builder.setTicker("开始下载")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setDefaults(Notification.DEFAULT_LIGHTS)//消息提示模式
                .setProgress(100, 0, false);

        sqlite = new SQLStorage();
        sqlite.checkDb();

        for (String url : sqlite.queryUnfinishedGalleries()) {
            downloadQueue.offer(url);
        }

        downloadThread = new DownloadThread();
        downloadThread.start();
    }

    @Override
    public void onDestroy() {
        Log.i("Service", "onDestroy");
        //save not complete galleries
        sqlite.close();
        if (notificationManager != null) {
            notificationManager.cancelAll();
        }
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String url = intent.getStringExtra("url");
        addToQueue(url);

        return super.onStartCommand(intent, flags, startId);
    }

    private final ArrayBlockingQueue<String> downloadQueue = new ArrayBlockingQueue<>(1000);
    private final Pattern galleryRegex = Pattern.compile("https://exhentai\\.org/g/(\\w+)/\\w+");
    private final Pattern galleryPageRegex = Pattern.compile("https://exhentai\\.org/s/\\w+/(\\d+)-(\\d+)");
    private final String userAgent = "Mozilla/5.0 (Linux; Android 8.0.0; MI 6 Build/OPR1.170623.027; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/62.0.3202.84 Mobile Safari/537.36";

    private String baseDirectory;
    private Connection connection;
    private OkHttpClient client;
    private NotificationManager notificationManager;
    private Notification.Builder builder;
    private SQLStorage sqlite;
    private Thread downloadThread;
    private boolean threadRunning = true;
    private boolean skip = false;
    private String curPageUrl;

    private void addToQueue(@Nullable String url) {
        if (url == null) return;
        String gallery = getGalleryId(url);
        if (gallery == null) {
            Toast.makeText(getApplicationContext(), "不是有效的图集地址", Toast.LENGTH_SHORT).show();
        } else if (sqlite.insert(gallery, url)) {
            downloadQueue.offer(url);
            Toast.makeText(getApplicationContext(), "添加成功", Toast.LENGTH_SHORT).show();
        } else Toast.makeText(getApplicationContext(), "图集已存在", Toast.LENGTH_SHORT).show();
    }

    private String getGalleryId(@Nullable String url) {
        if (url == null) return null;
        Matcher m1 = galleryRegex.matcher(url);
        Matcher m2 = galleryPageRegex.matcher(url);
        if (m1.find()) {
            return m1.group(1);
        } else if (m2.find()) {
            return m2.group(1);
        } else return null;
    }

    private class DownloadThread extends Thread {
        @Override
        public void run() {
            try {
                while (threadRunning) {
                    curPageUrl = downloadQueue.take();
                    if (curPageUrl.equals("")) {
                        continue;
                    }
                    String title = "";
                    if (galleryRegex.matcher(curPageUrl).find()) {
                        while (true) {
                            try {
                                Document document = connection.url(curPageUrl).get();
                                title = document.title();
                                curPageUrl = document.selectFirst(".gdtl a").attr("href");
                                break;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    skip = false;
                    Matcher matcher = galleryPageRegex.matcher(curPageUrl);
                    final String galleryId;
                    if (matcher.find()) {
                        galleryId = matcher.group(1);
                    } else {
                        continue;
                    }

                    File folder = new File(baseDirectory + "/" + galleryId);
                    if (!folder.exists()) folder.mkdir();

                    notificationManager.cancelAll();
                    builder.setContentTitle(title).setContentText("获取中...")
                            .setProgress(100, 0, false);
                    notificationManager.notify(Integer.parseInt(galleryId), builder.build());
                    Log.i("DownloadThread", "Download Gallery" + galleryId);

                    String pageId = null, finalPageId = null;
                    while (threadRunning) {
                        if (skip) {
                            notificationManager.cancel(Integer.parseInt(galleryId));
                            break;
                        }
                        try {
                            Matcher m = Pattern.compile("/\\d+-(\\d+)").matcher(curPageUrl);
                            if (m.find()) {
                                pageId = m.group(1);
                            } else {
                                throw new IllegalArgumentException("网页地址错误");
                            }
                            Document document = connection.url(curPageUrl).get();
                            if (title.equals("")) title = document.title();
                            Element img = document.selectFirst("#img");
                            Element next = document.selectFirst("#next");

                            //get null element, try to reload page
                            if (img == null || next == null) {
                                Log.i("DownloadThread", "wrong document");
                                Thread.sleep(500);
                                continue;
                            }
                            if (finalPageId == null) {
                                Element last = next.nextElementSibling();
                                String lastPageUrl = last.attr("href");
                                Matcher m1 = Pattern.compile("/\\d+-(\\d+)").matcher(lastPageUrl);
                                if (m1.find()) {
                                    finalPageId = m1.group(1);
                                }
                            }

                            String imgSrc = img.attr("src");
                            if(imgSrc.equals("https://exhentai.org/img/509.gif")){
                                Log.i("DownloadThread", "Bandwidth Exceeded");
                                Thread.sleep(30000);
                                continue;
                            }
                            if (!downloadImage(galleryId, pageId, imgSrc)) {
                                Log.i("DownloadThread", "download error: " + pageId + " " + imgSrc);
                                String onerror = img.attr("onerror");
                                if (onerror != null && !onerror.equals("")) {
                                    Matcher em = Pattern.compile("nl\\('(\\d+-\\d+)'\\)").matcher(onerror);
                                    if (em.find()) {
                                        String nl = em.group(1);
                                        curPageUrl += "?nl=" + nl;
                                    }
                                } else {
                                    curPageUrl = curPageUrl.replaceFirst("\\?nl=\\d+-\\d+", "");
                                }
                                continue;
                            }

                            //判断是否最后一页
                            if (finalPageId != null && Integer.parseInt(pageId) >= Integer.parseInt(finalPageId)) {
                                Log.i("DownloadThread", "最后一页");
                                curPageUrl = null;
                                builder.setContentTitle(title).setContentText(pageId + " / " + finalPageId + "   " + galleryId + " 已完成，队列剩余" + downloadQueue.size())
                                        .setProgress(Integer.parseInt(pageId), Integer.parseInt(finalPageId), false);
                                notificationManager.notify(Integer.parseInt(galleryId), builder.build());
                                sqlite.updateProgress(galleryId, finalPageId, true);
                                break;
                            }

                            if (finalPageId != null) {
                                try {
                                    builder.setContentTitle(title).setContentText(pageId + " / " + finalPageId + "   " + galleryId + " 队列剩余" + downloadQueue.size())
                                            .setProgress(Integer.parseInt(finalPageId), Integer.parseInt(pageId), false);
                                    notificationManager.notify(Integer.parseInt(galleryId), builder.build());
                                } catch (NumberFormatException ignored) {
                                }
                            }

                            //下一页
                            curPageUrl = next.attr("href");
                            sqlite.updateUrl(getGalleryId(curPageUrl), curPageUrl);
                            Log.i("DownloadThread", "下一页：" + curPageUrl);
                            Thread.sleep(500);
                        } catch (NullPointerException | IOException ignored) {
                        }
                    }
                }
                notificationManager.cancelAll();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean downloadImage(String galleryId, String pageId, String imgSrc) {
        File image = new File(baseDirectory + "/" + galleryId + "/" + pageId + ".jpg");

        Log.i("DownloadThread", "download start: " + pageId);
        for (int i = 0; i < 2; i++) {
            Request request = new Request.Builder().url(imgSrc).build();
            try (Response response = client.newCall(request).execute();
                 BufferedSink sink = Okio.buffer(Okio.sink(image));) {
                if (!response.isSuccessful())
                    throw new IOException("Unexpected code " + response);
                sink.writeAll(response.body().source());
                Log.i("DownloadThread", "download succeed: " + pageId);
                return true;
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    /**
     * 将下载历史写入sqlite
     */
    public class SQLStorage {
        private final String dbName = "hentai";

        private SQLiteDatabase db;

        protected SQLStorage() {
            db = SQLiteDatabase.openOrCreateDatabase(String.format("%s/.%s.db", baseDirectory, dbName), null);
        }

        protected List<String> queryUnfinishedGalleries() {
            Cursor cursor = db.rawQuery("select url from history where finished=0", null);
            List<String> list = new ArrayList<>();
            while (cursor.moveToNext()) {
                list.add(cursor.getString(0));
            }
            return list;
        }

        @SuppressLint("DefaultLocale")
        protected boolean insert(String gallery, String url) {
            try {
                db.execSQL(String.format("insert into history values(%s,0,'%s',0)", gallery, url));
                return true;
            } catch (SQLException ignored) {
                return false;
            }
        }

        @SuppressLint("DefaultLocale")
        protected void updateProgress(@Nullable String gallery, @Nullable String pages, boolean finished) {
            if (gallery == null || pages == null) return;
            db.execSQL(String.format("update history set pages=%s,finished='%s' where gallery=%s", pages, finished, gallery));
        }

        @SuppressLint("DefaultLocale")
        protected void updateUrl(@Nullable String gallery, @Nullable String url) {
            if (gallery == null || url == null) return;
            db.execSQL(String.format("update history set url='%s' where gallery=%s", url, gallery));
        }

        /**
         * check datebase and table(init if not exist)
         */
        protected void checkDb() {
            Log.i("Service", "checkDB");
            if (db.isOpen()) {
                try {
                    db.execSQL("select * from history limit 1");
                } catch (SQLException e) {
                    db.execSQL("create table if not exists history (gallery INTEGER PRIMARY KEY, pages INTEGER, url VARCHAR(128), finished BOOLEAN)");
                }
            }
        }

        protected void close() {
            db.close();
        }
    }
}
