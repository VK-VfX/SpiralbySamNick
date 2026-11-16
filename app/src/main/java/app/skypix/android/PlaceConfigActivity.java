package app.skypix.android;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Shown when a widget is added (or reconfigured): which place should it show? Skipped when there are no saved places. */
public final class PlaceConfigActivity extends Activity {
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        if (in != null && in.getExtras() != null) {
            widgetId = in.getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        // backing out cancels adding the widget
        setResult(RESULT_CANCELED, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        final List<Places.Saved> saved = Places.list(this);
        if (saved.isEmpty()) {
            choose("");
            return;
        }
        getWindow().setStatusBarColor(Ui.bg(this));
        getWindow().setNavigationBarColor(Ui.bg(this));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 36), pad, pad);
        col.addView(Ui.text(this, "Which place?", 30, 500, Ui.onSurface(this)));
        TextView sub = Ui.text(this, "Choose what this widget shows. You can change it later by long-pressing the widget.", 14.5f, 400, Ui.onSurfaceVar(this));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = Ui.dp(this, 6);
        sp.bottomMargin = Ui.dp(this, 18);
        col.addView(sub, sp);

        addRow(col, "Where I am", "Follows your location", "");
        for (Places.Saved s : saved) addRow(col, s.name, s.place.title(), s.id);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.bg(this));
        sv.addView(col, new ViewGroupLayout().match());
        setContentView(sv);
    }

    /** Tiny helper so the layout params read clearly. */
    private static final class ViewGroupLayout {
        android.view.ViewGroup.LayoutParams match() {
            return new android.view.ViewGroup.LayoutParams(-1, -2);
        }
    }

    private void addRow(LinearLayout col, String title, String sub, final String id) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16));
        row.setBackground(Ui.surface(this, Ui.container(this), 24));
        row.addView(Ui.text(this, title, 17, 500, Ui.onSurface(this)));
        row.addView(Ui.text(this, sub, 13.5f, 400, Ui.onSurfaceVar(this)));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { choose(id); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 10);
        col.addView(row, lp);
    }

    private void choose(String placeId) {
        Places.setWidgetPlace(this, widgetId, placeId);
        WidgetUpdater.updateOne(this, widgetId);
        Jobs.syncAnimService(getApplicationContext());
        Jobs.kick(this);
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        finish();
    }
}
