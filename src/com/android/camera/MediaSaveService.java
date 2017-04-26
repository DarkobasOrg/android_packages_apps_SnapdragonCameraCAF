/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera;

import java.io.File;
import java.nio.ByteOrder;

import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.IBinder;
import android.provider.MediaStore.Video;
import android.util.Log;

import com.android.camera.exif.ExifInterface;
import com.android.camera.mpo.MpoData;
import com.android.camera.mpo.MpoImageData;
import com.android.camera.mpo.MpoInterface;

/*
 * Service for saving images in the background thread.
 */
public class MediaSaveService extends Service {
    public static final String VIDEO_BASE_URI = "content://media/external/video/media";

    // The memory limit for unsaved image is 50MB.
    private static final int SAVE_TASK_MEMORY_LIMIT_IN_MB =
                                   android.os.SystemProperties.getInt("persist.sys.camera.perf.memlimit", 60);
    private static final int SAVE_TASK_MEMORY_LIMIT = SAVE_TASK_MEMORY_LIMIT_IN_MB * 1024 * 1024;
    private static final String TAG = "CAM_" + MediaSaveService.class.getSimpleName();

    private final IBinder mBinder = new LocalBinder();
    private Listener mListener;
    // Memory used by the total queued save request, in bytes.
    private long mMemoryUse;

    public interface Listener {
        public void onQueueStatus(boolean full);
    }

    public interface OnMediaSavedListener {
        public void onMediaSaved(Uri uri);
    }

    class LocalBinder extends Binder {
        public MediaSaveService getService() {
            return MediaSaveService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flag, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
    }

    @Override
    public void onCreate() {
        mMemoryUse = 0;
    }

    public boolean isQueueFull() {
        return (mMemoryUse >= SAVE_TASK_MEMORY_LIMIT);
    }

    public void addMpoImage(final byte[] csImage,
            final byte[] bayerImg, final byte[] monoImg,
            int width, int height,
            String title, long date, Location loc, int orientation,
            OnMediaSavedListener l, ContentResolver resolver,
            String pictureFormat) {
        if (isQueueFull()) {
            Log.e(TAG, "Cannot add image when the queue is full");
            return;
        }

        MpoSaveTask t = new MpoSaveTask(csImage, bayerImg, monoImg,
                width, height, title, date, loc, orientation, l,
                resolver, pictureFormat);

        long size = (csImage == null ? 0
                : csImage.length)
                + bayerImg.length + monoImg.length;
        mMemoryUse += size;
        if (isQueueFull()) {
            onQueueFull();
        }
        t.execute();
    }

    public void addImage(final byte[] data, String title, long date, Location loc,
            int width, int height, int orientation, ExifInterface exif,
            OnMediaSavedListener l, ContentResolver resolver, String pictureFormat) {
        if (isQueueFull()) {
            Log.e(TAG, "Cannot add image when the queue is full");
            return;
        }
        ImageSaveTask t = new ImageSaveTask(data, title, date,
                (loc == null) ? null : new Location(loc),
                width, height, orientation, exif, resolver, l, pictureFormat);

        mMemoryUse += data.length;
        if (isQueueFull()) {
            onQueueFull();
        }
        t.execute();
    }

    public void addRawImage(final byte[] data, String title, String pictureFormat) {
        if (isQueueFull()) {
            Log.e(TAG, "Cannot add image when the queue is full");
            return;
        }
        RawImageSaveTask t = new RawImageSaveTask(data, title, pictureFormat);

        mMemoryUse += data.length;
        if (isQueueFull()) {
            onQueueFull();
        }
        t.execute();
    }

    public void addImage(final byte[] data, String title, long date, Location loc,
                         int orientation, ExifInterface exif,
                         OnMediaSavedListener l, ContentResolver resolver) {
        // When dimensions are unknown, pass 0 as width and height,
        // and decode image for width and height later in a background thread
        addImage(data, title, date, loc, 0, 0, orientation, exif, l, resolver,
                 PhotoModule.PIXEL_FORMAT_JPEG);
    }
    public void addImage(final byte[] data, String title, Location loc,
            int width, int height, int orientation, ExifInterface exif,
            OnMediaSavedListener l, ContentResolver resolver) {
        addImage(data, title, System.currentTimeMillis(), loc, width, height,
                orientation, exif, l, resolver,PhotoModule.PIXEL_FORMAT_JPEG);
    }

    public void addVideo(String path, long duration, ContentValues values,
            OnMediaSavedListener l, ContentResolver resolver) {
        // We don't set a queue limit for video saving because the file
        // is already in the storage. Only updating the database.
        new VideoSaveTask(path, duration, values, l, resolver).execute();
    }

    public void setListener(Listener l) {
        mListener = l;
        if (l == null) return;
        l.onQueueStatus(isQueueFull());
    }

    private void onQueueFull() {
        if (mListener != null) mListener.onQueueStatus(true);
    }

    private void onQueueAvailable() {
        if (mListener != null) mListener.onQueueStatus(false);
    }

    private class MpoSaveTask extends AsyncTask<Void, Void, Uri> {
        private byte[] csImage;
        private byte[] bayerImage;
        private byte[] monoImage;
        private String title;
        private long date;
        private Location loc;
        private int width, height;
        private int orientation;
        private ContentResolver resolver;
        private OnMediaSavedListener listener;
        private String pictureFormat;

        public MpoSaveTask(byte[] csImage, byte[] bayerImg,
                byte[] monoImg, int width, int height, String title, long date,
                Location loc, int orientation, OnMediaSavedListener listener,
                ContentResolver resolver, String pictureFormat) {
            this.csImage = csImage;
            this.bayerImage = bayerImg;
            this.monoImage = monoImg;
            this.title = title;
            this.date = date;
            this.loc = loc;
            this.width = width;
            this.height = height;
            this.orientation = orientation;
            this.resolver = resolver;
            this.listener = listener;
            this.pictureFormat = pictureFormat;
        }

