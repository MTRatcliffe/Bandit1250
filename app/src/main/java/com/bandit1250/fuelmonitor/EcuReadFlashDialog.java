package com.bandit1250.fuelmonitor;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.widget.*;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * ECU firmware transport screen.
 *
 * Firmware tools intentionally keep Bluetooth ELM and raw USB KKL as separate
 * transports:
 *
 * - Bluetooth ELM: normal SDS/KWP only. READ ECU is enabled, but it can only
 *   produce a 1 MiB file if the ECU accepts the existing read-only KWP 0x23
 *   memory path. It does not pretend an ELM can do the 57.6k raw monitor.
 *
 * - USB KKL: Android opens the USB-UART directly. The safe capability test
 *   configures 10,400 and 57,600 baud and queries RTS/DTR support WITHOUT
 *   sending any serial/K-line payload. A full raw read is offered only after
 *   that transport test passes.
 *
 * FLASH ECU remains disabled. Reading is much less dangerous than writing, but
 * the raw Denso read still resets/enters/unlocks the ECU monitor and therefore
 * is not equivalent to a harmless diagnostic query.
 */
public final class EcuReadFlashDialog {
    private static final String PREF_TRANSPORT = "ecu_fw_transport";
    private static final String PREF_LAST_ELM_REPORT = "ecu_flash_adapter_report";
    private static final String PREF_LAST_USB_REPORT = "ecu_usb_adapter_report";
    private static final String ACTION_USB_PERMISSION =
            "com.bandit1250.fuelmonitor.USB_PERMISSION";

    private enum Transport {
        BLUETOOTH_ELM,
        USB_KKL
    }

    private final MainActivity activity;

    private AlertDialog dialog;
    private RadioButton bluetoothRadio;
    private RadioButton usbRadio;
    private Button selectUsb;
    private TextView usbSummary;
    private TextView testDescription;
    private TextView result;
    private Button capabilityTest;
    private Button readBin;
    private Button flashEcu;
    private Button shareLog;

    private int selectedUsbDeviceId = -1;
    private UsbKklTransport.Capability lastUsbCapability;

    private BroadcastReceiver usbPermissionReceiver;
    private boolean usbReceiverRegistered = false;
    private boolean pendingUsbCapabilityAfterPermission = false;

    public EcuReadFlashDialog(MainActivity activity) {
        this.activity = activity;
    }

