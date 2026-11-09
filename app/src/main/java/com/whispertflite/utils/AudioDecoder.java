package com.whispertflite.utils;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

public class AudioDecoder {
    private static final int TARGET_RATE = 16000;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int channels;
    private double step;
    private double nextOut = 0;
    private long n = 0;
    private int prev = 0;

    public static byte[] decode(Context context, Uri uri) throws IOException {
        return new AudioDecoder().run(context, uri);
    }

    private byte[] run(Context context, Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, uri, null);
        int track = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                track = i;
                format = f;
                break;
            }
        }
        if (track < 0) {
            extractor.release();
            throw new IOException("No audio track found");
        }
        extractor.selectTrack(track);
        setFormat(format);

        MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        try {
            codec.configure(format, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        int size = extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    feed(outBuf.order(ByteOrder.nativeOrder()).asShortBuffer());
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    setFormat(codec.getOutputFormat());
                }
            }
        } finally {
            codec.stop();
            codec.release();
            extractor.release();
        }
        return out.toByteArray();
    }

    private void setFormat(MediaFormat f) {
        int srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        step = srcRate / (double) TARGET_RATE;
    }

    // Mix to mono, then resample to 16 kHz with linear interpolation
    private void feed(ShortBuffer s) {
        while (s.remaining() >= channels) {
            int sum = 0;
            for (int c = 0; c < channels; c++) sum += s.get();
            int cur = sum / channels;
            while (nextOut <= n) {
                double frac = nextOut - (n - 1);
                int v = (int) (prev + (cur - prev) * frac);
                out.write(v & 0xff);
                out.write((v >> 8) & 0xff);
                nextOut += step;
            }
            prev = cur;
            n++;
        }
    }
}
