package com.rnfs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import android.os.AsyncTask;

import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.rnfs.resume.DownloadService;
import com.rnfs.resume.DownloadThreadInfo;
import com.rnfs.resume.ThreadDaoImpl;

public class Downloader extends AsyncTask<DownloadParams, long[], DownloadResult> {
  private static final String TAG = "Downloader";
  private DownloadParams mParam;
  private AtomicBoolean mAbort = new AtomicBoolean(false);
  DownloadResult res;
  private int mProgress = 0;
  private boolean isPause = false;

  public ThreadDaoImpl mThreadDao = null;

  public void initThreadDaoImpl(Context context)
  {
    this.mThreadDao = new ThreadDaoImpl(context);
  }
  protected DownloadResult doInBackground(DownloadParams... params) {
    mParam = params[0];
    res = new DownloadResult();
    new Thread(new Runnable() {
      public void run() {
        try {
          downloading(mParam, res);

        } catch (Exception ex) {
          res.exception = ex;
          mParam.onTaskCompleted.onTaskCompleted(res);
        }
      }
    }).start();

    return res;
  }
  private void downloading(DownloadParams param, DownloadResult res) throws Exception {

    URL url = param.src;
    HttpURLConnection connection = (HttpURLConnection)url.openConnection();
    connection.setRequestMethod("GET");
    int responeCode = connection.getResponseCode();
    long lengthOfFile = -1;
    if(HttpURLConnection.HTTP_OK == responeCode)
    {
      lengthOfFile = getContentLength(connection);
      if(lengthOfFile<0)
      {
        return;
      }
      File file = new File(param.dest.getPath());
      RandomAccessFile randomAccessFile = new RandomAccessFile(file,"rwd");
      randomAccessFile.setLength(lengthOfFile);
      res.bytesWritten = lengthOfFile;
      //读取数据库线程信息
      List<DownloadThreadInfo> threadInfos = mThreadDao.getThreads(param.src.getPath());
      DownloadThreadInfo threadInfo = null;
      if (threadInfos.size() == 0) {
        Log.i(TAG, "download: thread size 0");
        //初始化线程信息
        threadInfo = new DownloadThreadInfo(0, param.src.getPath(), 0, (int)lengthOfFile, 0);
      } else {
        Log.i(TAG, "download: thread size > 0");
        //单线程下载
        threadInfo = threadInfos.get(0);
      }

      int start = threadInfo.getBegin() + threadInfo.getProgress();

      if (param.onDownloadBegin != null) {
        Map<String, List<String>> headers = connection.getHeaderFields();

        Map<String, String> headersFlat = new HashMap<>();

        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
          String headerKey = entry.getKey();
          String valueKey = entry.getValue().get(0);

          if (headerKey != null && valueKey != null) {
            headersFlat.put(headerKey, valueKey);
          }
        }
        param.onDownloadBegin.onDownloadBegin(responeCode, start,lengthOfFile, headersFlat);
      }
      res.statusCode = responeCode;
      InputStream input = null;

      //查询数据库中是否存在记录
      if (!mThreadDao.isExists(threadInfo.getUrl(), threadInfo.getId())) {
        mThreadDao.insertThread(threadInfo);
      }

      try {
        //断点续传关键: 请求信息设置range字段
        connection =(HttpURLConnection)url.openConnection();
        connection.setRequestProperty("Range", "bytes=" + start + "-" + threadInfo.getEnd());
        //断点续传的关键:RandomAccessFile
        //rwd:read write delete
        //randomAccessFile = new RandomAccessFile(file, "rwd");
        randomAccessFile.seek(start);
        mProgress += threadInfo.getProgress();
        Log.i(TAG, "run: connection.getResponseCode: " + connection.getResponseCode());
        //开始下载
        //注意下载时候这里的responseCode是206
        if (HttpURLConnection.HTTP_PARTIAL == connection.getResponseCode()) {
          res.statusCode = connection.getResponseCode();
          //读取数据
          input = connection.getInputStream();
          byte[] buffer = new byte[1024];
          int len;

          double lastProgressValue = 0;
          long lastProgressEmitTimestamp = 0;

          while ((len = input.read(buffer)) != -1) {

            int total = mProgress + len;

            if (param.progressInterval > 0) {
              long timestamp = System.currentTimeMillis();
              if (timestamp - lastProgressEmitTimestamp > param.progressInterval) {
                lastProgressEmitTimestamp = timestamp;
                publishProgress(new long[]{lengthOfFile, total});
              }
            } else if (param.progressDivider <= 0) {
              publishProgress(new long[]{lengthOfFile, total});
            } else {
              double progress = Math.round(((double) total * 100) / lengthOfFile);
              if (progress % param.progressDivider == 0) {
                if ((progress != lastProgressValue) || (total == lengthOfFile)) {
                  Log.d("Downloader", "EMIT: " + String.valueOf(progress) + ", TOTAL:" + String.valueOf(total));
                  lastProgressValue = progress;
                  publishProgress(new long[]{lengthOfFile, total});
                }
              }
            }

            //写入文件
            randomAccessFile.write(buffer, 0, len);
            //把下载进度发送给Activity
            mProgress += len;
            //暂停状态，保存下载信息到数据库
            if (mAbort.get()) {
              mThreadDao.updateThread(threadInfo.getUrl(), threadInfo.getId(), mProgress);
              return;
            }
          }
          mThreadDao.updateThread(threadInfo.getUrl(), threadInfo.getId(), mProgress);
        } else {

        }

        param.onTaskCompleted.onTaskCompleted(res);
      } catch (MalformedURLException e) {
        e.printStackTrace();
      } catch (IOException e) {
        e.printStackTrace();
      } finally {
        try {
          if(input!=null)input.close();
          randomAccessFile.close();
          connection.disconnect();
        } catch (IOException e) {
          e.printStackTrace();
        }

      }
    }
  }
  private void download(DownloadParams param, DownloadResult res) throws Exception {
    InputStream input = null;
    OutputStream output = null;
    HttpURLConnection connection = null;

    try {
      connection = (HttpURLConnection)param.src.openConnection();

      ReadableMapKeySetIterator iterator = param.headers.keySetIterator();

      while (iterator.hasNextKey()) {
        String key = iterator.nextKey();
        String value = param.headers.getString(key);
        connection.setRequestProperty(key, value);
      }

      connection.setConnectTimeout(param.connectionTimeout);
      connection.setReadTimeout(param.readTimeout);
      connection.connect();

      int statusCode = connection.getResponseCode();
      long lengthOfFile = getContentLength(connection);

      boolean isRedirect = (
        statusCode != HttpURLConnection.HTTP_OK &&
        (
          statusCode == HttpURLConnection.HTTP_MOVED_PERM ||
          statusCode == HttpURLConnection.HTTP_MOVED_TEMP ||
          statusCode == 307 ||
          statusCode == 308
        )
      );

      if (isRedirect) {
        String redirectURL = connection.getHeaderField("Location");
        connection.disconnect();

        connection = (HttpURLConnection) new URL(redirectURL).openConnection();
        connection.setConnectTimeout(5000);
        connection.connect();

        statusCode = connection.getResponseCode();
        lengthOfFile = getContentLength(connection);
      }
      if(statusCode >= 200 && statusCode < 300) {
        Map<String, List<String>> headers = connection.getHeaderFields();

        Map<String, String> headersFlat = new HashMap<>();

        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
          String headerKey = entry.getKey();
          String valueKey = entry.getValue().get(0);

          if (headerKey != null && valueKey != null) {
            headersFlat.put(headerKey, valueKey);
          }
        }

        if (mParam.onDownloadBegin != null) {
          mParam.onDownloadBegin.onDownloadBegin(statusCode, res.bytesWritten,lengthOfFile, headersFlat);
        }

        input = new BufferedInputStream(connection.getInputStream(), 8 * 1024);
        output = new FileOutputStream(param.dest);

        byte data[] = new byte[8 * 1024];
        long total = 0;
        int count;
        double lastProgressValue = 0;
        long lastProgressEmitTimestamp = 0;
        boolean hasProgressCallback = mParam.onDownloadProgress != null;

        while ((count = input.read(data)) != -1) {
          if (mAbort.get()) throw new Exception("Download has been aborted");

          total += count;

          if (hasProgressCallback) {
            if (param.progressInterval > 0) {
              long timestamp = System.currentTimeMillis();
              if (timestamp - lastProgressEmitTimestamp > param.progressInterval) {
                lastProgressEmitTimestamp = timestamp;
                publishProgress(new long[]{lengthOfFile, total});
              }
            } else if (param.progressDivider <= 0) {
              publishProgress(new long[]{lengthOfFile, total});
            } else {
              double progress = Math.round(((double) total * 100) / lengthOfFile);
              if (progress % param.progressDivider == 0) {
                if ((progress != lastProgressValue) || (total == lengthOfFile)) {
                  Log.d("Downloader", "EMIT: " + String.valueOf(progress) + ", TOTAL:" + String.valueOf(total));
                  lastProgressValue = progress;
                  publishProgress(new long[]{lengthOfFile, total});
                }
              }
            }
          }

          output.write(data, 0, count);
        }

        output.flush();
        res.bytesWritten = total;
      }
      res.statusCode = statusCode;
 } finally {
      if (output != null) output.close();
      if (input != null) input.close();
      if (connection != null) connection.disconnect();
    }
  }

  private long getContentLength(HttpURLConnection connection){
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
      return connection.getContentLengthLong();
    }
    return connection.getContentLength();
  }

  protected void stop() {
    mAbort.set(true);
  }

  @Override
  protected void onProgressUpdate(long[]... values) {
    super.onProgressUpdate(values);
    if (mParam.onDownloadProgress != null) {
      mParam.onDownloadProgress.onDownloadProgress(values[0][0], values[0][1]);
    }
  }

  protected void onPostExecute(Exception ex) {

  }
}