        @Override
        protected Uri doInBackground(Void... v) {
            // encode jpeg and add exif for all images
            MpoData mpo = new MpoData();
            MpoImageData bayer = new MpoImageData(bayerImage,
                    ByteOrder.BIG_ENDIAN);

            MpoImageData mono = new MpoImageData(monoImage,
                    ByteOrder.BIG_ENDIAN);

            if (csImage == null) {
                mpo.addAuxiliaryMpoImage(mono);
                mpo.setPrimaryMpoImage(bayer);
            } else {
                MpoImageData cs = new MpoImageData(csImage,
                        ByteOrder.BIG_ENDIAN);

                mpo.addAuxiliaryMpoImage(bayer);
                mpo.addAuxiliaryMpoImage(mono);
                mpo.setPrimaryMpoImage(cs);
            }

            // combine to single mpo
            String path = Storage.generateFilepath(title, pictureFormat);
            int size = MpoInterface.writeMpo(mpo, path);
            // Try to get the real image size after add exif.
            File f = new File(path);
            if (f.exists() && f.isFile()) {
                size = (int) f.length();
            }
            return Storage.addImage(resolver, title, date, loc, orientation,
                    size, path, width, height, pictureFormat);
        }

        @Override
        protected void onPostExecute(Uri uri) {
            if (listener != null)
                listener.onMediaSaved(uri);
            boolean previouslyFull = isQueueFull();
            long size = (csImage == null ? 0
                    : csImage.length)
                    + bayerImage.length
                    + monoImage.length;
            mMemoryUse -= size;
            if (isQueueFull() != previouslyFull)
                onQueueAvailable();
        }
    }

    private class RawImageSaveTask extends AsyncTask<Void, Void, Long> {
        private byte[] data;
        private String title;
        private String pictureFormat;

        public RawImageSaveTask(byte[] data, String title, String pictureFormat) {
            this.data = data;
            this.title = title;
            this.pictureFormat = pictureFormat;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected Long doInBackground(Void... params) {
            long length = Storage.addRawImage(title, data, pictureFormat);
            return new Long(length);
        }

        @Override
        protected void onPostExecute(Long l) {
            boolean previouslyFull = isQueueFull();
            mMemoryUse -= data.length;
            if (isQueueFull() != previouslyFull) onQueueAvailable();
        }
    }

    private class ImageSaveTask extends AsyncTask <Void, Void, Uri> {
        private byte[] data;
        private String title;
        private long date;
        private Location loc;
        private int width, height;
        private int orientation;
        private ExifInterface exif;
        private ContentResolver resolver;
        private OnMediaSavedListener listener;
        private String pictureFormat;

        public ImageSaveTask(byte[] data, String title, long date, Location loc,
                             int width, int height, int orientation, ExifInterface exif,
                             ContentResolver resolver, OnMediaSavedListener listener, String pictureFormat) {
            this.data = data;
            this.title = title;
            this.date = date;
            this.loc = loc;
            this.width = width;
            this.height = height;
            this.orientation = orientation;
            this.exif = exif;
            this.resolver = resolver;
            this.listener = listener;
            this.pictureFormat = pictureFormat;
        }

        @Override
        protected void onPreExecute() {
            // do nothing.
        }

        @Override
        protected Uri doInBackground(Void... v) {
            if (width == 0 || height == 0) {
                // Decode bounds
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, options);
                width = options.outWidth;
                height = options.outHeight;
            }
            return Storage.addImage(
                    resolver, title, date, loc, orientation, exif, data, width, height, pictureFormat);
        }

        @Override
        protected void onPostExecute(Uri uri) {
            if (listener != null) listener.onMediaSaved(uri);
            boolean previouslyFull = isQueueFull();
            mMemoryUse -= data.length;
            if (isQueueFull() != previouslyFull) onQueueAvailable();
        }
    }

    private class VideoSaveTask extends AsyncTask <Void, Void, Uri> {
        private String path;
        private long duration;
        private ContentValues values;
        private OnMediaSavedListener listener;
        private ContentResolver resolver;

        public VideoSaveTask(String path, long duration, ContentValues values,
                OnMediaSavedListener l, ContentResolver r) {
            this.path = path;
            this.duration = duration;
            this.values = new ContentValues(values);
            this.listener = l;
            this.resolver = r;
        }

        @Override
        protected Uri doInBackground(Void... v) {
            values.put(Video.Media.SIZE, new File(path).length());
            values.put(Video.Media.DURATION, duration);
            Uri uri = null;
            try {
                Uri videoTable = Uri.parse(VIDEO_BASE_URI);
                uri = resolver.insert(videoTable, values);

                // Rename the video file to the final name. This avoids other
                // apps reading incomplete data.  We need to do it after we are
                // certain that the previous insert to MediaProvider is completed.
                String finalName = values.getAsString(
                        Video.Media.DATA);
                if (new File(path).renameTo(new File(finalName))) {
                    path = finalName;
                }

                resolver.update(uri, values, null, null);
            } catch (Exception e) {
                // We failed to insert into the database. This can happen if
                // the SD card is unmounted.
                Log.e(TAG, "failed to add video to media store", e);
                uri = null;
            } finally {
                Log.v(TAG, "Current video URI: " + uri);
            }
            return uri;
        }

        @Override
        protected void onPostExecute(Uri uri) {
            if (listener != null) listener.onMediaSaved(uri);
        }
    }
}
