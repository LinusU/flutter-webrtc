package com.cloudwebrtc.webrtc.record;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;
import org.webrtc.YuvHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import io.flutter.plugin.common.MethodChannel;

public class FrameCapturer implements VideoSink {
    private VideoTrack videoTrack;
    private File file;
    private final MethodChannel.Result callback;
    private boolean gotFrame = false;

    public FrameCapturer(VideoTrack track, File file, MethodChannel.Result callback) {
        videoTrack = track;
        this.file = file;
        this.callback = callback;
        track.addSink(this);
    }

    @Override
    public void onFrame(VideoFrame videoFrame) {
        if (gotFrame)
            return;
        gotFrame = true;
        videoFrame.retain();
        VideoFrame.Buffer buffer = videoFrame.getBuffer();
        VideoFrame.I420Buffer i420Buffer = buffer.toI420();
        ByteBuffer y = i420Buffer.getDataY();
        ByteBuffer u = i420Buffer.getDataU();
        ByteBuffer v = i420Buffer.getDataV();
        int width = i420Buffer.getWidth();
        int height = i420Buffer.getHeight();
        int[] strides = new int[] {
            i420Buffer.getStrideY(),
            i420Buffer.getStrideU(),
            i420Buffer.getStrideV()
        };
        final int chromaWidth = (width + 1) / 2;
        final int chromaHeight = (height + 1) / 2;
        final int minSize = width * height + chromaWidth * chromaHeight * 2;
        Log.w("LINUS", "minSize = " + Integer.toString(minSize));
        Log.w("LINUS", "foobar = " + Integer.toString(width * height * 3 / 2));
        Log.w("LINUS", "strides = " + Integer.toString(strides[0]) + ", " + Integer.toString(strides[1]) + ", " + Integer.toString(strides[2]));
        ByteBuffer yuvBuffer = ByteBuffer.allocateDirect(minSize);
        YuvHelper.I420ToNV12(y, strides[0], v, strides[2], u, strides[1], yuvBuffer, width, height);

        Log.w("LINUS", Integer.toString(videoFrame.getRotation()));

        Log.w("LINUS", "BEFORE width: " + Integer.toString(width) + " height: " + Integer.toString(height));
        byte[] yuv;
        int[] strides2;
        switch (videoFrame.getRotation()) {
            case 0:
                yuv = yuvBuffer.array();
                strides2 = strides;
                break;
            case 90:
                yuv = rotate90(yuvBuffer.array(), width, height, minSize);
                width = width ^ height;
                height = width ^ height;
                width = width ^ height;
                strides2 = new int[]{width, width / 2, width / 2};
                break;
            case 180:
                yuv = rotate180(yuvBuffer.array(), width, height, minSize);
                strides2 = strides;
                break;
            case 270:
                yuv = rotate270(yuvBuffer.array(), width, height, minSize);
                width = width ^ height;
                height = width ^ height;
                width = width ^ height;
                strides2 = new int[]{width, width / 2, width / 2};
                break;
            default:
                // Rotation is checked to always be 0, 90, 180 or 270 by VideoFrame
                throw new RuntimeException("Invalid rotation");
        }

        Log.w("LINUS", "AFTER width: " + Integer.toString(width) + " height: " + Integer.toString(height));


        YuvImage yuvImage = new YuvImage(
            yuv,
            ImageFormat.NV21,
            width,
            height,
            strides2
        );
        videoFrame.release();
        new Handler(Looper.getMainLooper()).post(() -> {
            videoTrack.removeSink(this);
        });
        Log.w("LINUS", file.getAbsolutePath());
        try {
            if (!file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.getParentFile().mkdirs();
                //noinspection ResultOfMethodCallIgnored
                file.createNewFile();
            }
        } catch (IOException io) {
            callback.error("IOException", io.getLocalizedMessage(), io);
            return;
        }
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            yuvImage.compressToJpeg(
                new Rect(0, 0, width, height),
                100,
                outputStream
            );
            callback.success(null);
        } catch (IOException io) {
            callback.error("IOException", io.getLocalizedMessage(), io);
        } catch (IllegalArgumentException iae) {
            callback.error("IllegalArgumentException", iae.getLocalizedMessage(), iae);
        } finally {
            file = null;
        }
    }

    private byte[] rotate90(byte[] source, int width, int height, int minSize) {
        int idx = 0;
        byte[] yuv = new byte[minSize];

        // Rotate the Y luma
        for (int x = 0; x < width; x += 1) {
            for (int y = height - 1; y >= 0; y -= 1) {
                yuv[idx++] = source[y * width + x];
            }
        }

        int uvOffset = idx;

        // Rotate the U and V color components
        for (int x = 0; x < (width / 2); x += 1) {
            for (int y = (height / 2) - 1; y >= 0; y -= 1) {
                byte Y = source[uvOffset + y * width + x];
                byte U = source[uvOffset + y * width + x];
                yuv[idx++] = U;
                yuv[idx++] = Y;
            }
        }

        return yuv;
    }

    private byte[] rotate180(byte[] source, int width, int height, int minSize) {
        int resultPointer, sourcePointer;
        byte[] yuv = new byte[minSize];

        // Rotate the Y luma
        resultPointer = 0;
        sourcePointer = width * height;
        while (sourcePointer != 0) {
            yuv[resultPointer++] = source[--sourcePointer];
        }

        // Rotate the U and V color components
        sourcePointer = minSize;
        while (resultPointer < minSize) {
            byte y = source[--sourcePointer];
            byte u = source[--sourcePointer];
            yuv[resultPointer++] = u;
            yuv[resultPointer++] = y;
        }

        return yuv;
    }

    private byte[] rotate270(byte[] source, int width, int height, int minSize) {
        int idx;
        byte[] yuv = new byte[minSize];

        // Rotate the Y luma
        idx = 0;
        for (int x = 0; x < width; x += 1) {
            for (int y = height - 1; y >= 0; y -= 1) {
                yuv[idx++] = source[y * width + x];
            }
        }

        // Rotate the U and V color components
        idx = minSize - 1;
        for (int x = width - 1; x > 0; x -= 2) {
            for (int y = 0; y < height / 2; y += 1) {
                yuv[idx--] = source[(width * height) + (y * width) + x];
                yuv[idx--] = source[(width * height) + (y * width) + x - 1];
            }
        }

        return yuv;
    }
}
