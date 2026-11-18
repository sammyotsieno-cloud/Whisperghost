package com.whispertflite;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Facts about the last run, shown on the Statistics screen. To show something new: Stats.set("Name", "value") or Stats.log("what happened"). */
public class Stats {
    private static final ArrayList<String> EVENTS = new ArrayList<>();
    private static final LinkedHashMap<String, String> FACTS = new LinkedHashMap<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public static synchronized void begin(String recording) {
        EVENTS.clear();
        FACTS.clear();
        FACTS.put("Recording", recording);
        log("Started");
    }

    public static synchronized void set(String key, String value) {
        FACTS.put(key, value);
    }

    public static synchronized void log(String message) {
        EVENTS.add(TIME.format(new Date()) + "  " + message);
        if (EVENTS.size() > 400) EVENTS.remove(0);
    }

    public static synchronized boolean isEmpty() {
        return FACTS.isEmpty() && EVENTS.isEmpty();
    }

    public static synchronized void clear() {
        EVENTS.clear();
        FACTS.clear();
    }

    public static synchronized String report() {
        StringBuilder sb = new StringBuilder("LAST RUN\n");
        for (Map.Entry<String, String> e : FACTS.entrySet()) sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        sb.append("\nEVENTS\n");
        for (String s : EVENTS) sb.append("  ").append(s).append("\n");
        return sb.toString();
    }
}
