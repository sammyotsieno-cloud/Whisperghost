package com.whispertflite.data;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One JSON file per transcript. Saves go to a temp file first, so a crash never damages the old copy. */
public class TranscriptStore {
    private static final String TAG = "TranscriptStore";
    private final File dir;

    public TranscriptStore(Context c) {
        dir = new File(c.getFilesDir(), "transcripts");
        dir.mkdirs();
    }

    public synchronized void save(Transcript t) throws IOException {
        t.updatedAt = System.currentTimeMillis();
        File tmp = new File(dir, t.id + ".json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(toJson(t).toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (JSONException e) { throw new IOException(e); }
        if (!tmp.renameTo(new File(dir, t.id + ".json"))) throw new IOException("Could not save " + t.id);
    }

    public synchronized Transcript load(String id) {
        File f = new File(dir, id + ".json");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            new DataInputStream(in).readFully(b);
            return fromJson(new JSONObject(new String(b, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.e(TAG, "Could not read " + id, e);
            return null;
        }
    }

    /** Newest first. */
    public synchronized List<Transcript> list() {
        ArrayList<Transcript> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) for (File f : files) {
            Transcript t = load(f.getName().replace(".json", ""));
            if (t != null) out.add(t);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return out;
    }

    public synchronized void delete(String id) {
        new File(dir, id + ".json").delete();
        new File(dir, id + ".json.tmp").delete();
    }

    private static JSONObject toJson(Transcript t) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", t.id).put("title", t.title).put("audioUri", t.audioUri).put("audioKey", t.audioKey)
         .put("model", t.model).put("language", t.language).put("created", t.createdAt)
         .put("updated", t.updatedAt).put("complete", t.complete);
        JSONObject names = new JSONObject();
        for (Map.Entry<Integer, String> e : t.speakerNames.entrySet()) names.put(String.valueOf(e.getKey()), e.getValue());
        o.put("speakerNames", names);
        JSONArray segs = new JSONArray();
        for (Transcript.Segment s : t.segments)
            segs.put(new JSONObject().put("a", s.startMs).put("b", s.endMs).put("sp", s.speaker)
                    .put("t", s.text).put("o", s.original).put("n", s.note).put("f", s.flags));
        o.put("segments", segs);
        return o;
    }

    private static Transcript fromJson(JSONObject o) throws JSONException {
        Transcript t = new Transcript();
        t.id = o.getString("id"); t.title = o.optString("title"); t.audioUri = o.optString("audioUri");
        t.audioKey = o.optString("audioKey"); t.model = o.optString("model"); t.language = o.optString("language");
        t.createdAt = o.optLong("created"); t.updatedAt = o.optLong("updated"); t.complete = o.optBoolean("complete");
        JSONObject names = o.optJSONObject("speakerNames");
        if (names != null) for (Iterator<String> it = names.keys(); it.hasNext();) {
            String k = it.next();
            t.speakerNames.put(Integer.parseInt(k), names.getString(k));
        }
        JSONArray segs = o.optJSONArray("segments");
        if (segs != null) for (int i = 0; i < segs.length(); i++) {
            JSONObject s = segs.getJSONObject(i);
            Transcript.Segment g = new Transcript.Segment(s.optInt("a"), s.optInt("b"), s.optString("t"));
            g.original = s.optString("o", g.text); g.speaker = s.optInt("sp"); g.note = s.optString("n"); g.flags = s.optInt("f");
            t.segments.add(g);
        }
        return t;
    }
}
