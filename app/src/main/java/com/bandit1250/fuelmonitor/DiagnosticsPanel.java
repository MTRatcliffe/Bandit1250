package com.bandit1250.fuelmonitor;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.util.*;

/**
 * Expandable full-session ECU diagnostics logger / chart stack.
 *
 * One timestamped sample stores every decoded value. Reordering graphs only
 * changes presentation; it never changes or discards the underlying samples.
 */
public final class DiagnosticsPanel {
    public static final class Sample {
        public final long timeMs;
        public final float[] values;

        Sample(long timeMs, float[] values) {
            this.timeMs = timeMs;
            this.values = values;
        }
    }

    private static final class Metric {
        final String id;
        final String name;
        final String unit;
        final int decimals;
        final double fixedMin;
        final double fixedMax;

        Metric(
                String id,
                String name,
                String unit,
                int decimals,
                double fixedMin,
                double fixedMax
        ) {
            this.id = id;
            this.name = name;
            this.unit = unit;
            this.decimals = decimals;
            this.fixedMin = fixedMin;
            this.fixedMax = fixedMax;
        }
    }

    private static final Metric[] METRICS = {
            new Metric("rpm", "RPM", "rpm", 0, 0, 10000),
            new Metric("ecu_speed", "ECU speed", "km/h", 0, 0, 300),
            new Metric("tps", "TPS", "%", 1, 0, 100),
            new Metric("iap1", "IAP-1", "kPa", 1, 0, 110),
            new Metric("coolant", "Coolant", "°C", 1, -20, 130),
            new Metric("intake", "Intake temp", "°C", 1, -20, 80),
            new Metric("eap", "EAP raw", "raw", 0, 0, 255),
            new Metric("battery", "Battery", "V", 2, 10, 16),
            new Metric("o2", "O₂ raw", "raw", 0, 0, 255),
            new Metric("gear", "Gear sensor raw", "raw", 0, 0, 255),
            new Metric("iap2", "IAP-2", "kPa", 1, 0, 110),
            new Metric("desired_idle", "Desired idle", "rpm", 0, 0, 2500),
            new Metric("isc", "ISC position raw", "raw", 0, 0, 255),
            new Metric("inj1", "Injector 1", "ms", 3, 0, 10),
            new Metric("inj2", "Injector 2", "ms", 3, 0, 10),
            new Metric("inj3", "Injector 3", "ms", 3, 0, 10),
            new Metric("inj4", "Injector 4", "ms", 3, 0, 10),
            new Metric("injavg", "Injector average", "ms", 3, 0, 10),
            new Metric("ign1", "Ignition 1", "°", 1, -40, 60),
            new Metric("ign2", "Ignition 2", "°", 1, -40, 60),
            new Metric("ign3", "Ignition 3", "°", 1, -40, 60),
            new Metric("ign4", "Ignition 4", "°", 1, -40, 60),
            new Metric("stps", "Secondary TPS", "%", 1, 0, 100),
            new Metric("status45", "Status 45", "raw", 0, 0, 255),
            new Metric("fan", "Cooling fan", "raw", 0, 0, 255),
            new Metric("exhaust", "Exhaust valve", "raw", 0, 0, 255),
            new Metric("clutch", "Clutch / starter", "raw", 0, 0, 255),
            new Metric("neutral", "Neutral", "raw", 0, 0, 255)
    };

    private final Activity activity;
    private final SharedPreferences prefs;
    private final ScrollView hostScroll;

    private final LinearLayout root;
    private final LinearLayout rows;
    private final ArrayList<Sample> history = new ArrayList<>();
    private final ArrayList<Integer> order = new ArrayList<>();

    private static final String DEFAULT_NOTE =
            "Min / Max follow the selected time window. " +
            "FIX uses a sensible diagnostic scale; AUTO zooms to the data.";

    private final Button b10;
    private final Button b60;
    private final Button b300;
    private final Button bMax;
    private final TextView note;
    private final Button returnLive;

    private long windowMs = 10_000L;
    private long lastRefreshMs = 0L;
    private boolean frozenFastCapture = false;

    private static final class RowViews {
        final int metricIndex;
        final TextView stats;
        final Button scale;
        final SensorChartView chart;

