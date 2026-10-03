package com.bandit1250.fuelmonitor;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int REQ_PERMS = 1001;
    private static final double UK_LITRES_PER_GALLON = 4.54609;
    private static final double MIN_MPG_SPEED_MPH = 3.0;
    private static final long MPG_WINDOW_MS = 10_000L;
    private static final long HISTORY_WINDOW_MS = 120_000L;

    private static final int STATUS_RED = Color.rgb(190, 0, 0);
    private static final int STATUS_YELLOW = Color.rgb(180, 130, 0);
    private static final int STATUS_GREEN = Color.rgb(0, 130, 0);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final ArrayDeque<MpgSample> mpgSamples = new ArrayDeque<>();
    private final ArrayDeque<HistoryChartsView.Point> history = new ArrayDeque<>();

    private BluetoothAdapter adapter;
    private BluetoothDevice selectedDevice;
    private Elm327Client elm;
    private SuzukiSds sds;
    private volatile boolean polling = false;

    private LocationManager locationManager;
    private LocationListener locationListener;

    private volatile double gpsSpeedMph = Double.NaN;
    private volatile double gpsAccuracyM = Double.NaN;
    private volatile double lastEstimatedLph = Double.NaN;
    private volatile long lastPollDurationMs = -1;

    private double injectorFlowCcMin = 220.0;
    private double netLatencyMs = 0.600;
    private double calibrationFactor = 1.000;

    private SharedPreferences prefs;
    private ProtocolSessionLogger protocolLogger;

    private FrameLayout pageFrame;
    private ScrollView mainScroll;
    private DiagnosticsPanel diagnosticsPanel;
    private View mainPage;
    private View chartsPage;
    private HistoryChartsView chartsView;

    private TextView infoText;
    private TextView status;
    private TextView instantMpg;
    private TextView avgMpg;
    private TextView fuelRate;
    private TextView gpsMeta;
    private TextView decoded;
    private TableLayout liveTable;
    private TextView raw;
    private TextView log;

    private Button deviceButton;
    private Button connect;

    private long lastHistorySampleMs = 0;

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

        prefs = getSharedPreferences("bandit_monitor", MODE_PRIVATE);
        protocolLogger = new ProtocolSessionLogger(this);
        injectorFlowCcMin = readDoublePref("injector_flow", 220.0);
        netLatencyMs = readDoublePref("net_latency", 0.600);
        calibrationFactor = readDoublePref("cal_factor", 1.000);

        buildUi();

        BluetoothManager bm = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();

        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
        createLocationListener();

        if (adapter == null) {
            setStatusRed("BT: unavailable");
            connect.setEnabled(false);
            deviceButton.setEnabled(false);
        }

        ensurePermissions();
    }

    private void buildUi() {
        pageFrame = new FrameLayout(this);

        mainPage = buildMainPage();
        chartsPage = buildChartsPage();
        chartsPage.setVisibility(View.GONE);

        pageFrame.addView(mainPage);
        pageFrame.addView(chartsPage);

        setContentView(pageFrame);
    }

    private View buildMainPage() {
        mainScroll = new ScrollView(this);
        ScrollView sv = mainScroll;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(10), dp(18), dp(34));
        sv.addView(root);

        // ----- Single-line title + hidden information -----
        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Bandit Monitor V0.14.0");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        Button infoButton = compactButton("ⓘ");
        infoButton.setOnClickListener(v ->
                infoText.setVisibility(
                        infoText.getVisibility() == View.VISIBLE
                                ? View.GONE
                                : View.VISIBLE
                )
        );
        titleRow.addView(infoButton);

        root.addView(titleRow);

        infoText = text(
                "2008 GSF1250SA • ELM327 Bluetooth • Suzuki SDS 21 08\n" +
                "CONNECT performs Bluetooth connection, SDS initialisation and continuous polling.\n" +
                "Tap 📈 beside Live ECU data to show/hide the full diagnostics chart stack.\n" +
                "Tap ⚙ beside Fuel L/h to edit injector flow, dead time and calibration factor."
        );
        infoText.setVisibility(View.GONE);
        root.addView(infoText);

        // ----- Compact Bluetooth selection / connect row -----
        LinearLayout bluetoothRow = row();

        deviceButton = new Button(this);
        deviceButton.setText("BT DEVICE");
        deviceButton.setSingleLine(true);
        deviceButton.setEllipsize(TextUtils.TruncateAt.END);
        deviceButton.setOnClickListener(v -> showDevicePicker());

        connect = new Button(this);
        connect.setText("CONNECT");
        connect.setOnClickListener(v -> connectAndRun());

        bluetoothRow.addView(deviceButton, weight());
        bluetoothRow.addView(connect, weight());
        root.addView(bluetoothRow);

        // ----- One-line colour-coded status -----
        status = text("BT: OFF");
        status.setTextSize(16);
        status.setTypeface(null, Typeface.BOLD);
        status.setSingleLine(true);
        root.addView(status);
        setStatusRed("BT: OFF");

        // ----- Bordered glanceable MPG / fuel panel -----
        LinearLayout mpgPanel = new LinearLayout(this);
        mpgPanel.setOrientation(LinearLayout.VERTICAL);
        mpgPanel.setPadding(dp(10), dp(10), dp(10), dp(10));
        mpgPanel.setGravity(Gravity.CENTER_HORIZONTAL);

        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(Color.TRANSPARENT);
        panelBackground.setStroke(dp(2), Color.rgb(110,110,110));
        panelBackground.setCornerRadius(dp(10));
        mpgPanel.setBackground(panelBackground);

        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        panelParams.topMargin = dp(8);
        panelParams.bottomMargin = dp(8);

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

        LinearLayout fuelRow = row();
        fuelRow.setGravity(Gravity.CENTER_VERTICAL);

        fuelRate = new TextView(this);
        fuelRate.setText("Fuel  — L/h");
        fuelRate.setTextSize(22);
        fuelRate.setTypeface(null, Typeface.BOLD);
        fuelRate.setGravity(Gravity.CENTER);
        fuelRow.addView(fuelRate, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        Button calibrate = compactButton("⚙");
        calibrate.setContentDescription("Fuel calibration");
        calibrate.setOnClickListener(v -> showCalibrationDialog());
        fuelRow.addView(calibrate);

        mpgPanel.addView(fuelRow);

        gpsMeta = new TextView(this);
        gpsMeta.setText("GPS: waiting for fix");
        gpsMeta.setTextSize(14);
        gpsMeta.setGravity(Gravity.CENTER_HORIZONTAL);
        mpgPanel.addView(gpsMeta);

        root.addView(mpgPanel, panelParams);

        // ----- Live ECU data + expandable diagnostics charts -----
        LinearLayout liveHeadRow = row();
        liveHeadRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView decodedHead = text("Live ECU data");
        decodedHead.setTextSize(18);
        decodedHead.setTypeface(null, Typeface.BOLD);
        liveHeadRow.addView(decodedHead, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        Button chartToggle = compactButton("📈");
        chartToggle.setContentDescription("Show or hide ECU charts");
        liveHeadRow.addView(chartToggle);

        Button ecuTools = compactButton("⚠ ECU");
        ecuTools.setContentDescription("Fault codes and ECU tools");
        ecuTools.setOnClickListener(v -> new EcuToolsDialog(this).show());
        liveHeadRow.addView(ecuTools);

        root.addView(liveHeadRow);

        diagnosticsPanel = new DiagnosticsPanel(this, prefs, sv);
        root.addView(diagnosticsPanel.getView());

        chartToggle.setOnClickListener(v -> diagnosticsPanel.toggle());

        decoded = text("Waiting for SDS data…");
        decoded.setTextSize(13);
        decoded.setTypeface(Typeface.MONOSPACE);
        decoded.setTextIsSelectable(true);
        root.addView(decoded);

        // Three-column live table: variable, raw binary bytes, human conversion.
        // Binary is intentionally used instead of hexadecimal so the wire values
        // are readable at a glance without mentally converting hex.
        liveTable = new TableLayout(this);
        liveTable.setStretchAllColumns(true);
        liveTable.setShrinkAllColumns(true);
        liveTable.setPadding(0, dp(4), 0, dp(8));

        TableRow liveHead = new TableRow(this);
        liveHead.addView(tableCell("Variable", true, false));
        liveHead.addView(tableCell("Raw (binary)", true, true));
        liveHead.addView(tableCell("Converted", true, false));
        liveTable.addView(liveHead);
        root.addView(liveTable);

        TextView rawHead = text("Raw 2108 response (hex, debug)");
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

        return sv;
    }

    private View buildChartsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(10), dp(12), dp(12));
        page.setBackgroundColor(Color.WHITE);

        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        Button back = new Button(this);
        back.setText("← BACK");
        back.setOnClickListener(v -> showMain());
        titleRow.addView(back);

        TextView title = new TextView(this);
        title.setText("Live history • last 2 min");
        title.setTextSize(19);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        page.addView(titleRow);

        TextView hint = text("MPG, RPM, fuel flow and GPS speed update while SDS polling continues.");
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        page.addView(hint);

        ScrollView chartScroll = new ScrollView(this);
        chartsView = new HistoryChartsView(this);
        chartScroll.addView(chartsView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                dp(860)
        ));

        page.addView(chartScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        return page;
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

    private Button compactButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private EditText dialogNumber(double value, int decimals) {
        EditText e = new EditText(this);
        e.setInputType(
                InputType.TYPE_CLASS_NUMBER |
                InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
        e.setText(String.format(Locale.UK, "%." + decimals + "f", value));
        e.setSelectAllOnFocus(true);
        return e;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------------
    // Bluetooth device selection
    // ---------------------------------------------------------------------

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
        if (adapter != null) refreshPairedList();

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startGps();
        } else {
            gpsMeta.setText("GPS permission denied");
        }
    }

    private void refreshPairedList() {
        if (adapter == null) return;

        try {
            devices.clear();
            devices.addAll(adapter.getBondedDevices());

            devices.sort(Comparator.comparing(
                    d -> safeDeviceName(d).toLowerCase(Locale.UK)
            ));

            String savedAddress = prefs.getString("bt_address", null);
            selectedDevice = null;

            if (savedAddress != null) {
                for (BluetoothDevice d : devices) {
                    if (savedAddress.equalsIgnoreCase(d.getAddress())) {
                        selectedDevice = d;
                        break;
                    }
                }
            }

            if (selectedDevice == null && devices.size() == 1) {
                selectedDevice = devices.get(0);
            }

            updateDeviceButton();

        } catch (SecurityException e) {
            setStatusRed("BT: permission");
        }
    }

    private void showDevicePicker() {
        if (adapter == null) return;

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            ensurePermissions();
            return;
        }

        refreshPairedList();

        if (devices.isEmpty()) {
            toast("No paired Bluetooth devices. Pair the ELM/V-LINK in Android Bluetooth settings first.");
            return;
        }

        String[] names = new String[devices.size()];
        int checked = -1;

        for (int i=0; i<devices.size(); i++) {
            BluetoothDevice d = devices.get(i);
            names[i] = safeDeviceName(d) + "\n" + d.getAddress();

            if (selectedDevice != null &&
                    selectedDevice.getAddress().equalsIgnoreCase(d.getAddress())) {
                checked = i;
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Select Bluetooth device")
                .setSingleChoiceItems(names, checked, null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(x -> {
            ListView list = dialog.getListView();
            list.setOnItemClickListener((parent, view, position, id) -> {
                selectedDevice = devices.get(position);
                prefs.edit()
                        .putString("bt_address", selectedDevice.getAddress())
                        .apply();
                updateDeviceButton();
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    private void updateDeviceButton() {
        if (selectedDevice == null) {
            deviceButton.setText("BT DEVICE");
        } else {
            deviceButton.setText("BT: " + safeDeviceName(selectedDevice));
        }
    }

    private String safeDeviceName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null || n.trim().isEmpty() ? "Unknown" : n;
        } catch (SecurityException e) {
            return "Bluetooth device";
        }
    }

    // ---------------------------------------------------------------------
    // Status
    // ---------------------------------------------------------------------

    private void setStatusRed(String message) {
        status.setText(message);
        status.setTextColor(STATUS_RED);
    }

    private void setStatusYellow(String message) {
        status.setText(message);
        status.setTextColor(STATUS_YELLOW);
    }

    private void setStatusGreen(String message) {
        status.setText(message);
        status.setTextColor(STATUS_GREEN);
    }

    private void updateConnectedStatus() {
        if (sds == null || !polling) {
            setStatusYellow("BT: OK • SDS: ERR");
            return;
        }

        if (lastPollDurationMs >= 0) {
            setStatusGreen("BT+SDS: OK • " + lastPollDurationMs + " ms");
        } else {
            setStatusGreen("BT+SDS: OK");
        }
    }

    // ---------------------------------------------------------------------
    // GPS
    // ---------------------------------------------------------------------

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
            ) {}
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

    // ---------------------------------------------------------------------
    // One-button connection / SDS polling
    // ---------------------------------------------------------------------

    private void connectAndRun() {
        if (selectedDevice == null) {
            showDevicePicker();
            return;
        }

        BluetoothDevice device = selectedDevice;

        polling = false;
        if (elm != null) elm.close();

        connect.setEnabled(false);
        connect.setText("CONNECTING…");
        setStatusYellow("BT: connecting…");

        mpgSamples.clear();
        history.clear();
        lastHistorySampleMs = 0;
        lastPollDurationMs = -1;
        if (diagnosticsPanel != null) diagnosticsPanel.resetSession();

        io.execute(() -> {
            boolean bluetoothConnected = false;

            try {
                elm = new Elm327Client(device);
                elm.connect();
                bluetoothConnected = true;

                ui.post(() -> setStatusYellow("BT: OK • SDS: …"));

                sds = new SuzukiSds(elm, this::appendLog);
                sds.initialise();

                long t0 = SystemClock.elapsedRealtime();
                String first = sds.read2108();
                lastPollDurationMs = SystemClock.elapsedRealtime() - t0;

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
                        setStatusYellow("BT: OK • SDS: ERR");
                    } else {
                        setStatusRed("BT: OFF");
                    }

                    appendLog("CONNECT/INIT ERROR: " + message);
                    connect.setText("CONNECT");
                    connect.setEnabled(true);
                });
            }
        });
    }

    private void pollLoop() {
        while (polling && sds != null) {
            try {
                long t0 = SystemClock.elapsedRealtime();
                String r = sds.read2108();
                lastPollDurationMs = SystemClock.elapsedRealtime() - t0;

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
                        setStatusYellow("BT: OK • SDS: ERR");
                        appendLog("POLLING STOPPED: " + e.getMessage());
                        connect.setText("RECONNECT");
                        connect.setEnabled(true);
                    });
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Fuel calibration
    // ---------------------------------------------------------------------

    private void showCalibrationDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);

        TextView f1 = text("Injector static flow (cc/min @ ~3 bar)");
        EditText flowInput = dialogNumber(injectorFlowCcMin, 1);
        box.addView(f1);
        box.addView(flowInput);

        TextView f2 = text("Net injector dead time / latency (ms)");
        EditText latencyInput = dialogNumber(netLatencyMs, 3);
        box.addView(f2);
        box.addView(latencyInput);

        TextView f3 = text("Tank calibration factor");
        EditText calInput = dialogNumber(calibrationFactor, 3);
        box.addView(f3);
        box.addView(calInput);

        new AlertDialog.Builder(this)
                .setTitle("Fuel calibration")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog,which) -> {
                    injectorFlowCcMin = parse(
                            flowInput.getText().toString(),
                            injectorFlowCcMin
                    );
                    netLatencyMs = parse(
                            latencyInput.getText().toString(),
                            netLatencyMs
                    );
                    calibrationFactor = parse(
                            calInput.getText().toString(),
                            calibrationFactor
                    );

                    prefs.edit()
                            .putLong("injector_flow", Double.doubleToRawLongBits(injectorFlowCcMin))
                            .putLong("net_latency", Double.doubleToRawLongBits(netLatencyMs))
                            .putLong("cal_factor", Double.doubleToRawLongBits(calibrationFactor))
                            .apply();
                })
                .show();
    }

    private double readDoublePref(String key, double def) {
        if (!prefs.contains(key)) return def;
        return Double.longBitsToDouble(
                prefs.getLong(key, Double.doubleToRawLongBits(def))
        );
    }

    // ---------------------------------------------------------------------
    // Decode + fuel model
    // ---------------------------------------------------------------------

    private void decodeAndShow(String response) {
        try {
            BanditLiveData d = BanditDecoder.decode(response);

            double estimatedLph = FuelCalculator.estimatedLitresPerHour(
                    d.rpm,
                    d.averageMs,
                    injectorFlowCcMin,
                    netLatencyMs,
                    calibrationFactor
            );

            lastEstimatedLph = estimatedLph;

            decoded.setVisibility(View.GONE);
            renderLiveDataTable(d);
            if (diagnosticsPanel != null) diagnosticsPanel.addSample(d);

            updateMpgPanel();

        } catch (Exception e) {
            lastEstimatedLph = Double.NaN;

            decoded.setVisibility(View.VISIBLE);
            decoded.setText(
                    "Decoder error: " + e.getMessage() +
                    "\nRaw frame remains available below."
            );

            updateMpgPanel();
        }
    }

    // ---------------------------------------------------------------------
    // Live ECU data table
    // ---------------------------------------------------------------------

    private TextView tableCell(String value, boolean bold, boolean mono) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(mono ? 10 : 11);
        v.setPadding(dp(3), dp(5), dp(3), dp(5));
        v.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) v.setTypeface(null, Typeface.BOLD);
        if (mono) v.setTypeface(Typeface.MONOSPACE);
        return v;
    }

    private void renderLiveDataTable(BanditLiveData d) {
        if (liveTable == null) return;

        // Preserve the header and replace the changing data rows.
        while (liveTable.getChildCount() > 1) {
            liveTable.removeViewAt(1);
        }

        addLiveRow("RPM", bin16(d.frame, 13, 14),
                String.format(Locale.UK, "%d rpm", d.rpm));
        addLiveRow("ECU speed", bin8(d.frame, 12),
                String.format(Locale.UK, "%.0f km/h (est.)", d.ecuSpeedKph));
        addLiveRow("TPS", bin8(d.frame, 15),
                String.format(Locale.UK, "%.1f %% (est.)", d.tpsPct));
        addLiveRow("IAP-1", bin8(d.frame, 16),
                String.format(Locale.UK, "%.1f kPa (est.)", d.iap1Kpa));
        addLiveRow("Coolant", bin8(d.frame, 17),
                String.format(Locale.UK, "%.1f °C (est.)", d.engineTempC));
        addLiveRow("Intake temp", bin8(d.frame, 18),
                String.format(Locale.UK, "%.1f °C (est.)", d.intakeTempC));
        addLiveRow("EAP", bin8(d.frame, 19), "unverified");
        addLiveRow("Battery", bin8(d.frame, 20),
                String.format(Locale.UK, "%.2f V (est.)", d.batteryEstV));

        addLiveRow("O₂ sensor", bin8(d.frame, 21), o2Guess(d.o2Raw));

        addLiveRow("Gear sensor", bin8(d.frame, 22),
                String.format(Locale.UK, "%d raw", d.gearRaw));
        addLiveRow("IAP-2", bin8(d.frame, 23),
                String.format(Locale.UK, "%.1f kPa (est.)", d.iap2Kpa));

        double desiredIdleRpm = d.idleSpeedRaw * 12.5;
        addLiveRow("Desired idle", bin8(d.frame, 24),
                String.format(Locale.UK, "~%.0f rpm (est.)", desiredIdleRpm));

        addLiveRow("ISC position", bin8(d.frame, 25),
                String.format(Locale.UK, "%d raw", d.iscRaw));

        addLiveRow("Injector 1", bin16(d.frame, 27, 28),
                String.format(Locale.UK, "%.3f ms", d.inj1));
        addLiveRow("Injector 2", bin16(d.frame, 29, 30),
                String.format(Locale.UK, "%.3f ms", d.inj2));
        addLiveRow("Injector 3", bin16(d.frame, 31, 32),
                String.format(Locale.UK, "%.3f ms", d.inj3));
        addLiveRow("Injector 4", bin16(d.frame, 33, 34),
                String.format(Locale.UK, "%.3f ms", d.inj4));
        addLiveRow("Injector avg", "—",
                String.format(Locale.UK, "%.3f ms", d.averageMs));

        addLiveRow("Ignition 1", bin8(d.frame, 37),
                formatMaybeValue(d.ign1Deg, "°"));
        addLiveRow("Ignition 2", bin8(d.frame, 38),
                formatMaybeValue(d.ign2Deg, "°"));
        addLiveRow("Ignition 3", bin8(d.frame, 39),
                formatMaybeValue(d.ign3Deg, "°"));
        addLiveRow("Ignition 4", bin8(d.frame, 40),
                formatMaybeValue(d.ign4Deg, "°"));
        addLiveRow("Secondary TPS", bin8(d.frame, 42),
                formatMaybeValue(d.secondaryTpsPct, "%"));

        addLiveRow("Status 45", bin8(d.frame, 45), "state byte");
        addLiveRow("Cooling fan", bin8(d.frame, 46), "state byte");
        addLiveRow("Exhaust valve", bin8(d.frame, 47), "state byte");
        addLiveRow("Clutch/starter", bin8(d.frame, 48), "state byte");
        addLiveRow("Neutral", bin8(d.frame, 49), "state byte");
    }

    private void addLiveRow(String name, String rawBinary, String converted) {
        TableRow row = new TableRow(this);
        row.addView(tableCell(name, false, false));
        row.addView(tableCell(rawBinary, false, true));
        row.addView(tableCell(converted, false, false));
        liveTable.addView(row);
    }

    private String o2Guess(int rawValue) {
        // Deliberately keep this as a simple raw-value interpretation.
        // Do not special-case 0xFF: if an O2 eliminator or fault leaves the
        // byte fixed high, that static raw value is itself diagnostically useful.
        if (rawValue < 0) return "—";
        if (rawValue < 28) return "LEAN?";
        if (rawValue > 28) return "RICH?";
        return "CROSS?";
    }

    private String bin8(byte[] frame, int index) {
        if (frame == null || index < 0 || index >= frame.length) return "—";
        return bin8(frame[index] & 0xFF);
    }

    private String bin8(int value) {
        String s = Integer.toBinaryString(value & 0xFF);
        return "00000000".substring(s.length()) + s;
    }

    private String bin16(byte[] frame, int hi, int lo) {
        if (frame == null || hi < 0 || lo < 0 ||
                hi >= frame.length || lo >= frame.length) {
            return "—";
        }
        return bin8(frame[hi] & 0xFF) + " " + bin8(frame[lo] & 0xFF);
    }

    private String formatMaybeValue(double value, String unit) {
        if (!Double.isFinite(value)) return "—";
        return String.format(Locale.UK, "%.1f %s (est.)", value, unit);
    }

    // ---------------------------------------------------------------------
    // MPG panel + 10 second average
    // ---------------------------------------------------------------------

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

        if (firstTime < 0 || lastTime - firstTime < 2000L) {
            return Double.NaN;
        }

        if (miles < 0.001 || litres <= 0.000001) {
            return Double.NaN;
        }

        double mpg = miles / (litres / UK_LITRES_PER_GALLON);

        return Double.isFinite(mpg) && mpg < 9999.0
                ? mpg
                : Double.NaN;
    }

    // ---------------------------------------------------------------------
    // History charts
    // ---------------------------------------------------------------------

    private void addHistorySample(double rpm, double lph) {
        long now = SystemClock.elapsedRealtime();

        // Keep chart density sensible while the ECU poll can be much faster.
        if (now - lastHistorySampleMs < 250L) return;
        lastHistorySampleMs = now;

        double mpg = calculateInstantMpg();
        double speed = Double.isFinite(gpsSpeedMph)
                ? gpsSpeedMph
                : Double.NaN;

        history.addLast(new HistoryChartsView.Point(
                now,
                mpg,
                rpm,
                lph,
                speed
        ));

        long cutoff = now - HISTORY_WINDOW_MS;

        while (!history.isEmpty() &&
                history.peekFirst().timeMs < cutoff) {
            history.removeFirst();
        }

        while (history.size() > 800) {
            history.removeFirst();
        }

        if (chartsPage.getVisibility() == View.VISIBLE) {
            chartsView.setPoints(new ArrayList<>(history));
        }
    }

    private void showCharts() {
        chartsView.setPoints(new ArrayList<>(history));
        mainPage.setVisibility(View.GONE);
        chartsPage.setVisibility(View.VISIBLE);
    }

    private void showMain() {
        chartsPage.setVisibility(View.GONE);
        mainPage.setVisibility(View.VISIBLE);
    }

    @Override public void onBackPressed() {
        if (chartsPage != null &&
                chartsPage.getVisibility() == View.VISIBLE) {
            showMain();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------------
    // Exclusive SDS operations used by fault-code / ECU tools
    // ---------------------------------------------------------------------

    /**
     * Temporarily stops the continuous 21 08 poll loop, runs one exclusive SDS
     * operation on the same single I/O thread, then resumes live polling.
     *
     * This prevents diagnostic commands and ECU memory-read probes from being
     * interleaved with live-data requests on the single K-Line connection.
     */
    <T> void runExclusiveSdsTask(
            String label,
            EcuToolsDialog.SdsAction<T> action,
            EcuToolsDialog.SdsCallback<T> callback
    ) {
        if (sds == null || elm == null || !elm.isConnected()) {
            ui.post(() -> callback.done(
                    null,
                    new IllegalStateException(
                            "Connect to the Bandit ECU first."
                    )
            ));
            return;
        }

        final boolean resumePolling = polling;

        // Causes the existing pollLoop() task to finish after its current
        // request. The task below is queued behind it on the same executor.
        polling = false;
        setStatusYellow("BT: OK • " + label + "…");

        io.execute(() -> {
            T result = null;
            Exception failure = null;

            try {
                if (protocolLogger != null) {
                    protocolLogger.setOperation(label);
                    sds.setTransactionListener(protocolLogger::record);
                }

                result = action.run(sds);

            } catch (Exception e) {
                failure = e;

            } finally {
                try {
                    sds.setTransactionListener(null);
                } catch (Exception ignored) {}

                if (protocolLogger != null) {
                    protocolLogger.setOperation("");
                }
            }

            final T finalResult = result;
            final Exception finalFailure = failure;

            ui.post(() -> callback.done(finalResult, finalFailure));

            if (resumePolling &&
                    sds != null &&
                    elm != null &&
                    elm.isConnected()) {
                polling = true;
                ui.post(this::updateConnectedStatus);

                // Continue occupying the I/O worker exactly as the normal
                // connection path does.
                pollLoop();
            }
        });
    }

    void shareEcuBin(File file) {
        shareInternalFile(
                file,
                "application/octet-stream",
                "Bandit 1250 ECU dump " + (file == null ? "" : file.getName()),
                "Share ECU .bin"
        );
    }

    File getProtocolLogFile() {
        return protocolLogger == null ? null : protocolLogger.getFile();
    }

    void clearProtocolLog() {
        if (protocolLogger != null) {
            protocolLogger.clear();
        }
    }

    void shareProtocolLog() {
        File file = getProtocolLogFile();
        shareInternalFile(
                file,
                "text/csv",
                "Bandit 1250 protocol log " + (file == null ? "" : file.getName()),
                "Share protocol CSV"
        );
    }

    private void shareInternalFile(
            File file,
            String mimeType,
            String subject,
            String chooserTitle
    ) {
        if (file == null || !file.isFile()) {
            toast("Share file is not available");
            return;
        }

        Uri uri = ShareFileProvider.uriFor(this, file);

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mimeType);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(Intent.createChooser(send, chooserTitle));
    }

    // ---------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------

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