    public void show() {
        registerUsbPermissionReceiver();

        ScrollView scroll = new ScrollView(activity);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(box);

        box.addView(heading("ECU Read / Flash"));

        TextView note = body(
                "Choose the physical transport used for firmware work. Normal Bandit SDS " +
                "uses 10,400-baud KWP. The M32R-era Denso/ECUeditor raw monitor uses a " +
                "separate 57,600-baud serial path.\n\n" +
                "FLASH ECU remains disabled. READ ECU is available on both transports, " +
                "but the Bluetooth ELM path can only use the existing read-only KWP memory " +
                "method; it cannot magically gain raw 57.6k K-line support."
        );
        box.addView(note);

        TextView transportHead = heading("Firmware transport");
        transportHead.setPadding(0, dp(12), 0, dp(4));
        box.addView(transportHead);

        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(RadioGroup.VERTICAL);

        bluetoothRadio = new RadioButton(activity);
        bluetoothRadio.setId(android.view.View.generateViewId());
        bluetoothRadio.setText("Bluetooth ELM / Vgate");
        group.addView(bluetoothRadio);

        usbRadio = new RadioButton(activity);
        usbRadio.setId(android.view.View.generateViewId());
        usbRadio.setText("USB KKL / raw K-line cable");
        group.addView(usbRadio);

        box.addView(group);

        selectUsb = new Button(activity);
        selectUsb.setText("SELECT USB CABLE");
        selectUsb.setOnClickListener(v -> chooseUsbDevice());
        box.addView(selectUsb);

        usbSummary = mono("No USB cable selected.");
        usbSummary.setPadding(0, dp(2), 0, dp(8));
        box.addView(usbSummary);

        TextView testHead = heading("Transport capability test");
        testHead.setPadding(0, dp(12), 0, dp(4));
        box.addView(testHead);

        testDescription = body("");
        testDescription.setTextSize(12);
        box.addView(testDescription);

        capabilityTest = new Button(activity);
        capabilityTest.setOnClickListener(v -> runSelectedCapabilityTest());
        box.addView(capabilityTest);

        result = mono(lastReport());
        result.setTextIsSelectable(true);
        result.setPadding(0, dp(8), 0, dp(10));
        box.addView(result);

        TextView readHead = heading("Firmware read");
        readHead.setPadding(0, dp(12), 0, dp(4));
        box.addView(readHead);

        TextView readWarning = body(
                "A READ does not intentionally erase or program flash, but a raw read is " +
                "still not risk-free. Entering the Denso monitor resets the ECU and changes " +
                "its operating state. If the protocol assumption is wrong, bytes intended " +
                "as read/unlock commands could be interpreted differently. Power loss, bad " +
                "wiring, another tester talking on K-line, or a failed monitor entry can " +
                "leave the ECU unresponsive until it is power-cycled. Keep the engine OFF " +
                "and battery voltage stable."
        );
        readWarning.setTextSize(12);
        readWarning.setTextColor(Color.rgb(155, 85, 0));
        box.addView(readWarning);

        LinearLayout firmwareRow = row();

        readBin = new Button(activity);
        readBin.setText("READ ECU • 1 MiB");
        readBin.setEnabled(true);
        readBin.setOnClickListener(v -> confirmRead());
        firmwareRow.addView(readBin, weight());

        flashEcu = new Button(activity);
        flashEcu.setText("FLASH ECU");
        flashEcu.setEnabled(false);
        firmwareRow.addView(flashEcu, weight());

        box.addView(firmwareRow);

        TextView flashNote = body(
                "FLASH ECU stays disabled in this build. Reading and flashing are being " +
                "kept separate until the read transport is proven on this Bandit and a " +
                "repeatable 1 MiB dump can be verified."
        );
        flashNote.setTextSize(12);
        box.addView(flashNote);

        shareLog = new Button(activity);
        shareLog.setText("SHARE PROTOCOL CSV");
        shareLog.setOnClickListener(v -> activity.shareProtocolLog());
        box.addView(shareLog);

        String savedTransport = prefs().getString(
                PREF_TRANSPORT,
                Transport.BLUETOOTH_ELM.name()
        );

        if (Transport.USB_KKL.name().equals(savedTransport)) {
            usbRadio.setChecked(true);
        } else {
            bluetoothRadio.setChecked(true);
        }

        group.setOnCheckedChangeListener((g, checkedId) -> {
            Transport t = checkedId == usbRadio.getId()
                    ? Transport.USB_KKL
                    : Transport.BLUETOOTH_ELM;

            prefs().edit().putString(PREF_TRANSPORT, t.name()).apply();
            updateTransportUi();

            if (t == Transport.USB_KKL && selectedUsbDeviceId < 0) {
                autoSelectUsbIfUnambiguous();
            }
        });

        autoSelectUsbIfUnambiguous();
        updateTransportUi();

        dialog = new AlertDialog.Builder(activity)
                .setTitle("ECU Read / Flash")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        dialog.setOnDismissListener(d -> {
            dialog = null;
            unregisterUsbPermissionReceiver();
        });

        dialog.show();
    }

    private Transport selectedTransport() {
        return usbRadio != null && usbRadio.isChecked()
                ? Transport.USB_KKL
                : Transport.BLUETOOTH_ELM;
    }

