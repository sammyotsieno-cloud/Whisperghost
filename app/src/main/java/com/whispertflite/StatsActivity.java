package com.whispertflite;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class StatsActivity extends AppCompatActivity {
    private TextView body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.windowBackground));
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad, pad, pad / 2);
        TextView title = new TextView(this);
        title.setText("Statistics");
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(makeButton("Copy", v -> copyAll()));
        bar.addView(makeButton("Clear", v -> {
            Stats.clear();
            getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE).edit().remove("stats_last").apply();
            refresh();
        }));
        bar.addView(makeButton("Close", v -> finish()));
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        body = new TextView(this);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(12);
        body.setTextIsSelectable(true);
        body.setPadding(pad, pad / 2, pad, pad);
        body.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        refresh();
    }

    private Button makeButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void refresh() {
        android.content.SharedPreferences p = getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE);
        StringBuilder sb = new StringBuilder("APP\n");
        sb.append("  Version: ").append(BuildConfig.VERSION_NAME).append(" (code ").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("  Phone: ").append(android.os.Build.MODEL).append(", Android ").append(android.os.Build.VERSION.RELEASE).append("\n");
        sb.append("  Memory limit for the app: ").append(Runtime.getRuntime().maxMemory() / 1048576).append(" MB\n\n");
        String last = Stats.isEmpty() ? p.getString("stats_last", "") : Stats.report();
        if (last.isEmpty()) last = "Nothing recorded yet. Transcribe a recording, then come back here.\n";
        body.setText(sb.toString() + last);
    }

    private void copyAll() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("statistics", body.getText()));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }
}
