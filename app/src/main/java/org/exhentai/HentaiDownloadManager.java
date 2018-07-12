package org.exhentai;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static android.content.Context.NOTIFICATION_SERVICE;

public class HentaiDownloadManager {
    private final ArrayBlockingQueue<String> downloadQueue = new ArrayBlockingQueue<String>(1000);
    private final Pattern galleryRegex = Pattern.compile("https://exhentai\\.org/g/\\w+/\\w+");
    private final Pattern galleryPageRegex = Pattern.compile("https://exhentai\\.org/s/\\w+/\\w+");
    private final String baseDirectory;
    private final String userAgent = "Mozilla/5.0 (Linux; Android 8.0.0; MI 6 Build/OPR1.170623.027; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/62.0.3202.84 Mobile Safari/537.36";

    private final Activity activity;
    private Connection connection;
    private final OkHttpClient client;
    private final NotificationManager notificationManager;
    private final Notification.Builder builder;
    private final Thread downloadThread;
    private boolean threadRunning = true;

    HentaiDownloadManager(Activity act, String dir, HashMap<String, String> cookie) {
        activity = act;
        baseDirectory = dir;
        connection = Jsoup.connect("http://example.com").cookies(cookie).userAgent(userAgent);
        client = new OkHttpClient.Builder().build();
        notificationManager = (NotificationManager) MainActivity.globalActivity.getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(new NotificationChannel("hentai", "exhentai", NotificationManager.IMPORTANCE_DEFAULT));
            builder = new Notification.Builder(MainActivity.globalActivity, "hentai");
        } else {
            builder = new Notification.Builder(MainActivity.globalActivity);
        }
        builder.setTicker("开始下载")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setWhen(SystemClock.currentThreadTimeMillis())
                .setDefaults(Notification.DEFAULT_LIGHTS)//消息提示模式
                .setProgress(100, 0, false);

        downloadThread = new DownloadThread();
        downloadThread.start();
    }

    void add(String url) {
        if (offer(url))
            Toast.makeText(MainActivity.globalActivity, "添加成功", Toast.LENGTH_LONG).show();
        else
            Toast.makeText(MainActivity.globalActivity, "添加失败", Toast.LENGTH_LONG).show();
    }

    private boolean offer(String url) {
        if (url == null || downloadQueue.contains(url)) return false;
        if (galleryRegex.matcher(url).find() || galleryPageRegex.matcher(url).find())
            return downloadQueue.offer(url);
        else return false;
    }

    private class DownloadThread extends Thread {
        @Override
        public void run() {
            try {
                while (threadRunning) {
                    String url = downloadQueue.take();
                    if (url == null || url.equals("")) {
                        continue;
                    }
                    if (galleryRegex.matcher(url).find()) {
                        while (true) {
                            try {
                                Document document = connection.url(url).get();
                                url = document.selectFirst(".gdtl a").attr("href");
                                break;
                            } catch (Exception ignored) {
                            }
                        }
                    }

                    Matcher matcher = Pattern.compile("/(\\d+)-\\d+").matcher(url);
                    final String galleryId;
                    if (matcher.find()) {
                        galleryId = matcher.group(1);
                    } else {
                        continue;
                    }

                    File folder = new File(baseDirectory + "/" + galleryId);
                    if (!folder.exists()) {
                        folder.mkdir();
                    }

                    builder.setContentTitle(galleryId + " 下载中").setContentText("获取中...")
                            .setProgress(100, 0, false);
                    notificationManager.notify(Integer.parseInt(galleryId), builder.build());
                    Log.i("DownloadThread", "Download Gallery" + galleryId);

                    String curPageUrl = url;
                    String pageId = null, finalPageId = null;
                    while (threadRunning) {
                        try {
                            Matcher m = Pattern.compile("/\\d+-(\\d+)").matcher(curPageUrl);
                            if (m.find()) {
                                pageId = m.group(1);
                            } else {
                                throw new IllegalArgumentException("网页地址错误");
                            }
                            Document document = connection.url(curPageUrl).get();
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
                            File image = new File(baseDirectory + "/" + galleryId + "/" + pageId + ".jpg");
                            if (!image.exists()) {
                                String imgSrc = img.attr("src");
                                Request request = new Request.Builder().url(imgSrc).build();
                                Response response = client.newCall(request).execute();
                                if (!response.isSuccessful())
                                    throw new IOException("Unexpected code " + response);
                                byte[] buffer = response.body().bytes();
                                FileOutputStream output = new FileOutputStream(baseDirectory + "/" + galleryId + "/" + pageId + ".jpg");
                                output.write(buffer);
                                output.close();
                                response.close();
                                Log.i("DownloadThread", "download succedd: " + pageId);
                            } else {
                                Log.i("DownloadThread", "skipped exist file: " + pageId);
                            }

                            //判断是否最后一页
                            if (finalPageId != null && Integer.parseInt(pageId) == Integer.parseInt(finalPageId)) {
                                Log.i("DownloadThread", "最后一页");
                                notificationManager.cancel(Integer.parseInt(galleryId));
                                break;
                            }

                            if (finalPageId != null) {
                                try {
                                    builder.setContentTitle(galleryId + " 下载中").setContentText(pageId + " / " + finalPageId)
                                            .setProgress(Integer.parseInt(finalPageId), Integer.parseInt(pageId), false);
                                    notificationManager.notify(Integer.parseInt(galleryId), builder.build());
                                } catch (NumberFormatException e) {
                                    e.printStackTrace();
                                }
                            }

                            //下一页
                            Log.i("DownloadThread", "下一页：" + curPageUrl);
                            curPageUrl = next.attr("href");
                            Thread.sleep(500);
                        } catch (NullPointerException | IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    void reset() {
        notificationManager.cancelAll();
        threadRunning = false;
        downloadQueue.offer("");
    }
}