    private void updateTransportUi() {
        boolean usb = selectedTransport() == Transport.USB_KKL;

        selectUsb.setVisibility(usb ? android.view.View.VISIBLE : android.view.View.GONE);
        usbSummary.setVisibility(usb ? android.view.View.VISIBLE : android.view.View.GONE);

        if (usb) {
            capabilityTest.setText("TEST SELECTED USB CABLE");
            testDescription.setText(
                    "USB test: enumerate the raw serial driver, open the selected cable, " +
                    "set 10,400 then 57,600 baud at 8-N-1, and query whether RTS/DTR are " +
                    "available. It does NOT transmit any byte on K-line and does not toggle " +
                    "RTS/DTR."
            );
        } else {
            capabilityTest.setText("TEST BLUETOOTH ELM");
            testDescription.setText(
                    "Bluetooth test: ATI, AT@1, AT IB10, AT IB96, AT IB12 and AT IB15. " +
                    "These are adapter AT commands only. The app restores AT IB10 and " +
                    "re-proves the normal Bandit SDS session afterwards."
            );
        }
    }

    // ---------------------------------------------------------------------
    // USB device selection / permission
    // ---------------------------------------------------------------------

    private void autoSelectUsbIfUnambiguous() {
        List<UsbKklTransport.DeviceInfo> devices =
                UsbKklTransport.list(activity);

        if (devices.size() == 1) {
            setSelectedUsb(devices.get(0));
        } else if (selectedUsbDeviceId >= 0) {
            for (UsbKklTransport.DeviceInfo d : devices) {
                if (d.deviceId == selectedUsbDeviceId) {
                    setSelectedUsb(d);
                    return;
                }
            }
        } else if (devices.isEmpty() && usbSummary != null) {
            usbSummary.setText(
                    "No supported USB serial cable detected. Connect the KKL cable " +
                    "through USB OTG, then tap SELECT USB CABLE."
            );
        }
    }

    private void chooseUsbDevice() {
        List<UsbKklTransport.DeviceInfo> devices =
                UsbKklTransport.list(activity);

        if (devices.isEmpty()) {
            result.setText(
                    "No supported USB serial device found.\n\n" +
                    "The built-in USB layer supports common FTDI, CH340/CH341, " +
                    "CP210x, PL2303 and CDC/ACM serial adapters."
            );
            return;
        }

        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            UsbKklTransport.DeviceInfo d = devices.get(i);
            labels[i] = d.label +
                    (d.permission ? "\nUSB permission: granted" : "\nUSB permission: needed");
        }

