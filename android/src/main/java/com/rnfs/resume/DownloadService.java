package com.rnfs.resume;
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
import com.rnfs.DownloadParams;
import com.rnfs.DownloadResult;


public class DownloadService extends AsyncTask<FileInfo, long[], DownloadResult> {
    private static final String TAG = "DownloadService";
    public static final String ACTION_START = "start";
    public static final String ACTION_PAUSE = "pause";
    public static final String ACTION_UPDATE = "update";
    public static final String DOWNLOAD_SAVE_PATH = Environment.getExternalStorageDirectory().getAbsolutePath() + "/downloads/";
    private FileInfo mParam;
    private AtomicBoolean mAbort = new AtomicBoolean(false);
    DownloadResult res;
    private Context mContext = null;
    private DownloadTask downloadTask = null;
    private ThreadDao mThreadDao = null;
    private int mProgress = 0;
    public boolean isPause = false;

    public  DownloadService(Context context)
    {
        this.mContext = context;
    }
    @Override
    protected DownloadResult doInBackground(FileInfo... params) {
        mParam = params[0];
        res = new DownloadResult();
        mThreadDao = new ThreadDaoImpl(this.mContext);
        new Thread(new Runnable() {
            public void run() {
                try {
                    download(mParam, res);

                } catch (Exception ex) {
                    res.exception = ex;

                }
            }
        }).start();

        return res;
    }


    private void download(FileInfo param, DownloadResult res) throws Exception {

        URL url = new URL(param.getUrl());
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setRequestMethod("GET");
        int responeCode = connection.getResponseCode();
        int length = -1;
        if(HttpURLConnection.HTTP_OK == responeCode)
        {
            length = connection.getContentLength();
            if(length<0)
            {
                return;
            }
            File dir = new File(DOWNLOAD_SAVE_PATH);
            if (!dir.exists()) {
                dir.mkdir();
            }
            File file = new File(dir,param.getFileName());

            RandomAccessFile randomAccessFile = new RandomAccessFile(file,"rwd");
            randomAccessFile.setLength(length);
            param.setLength(length);
            //读取数据库线程信息
            List<DownloadThreadInfo> threadInfos = mThreadDao.getThreads(param.getUrl());
            DownloadThreadInfo threadInfo = null;
            if (threadInfos.size() == 0) {
                Log.i(TAG, "download: thread size 0");
                //初始化线程信息
                threadInfo = new DownloadThreadInfo(0, param.getUrl(), 0, param.getLength(), 0);
            } else {
                Log.i(TAG, "download: thread size > 0");
                //单线程下载
                threadInfo = threadInfos.get(0);
            }
            _download(threadInfo,param);

        }
    }

    private  void _download(DownloadThreadInfo threadInfo,FileInfo fileInfo)
    {
        HttpURLConnection connection = null;
        RandomAccessFile randomAccessFile = null;
        InputStream input = null;

        //查询数据库中是否存在记录
        if (!mThreadDao.isExists(threadInfo.getUrl(), threadInfo.getId())) {
            mThreadDao.insertThread(threadInfo);
        }
        try {
            URL url = new URL(threadInfo.getUrl());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            //设置下载位置
            int start = threadInfo.getBegin() + threadInfo.getProgress();
            //断点续传关键: 请求信息设置range字段
            connection.setRequestProperty("Range", "bytes=" + start + "-" + threadInfo.getEnd());
            //在本地创建文件
            File dir = new File(DownloadService.DOWNLOAD_SAVE_PATH);
            if (!dir.exists()) {
                dir.mkdir();
            }
            File file = new File(dir, fileInfo.getFileName());
            //断点续传的关键:RandomAccessFile
            //rwd:read write delete
            randomAccessFile = new RandomAccessFile(file, "rwd");
            randomAccessFile.seek(start);
            mProgress += threadInfo.getProgress();
            Log.i(TAG, "run: connection.getResponseCode: " + connection.getResponseCode());
            //开始下载
            //注意下载时候这里的responseCode是206
            if (HttpURLConnection.HTTP_PARTIAL == connection.getResponseCode()) {
                //读取数据
                input = connection.getInputStream();
                byte[] buffer = new byte[8*1024];
                int len;

                double lastProgressValue = 0;
                long lastProgressEmitTimestamp = 0;

                while ((len = input.read(buffer)) != -1) {

                    int total = mProgress+len;
                    int lengthOfFile = this.mParam.getLength();
                    if (fileInfo.progressInterval > 0) {
                        long timestamp = System.currentTimeMillis();
                        if (timestamp - lastProgressEmitTimestamp > fileInfo.progressInterval) {
                            lastProgressEmitTimestamp = timestamp;
                            publishProgress(new long[]{lengthOfFile, total});
                        }
                    } else if (fileInfo.progressDivider <= 0) {
                        publishProgress(new long[]{lengthOfFile, total});
                    } else {
                        double progress = Math.round(((double) total * 100) / lengthOfFile);
                        if (progress % fileInfo.progressDivider == 0) {
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
                    if (isPause) {
                        mThreadDao.updateThread(threadInfo.getUrl(), threadInfo.getId(), mProgress);
                        return;
                    }
                }
                mThreadDao.deleteThread(threadInfo.getUrl(), threadInfo.getId());

            } else {
                return;
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                input.close();
                randomAccessFile.close();
                connection.disconnect();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }
    public void onPregress(long progress)
    {
        publishProgress(new long[]{this.mParam.getLength(),progress});
    }
    public void pause()
    {
     this.isPause = true;
    }
    @Override
    protected void onProgressUpdate(long[]... values) {
        super.onProgressUpdate(values);
        if (mParam.onDownloadProgress != null) {
            mParam.onDownloadProgress.onDownloadProgress(values[0][0], values[0][1]);
        }
    }
    protected void onPostExecute(Exception ex) {
        System.out.println(ex);
    }
}