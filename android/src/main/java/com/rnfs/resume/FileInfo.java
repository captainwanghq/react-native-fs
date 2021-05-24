package com.rnfs.resume;

import com.rnfs.DownloadParams;
import com.rnfs.DownloadResult;

import java.io.Serializable;
import java.util.Map;

public class FileInfo implements Serializable {
    public interface OnTaskCompleted {
        void onTaskCompleted(DownloadResult res);
    }

    public interface OnDownloadBegin {
        void onDownloadBegin(int statusCode, long contentLength, Map<String, String> headers);
    }

    public interface OnDownloadProgress {
        void onDownloadProgress(long contentLength, long bytesWritten);
    }
    private int id;
    private String url;
    private String fileName;
    private int length;
    private int progress;
    public int progressInterval;
    public float progressDivider;
    public OnTaskCompleted onTaskCompleted;
    public OnDownloadBegin onDownloadBegin;
    public OnDownloadProgress onDownloadProgress;
    public FileInfo() {
    }

    public FileInfo(int id, String url, String fileName, int length, int progress) {
        this.id = id;
        this.url = url;
        this.fileName = fileName;
        this.length = length;
        this.progress = progress;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }


    @Override
    public String toString() {
        return "FileInfo{" +
                "id=" + id +
                ", url='" + url + '\'' +
                ", fileName='" + fileName + '\'' +
                ", length=" + length +
                ", progress=" + progress +
                '}';
    }
}