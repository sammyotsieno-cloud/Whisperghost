package com.whispertflite.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public class Transcript {
    public static final int FLAG_UNSURE = 1, FLAG_REVIEW = 2, FLAG_STAR = 4;

    public static class Segment {
        public int startMs, endMs, speaker; // speaker 0 = not assigned
        public String text, original, note = "";
        public int flags;
        public Segment(int startMs, int endMs, String text) {
            this.startMs = startMs; this.endMs = endMs;
            this.text = text; this.original = text;
        }
    }

    public String id = Long.toString(System.currentTimeMillis(), 36);
    public String title = "", audioUri = "", audioKey = "", model = "", language = "";
    public long createdAt = System.currentTimeMillis(), updatedAt = createdAt;
    public boolean complete;
    public final ArrayList<Segment> segments = new ArrayList<>();
    public final LinkedHashMap<Integer, String> speakerNames = new LinkedHashMap<>();
}
