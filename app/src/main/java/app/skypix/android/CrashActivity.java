package app.skypix.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Runs in its own process and uses only stock views, so it can still appear when the app itself is broken. */
public class CrashActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String report = getIntent().getStringExtra("report") == null ? "(no details)" : getIntent().getStringExtra("report");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p * 2, p, p);

        TextView title = new TextView(this);
        title.setText("Purrcast hit a problem");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Copy this and send it over so it can be fixed.");
        sub.setTextColor(0xAAFFFFFF);
        sub.setTextSize(13);
        root.addView(sub);

        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTextColor(0xFFFFB4B4);
        tv.setTextSize(10.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(-1, 0, 1f);
        svp.topMargin = p;
        svp.bottomMargin = p;
        root.addView(sv, svp);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        Button copy = new Button(this);
        copy.setText("COPY");
        copy.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Purrcast crash", report));
                Toast.makeText(CrashActivity.this, "Copied", Toast.LENGTH_SHORT).show();
            }
        });
        Button share = new Button(this);
        share.setText("SHARE");
        share.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report);
                startActivity(Intent.createChooser(s, "Share crash report"));
            }
        });
        Button close = new Button(this);
        close.setText("CLOSE");
        close.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { finishAndRemoveTask(); }
        });
        row.addView(copy);
        row.addView(share);
        row.addView(close);
        root.addView(row);
        setContentView(root);
    }
}
