package com.bandit1250.fuelmonitor;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;

import java.util.*;

public final class HistoryChartsView extends View {
    public static final class Point {
        public final long timeMs;
        public final double mpg;
        public final double rpm;
        public final double lph;
        public final double speedMph;

        public Point(long timeMs, double mpg, double rpm, double lph, double speedMph) {
            this.timeMs = timeMs;
            this.mpg = mpg;
            this.rpm = rpm;
            this.lph = lph;
            this.speedMph = speedMph;
        }
    }

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<Point> points = Collections.emptyList();

    public HistoryChartsView(Context c) { super(c); init(); }
    public HistoryChartsView(Context c, AttributeSet a) { super(c,a); init(); }

    private void init() {
        grid.setColor(Color.rgb(220,220,220));
        grid.setStrokeWidth(dp(1));

        line.setColor(Color.rgb(30,105,180));
        line.setStrokeWidth(dp(2));
        line.setStyle(Paint.Style.STROKE);

        text.setColor(Color.DKGRAY);
        text.setTextSize(dp(12));

        border.setColor(Color.rgb(120,120,120));
        border.setStrokeWidth(dp(1));
        border.setStyle(Paint.Style.STROKE);

        setBackgroundColor(Color.WHITE);
    }

    public void setPoints(List<Point> p) {
        points = p == null ? Collections.emptyList() : new ArrayList<>(p);
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);

        float left = dp(46);
        float right = getWidth() - dp(12);
        float top = dp(12);
        float gap = dp(18);
        float h = (getHeight() - top - dp(12) - gap*3f) / 4f;

        drawChart(c, left, top, right, top+h, "Instant MPG", 0);
        top += h + gap;
        drawChart(c, left, top, right, top+h, "RPM", 1);
        top += h + gap;
        drawChart(c, left, top, right, top+h, "Fuel L/h", 2);
        top += h + gap;
        drawChart(c, left, top, right, top+h, "GPS mph", 3);
    }

    private void drawChart(Canvas c, float l, float t, float r, float b, String title, int metric) {
        c.drawRect(l,t,r,b,border);

        for (int i=1;i<4;i++) {
            float y=t+(b-t)*i/4f;
            c.drawLine(l,y,r,y,grid);
        }

        text.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText(title, l, t-dp(3), text);
        text.setTypeface(Typeface.DEFAULT);

        if (points.size() < 2) {
            c.drawText("waiting for data", l+dp(8), t+dp(22), text);
            return;
        }

        long minTime = points.get(0).timeMs;
        long maxTime = points.get(points.size()-1).timeMs;
        if (maxTime <= minTime) maxTime = minTime + 1;

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (Point p : points) {
            double v=value(p,metric);
            if (!Double.isFinite(v)) continue;
            min=Math.min(min,v);
            max=Math.max(max,v);
        }

        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            c.drawText("no valid samples", l+dp(8), t+dp(22), text);
            return;
        }

        // Keep zero as a useful baseline where it makes sense.
        if (metric==1 || metric==2 || metric==3) min=Math.min(0.0,min);

        if (Math.abs(max-min) < 0.0001) {
            double pad = Math.max(1.0, Math.abs(max)*0.1);
            min -= pad;
            max += pad;
        } else {
            double pad=(max-min)*0.08;
            min-=pad;
            max+=pad;
        }

        c.drawText(format(max,metric), dp(2), t+dp(12), text);
        c.drawText(format(min,metric), dp(2), b, text);

        Path path=new Path();
        boolean started=false;

        for (Point p : points) {
            double v=value(p,metric);
            if (!Double.isFinite(v)) {
                started=false;
                continue;
            }

            float x = l + (float)((p.timeMs-minTime)/(double)(maxTime-minTime))*(r-l);
            float y = b - (float)((v-min)/(max-min))*(b-t);

            if (!started) {
                path.moveTo(x,y);
                started=true;
            } else {
                path.lineTo(x,y);
            }
        }

        c.drawPath(path,line);

        double seconds=(maxTime-minTime)/1000.0;
        String span=seconds<60
                ? String.format(Locale.UK,"%.0f s",seconds)
                : String.format(Locale.UK,"%.1f min",seconds/60.0);
        float w=text.measureText(span);
        c.drawText(span,r-w,b-dp(4),text);
    }

    private static double value(Point p,int metric) {
        switch(metric) {
            case 0: return p.mpg;
            case 1: return p.rpm;
            case 2: return p.lph;
            default: return p.speedMph;
        }
    }

    private static String format(double v,int metric) {
        if (metric==1) return String.format(Locale.UK,"%.0f",v);
        return String.format(Locale.UK,"%.1f",v);
    }

    private float dp(float v) {
        return v*getResources().getDisplayMetrics().density;
    }
}
