package com.bandit1250.fuelmonitor;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import android.text.InputType;
import android.widget.*;

import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int REQ_PERMS = 1001;
    private static final double UK_LITRES_PER_GALLON = 4.54609;
    private static final double MIN_MPG_SPEED_MPH = 3.0;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<BluetoothDevice> devices = new ArrayList<>();

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
    private TextView status, ecu, comms, gps, live, raw, log;
    private EditText flow, latency, cal;
    private Button connect, init, poll;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();

        BluetoothManager bm = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();

        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        createLocationListener();

        if (adapter == null) {
            status.setText("Bluetooth unavailable");
            connect.setEnabled(false);
        }

        ensurePermissions();
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,24,24,40);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("Bandit Fuel Monitor — v0.5.0-test (build 5)");
        title.setTextSize(24);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("2008 GSF1250SA • ELM327 Bluetooth • Suzuki SDS 2108\nGPS speed is combined with estimated fuel flow for basic instantaneous UK MPG.");
        root.addView(note);

        spinner = new Spinner(this);
        root.addView(spinner);

        LinearLayout r1 = row();
        Button refresh = new Button(this);
        refresh.setText("Refresh paired");
        refresh.setOnClickListener(v -> ensurePermissions());
        connect = new Button(this);
        connect.setText("Connect ELM");
        connect.setOnClickListener(v -> connect());
        r1.addView(refresh, weight());
        r1.addView(connect, weight());
        root.addView(r1);

        LinearLayout r2 = row();
        init = new Button(this);
        init.setText("Initialise SDS");
        init.setEnabled(false);
        init.setOnClickListener(v -> initialise());

        poll = new Button(this);
        poll.setText("Start 2108");
        poll.setEnabled(false);
        poll.setOnClickListener(v -> togglePoll());

        r2.addView(init, weight());
        r2.addView(poll, weight());
        root.addView(r2);

        status = text("Bluetooth: disconnected");
        ecu = text("Suzuki ECU: not initialised");
        comms = text("Comms: misses 0 • recoveries 0");
        gps = text("GPS: waiting for permission/fix • Speed — mph • Est. UK MPG —");

        root.addView(status);
        root.addView(ecu);
        root.addView(comms);
        root.addView(gps);

        TextView modelHead = text("Fuel model (editable provisional assumptions)");
        modelHead.setTextSize(17);
        root.addView(modelHead);

        TextView flowLabel = text("Injector static flow (cc/min @ ~3 bar)");
        root.addView(flowLabel);
        flow = number("220.0");
        root.addView(flow);

        TextView latencyLabel = text("Net injector latency (ms: opening delay minus closing-flow tail)");
        root.addView(latencyLabel);
        latency = number("0.600");
        root.addView(latency);

        TextView calLabel = text("Tank calibration factor");
        root.addView(calLabel);
        cal = number("1.000");
        root.addView(cal);

        live = text(
                "RPM: —\n" +
                "Inj1: — ms\n" +
                "Inj2: — ms\n" +
                "Inj3: — ms\n" +
                "Inj4: — ms\n" +
                "Average commanded PW: — ms\n" +
                "Estimated flowing PW: — ms\n" +
                "PW-only fuel: — L/h\n" +
                "Estimated fuel: — L/h"
        );
        live.setTextSize(19);
        root.addView(live);

        TextView rh = text("Raw 2108 response");
        rh.setTextSize(18);
        root.addView(rh);

        raw = text("—");
        raw.setTextIsSelectable(true);
        root.addView(raw);

        TextView lh = text("ELM / SDS log");
        lh.setTextSize(18);
        root.addView(lh);

        log = text("");
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
        t.setPadding(0,8,0,8);
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

    private void ensurePermissions() {
        ArrayList<String> missing = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        } else {
            afterPermissions();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQ_PERMS) afterPermissions();
    }

    private void afterPermissions() {
        if (adapter != null) refreshPaired();

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startGps();
        } else {
            gps.setText("GPS permission denied • Speed — mph • Est. UK MPG —");
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
                        "\n" + d.getAddress()
                );
            }

            spinner.setAdapter(new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    names
            ));

            status.setText("Paired Bluetooth devices: " + devices.size());
        } catch (SecurityException e) {
            status.setText("Bluetooth permission required");
        }
    }

    private void createLocationListener() {
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (location == null) return;

                gpsAccuracyM = location.hasAccuracy() ? location.getAccuracy() : Double.NaN;

                if (location.hasSpeed()) {
                    gpsSpeedMph = location.getSpeed() * 2.2369362920544;
                } else {
                    gpsSpeedMph = Double.NaN;
                }

                updateGpsReadout();
            }

            @Override public void onProviderEnabled(String provider) {
                updateGpsReadout();
            }

            @Override public void onProviderDisabled(String provider) {
                if (LocationManager.GPS_PROVIDER.equals(provider)) {
                    gpsSpeedMph = Double.NaN;
                    gps.setText("GPS disabled • Speed — mph • Est. UK MPG —");
                }
            }

            @Override public void onStatusChanged(String provider, int statusValue, Bundle extras) {
                // Required on older Android API levels; no special handling needed.
            }
        };
    }

    private void startGps() {
        if (locationManager == null) {
            gps.setText("GPS unavailable • Speed — mph • Est. UK MPG —");
            return;
        }

        try {
            locationManager.removeUpdates(locationListener);

            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gps.setText("GPS disabled • Speed — mph • Est. UK MPG —");
                return;
            }

            // 500 ms / 0.5 m is responsive enough for a basic live MPG display
            // without needing any external location library.
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0.5f,
                    locationListener
            );

            gps.setText("GPS: waiting for fix • Speed — mph • Est. UK MPG —");
        } catch (SecurityException e) {
            gps.setText("GPS permission required • Speed — mph • Est. UK MPG —");
        }
    }

    private void updateGpsReadout() {
        String speedText = Double.isNaN(gpsSpeedMph)
                ? "—"
                : String.format(Locale.UK, "%.1f", gpsSpeedMph);

        String accuracyText = Double.isNaN(gpsAccuracyM)
                ? "—"
                : String.format(Locale.UK, "%.0f", gpsAccuracyM);

        String mpgText = "—";

        if (!Double.isNaN(gpsSpeedMph) &&
                gpsSpeedMph >= MIN_MPG_SPEED_MPH &&
                !Double.isNaN(lastEstimatedLph) &&
                lastEstimatedLph > 0.05) {

            double mpg = gpsSpeedMph * UK_LITRES_PER_GALLON / lastEstimatedLph;

            if (Double.isFinite(mpg) && mpg >= 0.0 && mpg < 9999.0) {
                mpgText = String.format(Locale.UK, "%.1f", mpg);
            }
        }

        gps.setText(
                "GPS: ±" + accuracyText + " m" +
                " • Speed " + speedText + " mph" +
                " • Est. UK MPG " + mpgText
        );
    }

    private void connect() {
        if (devices.isEmpty()) {
            toast("Pair the ELM327 in Android Bluetooth settings first.");
            return;
        }

        int pos = spinner.getSelectedItemPosition();
        if (pos < 0) pos = 0;

        BluetoothDevice d = devices.get(pos);
        connect.setEnabled(false);

        io.execute(() -> {
            try {
                if (elm != null) elm.close();

                elm = new Elm327Client(d);
                elm.connect();

                sds = new SuzukiSds(elm, this::appendLog);

                ui.post(() -> {
                    status.setText("Bluetooth: connected to " + safeName(d));
                    init.setEnabled(true);
                    connect.setEnabled(true);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    status.setText("Connect failed: " + e.getMessage());
                    connect.setEnabled(true);
                });
            }
        });
    }

    private void initialise() {
        if (sds == null) return;

        init.setEnabled(false);

        io.execute(() -> {
            try {
                sds.initialise();
                String first = sds.read2108();

                ui.post(() -> {
                    ecu.setText("Suzuki ECU: SDS initialised");
                    raw.setText(first);
                    poll.setEnabled(true);
                    init.setEnabled(true);
                    decodeAndShow(first);

                    if (sds != null) {
                        comms.setText(
                                "Comms: misses " + sds.getMissedFrames() +
                                " • recoveries " + sds.getRecoveryCount()
                        );
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    ecu.setText("SDS init failed: " + e.getMessage());
                    init.setEnabled(true);
                });
            }
        });
    }

    private void togglePoll() {
        polling = !polling;
        poll.setText(polling ? "Stop 2108" : "Start 2108");
        if (polling) io.execute(this::pollLoop);
    }

    private void pollLoop() {
        while (polling && sds != null) {
            try {
                String r = sds.read2108();

                ui.post(() -> {
                    raw.setText(r);
                    decodeAndShow(r);

                    if (sds != null) {
                        comms.setText(
                                "Comms: misses " + sds.getMissedFrames() +
                                " • recoveries " + sds.getRecoveryCount()
                        );
                    }
                });

                try {
                    Thread.sleep(120);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    polling = false;
                }
            } catch (Exception e) {
                polling = false;

                ui.post(() -> {
                    poll.setText("Start 2108");
                    ecu.setText("Polling stopped: " + e.getMessage());
                });
            }
        }
    }

    private void decodeAndShow(String r) {
        try {
            BanditLiveData d = BanditDecoder.decode(r);

            double q = parse(flow.getText().toString(), 220.0);
            double netLatency = parse(latency.getText().toString(), 0.600);
            double factor = parse(cal.getText().toString(), 1.0);

            double flowingPw = FuelCalculator.effectivePulseMs(
                    d.averageMs,
                    netLatency
            );

            double rawLph = FuelCalculator.rawLitresPerHour(
                    d.rpm,
                    d.averageMs,
                    q,
                    factor
            );

            double estimatedLph = FuelCalculator.estimatedLitresPerHour(
                    d.rpm,
                    d.averageMs,
                    q,
                    netLatency,
                    factor
            );

            lastEstimatedLph = estimatedLph;
            updateGpsReadout();

            live.setText(String.format(
                    Locale.UK,
                    "RPM: %d\n" +
                    "Inj1: %.3f ms\n" +
                    "Inj2: %.3f ms\n" +
                    "Inj3: %.3f ms\n" +
                    "Inj4: %.3f ms\n" +
                    "Average commanded PW: %.3f ms\n" +
                    "Estimated flowing PW: %.3f ms\n" +
                    "PW-only fuel: %.3f L/h\n" +
                    "Estimated fuel: %.3f L/h",
                    d.rpm,
                    d.inj1,
                    d.inj2,
                    d.inj3,
                    d.inj4,
                    d.averageMs,
                    flowingPw,
                    rawLph,
                    estimatedLph
            ));
        } catch (Exception e) {
            lastEstimatedLph = Double.NaN;
            updateGpsReadout();

            live.setText(
                    "Decoder not yet valid for this frame:\n" +
                    e.getMessage() +
                    "\n\nRaw frame above is still useful."
            );
        }
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

    private String safeName(BluetoothDevice d) {
        try {
            return d.getName() == null ? d.getAddress() : d.getName();
        } catch (SecurityException e) {
            return "ELM327";
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