        new AlertDialog.Builder(activity)
                .setTitle("Select USB serial / KKL cable")
                .setItems(labels, (d, which) -> {
                    UsbKklTransport.DeviceInfo chosen = devices.get(which);
                    setSelectedUsb(chosen);

                    if (!chosen.permission) {
                        requestUsbPermission(false);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setSelectedUsb(UsbKklTransport.DeviceInfo info) {
        selectedUsbDeviceId = info.deviceId;
        lastUsbCapability = null;

        if (usbSummary != null) {
            usbSummary.setText(
                    "Selected USB cable\n" +
                    info.label + "\n" +
                    "USB permission: " +
                    (info.permission ? "granted" : "required")
            );
        }
    }

    private void registerUsbPermissionReceiver() {
        if (usbReceiverRegistered) return;

        usbPermissionReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;

                boolean granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                );

                autoSelectUsbIfUnambiguous();

                if (granted) {
                    if (result != null) {
                        result.setText("USB permission granted.");
                    }

                    if (pendingUsbCapabilityAfterPermission) {
                        pendingUsbCapabilityAfterPermission = false;
                        runUsbCapabilityTest();
                    }
                } else {
                    pendingUsbCapabilityAfterPermission = false;
                    if (result != null) {
                        result.setText("USB permission was not granted.");
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);

        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(
                    usbPermissionReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            activity.registerReceiver(usbPermissionReceiver, filter);
        }

        usbReceiverRegistered = true;
    }

    private void unregisterUsbPermissionReceiver() {
        if (!usbReceiverRegistered || usbPermissionReceiver == null) return;
        try {
            activity.unregisterReceiver(usbPermissionReceiver);
        } catch (Exception ignored) {}
        usbReceiverRegistered = false;
    }

    private boolean hasSelectedUsbPermission() {
        UsbDevice device =
                UsbKklTransport.getUsbDevice(activity, selectedUsbDeviceId);

        UsbManager manager =
                (UsbManager) activity.getSystemService(Context.USB_SERVICE);

        return device != null &&
                manager != null &&
                manager.hasPermission(device);
    }

    private void requestUsbPermission(boolean runCapabilityAfterGrant) {
        UsbDevice device =
                UsbKklTransport.getUsbDevice(activity, selectedUsbDeviceId);

        UsbManager manager =
                (UsbManager) activity.getSystemService(Context.USB_SERVICE);

        if (device == null || manager == null) {
            result.setText("Selected USB cable is no longer attached.");
            return;
        }

        pendingUsbCapabilityAfterPermission = runCapabilityAfterGrant;

        Intent permissionIntent = new Intent(ACTION_USB_PERMISSION)
                .setPackage(activity.getPackageName());

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }

        PendingIntent pi = PendingIntent.getBroadcast(
                activity,
                9201,
                permissionIntent,
                flags
        );

        manager.requestPermission(device, pi);
        result.setText("Android USB permission requested…");
    }

    // ---------------------------------------------------------------------
    // Capability tests
    // ---------------------------------------------------------------------

    private void runSelectedCapabilityTest() {
        if (selectedTransport() == Transport.USB_KKL) {
            if (selectedUsbDeviceId < 0) {
                chooseUsbDevice();
                return;
            }

            if (!hasSelectedUsbPermission()) {
                requestUsbPermission(true);
                return;
            }

            runUsbCapabilityTest();
        } else {
            confirmBluetoothCapabilityTest();
        }
    }

    private void confirmBluetoothCapabilityTest() {
        new AlertDialog.Builder(activity)
                .setTitle("Test the connected Bluetooth ELM?")
                .setMessage(
                        "Only adapter AT commands are sent. No ECU programming command " +
                        "is used. The adapter is restored to 10,400 baud and normal SDS " +
                        "identity is re-proved afterwards."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run test", (d, which) ->
                        runBluetoothCapabilityTest())
                .show();
    }

    private void runBluetoothCapabilityTest() {
        setBusy(true);
        result.setText(
                "Testing Bluetooth ELM adapter…\n" +
                "No ECU programming command is being sent."
        );

        activity.runExclusiveSdsTask(
                "ECU read-flash Bluetooth capability",
                this::performElmTest,
                (report, error) -> {
                    setBusy(false);

                    if (error != null) {
                        result.setText(
                                "Bluetooth capability test failed:\n" +
                                error.getMessage()
                        );
                        return;
                    }

                    result.setText(report.displayText);
                    prefs().edit()
                            .putString(PREF_LAST_ELM_REPORT, report.displayText)
                            .apply();
                }
        );
    }

    private CapabilityReport performElmTest(SuzukiSds sds) throws Exception {
        final String[] commands = {
                "ATI",
                "AT@1",
                "AT IB10",
                "AT IB96",
                "AT IB12",
                "AT IB15"
        };

        LinkedHashMap<String, String> responses = new LinkedHashMap<>();
        LinkedHashMap<String, Long> durations = new LinkedHashMap<>();

        for (int i = 0; i < commands.length; i++) {
            String command = commands[i];

            final int done = i + 1;
            activity.runOnUiThread(() -> {
                if (result != null) {
                    result.setText(
                            "Bluetooth adapter capability test\n" +
                            done + " / " + commands.length + "\n" +
                            "TX: " + command
                    );
                }
            });

            long start = System.nanoTime();
            String response;

            try {
                response = sds.requestRaw(command, 3000);
            } catch (Exception e) {
                response = "I/O ERROR: " + e.getMessage();
            }

            long elapsedMs = Math.max(
                    0L,
                    (System.nanoTime() - start) / 1_000_000L
            );

            responses.put(command, response == null ? "" : response);
            durations.put(command, elapsedMs);
        }

        String restore;
        try {
            restore = sds.requestRaw("AT IB10", 3000);
        } catch (Exception e) {
            restore = "I/O ERROR: " + e.getMessage();
        }

        boolean normalSdsRestored = sds.recoverKnownGoodSession();

        String adapterId = oneLine(responses.get("ATI"));
        String description = oneLine(responses.get("AT@1"));

        StringBuilder out = new StringBuilder();
        out.append("BLUETOOTH ELM CAPABILITY\n");
        out.append("Adapter ID: ").append(adapterId).append("\n");
        out.append("Description: ").append(description).append("\n\n");

        for (String command : commands) {
            out.append("TX: ").append(command).append("\n");
            out.append("RX: ").append(responses.get(command)).append("\n");
            out.append("Elapsed: ")
                    .append(durations.get(command))
                    .append(" ms\n\n");
        }

        out.append("Restore AT IB10: ").append(restore).append("\n");
        out.append("Normal SDS recovered: ")
                .append(normalSdsRestored ? "YES" : "NO")
                .append("\n\n");

        out.append(
                "57,600 vehicle-side K-line is NOT demonstrated by these ELM " +
                "commands. READ ECU remains enabled only for the separate read-only " +
                "KWP memory probe, not the ECUeditor raw monitor."
        );

        return new CapabilityReport(out.toString());
    }

    private void runUsbCapabilityTest() {
        setBusy(true);
        result.setText(
                "Testing selected USB serial cable…\n" +
                "No byte will be transmitted on K-line."
        );

        long start = System.nanoTime();

        activity.runExclusiveTransportTask(
                "USB KKL capability",
                false,
                () -> UsbKklTransport.capabilityTest(
                        activity,
                        selectedUsbDeviceId
                ),
                (capability, error) -> {
                    setBusy(false);
                    long ms = Math.max(
                            0L,
                            (System.nanoTime() - start) / 1_000_000L
                    );

                    if (error != null) {
                        String msg =
                                "USB capability test failed:\n" +
                                error.getMessage();
                        result.setText(msg);
                        activity.recordProtocolEvent(
                                "USB KKL capability",
                                "USB SERIAL CAPABILITY",
                                msg,
                                ms
                        );
                        return;
                    }

                    lastUsbCapability = capability;
                    result.setText(capability.report);
                    prefs().edit()
                            .putString(PREF_LAST_USB_REPORT, capability.report)
                            .apply();

                    activity.recordProtocolEvent(
                            "USB KKL capability",
                            "SET 10400 / SET 57600 / QUERY RTS+DTR",
                            capability.report,
                            ms
                    );
                }
        );
    }

    // ---------------------------------------------------------------------
    // ECU read
    // ---------------------------------------------------------------------

    private void confirmRead() {
        if (selectedTransport() == Transport.USB_KKL) {
            confirmUsbRead();
        } else {
            confirmBluetoothRead();
        }
    }

    private void confirmBluetoothRead() {
        new AlertDialog.Builder(activity)
                .setTitle("Attempt 1 MiB read over Bluetooth ELM?")
                .setMessage(
                        "This path stays in normal SDS/KWP and first probes the read-only " +
                        "0x23 ReadMemoryByAddress service. If the ECU rejects it, the attempt " +
                        "stops immediately. If it works, the app reads 1 MiB and keeps the " +
                        "file only if every block succeeds.\n\n" +
                        "This does NOT use the 57.6k Denso monitor and does not erase/write " +
                        "flash. Keep the engine OFF and voltage stable."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Attempt read", (d, which) ->
                        startBluetoothRead())
                .show();
    }

    private void startBluetoothRead() {
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Reading ECU over Bluetooth");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setCancelable(false);
        progress.setMessage("Probing read-only KWP memory access…");
        progress.show();

        setBusy(true);

        File dir = new File(activity.getFilesDir(), "ecu_dumps");
        String stamp = new SimpleDateFormat(
                "yyyy-MM-dd_HHmmss",
                Locale.UK
        ).format(new Date());
        File output = new File(
                dir,
                "Bandit1250_ELM_KWP_" + stamp + ".bin"
        );

        activity.runExclusiveSdsTask(
                "Bluetooth ECU 1MiB read",
                sds -> {
                    EcuMemoryReader.ProbeResult probe =
                            EcuMemoryReader.probe(sds);

                    if (!probe.supported) {
                        throw new java.io.IOException(
                                "Normal SDS does not expose KWP 0x23 memory read.\n" +
                                probe.response + "\n" +
                                probe.explanation
                        );
                    }

                    return EcuMemoryReader.read(
                            sds,
                            output,
                            1024L * 1024L,
                            probe.addressBytes,
                            probe.blockSize,
                            (done, total, address) ->
                                    activity.runOnUiThread(() -> {
                                        int pct = total <= 0
                                                ? 0
                                                : (int)Math.min(
                                                        100L,
                                                        done * 100L / total
                                                );
                                        progress.setProgress(pct);
                                        progress.setMessage(String.format(
                                                Locale.UK,
                                                "Read-only KWP memory read\n" +
                                                "0x%08X\n%,d / %,d bytes",
                                                address,
                                                done,
                                                total
                                        ));
                                    })
                    );
                },
                (read, error) -> {
                    setBusy(false);
                    if (progress.isShowing()) progress.dismiss();

                    if (error != null) {
                        result.setText(
                                "Bluetooth ECU read stopped.\n\n" +
                                error.getMessage() +
                                "\n\nNo partial BIN was kept."
                        );
                        return;
                    }

                    result.setText(
                            "Bluetooth ECU read complete.\n" +
                            "File: " + read.file.getName() + "\n" +
                            "Bytes: " + read.bytes + "\n" +
                            "SHA-256: " + read.sha256
                    );

                    showReadComplete(
                            read.file,
                            read.bytes,
                            read.sha256,
                            "Normal SDS/KWP read"
                    );
                }
        );
    }

    private void confirmUsbRead() {
        if (selectedUsbDeviceId < 0) {
            chooseUsbDevice();
            return;
        }

        if (!hasSelectedUsbPermission()) {
            requestUsbPermission(false);
            result.setText(
                    "Grant USB permission, then tap READ ECU again."
            );
            return;
        }

        if (lastUsbCapability == null ||
                lastUsbCapability.device.deviceId != selectedUsbDeviceId) {
            result.setText(
                    "Run TEST SELECTED USB CABLE first. A raw ECU read is only " +
                    "unlocked after this cable proves 57,600 baud and exposes RTS."
            );
            return;
        }

        if (!lastUsbCapability.readCandidate()) {
            result.setText(
                    "READ BLOCKED for this USB cable.\n\n" +
                    "The capability test did not prove both 57,600 baud and RTS control."
            );
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle("Experimental raw 1 MiB ECU read")
                .setMessage(
                        "This is more invasive than a normal diagnostic read. It will:\n\n" +
                        "• switch the USB UART to 57,600 baud\n" +
                        "• toggle RTS to reset/enter the Denso monitor\n" +
                        "• synchronise and send the known monitor read-unlock sequence\n" +
                        "• request all 4096 × 256-byte flash pages\n\n" +
                        "It contains NO erase or flash-write command, but it can still leave " +
                        "the ECU in monitor/programming state until the ignition is power-cycled. " +
                        "If this Bandit's monitor differs from the related ECUeditor ECUs, " +
                        "commands could be interpreted differently.\n\n" +
                        "ENGINE OFF. Stable battery. Correct K-line/programming-mode harness. " +
                        "Do not leave another ELM/diagnostic tester connected to K-line.\n\n" +
                        "Continue?"
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("READ ECU", (d, which) ->
                        startUsbRead())
                .show();
    }

    private void startUsbRead() {
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Raw Denso ECU read");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setCancelable(false);
        progress.setMessage(
                "Entering 57.6k read monitor…\n" +
                "Do not switch off power."
        );
        progress.show();

        setBusy(true);
        long start = System.nanoTime();

        activity.runExclusiveTransportTask(
                "USB raw 1MiB ECU read",
                true,
                () -> UsbKklTransport.readDensoImage(
                        activity,
                        selectedUsbDeviceId,
                        (pagesDone, totalPages, bytesDone) ->
                                activity.runOnUiThread(() -> {
                                    int pct = totalPages <= 0
                                            ? 0
                                            : pagesDone * 100 / totalPages;
                                    progress.setProgress(pct);
                                    progress.setMessage(String.format(
                                            Locale.UK,
                                            "Raw Denso read\n" +
                                            "Page %,d / %,d\n" +
                                            "%,d / 1,048,576 bytes",
                                            pagesDone,
                                            totalPages,
                                            bytesDone
                                    ));
                                })
                ),
                (read, error) -> {
                    setBusy(false);
                    if (progress.isShowing()) progress.dismiss();

                    long ms = Math.max(
                            0L,
                            (System.nanoTime() - start) / 1_000_000L
                    );

                    if (error != null) {
                        String msg =
                                "USB raw ECU read stopped.\n\n" +
                                error.getMessage() +
                                "\n\nNo partial BIN was kept. " +
                                "Cycle the ignition before normal ECU use.";
                        result.setText(msg);

                        activity.recordProtocolEvent(
                                "USB raw ECU read",
                                "DENSO RAW READ 1MiB",
                                msg,
                                ms
                        );
                        return;
                    }

                    String msg =
                            "USB raw ECU read complete.\n" +
                            "File: " + read.file.getName() + "\n" +
                            "Bytes: " + read.bytes + "\n" +
                            "ECU ID area: " + read.ecuId + "\n" +
                            "SHA-256: " + read.sha256 + "\n\n" +
                            "Cycle the ignition before trying to start/use the ECU.";

                    result.setText(msg);

                    activity.recordProtocolEvent(
                            "USB raw ECU read",
                            "DENSO RAW READ 1MiB",
                            msg,
                            ms
                    );

                    showReadComplete(
                            read.file,
                            read.bytes,
                            read.sha256,
                            "57.6k raw USB KKL read"
                    );
                }
        );
    }

    private void showReadComplete(
            File file,
            long bytes,
            String sha256,
            String method
    ) {
        new AlertDialog.Builder(activity)
                .setTitle("ECU read complete")
                .setMessage(
                        "Method: " + method + "\n" +
                        "File: " + file.getName() + "\n" +
                        "Bytes: " + bytes + "\n" +
                        "SHA-256:\n" + sha256 + "\n\n" +
                        "For a raw USB read, power-cycle the ignition before normal use."
                )
                .setNegativeButton("Close", null)
                .setPositiveButton("Share BIN", (d, which) ->
                        activity.shareEcuBin(file)
                )
                .show();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private String lastReport() {
        String usb = prefs().getString(PREF_LAST_USB_REPORT, "");
        String elm = prefs().getString(PREF_LAST_ELM_REPORT, "");

        if (!usb.isEmpty()) {
            return "Last USB test:\n\n" + usb;
        }

        if (!elm.isEmpty()) {
            return "Last Bluetooth test:\n\n" + elm;
        }

        return "No firmware transport test has been run yet.";
    }

    private void setBusy(boolean busy) {
        if (capabilityTest != null) capabilityTest.setEnabled(!busy);
        if (selectUsb != null) selectUsb.setEnabled(!busy);
        if (readBin != null) readBin.setEnabled(!busy);
        if (shareLog != null) shareLog.setEnabled(!busy);
        if (bluetoothRadio != null) bluetoothRadio.setEnabled(!busy);
        if (usbRadio != null) usbRadio.setEnabled(!busy);
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(
                "bandit_monitor",
                Context.MODE_PRIVATE
        );
    }

    private String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
    }

    private TextView heading(String value) {
        TextView t = body(value);
        t.setTextSize(17);
        t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private TextView body(String value) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextSize(13);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView mono(String value) {
        TextView t = body(value);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(activity);
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

    private int dp(int value) {
        return Math.round(
                value * activity.getResources().getDisplayMetrics().density
        );
    }

    private static final class CapabilityReport {
        final String displayText;

        CapabilityReport(String displayText) {
            this.displayText = displayText;
        }
    }
}
