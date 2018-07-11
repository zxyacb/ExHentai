package com.yasha.exhentai;

import android.app.Activity;
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
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HentaiDownloadManager {
    private final ArrayBlockingQueue<String> downloadQueue = new ArrayBlockingQueue<String>(1000);

    private final String baseDirectory;
    private final String userAgent = "Mozilla/5.0 (Linux; Android 8.0.0; MI 6 Build/OPR1.170623.027; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/62.0.3202.84 Mobile Safari/537.36";

    private Connection connection;
    private final OkHttpClient client;
    private final Thread downloadThread;
    private boolean threadRunning = true;

    HentaiDownloadManager(String dir, HashMap<String, String> cookie) {
        baseDirectory = dir;
        connection = Jsoup.connect("http://example.com").cookies(cookie).userAgent(userAgent);
        client = new OkHttpClient.Builder().build();
        downloadThread = new DownloadThread();
        downloadThread.start();
    }

    boolean add(String url) {
        return downloadQueue.offer(url);
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

                            String imgSrc = img.attr("src");
                            Request request = new Request.Builder().url(imgSrc).build();
                            File image = new File(baseDirectory + "/" + galleryId + "/" + pageId + ".jpg");
                            if (!image.exists()) {
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
                                final Activity activity = MainActivity.Companion.getGlobalActivity();
                                activity.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(activity, "下载完成", Toast.LENGTH_LONG);
                                    }
                                });
                                break;
                            }

                            //前往下一页
                            curPageUrl = next.attr("href");
                            Log.i("DownloadThread", "下一页：" + curPageUrl);
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
        threadRunning = false;
        downloadQueue.offer("");
    }
}