        RowViews(
                int metricIndex,
                TextView stats,
                Button scale,
                SensorChartView chart
        ) {
            this.metricIndex = metricIndex;
            this.stats = stats;
            this.scale = scale;
            this.chart = chart;
        }
    }

    private final ArrayList<RowViews> rowViews = new ArrayList<>();

    public DiagnosticsPanel(
            Activity activity,
            SharedPreferences prefs,
            ScrollView hostScroll
    ) {
        this.activity = activity;
        this.prefs = prefs;
        this.hostScroll = hostScroll;

        loadOrder();

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(6), 0, dp(10));
        root.setVisibility(View.GONE);

        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = text("Chart window");
        label.setTypeface(null, Typeface.BOLD);
        controls.addView(label, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        b10 = windowButton("10 s", 10_000L);
        b60 = windowButton("1 min", 60_000L);
        b300 = windowButton("5 min", 300_000L);
        bMax = windowButton("MAX", -1L);

        controls.addView(b10);
        controls.addView(b60);
        controls.addView(b300);
        controls.addView(bMax);

        root.addView(controls);

        note = text(DEFAULT_NOTE);
        note.setTextSize(11);
        root.addView(note);

        returnLive = new Button(activity);
        returnLive.setText("RETURN TO LIVE CHARTS");
        returnLive.setVisibility(View.GONE);
        returnLive.setOnClickListener(v -> resumeLiveCharts());
        root.addView(returnLive);

        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows);

