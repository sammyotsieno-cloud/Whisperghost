package com.whispertflite.cpp;

public class WhisperCpp {
    static {
        System.loadLibrary("whisper");
    }

    public static native long initContext(String modelPath);
    public static native void freeContext(long ctx);
    public static native void requestCancel();
    public static native int transcribe(long ctx, int threads, float[] audio, String lang, boolean translate, String prompt);
    public static native int segmentCount(long ctx);
    public static native byte[] segmentText(long ctx, int index);
    public static native long segmentT0(long ctx, int index);
    public static native long segmentT1(long ctx, int index);

    public interface ProgressListener {
        void onProgress(int percent);
    }

    private static volatile ProgressListener listener;

    public static void setProgressListener(ProgressListener l) {
        listener = l;
    }

    public static void onProgress(int percent) {
        ProgressListener l = listener;
        if (l != null) l.onProgress(percent);
    }
}
