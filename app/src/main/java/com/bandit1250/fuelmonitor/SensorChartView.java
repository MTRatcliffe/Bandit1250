package com.bandit1250.fuelmonitor;

import android.content.Context;
import android.graphics.*;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * Lightweight single-sensor line chart used by DiagnosticsPanel.
 *
 * Every chart uses the same supplied start/end timestamps, so vertical time
 * grid lines line up across the complete stack. The normal 10 s / 1 min /
 * 5 min windows are plotted without thinning. Very long MAX sessions are
 * thinned only for drawing performance; min/max statistics remain calculated
 * from every sample by DiagnosticsPanel.
 */
public final class SensorChartView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labels = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<DiagnosticsPanel.Sample> samples;
    private int metricIndex;
    private long startMs;
    private long endMs;
    private double dataMin = Double.NaN;
    private double dataMax = Double.NaN;
    private double fixedMin = 0.0;
    private double fixedMax = 1.0;
    private boolean autoScale = false;

    public SensorChartView(Context context) {
        super(context);

        grid.setColor(Color.rgb(220, 220, 220));
        grid.setStrokeWidth(dp(1));

        line.setColor(Color.rgb(35, 105, 180));
        line.setStrokeWidth(dp(2));
        line.setStyle(Paint.Style.STROKE);

        border.setColor(Color.rgb(120, 120, 120));
        border.setStrokeWidth(dp(1));
        border.setStyle(Paint.Style.STROKE);

        labels.setColor(Color.DKGRAY);
        labels.setTextSize(dp(10));

        setBackgroundColor(Color.WHITE);
    }

    public void setSeries(
            List<DiagnosticsPanel.Sample> samples,
            int metricIndex,
            long startMs,
            long endMs,
            double dataMin,
            double dataMax,
            boolean autoScale,
            double fixedMin,
            double fixedMax
    ) {
        this.samples = samples;
        this.metricIndex = metricIndex;
        this.startMs = startMs;
        this.endMs = endMs;
        this.dataMin = dataMin;
        this.dataMax = dataMax;
        this.autoScale = autoScale;
        this.fixedMin = fixedMin;
        this.fixedMax = fixedMax;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);

        float left = dp(42);
        float right = getWidth() - dp(8);
        float top = dp(7);
        float bottom = getHeight() - dp(19);

        if (right <= left || bottom <= top) return;

        c.drawRect(left, top, right, bottom, border);

        // Identical x-grid fractions on every row.
        for (int i = 1; i < 4; i++) {
            float x = left + (right - left) * i / 4f;
            c.drawLine(x, top, x, bottom, grid);
        }
        for (int i = 1; i < 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            c.drawLine(left, y, right, y, grid);
        }

        if (samples == null || samples.isEmpty() ||
                !Double.isFinite(dataMin) || !Double.isFinite(dataMax)) {
            c.drawText("waiting", left + dp(8), top + dp(18), labels);
            return;
        }

        double yMin;
        double yMax;

        if (autoScale) {
            yMin = dataMin;
            yMax = dataMax;

            if (Math.abs(yMax - yMin) < 0.000001) {
                double pad = Math.max(1.0, Math.abs(yMax) * 0.10);
                yMin -= pad;
                yMax += pad;
            } else {
                double pad = (yMax - yMin) * 0.08;
                yMin -= pad;
                yMax += pad;
            }
        } else {
            yMin = fixedMin;
            yMax = fixedMax;
        }

        if (!(yMax > yMin)) {
            yMin = dataMin - 1.0;
            yMax = dataMax + 1.0;
        }

        c.drawText(shortNumber(yMax), dp(2), top + dp(10), labels);
        c.drawText(shortNumber(yMin), dp(2), bottom, labels);

        long plotStart = startMs;
        long plotEnd = Math.max(endMs, plotStart + 1L);

        // Locate first point in the window.
        int first = 0;
        while (first < samples.size() &&
                samples.get(first).timeMs < plotStart) {
            first++;
        }

        int count = samples.size() - first;

        // Keep very long MAX sessions responsive. Ordinary 5 minute and shorter
        // windows are normally far below this threshold at ~4 SDS frames/sec.
        int step = Math.max(1, (int)Math.ceil(count / 900.0));

        Path path = new Path();
        boolean started = false;

        for (int i = first; i < samples.size(); i += step) {
            DiagnosticsPanel.Sample s = samples.get(i);

            if (s.timeMs > plotEnd) break;
            if (metricIndex < 0 || metricIndex >= s.values.length) continue;

            double v = s.values[metricIndex];
            if (!Double.isFinite(v)) {
                started = false;
                continue;
            }

            float x = left +
                    (float)((s.timeMs - plotStart) / (double)(plotEnd - plotStart)) *
                    (right - left);

            float y = bottom -
                    (float)((v - yMin) / (yMax - yMin)) *
                    (bottom - top);

            y = Math.max(top, Math.min(bottom, y));

            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else {
                path.lineTo(x, y);
            }
        }

        c.drawPath(path, line);

        String span = formatSpan(plotEnd - plotStart);
        c.drawText("-" + span, left, getHeight() - dp(3), labels);

        String now = "now";
        float nw = labels.measureText(now);
        c.drawText(now, right - nw, getHeight() - dp(3), labels);
    }

    private String formatSpan(long ms) {
        double seconds = ms / 1000.0;
        if (seconds < 60.0) {
            return String.format(Locale.UK, "%.0fs", seconds);
        }
        return String.format(Locale.UK, "%.1fm", seconds / 60.0);
    }

    private String shortNumber(double value) {
        double a = Math.abs(value);
        if (a >= 1000.0) return String.format(Locale.UK, "%.0f", value);
        if (a >= 100.0) return String.format(Locale.UK, "%.0f", value);
        if (a >= 10.0) return String.format(Locale.UK, "%.1f", value);
        return String.format(Locale.UK, "%.2f", value);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