        rebuildRows();
        updateWindowButtons();
    }

    public View getView() {
        return root;
    }

    public void toggle() {
        if (root.getVisibility() == View.VISIBLE) {
            root.setVisibility(View.GONE);
        } else {
            root.setVisibility(View.VISIBLE);
            refresh(true);
        }
    }

    public void resetSession() {
        history.clear();
        frozenFastCapture = false;
        windowMs = 10_000L;
        lastRefreshMs = 0L;
        note.setText(DEFAULT_NOTE);
        returnLive.setVisibility(View.GONE);
        updateWindowButtons();
        refresh(true);
    }

    /**
     * Add an ordinary live sample.  While a completed FAST CAPTURE is being
     * inspected, live polling deliberately does not alter the frozen trace.
     * RETURN TO LIVE CHARTS clears the capture and starts a fresh live history.
     */
    public void addSample(BanditLiveData d) {
        if (frozenFastCapture) return;

        history.add(makeSample(SystemClock.elapsedRealtime(), d));

        if (root.getVisibility() == View.VISIBLE) {
            refresh(false);
        }
    }

    /**
     * Replace the diagnostic chart history with an exact timestamped fast
     * capture.  The caller bulk-adds samples with addFastCaptureSample(), then
     * calls finishFastCaptureDisplay().  No chart redraw occurs during bulk
     * insertion, so hundreds of high-rate samples can be loaded cheaply.
     */
    public void beginFastCaptureDisplay() {
        history.clear();
        frozenFastCapture = true;
        lastRefreshMs = 0L;
    }

    public void addFastCaptureSample(long timeMs, BanditLiveData d) {
        history.add(makeSample(timeMs, d));
    }

    public void finishFastCaptureDisplay(String summary) {
        frozenFastCapture = true;

        // MAX means the entire 30 s capture is shown, with no point thinning at
        // the expected ELM sample count.  The normal 10 s / 1 min / 5 min
        // buttons remain available for closer inspection.
        windowMs = -1L;
        updateWindowButtons();

        note.setText(
                (summary == null ? "FAST CAPTURE complete." : summary) +
                "\nFrozen high-resolution capture. Live polling continues in " +
                "the background but will not overwrite these charts."
        );
        returnLive.setVisibility(View.VISIBLE);
        root.setVisibility(View.VISIBLE);
        refresh(true);
    }

    private void resumeLiveCharts() {
        frozenFastCapture = false;
        history.clear();
        windowMs = 10_000L;
        lastRefreshMs = 0L;
        note.setText(DEFAULT_NOTE);
        returnLive.setVisibility(View.GONE);
        updateWindowButtons();
        refresh(true);
    }

    private Sample makeSample(long timeMs, BanditLiveData d) {
        float[] v = new float[METRICS.length];

        v[0] = d.rpm;
        v[1] = f(d.ecuSpeedKph);
        v[2] = f(d.tpsPct);
        v[3] = f(d.iap1Kpa);
        v[4] = f(d.engineTempC);
        v[5] = f(d.intakeTempC);
        v[6] = d.eapRaw;
        v[7] = f(d.batteryEstV);
        v[8] = d.o2Raw;
        v[9] = d.gearRaw;
        v[10] = f(d.iap2Kpa);
        v[11] = f(d.idleSpeedRaw * 12.5);
        v[12] = d.iscRaw;
        v[13] = f(d.inj1);
        v[14] = f(d.inj2);
        v[15] = f(d.inj3);
        v[16] = f(d.inj4);
        v[17] = f(d.averageMs);
        v[18] = f(d.ign1Deg);
        v[19] = f(d.ign2Deg);
        v[20] = f(d.ign3Deg);
        v[21] = f(d.ign4Deg);
        v[22] = f(d.secondaryTpsPct);
        v[23] = d.status45;
        v[24] = d.coolingFanRaw;
        v[25] = d.exhaustValveRaw;
        v[26] = d.clutchStarterRaw;
        v[27] = d.neutralRaw;

        return new Sample(timeMs, v);
    }

    private float f(double value) {
        return Double.isFinite(value) ? (float)value : Float.NaN;
    }

    private void refresh(boolean force) {
        if (root.getVisibility() != View.VISIBLE && !force) return;

        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastRefreshMs < 500L) return;
        lastRefreshMs = now;

        if (history.isEmpty()) {
            for (RowViews rv : rowViews) {
                rv.stats.setText("Current —   Min —   Max —");
                rv.chart.setSeries(
                        history,
                        rv.metricIndex,
                        0,
                        1,
                        Double.NaN,
                        Double.NaN,
                        isAuto(rv.metricIndex),
                        METRICS[rv.metricIndex].fixedMin,
                        METRICS[rv.metricIndex].fixedMax
                );
            }
            return;
        }

        long end = history.get(history.size() - 1).timeMs;
        long start = windowMs < 0
                ? history.get(0).timeMs
                : Math.max(history.get(0).timeMs, end - windowMs);

        double[] min = new double[METRICS.length];
        double[] max = new double[METRICS.length];
        double[] current = new double[METRICS.length];
        Arrays.fill(min, Double.POSITIVE_INFINITY);
        Arrays.fill(max, Double.NEGATIVE_INFINITY);
        Arrays.fill(current, Double.NaN);

        for (int i = history.size() - 1; i >= 0; i--) {
            Sample s = history.get(i);
            if (s.timeMs < start) break;

            for (int m = 0; m < METRICS.length; m++) {
                double value = s.values[m];
                if (!Double.isFinite(value)) continue;

                if (!Double.isFinite(current[m])) current[m] = value;
                if (value < min[m]) min[m] = value;
                if (value > max[m]) max[m] = value;
            }
        }

        for (RowViews rv : rowViews) {
            int m = rv.metricIndex;
            Metric metric = METRICS[m];

            double mn = min[m] == Double.POSITIVE_INFINITY
                    ? Double.NaN
                    : min[m];
            double mx = max[m] == Double.NEGATIVE_INFINITY
                    ? Double.NaN
                    : max[m];

            rv.stats.setText(
                    "Current " + format(metric, current[m]) +
                    "   Min " + format(metric, mn) +
                    "   Max " + format(metric, mx)
            );

            boolean auto = isAuto(m);
            rv.scale.setText(auto ? "AUTO" : "FIX");

            rv.chart.setSeries(
                    history,
                    m,
                    start,
                    end,
                    mn,
                    mx,
                    auto,
                    metric.fixedMin,
                    metric.fixedMax
            );
        }
    }

    private String format(Metric metric, double value) {
        if (!Double.isFinite(value)) return "—";

        String pattern = "%." + metric.decimals + "f";
        String number = String.format(Locale.UK, pattern, value);

        return metric.unit == null || metric.unit.isEmpty()
                ? number
                : number + " " + metric.unit;
    }

    private Button windowButton(String label, long durationMs) {
        Button b = new Button(activity);
        b.setText(label);
        b.setTextSize(11);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(8), 0, dp(8), 0);

        b.setOnClickListener(v -> {
            windowMs = durationMs;
            updateWindowButtons();
            refresh(true);
        });

        return b;
    }

    private void updateWindowButtons() {
        styleWindowButton(b10, windowMs == 10_000L);
        styleWindowButton(b60, windowMs == 60_000L);
        styleWindowButton(b300, windowMs == 300_000L);
        styleWindowButton(bMax, windowMs < 0);
    }

    private void styleWindowButton(Button b, boolean selected) {
        b.setTypeface(
                null,
                selected ? Typeface.BOLD : Typeface.NORMAL
        );
        b.setEnabled(!selected);
    }

    private void rebuildRows() {
        rows.removeAllViews();
        rowViews.clear();

        for (int position = 0; position < order.size(); position++) {
            int metricIndex = order.get(position);
            Metric metric = METRICS[metricIndex];

            LinearLayout holder = new LinearLayout(activity);
            holder.setOrientation(LinearLayout.VERTICAL);
            holder.setPadding(0, dp(5), 0, dp(8));

            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            Button up = tinyButton("↑");
            Button down = tinyButton("↓");

            final int metricForButtons = metricIndex;

            up.setOnClickListener(v -> move(metricForButtons, -1));
            down.setOnClickListener(v -> move(metricForButtons, +1));

            TextView name = text(metric.name);
            name.setTypeface(null, Typeface.BOLD);
            name.setTextSize(13);

            Button scale = tinyButton(
                    isAuto(metricIndex) ? "AUTO" : "FIX"
            );
            scale.setTextSize(10);
            scale.setOnClickListener(v -> {
                boolean next = !isAuto(metricForButtons);
                prefs.edit()
                        .putBoolean("diag_auto_" + METRICS[metricForButtons].id, next)
                        .apply();
                refresh(true);
            });

            header.addView(up);
            header.addView(down);
            header.addView(name, new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
            ));
            header.addView(scale);

            holder.addView(header);

            TextView stats = text("Current —   Min —   Max —");
            stats.setTextSize(11);
            stats.setGravity(Gravity.RIGHT);
            holder.addView(stats);

            SensorChartView chart = new SensorChartView(activity);
            holder.addView(chart, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(135)
            ));

            rows.addView(holder);
            rowViews.add(new RowViews(metricIndex, stats, scale, chart));
        }

        refresh(true);
    }

    private Button tinyButton(String label) {
        Button b = new Button(activity);
        b.setText(label);
        b.setTextSize(12);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(7), 0, dp(7), 0);
        return b;
    }

    private TextView text(String value) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextColor(Color.rgb(40, 40, 40));
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private void move(int metricIndex, int delta) {
        int from = order.indexOf(metricIndex);
        if (from < 0) return;

        int to = from + delta;
        if (to < 0 || to >= order.size()) return;

        int scrollY = hostScroll == null ? 0 : hostScroll.getScrollY();

        Collections.swap(order, from, to);
        saveOrder();
        rebuildRows();

        if (hostScroll != null) {
            hostScroll.post(() -> hostScroll.scrollTo(0, scrollY));
        }
    }

    private boolean isAuto(int metricIndex) {
        return prefs.getBoolean(
                "diag_auto_" + METRICS[metricIndex].id,
                false
        );
    }

    private void loadOrder() {
        order.clear();

        String saved = prefs.getString("diag_chart_order", "");

        if (saved != null && !saved.trim().isEmpty()) {
            String[] parts = saved.split(",");

            for (String id : parts) {
                for (int i = 0; i < METRICS.length; i++) {
                    if (METRICS[i].id.equals(id) && !order.contains(i)) {
                        order.add(i);
                        break;
                    }
                }
            }
        }

        for (int i = 0; i < METRICS.length; i++) {
            if (!order.contains(i)) order.add(i);
        }
    }

    private void saveOrder() {
        StringBuilder s = new StringBuilder();

        for (int i = 0; i < order.size(); i++) {
            if (i > 0) s.append(',');
            s.append(METRICS[order.get(i)].id);
        }

        prefs.edit()
                .putString("diag_chart_order", s.toString())
                .apply();
    }

    private int dp(int value) {
        return Math.round(
                value * activity.getResources().getDisplayMetrics().density
        );
    }
}
