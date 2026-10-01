package com.bandit1250.fuelmonitor;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.*;
import android.os.*;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int REQ_PERMS = 1001;
    private static final double UK_LITRES_PER_GALLON = 4.54609;
    private static final double MIN_MPG_SPEED_MPH = 3.0;
    private static final long MPG_WINDOW_MS = 10_000L;

    private static final int STATUS_RED = Color.rgb(190, 0, 0);
    private static final int STATUS_YELLOW = Color.rgb(180, 130, 0);
    private static final int STATUS_GREEN = Color.rgb(0, 130, 0);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final ArrayDeque<MpgSample> mpgSamples = new ArrayDeque<>();

    private BluetoothAdapter adapter;
    private Elm327Client elm;
    private SuzukiSds sds;
    private volatile boolean polling = false;

    private LocationManager locationManager;
    private LocationListener locationListener;
    private volatile double gpsSpeedMph = Double.NaN;
    private volatile double gpsAccuracyM = Double.NaN;
    private volatile double lastEstimatedLph = Double.NaN;

    private Spinner spinner;
    private TextView infoText;
    private TextView status;
    private TextView instantMpg;
    private TextView avgMpg;
    private TextView fuelRate;
    private TextView gpsMeta;
    private TextView decoded;
    private TextView raw;
    private TextView log;

    private EditText flow;
    private EditText latency;
    private EditText cal;

    private Button connect;

    private static final class MpgSample {
        final long timeMs;
        final double speedMph;
        final double lph;

        MpgSample(long timeMs, double speedMph, double lph) {
            this.timeMs = timeMs;
            this.speedMph = speedMph;
            this.lph = lph;
        }
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();

        BluetoothManager bm = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();

        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        createLocationListener();

        if (adapter == null) {
            setBluetoothStatusRed("Bluetooth: unavailable");
            connect.setEnabled(false);
        }

        ensurePermissions();
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 12, 20, 36);
        sv.addView(root);

        // ----- Compact single-line title + info button -----
        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Bandit Monitor V0.7.0");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        Button infoButton = new Button(this);
        infoButton.setText("ⓘ");
        infoButton.setTextSize(16);
        infoButton.setMinWidth(0);
        infoButton.setMinimumWidth(0);
        infoButton.setMinHeight(0);
        infoButton.setMinimumHeight(0);
        infoButton.setPadding(10, 0, 10, 0);
        titleRow.addView(infoButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        root.addView(titleRow);

        infoText = text(
                "2008 GSF1250SA • ELM327 Bluetooth • Suzuki SDS 21 08\n" +
                "Connect performs Bluetooth connection, SDS initialisation and starts polling automatically.\n" +
                "GPS speed is used for MPG. Fuel flow remains provisional until injector flow/latency is calibrated.\n" +
                "Fields labelled 'est.' use published Suzuki scaling that has not yet been independently verified on this exact ECU."
        );
        infoText.setVisibility(View.GONE);
        root.addView(infoText);

        infoButton.setOnClickListener(v ->
                infoText.setVisibility(
                        infoText.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
                )
        );

        // ----- Device and the only normal workflow buttons -----
        spinner = new Spinner(this);
        root.addView(spinner);

        LinearLayout buttonRow = row();

        Button refresh = new Button(this);
        refresh.setText("REFRESH");
        refresh.setOnClickListener(v -> ensurePermissions());

        connect = new Button(this);
        connect.setText("CONNECT");
        connect.setOnClickListener(v -> connectAndRun());

        buttonRow.addView(refresh, weight());
        buttonRow.addView(connect, weight());
        root.addView(buttonRow);

        // ----- Colour-coded connection overview -----
        status = text("Bluetooth: not connected");
        status.setTextSize(16);
        status.setTypeface(null, Typeface.BOLD);
        root.addView(status);
        setBluetoothStatusRed("Bluetooth: not connected");

        // ----- Big, glanceable fuel economy panel -----
        LinearLayout mpgPanel = new LinearLayout(this);
        mpgPanel.setOrientation(LinearLayout.VERTICAL);
        mpgPanel.setPadding(8, 12, 8, 12);
        mpgPanel.setGravity(Gravity.CENTER_HORIZONTAL);

        instantMpg = new TextView(this);
        instantMpg.setText("Instant MPG  —");
        instantMpg.setTextSize(30);
        instantMpg.setTypeface(null, Typeface.BOLD);
        instantMpg.setGravity(Gravity.CENTER_HORIZONTAL);
        mpgPanel.addView(instantMpg);

        avgMpg = new TextView(this);
        avgMpg.setText("10 s MPG  —");
        avgMpg.setTextSize(23);
        avgMpg.setTypeface(null, Typeface.BOLD);
        avgMpg.setGravity(Gravity.CENTER_HORIZONTAL);
        mpgPanel.addView(avgMpg);

        fuelRate = new TextView(this);
        fuelRate.setText("Fuel  — L/h");
        fuelRate.setTextSize(22);
        fuelRate.setTypeface(null, Typeface.BOLD);
        fuelRate.setGravity(Gravity.CENTER_HORIZONTAL);
        mpgPanel.addView(fuelRate);

        gpsMeta = new TextView(this);
        gpsMeta.setText("GPS: waiting for fix");
        gpsMeta.setTextSize(14);
        gpsMeta.setGravity(Gravity.CENTER_HORIZONTAL);
        mpgPanel.addView(gpsMeta);

        root.addView(mpgPanel);

        // ----- Fuel model settings -----
        TextView modelHead = text("Fuel model");
        modelHead.setTextSize(16);
        modelHead.setTypeface(null, Typeface.BOLD);
        root.addView(modelHead);

        TextView flowLabel = text("Injector static flow (cc/min @ ~3 bar)");
        root.addView(flowLabel);
        flow = number("220.0");
        root.addView(flow);

        TextView latencyLabel = text("Net injector latency (ms)");
        root.addView(latencyLabel);
        latency = number("0.600");
        root.addView(latency);

        TextView calLabel = text("Tank calibration factor");
        root.addView(calLabel);
        cal = number("1.000");
        root.addView(cal);

        // ----- SZ Viewer-style decoded live block -----
        TextView decodedHead = text("Live ECU data");
        decodedHead.setTextSize(18);
        decodedHead.setTypeface(null, Typeface.BOLD);
        root.addView(decodedHead);

        decoded = text("Waiting for SDS data…");
        decoded.setTextSize(14);
        decoded.setTypeface(Typeface.MONOSPACE);
        decoded.setTextIsSelectable(true);
        root.addView(decoded);

        TextView rawHead = text("Raw 2108 response");
        rawHead.setTextSize(16);
        rawHead.setTypeface(null, Typeface.BOLD);
        root.addView(rawHead);

        raw = text("—");
        raw.setTextSize(12);
        raw.setTypeface(Typeface.MONOSPACE);
        raw.setTextIsSelectable(true);
        root.addView(raw);

        TextView logHead = text("ELM / SDS log");
        logHead.setTextSize(16);
        logHead.setTypeface(null, Typeface.BOLD);
        root.addView(logHead);

        log = text("");
        log.setTextSize(11);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        root.addView(log);

        setContentView(sv);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, 5, 0, 5);
        return t;
    }

    private EditText number(String s) {
        EditText e = new EditText(this);
        e.setText(s);
        e.setInputType(
                InputType.TYPE_CLASS_NUMBER |
                InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
        return e;
    }

    private void setBluetoothStatusRed(String message) {
        status.setText(message);
        status.setTextColor(STATUS_RED);
    }

    private void setBluetoothStatusYellow(String message) {
        status.setText(message);
        status.setTextColor(STATUS_YELLOW);
    }

    private void setBluetoothStatusGreen(String message) {
        status.setText(message);
        status.setTextColor(STATUS_GREEN);
    }

    private void ensurePermissions() {
        ArrayList<String> missing = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        } else {
            afterPermissions();
        }
    }

    @Override public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grants
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQ_PERMS) afterPermissions();
    }

    private void afterPermissions() {
        if (adapter != null) refreshPaired();

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startGps();
        } else {
            gpsMeta.setText("GPS permission denied");
        }
    }

    private void refreshPaired() {
        try {
            devices.clear();

            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            List<String> names = new ArrayList<>();

            for (BluetoothDevice d : bonded) {
                devices.add(d);
                names.add(
                        (d.getName() == null ? "Unknown" : d.getName()) +
                        "  " + d.getAddress()
                );
            }

            spinner.setAdapter(new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    names
            ));

            if (!polling) {
                setBluetoothStatusRed("Bluetooth: not connected");
            }
        } catch (SecurityException e) {
            setBluetoothStatusRed("Bluetooth: permission required");
        }
    }

    private void createLocationListener() {
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (location == null) return;

                gpsAccuracyM = location.hasAccuracy()
                        ? location.getAccuracy()
                        : Double.NaN;

                gpsSpeedMph = location.hasSpeed()
                        ? location.getSpeed() * 2.2369362920544
                        : Double.NaN;

                updateMpgPanel();
            }

            @Override public void onProviderEnabled(String provider) {
                updateMpgPanel();
            }

            @Override public void onProviderDisabled(String provider) {
                if (LocationManager.GPS_PROVIDER.equals(provider)) {
                    gpsSpeedMph = Double.NaN;
                    gpsMeta.setText("GPS disabled");
                    updateMpgPanel();
                }
            }

            @Override public void onStatusChanged(
                    String provider,
                    int statusValue,
                    Bundle extras
            ) {
                // Compatibility callback for older Android API levels.
            }
        };
    }

    private void startGps() {
        if (locationManager == null) {
            gpsMeta.setText("GPS unavailable");
            return;
        }

        try {
            locationManager.removeUpdates(locationListener);

            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gpsMeta.setText("GPS disabled");
                return;
            }

            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0.5f,
                    locationListener
            );

            gpsMeta.setText("GPS: waiting for fix");
        } catch (SecurityException e) {
            gpsMeta.setText("GPS permission required");
        }
    }

    /**
     * CONNECT is deliberately a one-button workflow:
     * Bluetooth RFCOMM -> Suzuki SDS init -> first 2108 -> continuous polling.
     */
    private void connectAndRun() {
        if (devices.isEmpty()) {
            toast("Pair the ELM327 in Android Bluetooth settings first.");
            return;
        }

        int pos = spinner.getSelectedItemPosition();
        if (pos < 0) pos = 0;

        BluetoothDevice device = devices.get(pos);

        // If reconnecting, break the existing blocking/polling session first.
        polling = false;
        if (elm != null) elm.close();

        connect.setEnabled(false);
        connect.setText("CONNECTING…");
        setBluetoothStatusYellow("Bluetooth: connecting…");

        mpgSamples.clear();

        io.execute(() -> {
            boolean bluetoothConnected = false;

            try {
                elm = new Elm327Client(device);
                elm.connect();
                bluetoothConnected = true;

                ui.post(() ->
                        setBluetoothStatusYellow(
                                "Bluetooth: connected • starting SDS…"
                        )
                );

                sds = new SuzukiSds(elm, this::appendLog);
                sds.initialise();

                String first = sds.read2108();

                polling = true;

                ui.post(() -> {
                    raw.setText(first);
                    decodeAndShow(first);
                    updateConnectedStatus();
                    connect.setText("RECONNECT");
                    connect.setEnabled(true);
                });

                pollLoop();

            } catch (Exception e) {
                polling = false;

                final boolean btWasConnected = bluetoothConnected;
                final String message = e.getMessage();

                ui.post(() -> {
                    if (btWasConnected) {
                        setBluetoothStatusYellow(
                                "Bluetooth: connected • SDS not communicating"
                        );
                    } else {
                        setBluetoothStatusRed(
                                "Bluetooth: connection failed"
                        );
                    }

                    appendLog("CONNECT/INIT ERROR: " + message);
                    connect.setText("CONNECT");
                    connect.setEnabled(true);
                });
            }
        });
    }

    private void updateConnectedStatus() {
        if (sds == null) {
            setBluetoothStatusYellow(
                    "Bluetooth: connected • SDS not communicating"
            );
            return;
        }

        setBluetoothStatusGreen(
                "Bluetooth: connected + communicating" +
                " • misses " + sds.getMissedFrames() +
                " • recoveries " + sds.getRecoveryCount()
        );
    }

    private void pollLoop() {
        while (polling && sds != null) {
            try {
                String r = sds.read2108();

                ui.post(() -> {
                    raw.setText(r);
                    decodeAndShow(r);
                    updateConnectedStatus();
                });

                try {
                    Thread.sleep(120);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    polling = false;
                }

            } catch (Exception e) {
                boolean wasPolling = polling;
                polling = false;

                if (wasPolling) {
                    ui.post(() -> {
                        setBluetoothStatusYellow(
                                "Bluetooth: connected • SDS not communicating"
                        );
                        appendLog("POLLING STOPPED: " + e.getMessage());
                        connect.setText("RECONNECT");
                        connect.setEnabled(true);
                    });
                }
            }
        }
    }

    private void decodeAndShow(String response) {
        try {
            BanditLiveData d = BanditDecoder.decode(response);

            double q = parse(flow.getText().toString(), 220.0);
            double netLatency = parse(latency.getText().toString(), 0.600);
            double factor = parse(cal.getText().toString(), 1.0);

            double estimatedLph = FuelCalculator.estimatedLitresPerHour(
                    d.rpm,
                    d.averageMs,
                    q,
                    netLatency,
                    factor
            );

            lastEstimatedLph = estimatedLph;

            decoded.setText(
                    BanditDecoder.formatAll(d) +
                    String.format(
                            Locale.UK,
                            "\n\nFuel model\n" +
                            "Estimated flowing PW  %.3f ms\n" +
                            "Estimated fuel        %.3f L/h",
                            FuelCalculator.effectivePulseMs(
                                    d.averageMs,
                                    netLatency
                            ),
                            estimatedLph
                    )
            );

            updateMpgPanel();

        } catch (Exception e) {
            lastEstimatedLph = Double.NaN;

            decoded.setText(
                    "Decoder error: " + e.getMessage() +
                    "\nRaw frame remains available below."
            );

            updateMpgPanel();
        }
    }

    private void updateMpgPanel() {
        double instant = calculateInstantMpg();

        if (Double.isFinite(instant)) {
            instantMpg.setText(
                    String.format(Locale.UK, "Instant MPG  %.1f", instant)
            );
        } else {
            instantMpg.setText("Instant MPG  —");
        }

        if (Double.isFinite(lastEstimatedLph)) {
            fuelRate.setText(
                    String.format(Locale.UK, "Fuel  %.3f L/h", lastEstimatedLph)
            );
        } else {
            fuelRate.setText("Fuel  — L/h");
        }

        if (Double.isFinite(gpsSpeedMph) &&
                Double.isFinite(lastEstimatedLph)) {
            addMpgSample(gpsSpeedMph, lastEstimatedLph);
        } else {
            pruneMpgSamples();
        }

        double rolling = calculateRolling10sMpg();

        if (Double.isFinite(rolling)) {
            avgMpg.setText(
                    String.format(Locale.UK, "10 s MPG  %.1f", rolling)
            );
        } else {
            avgMpg.setText("10 s MPG  —");
        }

        String speed = Double.isNaN(gpsSpeedMph)
                ? "—"
                : String.format(Locale.UK, "%.1f", gpsSpeedMph);

        String accuracy = Double.isNaN(gpsAccuracyM)
                ? "—"
                : String.format(Locale.UK, "%.0f", gpsAccuracyM);

        gpsMeta.setText(
                "GPS " + speed + " mph  •  ±" + accuracy + " m"
        );
    }

    private double calculateInstantMpg() {
        if (!Double.isFinite(gpsSpeedMph) ||
                gpsSpeedMph < MIN_MPG_SPEED_MPH ||
                !Double.isFinite(lastEstimatedLph) ||
                lastEstimatedLph <= 0.05) {
            return Double.NaN;
        }

        double mpg =
                gpsSpeedMph * UK_LITRES_PER_GALLON / lastEstimatedLph;

        if (!Double.isFinite(mpg) || mpg < 0.0 || mpg > 9999.0) {
            return Double.NaN;
        }

        return mpg;
    }

    private void addMpgSample(double speedMph, double lph) {
        long now = SystemClock.elapsedRealtime();

        mpgSamples.addLast(new MpgSample(
                now,
                Math.max(0.0, speedMph),
                Math.max(0.0, lph)
        ));

        pruneMpgSamples();
    }

    private void pruneMpgSamples() {
        long cutoff = SystemClock.elapsedRealtime() - MPG_WINDOW_MS - 1000L;

        while (mpgSamples.size() > 2 &&
                mpgSamples.peekFirst().timeMs < cutoff) {
            mpgSamples.removeFirst();
        }
    }

    /**
     * Physically meaningful 10 s average:
     * integrate distance and fuel over the window, then divide them.
     * This is better than arithmetic-averaging instantaneous MPG.
     */
    private double calculateRolling10sMpg() {
        if (mpgSamples.size() < 2) return Double.NaN;

        MpgSample previous = null;
        double miles = 0.0;
        double litres = 0.0;
        long firstTime = -1;
        long lastTime = -1;

        for (MpgSample current : mpgSamples) {
            if (previous != null) {
                long dtMs = current.timeMs - previous.timeMs;

                if (dtMs > 0) {
                    double hours = dtMs / 3_600_000.0;

                    double avgSpeed =
                            (previous.speedMph + current.speedMph) / 2.0;

                    double avgLph =
                            (previous.lph + current.lph) / 2.0;

                    miles += avgSpeed * hours;
                    litres += avgLph * hours;
                }
            } else {
                firstTime = current.timeMs;
            }

            lastTime = current.timeMs;
            previous = current;
        }

        // Don't present a "10 second" number from a tiny initial sample.
        if (firstTime < 0 || lastTime - firstTime < 2000L) {
            return Double.NaN;
        }

        // At/near standstill the L/h readout is the useful metric.
        if (miles < 0.001 || litres <= 0.000001) {
            return Double.NaN;
        }

        double mpg = miles / (litres / UK_LITRES_PER_GALLON);

        return Double.isFinite(mpg) && mpg < 9999.0
                ? mpg
                : Double.NaN;
    }

    private void appendLog(String s) {
        ui.post(() -> {
            String old = log.getText().toString();

            if (old.length() > 12000) {
                old = old.substring(old.length() - 8000);
            }

            log.setText(old + s + "\n");
        });
    }

    private double parse(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        polling = false;

        if (elm != null) elm.close();

        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {}
        }

        io.shutdownNow();
        super.onDestroy();
    }
}
