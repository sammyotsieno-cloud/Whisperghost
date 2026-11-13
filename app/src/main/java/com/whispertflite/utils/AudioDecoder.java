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

    // Cut points (byte offsets) that avoid splitting words: each cut sits at the quietest moment in the last 5 s of a 30 s window
    public static java.util.List<Integer> silenceCuts(byte[] pcm) {
        final int maxLen = 16000 * 2 * 30;
        final int search = 16000 * 2 * 5;
        final int win = 16000 * 2 / 5;
        final int hop = 16000 * 2 / 20;
        java.util.ArrayList<Integer> cuts = new java.util.ArrayList<>();
        cuts.add(0);
        if (pcm.length < 3200) return cuts;
        int pos = 0;
        while (pcm.length - pos > maxLen) {
            int lo = pos + maxLen - search;
            int hi = pos + maxLen - win;
            long best = Long.MAX_VALUE;
            int bestAt = hi;
            for (int s = lo; s <= hi; s += hop) {
                long sum = 0;
                for (int i = s; i < s + win; i += 8) {
                    int v = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
                    sum += Math.abs(v);
                }
                if (sum < best) { best = sum; bestAt = s + win / 2; }
            }
            bestAt &= ~1;
            cuts.add(bestAt);
            pos = bestAt;
        }
        int tail = pcm.length - pos;
        if (!(tail < 32000 && cuts.size() > 1)) cuts.add(pcm.length);
        return cuts;
    }

    // Start and end (in ms) of the speech inside pcm[from, to): trims the quiet edges
    public static int[] speechSpanMs(byte[] pcm, int from, int to) {
        final int frame = 640;
        int n = (to - from) / frame;
        if (n < 3) return new int[]{from / 32, to / 32};
        int[] en = new int[n];
        int max = 0;
        for (int f = 0; f < n; f++) {
            long sum = 0;
            int base = from + f * frame;
            for (int i = base; i < base + frame; i += 8) {
                sum += Math.abs((short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8)));
            }
            en[f] = (int) (sum / (frame / 8));
            if (en[f] > max) max = en[f];
        }
        int thr = Math.max(150, max / 8);
        int first = 0;
        while (first < n && en[first] < thr) first++;
        if (first >= n) return new int[]{from / 32, to / 32};
        int last = n - 1;
        while (last > first && en[last] < thr) last--;
        return new int[]{(from + first * frame) / 32, (from + (last + 1) * frame) / 32};
    }
}
