package com.bandit1250.fuelmonitor;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.*;

import java.util.Locale;

/**
 * Guided Suzuki/Denso A5 active controls.
 *
 * Firmware update:
 * Every implemented D4FASE80 A5 mode (00, 01, 03, 05, 06, 07) has an
 * explicit all-zero release branch in FUN_00008284:
 *
 *     A5 <MODE> 00 00 00 00
 *
 * The app therefore treats zero-parameter mode packets as canonical
 * "release to normal ECU control" commands. Active commands are sent once,
 * normal SDS/live polling resumes, and a hard timeout releases the mode.
 */
public final class ActiveControlsDialog {
    private static final String PREF_PENDING = "a5_pending_release";
    private static final String PREF_LABEL = "a5_pending_label";
    private static final String PREF_RELEASE = "a5_pending_release_cmd";

    private static final long STATUS_REFRESH_MS = 500L;

    private final MainActivity activity;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private AlertDialog dialog;
    private TextView banner;
    private TextView result;
    private GuidedTestLogger lastGuidedLog;

    private Runnable timeoutReleaseRunnable;
    private Runnable activeStatusRunnable;

    private long activeDeadlineElapsedMs = -1L;
    private int activeMode = -1;
    private String activeLabel = "";
    private String activeRelease = "";
    private boolean releaseInProgress = false;

    // ISC RPM live-verification state.
    private int iscRequestedRpm = -1;
    private long iscStartedElapsedMs = -1L;
    private long iscWithinTargetSinceMs = -1L;
    private long iscSettlingTimeMs = -1L;
    private int iscSampleCount = 0;
    private int iscMinRpm = Integer.MAX_VALUE;
    private int iscMaxRpm = Integer.MIN_VALUE;
    private double iscMeanRpm = 0.0;
    private double iscM2Rpm = 0.0;

    public ActiveControlsDialog(MainActivity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(
                "bandit_monitor",
                android.content.Context.MODE_PRIVATE
        );
    }

    public void show() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(box);

        banner = text("");
        banner.setTypeface(null, Typeface.BOLD);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.addView(banner);
        refreshBanner();

        TextView intro = text(
                "A5 ACTIVE CONTROLS\n\n" +
                "Every implemented A5 mode now has a firmware-proven zero-parameter " +
                "RELEASE packet: A5 <MODE> 00 00 00 00. Active commands are sent once, " +
                "then normal SDS polling continues. Manual held overrides have hard " +
                "timeouts and are also released when this screen closes."
        );
        intro.setTextSize(12);
        box.addView(intro);

        addCard(
                box,
                "IGNITION TIMING STABILISATION",
                "Firmware strongly identified / Experimental",
                "Enable: A5 00 80 00 00 00\nRelease: A5 00 00 00 00 00\nHard timeout: 30 s",
                "Temporarily suppresses normal dynamic ignition corrections in the low-RPM service window. " +
                        "The app immediately checks page-08 payload offsets 0x23-0x26 for the expected 40 40 40 40 state.",
                new String[]{"ENABLE (30 s MAX)", "RELEASE"},
                new Runnable[]{
                        () -> confirmStart(
                                "Ignition timing stabilisation",
                                "A50080000000",
                                "A50000000000",
                                0x00,
                                "Engine should be running at idle / low RPM. The override auto-releases after 30 seconds or when this screen closes.",
                                true,
                                30,
                                -1
                        ),
                        () -> sendRelease(
                                "Ignition timing stabilisation",
                                "A50000000000",
                                0x00
                        )
                }
        );

        addCard(
                box,
                "PAIR SOLENOID",
                "Strongly confirmed",
                "ON: A5 01 80 00 00 00\nRelease: A5 01 00 00 00 00\nHard timeout: 8 s",
                "Temporarily overrides the ECU PAIR-solenoid command. You may hear or feel the solenoid click. No permanent calibration change is expected.",
                new String[]{"ON (8 s MAX)", "RELEASE"},
                new Runnable[]{
                        () -> confirmStart(
                                "PAIR solenoid",
                                "A50180000000",
                                "A50100000000",
                                0x01,
                                "Short electrical-output test. It auto-releases after 8 seconds.",
                                false,
                                8,
                                -1
                        ),
                        () -> sendRelease(
                                "PAIR solenoid",
                                "A50100000000",
                                0x01
                        )
                }
        );

        addCard(
                box,
                "SECONDARY THROTTLES / STVA",
                "Strong inference — physical direction not yet identified",
                "Extreme A: A5 03 80 00 00 00\nExtreme B: A5 03 80 80 00 00\nRelease: A5 03 00 00 00 00\nHard timeout: 4 s",
                "Commands the secondary-throttle target to one of two end ranges. " +
                        "Default test condition is ignition ON, engine OFF. Zero parameters release the override; they do NOT mean a physical closed/off state.",
                new String[]{"EXTREME A (4 s)", "EXTREME B (4 s)", "RELEASE"},
                new Runnable[]{
                        () -> confirmStart(
                                "STVA Extreme A",
                                "A50380000000",
                                "A50300000000",
                                0x03,
                                "Use ignition ON and engine OFF. The app releases Mode 03 after 4 seconds.",
                                false,
                                4,
                                -1
                        ),
                        () -> confirmStart(
                                "STVA Extreme B",
                                "A50380800000",
                                "A50300000000",
                                0x03,
                                "Use ignition ON and engine OFF. The app releases Mode 03 after 4 seconds.",
                                false,
                                4,
                                -1
                        ),
                        () -> sendRelease(
                                "STVA",
                                "A50300000000",
                                0x03
                        )
                }
        );

        addIscCard(box);

        addCard(
                box,
                "COOLING FAN",
                "Firmware identified",
                "ON: A5 06 80 80 00 00\nForced OFF: A5 06 80 00 00 00\nRelease: A5 06 00 00 00 00\nHard timeout: 20 s",
                "FORCE OFF and RELEASE are different. A5 06 80 00 00 00 keeps the diagnostic override active while commanding OFF. " +
                        "A5 06 00 00 00 00 returns fan control to the ECU's normal temperature logic.",
                new String[]{"ON (20 s MAX)", "FORCE OFF (20 s)", "RELEASE TO ECU"},
                new Runnable[]{
                        () -> confirmStart(
                                "Cooling fan ON",
                                "A50680800000",
                                "A50600000000",
                                0x06,
                                "This should energise the ECU cooling-fan output. Keep hands/tools clear of the fan.",
                                false,
                                20,
                                -1
                        ),
                        () -> confirmStart(
                                "Cooling fan forced OFF",
                                "A50680000000",
                                "A50600000000",
                                0x06,
                                "This leaves the diagnostic override active while commanding OFF. It auto-releases after 20 seconds.",
                                false,
                                20,
                                -1
                        ),
                        () -> sendRelease(
                                "Cooling fan",
                                "A50600000000",
                                0x06
                        )
                }
        );

        addCard(
                box,
                "MODE 07 OPTIONAL OUTPUT",
                "Experimental — likely EVAP purge (~60%)",
                "Enable: A5 07 80 00 00 00\nRelease: A5 07 00 00 00 00\nHard timeout: 4 s",
                "Firmware proves this reaches SH7058 PE11. Physical identity is not confirmed on this market calibration. " +
                        "Use ignition ON / engine OFF and only short controlled activation.",
                new String[]{"SHORT ENABLE (4 s)", "RELEASE"},
                new Runnable[]{
                        () -> confirmStart(
                                "Mode 07 optional output",
                                "A50780000000",
                                "A50700000000",
                                0x07,
                                "Experimental physical-output test. Prefer ignition ON, engine OFF. Note whether any solenoid/relay clicks.",
                                false,
                                4,
                                -1
                        ),
                        () -> sendRelease(
                                "Mode 07 optional output",
                                "A50700000000",
                                0x07
                        )
                }
        );

        Button releaseAll = new Button(activity);
        releaseAll.setText("RELEASE ALL IMPLEMENTED A5 MODES");
        releaseAll.setOnClickListener(v -> confirmReleaseAll());
        box.addView(releaseAll);

        LinearLayout logRow = new LinearLayout(activity);
        logRow.setOrientation(LinearLayout.HORIZONTAL);

        Button shareProtocol = new Button(activity);
        shareProtocol.setText("SHARE PROTOCOL LOG");
        shareProtocol.setOnClickListener(v -> activity.shareProtocolLog());
        logRow.addView(shareProtocol, weight());

        Button shareGuided = new Button(activity);
        shareGuided.setText("SHARE LAST TEST");
        shareGuided.setOnClickListener(v -> {
            if (lastGuidedLog == null) {
                Toast.makeText(
                        activity,
                        "No guided control log yet",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
            activity.shareGuidedLog(lastGuidedLog.getFile());
        });
        logRow.addView(shareGuided, weight());

        box.addView(logRow);

        result = text("No active-control command sent from this screen yet.");
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextIsSelectable(true);
        result.setPadding(0, dp(10), 0, 0);
        box.addView(result);

        dialog = new AlertDialog.Builder(activity)
                .setTitle("Active controls")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        // Screen exit is a release condition. If the active request had an
        // uncertain communications result, PREF_PENDING remains true and this
        // still attempts the canonical zero-parameter clear packet.
        dialog.setOnDismissListener(d -> {
            stopStatusCallbacks();

            if (!releaseInProgress &&
                    prefs.getBoolean(PREF_PENDING, false)) {
                String label = prefs.getString(
                        PREF_LABEL,
                        "A5 override"
                );
                String release = prefs.getString(
                        PREF_RELEASE,
                        ""
                );
                int mode = modeFromCommand(release);

                if (!release.isEmpty() && mode >= 0) {
                    handler.postDelayed(
                            () -> sendReleaseInternal(
                                    label,
                                    release,
                                    mode,
                                    "Screen closed"
                            ),
                            150L
                    );
                }
            }
        });

        dialog.show();
    }

    // ---------------------------------------------------------------------
    // Mode 05 / ISC
    // ---------------------------------------------------------------------

    private void addIscCard(LinearLayout box) {
        LinearLayout card = cardBase();

        TextView title = text("ISC / IDLE CONTROL — MODE 05");
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);

        TextView confidence = text(
                "Release path PROVEN FIRMWARE • Air-volume meaning remains Strong inference"
        );
        confidence.setTextSize(12);
        card.addView(confidence);

        TextView info = text(
                "Allowed single selectors:\n" +
                "08 = ISC air-volume mode\n" +
                "20 = ISC reset/preset/learning procedure\n" +
                "80 = desired-idle RPM control\n\n" +
                "Do not combine selector bits. Canonical Mode-05 release:\n" +
                "A5 05 00 00 00 00"
        );
        info.setTextSize(12);
        card.addView(info);

        SeekBar rpmSeek = new SeekBar(activity);
        rpmSeek.setMax(12); // 1100..1700 in 50-rpm steps
        rpmSeek.setProgress(2); // 1200 rpm
        card.addView(rpmSeek);

        TextView rpmPreview = text("");
        rpmPreview.setTypeface(Typeface.MONOSPACE);
        card.addView(rpmPreview);

        Runnable refresh = () -> {
            int rpm = 1100 + rpmSeek.getProgress() * 50;
            int vv = rpmToVv(rpm);

            rpmPreview.setText(String.format(
                    Locale.US,
                    "Requested %d rpm\nTX A5 05 80 00 %02X 00\nRelease A5 05 00 00 00 00",
                    rpm,
                    vv
            ));
        };

        rpmSeek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        refresh.run();
                    }

                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                }
        );
        refresh.run();

        Button rpmStart = new Button(activity);
        rpmStart.setText("START ISC RPM CONTROL (30 s MAX)");
        rpmStart.setOnClickListener(v -> {
            int rpm = 1100 + rpmSeek.getProgress() * 50;
            int vv = rpmToVv(rpm);

            String command = String.format(
                    Locale.US,
                    "A5058000%02X00",
                    vv
            );

            confirmStart(
                    "ISC RPM control",
                    command,
                    "A50500000000",
                    0x05,
                    "Engine must be running. The ECU receives a desired-idle target; the app then watches actual RPM, error, settling, TPS, IAP, injector PW and ignition while normal polling continues.",
                    false,
                    30,
                    rpm
            );
        });
        card.addView(rpmStart);

        Button airTest = new Button(activity);
        airTest.setText("RUN ISC AIR-VOLUME TEST");
        airTest.setOnClickListener(v -> confirmIscAirVolumeTest());
        card.addView(airTest);

        Button release = new Button(activity);
        release.setText("RELEASE MODE 05 TO ECU");
        release.setOnClickListener(v ->
                sendRelease(
                        "ISC / Mode 05",
                        "A50500000000",
                        0x05
                )
        );
        card.addView(release);

        Button reset = new Button(activity);
        reset.setText("ISC LEARNED VALUE RESET / PRESET");
        reset.setOnClickListener(v -> confirmIscLearnReset());
        card.addView(reset);

        box.addView(card);
    }

    private int rpmToVv(int rpm) {
        int vv = (int)Math.round(rpm / 12.5);
        return Math.max(0x58, Math.min(0x88, vv));
    }

    private void confirmIscAirVolumeTest() {
        new AlertDialog.Builder(activity)
                .setTitle("ISC air-volume control")
                .setMessage(
                        "Experimental / Strong firmware inference\n\n" +
                        "Baseline: 21 08 / 80 / 90 / C0\n" +
                        "Active TX: A5 05 08 00 70 00\n" +
                        "Expected: E5 05\n" +
                        "Release: A5 05 00 00 00 00\n\n" +
                        "The app captures before/during/after state and always attempts the Mode-05 release packet."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                        "Run test",
                        (d, w) -> runIscAirVolumeTest()
                )
                .show();
    }

    private void runIscAirVolumeTest() {
        if (blockIfOverridePending()) return;

        lastGuidedLog = new GuidedTestLogger(
                activity,
                "ISC_air_volume"
        );
        result.setText("Capturing ISC air-volume baseline…");

        activity.runExclusiveSdsTask(
                "Guided ISC air-volume test",
                sds -> {
                    GuidedTestSupport.Snapshot before;
                    GuidedTestSupport.Snapshot during = null;
                    GuidedTestSupport.Snapshot after;
                    String activeResponse = "";
                    String releaseResponse = "";

                    activity.setProtocolOperationLabel(
                            "ISC AIR BASELINE"
                    );
                    before = GuidedTestSupport.captureAll(
                            sds,
                            lastGuidedLog,
                            "BASELINE",
                            false
                    );

                    markPending(
                            "ISC air-volume control",
                            "A50500000000"
                    );

                    try {
                        activity.setProtocolOperationLabel(
                                "ISC AIR ENABLE"
                        );
                        activeResponse = sds.requestRaw(
                                "A50508007000 1",
                                5000
                        );

                        lastGuidedLog.record(
                                "ACTION",
                                "A5",
                                "ISC air-volume",
                                activeResponse,
                                "TX A5 05 08 00 70 00"
                        );

                        Thread.sleep(1000L);

                        activity.setProtocolOperationLabel(
                                "ISC AIR ACTIVE"
                        );
                        during = GuidedTestSupport.captureAll(
                                sds,
                                lastGuidedLog,
                                "ACTION",
                                false
                        );

                        Thread.sleep(1500L);

                    } finally {
                        activity.setProtocolOperationLabel(
                                "ISC AIR RELEASE"
                        );

                        try {
                            releaseResponse = sds.requestRaw(
                                    "A50500000000 1",
                                    5000
                            );

                            lastGuidedLog.record(
                                    "RETURN",
                                    "A5",
                                    "ISC Mode-05 release",
                                    releaseResponse,
                                    "TX A5 05 00 00 00 00"
                            );

                            if (positive(releaseResponse, 0x05)) {
                                clearPending();
                            }

                        } catch (Exception releaseError) {
                            releaseResponse =
                                    "RELEASE ERROR: " +
                                    releaseError.getMessage();
                            // Keep pending state for recovery.
                        }
                    }

                    Thread.sleep(700L);

                    activity.setProtocolOperationLabel(
                            "ISC AIR AFTER"
                    );
                    after = GuidedTestSupport.captureAll(
                            sds,
                            lastGuidedLog,
                            "RETURN",
                            false
                    );

                    return new IscAirResult(
                            activeResponse,
                            releaseResponse,
                            before,
                            during,
                            after
                    );
                },
                (r, error) -> {
                    activity.setProtocolOperationLabel("");
                    refreshBanner();

                    if (error != null) {
                        result.setText(
                                "ISC air-volume test failed:\n" +
                                error.getMessage() +
                                "\n\nMode 05 remains marked possibly active unless the release was verified."
                        );
                        return;
                    }

                    String report =
                            "ISC AIR-VOLUME CONTROL\n" +
                            "Confidence: Experimental / Strong firmware inference\n\n" +
                            "Active RX: " + oneLine(r.activeResponse) + "\n" +
                            "Release RX: " + oneLine(r.releaseResponse) + "\n\n" +
                            GuidedTestSupport.diff(
                                    r.before,
                                    r.during,
                                    r.after
                            );

                    lastGuidedLog.record(
                            "RESULT",
                            "ANALYSIS",
                            "ISC air-volume",
                            "",
                            report
                    );
                    result.setText(report);
                }
        );
    }

    private void confirmIscLearnReset() {
        new AlertDialog.Builder(activity)
                .setTitle("ISC learned-value reset / preset")
                .setMessage(
                        "This is the firmware-identified A5 05 20 00 70 00 procedure. " +
                        "It triggers the ECU's internal reset/preset/learning state machine rather than directly writing 0x70 into calibration memory.\n\n" +
                        "The app captures 21 08 / 80 / 90 / C0, sends the procedure command, captures the immediate after-state, then clears Mode 05 with:\n" +
                        "A5 05 00 00 00 00\n\n" +
                        "Retained changes still need key-cycle testing."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                        "RESET / PRESET ISC",
                        (d, w) -> runIscLearnReset()
                )
                .show();
    }

    private void runIscLearnReset() {
        if (blockIfOverridePending()) return;

        lastGuidedLog = new GuidedTestLogger(
                activity,
                "ISC_learn_reset"
        );
        result.setText("Capturing ISC reset baseline…");

        activity.runExclusiveSdsTask(
                "Guided ISC learn reset",
                sds -> {
                    GuidedTestSupport.Snapshot before;
                    GuidedTestSupport.Snapshot afterProcedure;
                    GuidedTestSupport.Snapshot afterRelease;
                    String response = "";
                    String releaseResponse = "";

                    activity.setProtocolOperationLabel(
                            "ISC RESET BASELINE"
                    );
                    before = GuidedTestSupport.captureAll(
                            sds,
                            lastGuidedLog,
                            "BASELINE",
                            false
                    );

                    markPending(
                            "ISC learned reset/preset",
                            "A50500000000"
                    );

                    try {
                        activity.setProtocolOperationLabel(
                                "ISC RESET COMMAND"
                        );

                        response = sds.requestRaw(
                                "A50520007000 1",
                                5000
                        );

                        lastGuidedLog.record(
                                "ACTION",
                                "A5",
                                "ISC learned-state reset",
                                response,
                                "TX A5 05 20 00 70 00"
                        );

                        Thread.sleep(1500L);

                        activity.setProtocolOperationLabel(
                                "ISC RESET AFTER PROCEDURE"
                        );
                        afterProcedure =
                                GuidedTestSupport.captureAll(
                                        sds,
                                        lastGuidedLog,
                                        "AFTER_PROCEDURE",
                                        false
                                );

                    } finally {
                        activity.setProtocolOperationLabel(
                                "ISC RESET MODE05 RELEASE"
                        );

                        try {
                            releaseResponse = sds.requestRaw(
                                    "A50500000000 1",
                                    5000
                            );

                            lastGuidedLog.record(
                                    "RETURN",
                                    "A5",
                                    "ISC Mode-05 release",
                                    releaseResponse,
                                    "TX A5 05 00 00 00 00"
                            );

                            if (positive(releaseResponse, 0x05)) {
                                clearPending();
                            }

                        } catch (Exception releaseError) {
                            releaseResponse =
                                    "RELEASE ERROR: " +
                                    releaseError.getMessage();
                            // Pending marker intentionally retained.
                        }
                    }

                    Thread.sleep(700L);

                    activity.setProtocolOperationLabel(
                            "ISC RESET AFTER RELEASE"
                    );
                    afterRelease = GuidedTestSupport.captureAll(
                            sds,
                            lastGuidedLog,
                            "AFTER_RELEASE",
                            false
                    );

                    return new IscResetResult(
                            response,
                            releaseResponse,
                            before,
                            afterProcedure,
                            afterRelease
                    );
                },
                (r, error) -> {
                    activity.setProtocolOperationLabel("");
                    refreshBanner();

                    if (error != null) {
                        result.setText(
                                "ISC reset procedure failed:\n" +
                                error.getMessage() +
                                "\n\nMode 05 remains marked possibly active unless release was verified."
                        );
                        return;
                    }

                    String report =
                            "ISC RESET/PRESET RESPONSE: " +
                            oneLine(r.response) + "\n" +
                            "MODE-05 RELEASE: " +
                            oneLine(r.releaseResponse) + "\n\n" +
                            "Baseline -> procedure -> released comparison:\n" +
                            GuidedTestSupport.diff(
                                    r.before,
                                    r.afterProcedure,
                                    r.afterRelease
                            ) +
                            "\n\nInterpret retained changes only after a key-cycle follow-up.";

                    lastGuidedLog.record(
                            "RESULT",
                            "ANALYSIS",
                            "ISC reset/preset",
                            "",
                            report
                    );
                    result.setText(report);
                }
        );
    }

    // ---------------------------------------------------------------------
    // Held manual overrides
    // ---------------------------------------------------------------------

    private void confirmStart(
            String label,
            String command,
            String release,
            int mode,
            String warning,
            boolean verifyIgnitionBytes,
            int timeoutSeconds,
            int requestedRpm
    ) {
        if (blockIfOverridePending()) return;

        new AlertDialog.Builder(activity)
                .setTitle(label)
                .setMessage(
                        warning + "\n\n" +
                        "TX: " + spaced(command) + "\n" +
                        "Expected positive response: E5 " +
                        String.format(Locale.US, "%02X", mode) + "\n" +
                        "Canonical RELEASE: " +
                        spaced(release) + "\n" +
                        "Hard timeout: " +
                        timeoutSeconds + " seconds\n\n" +
                        "A positive E5 response proves command acceptance. " +
                        "Physical/live-data verification is shown separately."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                        "Send",
                        (d, w) -> sendStart(
                                label,
                                command,
                                release,
                                mode,
                                verifyIgnitionBytes,
                                timeoutSeconds,
                                requestedRpm
                        )
                )
                .show();
    }

    private void sendStart(
            String label,
            String command,
            String release,
            int mode,
            boolean verifyIgnitionBytes,
            int timeoutSeconds,
            int requestedRpm
    ) {
        result.setText("Sending " + label + "…");

        activity.runExclusiveSdsTask(
                "A5 " + label,
                sds -> {
                    sds.requestRaw("3E 1", 3000);

                    // Mark possibly-active immediately before the A5 write.
                    // If requestRaw times out after bytes went onto K-Line, the
                    // release remains recoverable instead of being forgotten.
                    markPending(label, release);

                    String response = sds.requestRaw(
                            command + " 1",
                            5000
                    );

                    String verification = "";

                    if (verifyIgnitionBytes &&
                            positive(response, mode)) {
                        Thread.sleep(250L);

                        String live = sds.requestRaw(
                                "2108 1",
                                4500
                        );

                        byte[] p =
                                GuidedTestSupport.extractPayload(
                                        live,
                                        0x08
                                );

                        if (p.length > 0x26) {
                            verification = String.format(
                                    Locale.US,
                                    "21 08 payload 23-26: %02X %02X %02X %02X",
                                    p[0x23] & 0xFF,
                                    p[0x24] & 0xFF,
                                    p[0x25] & 0xFF,
                                    p[0x26] & 0xFF
                            );
                        } else {
                            verification =
                                    "21 08 response too short for offsets 23-26";
                        }
                    }

                    return new ControlResult(
                            response,
                            verification
                    );
                },
                (r, error) -> {
                    if (error != null) {
                        // Keep PREF_PENDING: the ECU may have received the A5
                        // command even though the response was lost.
                        refreshBanner();

                        result.setText(
                                label + " communications error:\n" +
                                error.getMessage() +
                                "\n\nCommand is marked POSSIBLY ACTIVE. " +
                                "The app will attempt the zero-parameter release."
                        );

                        scheduleUncertainRelease(
                                label,
                                release,
                                mode
                        );
                        return;
                    }

                    if (negativeA5(r.response)) {
                        // 7F A5 12 means A5 exists but this request was not
                        // accepted; it is not a reason to call A5 unsupported.
                        clearPending();
                        resetActiveState();
                        refreshBanner();

                        result.setText(
                                label + "\n" +
                                "TX: " + spaced(command) + "\n" +
                                "RX: " + oneLine(r.response) + "\n" +
                                "Rejected by A5 handler. " +
                                "7F A5 12 = invalid format/parameter, not unsupported service."
                        );
                        return;
                    }

                    if (positive(r.response, mode)) {
                        beginHeldOverride(
                                label,
                                release,
                                mode,
                                timeoutSeconds,
                                requestedRpm
                        );
                    } else {
                        // Unknown/ambiguous non-negative response: retain the
                        // possible-active marker and clear it defensively.
                        refreshBanner();
                        scheduleUncertainRelease(
                                label,
                                release,
                                mode
                        );
                    }

                    result.setText(
                            label + "\n" +
                            "TX: " + spaced(command) + "\n" +
                            "RX: " + oneLine(r.response) + "\n" +
                            "Accepted: " +
                            (positive(r.response, mode)
                                    ? "YES"
                                    : "UNCERTAIN") +
                            (r.verification.isEmpty()
                                    ? ""
                                    : "\n" + r.verification)
                    );
                }
        );
    }

    private void beginHeldOverride(
            String label,
            String release,
            int mode,
            int timeoutSeconds,
            int requestedRpm
    ) {
        stopStatusCallbacks();

        activeLabel = label;
        activeRelease = release;
        activeMode = mode;
        activeDeadlineElapsedMs =
                SystemClock.elapsedRealtime() +
                timeoutSeconds * 1000L;

        iscRequestedRpm = requestedRpm;

        if (requestedRpm > 0) {
            resetIscStats();
            iscStartedElapsedMs =
                    SystemClock.elapsedRealtime();
        }

        timeoutReleaseRunnable = () ->
                sendReleaseInternal(
                        label,
                        release,
                        mode,
                        "Automatic timeout"
                );

        handler.postDelayed(
                timeoutReleaseRunnable,
                timeoutSeconds * 1000L
        );

        activeStatusRunnable = new Runnable() {
            @Override public void run() {
                if (!prefs.getBoolean(PREF_PENDING, false) ||
                        activeMode != mode) {
                    return;
                }

                refreshBanner();

                if (iscRequestedRpm > 0 &&
                        activeMode == 0x05) {
                    updateIscLiveVerification();
                }

                handler.postDelayed(
                        this,
                        STATUS_REFRESH_MS
                );
            }
        };

        handler.post(activeStatusRunnable);
        refreshBanner();
    }

    private void scheduleUncertainRelease(
            String label,
            String release,
            int mode
    ) {
        stopStatusCallbacks();

        activeLabel = label;
        activeRelease = release;
        activeMode = mode;

        // Give runExclusiveSdsTask time to restore normal polling / transport
        // state, then make a separate best-effort clear request.
        handler.postDelayed(
                () -> {
                    if (prefs.getBoolean(PREF_PENDING, false)) {
                        sendReleaseInternal(
                                label,
                                release,
                                mode,
                                "Recovery after uncertain A5 response"
                        );
                    }
                },
                1200L
        );
    }

    private void updateIscLiveVerification() {
        BanditLiveData d = activity.getLastLiveData();
        if (d == null) return;

        addIscRpmSample(d.rpm);

        int error = d.rpm - iscRequestedRpm;
        long now = SystemClock.elapsedRealtime();

        if (Math.abs(error) <= 50) {
            if (iscWithinTargetSinceMs < 0) {
                iscWithinTargetSinceMs = now;
            }

            if (iscSettlingTimeMs < 0 &&
                    now - iscWithinTargetSinceMs >= 1500L) {
                iscSettlingTimeMs =
                        iscWithinTargetSinceMs -
                        iscStartedElapsedMs;
            }
        } else {
            iscWithinTargetSinceMs = -1L;
        }

        double sd = iscSampleCount > 1
                ? Math.sqrt(
                        iscM2Rpm /
                        iscSampleCount
                )
                : 0.0;

        int p2p = iscSampleCount > 0
                ? iscMaxRpm - iscMinRpm
                : 0;

        long remainingMs = Math.max(
                0L,
                activeDeadlineElapsedMs -
                now
        );

        String settling = iscSettlingTimeMs >= 0
                ? String.format(
                        Locale.US,
                        "%.2f s",
                        iscSettlingTimeMs / 1000.0
                )
                : "not settled";

        result.setText(String.format(
                Locale.US,
                "ISC RPM CONTROL — ACTIVE\n" +
                "Requested: %d rpm\n" +
                "Actual: %d rpm\n" +
                "Target error: %+d rpm\n" +
                "Settling: %s\n" +
                "RPM SD: %.2f rpm\n" +
                "RPM peak-to-peak: %d rpm\n" +
                "TPS: %.2f %%\n" +
                "IAP: %.2f kPa\n" +
                "Injectors: %.3f / %.3f / %.3f / %.3f ms\n" +
                "Ignition: %.1f / %.1f / %.1f / %.1f deg\n" +
                "Idle target raw: 0x%02X\n" +
                "ISC raw: 0x%02X\n" +
                "Auto release in: %.1f s\n\n" +
                "Verification requires RPM moving toward target or a corresponding ISC/live-state change.",
                iscRequestedRpm,
                d.rpm,
                error,
                settling,
                sd,
                p2p,
                d.tpsPct,
                d.iap1Kpa,
                d.inj1,
                d.inj2,
                d.inj3,
                d.inj4,
                d.ign1Deg,
                d.ign2Deg,
                d.ign3Deg,
                d.ign4Deg,
                d.idleSpeedRaw & 0xFF,
                d.iscRaw & 0xFF,
                remainingMs / 1000.0
        ));
    }

    private void resetIscStats() {
        iscWithinTargetSinceMs = -1L;
        iscSettlingTimeMs = -1L;
        iscSampleCount = 0;
        iscMinRpm = Integer.MAX_VALUE;
        iscMaxRpm = Integer.MIN_VALUE;
        iscMeanRpm = 0.0;
        iscM2Rpm = 0.0;
    }

    private void addIscRpmSample(int rpm) {
        iscSampleCount++;
        iscMinRpm = Math.min(iscMinRpm, rpm);
        iscMaxRpm = Math.max(iscMaxRpm, rpm);

        double delta = rpm - iscMeanRpm;
        iscMeanRpm += delta / iscSampleCount;
        double delta2 = rpm - iscMeanRpm;
        iscM2Rpm += delta * delta2;
    }

    // ---------------------------------------------------------------------
    // Release handling
    // ---------------------------------------------------------------------

    private void sendRelease(
            String label,
            String command,
            int mode
    ) {
        stopStatusCallbacks();
        sendReleaseInternal(
                label,
                command,
                mode,
                "Manual release"
        );
    }

    private void sendReleaseInternal(
            String label,
            String command,
            int mode,
            String reason
    ) {
        if (releaseInProgress) return;

        releaseInProgress = true;

        if (result != null) {
            result.setText(
                    "Releasing " + label +
                    "…\nReason: " + reason
            );
        }

        activity.runExclusiveSdsTask(
                "A5 release " + label,
                sds -> {
                    try {
                        sds.requestRaw("3E 1", 3000);
                    } catch (Exception ignored) {
                        // Still attempt the canonical release packet.
                    }

                    return sds.requestRaw(
                            command + " 1",
                            5000
                    );
                },
                (response, error) -> {
                    releaseInProgress = false;

                    if (error != null) {
                        // Keep the possibly-active marker for reconnect recovery.
                        refreshBanner();

                        if (result != null) {
                            result.setText(
                                    "Release attempt failed:\n" +
                                    error.getMessage() +
                                    "\n\nOverride remains marked POSSIBLY ACTIVE. " +
                                    "Retry release after communications recover."
                            );
                        }
                        return;
                    }

                    boolean accepted =
                            positive(response, mode);

                    if (accepted) {
                        clearPending();
                        resetActiveState();
                    }

                    refreshBanner();

                    if (result != null) {
                        result.setText(
                                "Release " + label + "\n" +
                                "Reason: " + reason + "\n" +
                                "TX: " + spaced(command) + "\n" +
                                "RX: " + oneLine(response) + "\n" +
                                "Release accepted: " +
                                (accepted ? "YES" : "NO / UNCERTAIN")
                        );
                    }
                }
        );
    }

    private void confirmReleaseAll() {
        new AlertDialog.Builder(activity)
                .setTitle("Release all implemented A5 modes?")
                .setMessage(
                        "This sends the canonical zero-parameter clear packet for every implemented D4FASE80 mode:\n\n" +
                        "00 ignition\n" +
                        "01 PAIR\n" +
                        "03 STVA\n" +
                        "05 ISC family\n" +
                        "06 fan\n" +
                        "07 optional output\n\n" +
                        "These are RELEASE TO ECU commands, not requests to force each physical output OFF."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                        "Release all",
                        (d, w) -> releaseAllKnown()
                )
                .show();
    }

    private void releaseAllKnown() {
        stopStatusCallbacks();

        activity.runExclusiveSdsTask(
                "Release all implemented A5 overrides",
                sds -> {
                    StringBuilder r =
                            new StringBuilder();

                    String[] commands = {
                            "A50000000000",
                            "A50100000000",
                            "A50300000000",
                            "A50500000000",
                            "A50600000000",
                            "A50700000000"
                    };

                    for (String command : commands) {
                        int mode =
                                modeFromCommand(command);

                        String response =
                                sds.requestRaw(
                                        command + " 1",
                                        5000
                                );

                        r.append(spaced(command))
                                .append(" -> ")
                                .append(oneLine(response))
                                .append(
                                        positive(response, mode)
                                                ? " [accepted]"
                                                : " [not verified]"
                                )
                                .append("\n");
                    }

                    return r.toString().trim();
                },
                (report, error) -> {
                    if (error != null) {
                        if (result != null) {
                            result.setText(
                                    "Release-all interrupted:\n" +
                                    error.getMessage() +
                                    "\n\nA pending mode marker is retained if one existed."
                            );
                        }
                        return;
                    }

                    clearPending();
                    resetActiveState();
                    refreshBanner();

                    if (result != null) {
                        result.setText(
                                "Release-all complete\n\n" +
                                report
                        );
                    }
                }
        );
    }

    private void refreshBanner() {
        if (banner == null) return;

        if (prefs.getBoolean(PREF_PENDING, false)) {
            StringBuilder message =
                    new StringBuilder();

            message.append(
                    "⚠ A5 OVERRIDE ACTIVE / POSSIBLY ACTIVE\n"
            );
            message.append(
                    prefs.getString(
                            PREF_LABEL,
                            "Unknown A5 control"
                    )
            );
            message.append(
                    "\nRelease: "
            );
            message.append(
                    spaced(
                            prefs.getString(
                                    PREF_RELEASE,
                                    ""
                            )
                    )
            );

            if (activeDeadlineElapsedMs > 0) {
                long remaining = Math.max(
                        0L,
                        activeDeadlineElapsedMs -
                        SystemClock.elapsedRealtime()
                );

                message.append(
                        String.format(
                                Locale.US,
                                "\nAuto release: %.1f s",
                                remaining / 1000.0
                        )
                );
            }

            banner.setText(message.toString());
            banner.setTextColor(
                    Color.rgb(190, 0, 0)
            );

        } else {
            banner.setText(
                    "No A5 override recorded as active"
            );
            banner.setTextColor(
                    Color.rgb(0, 130, 0)
            );
        }
    }

    private void markPending(
            String label,
            String release
    ) {
        prefs.edit()
                .putBoolean(PREF_PENDING, true)
                .putString(PREF_LABEL, label)
                .putString(PREF_RELEASE, release)
                .putLong(
                        "a5_pending_since",
                        System.currentTimeMillis()
                )
                .apply();
    }

    private void clearPending() {
        prefs.edit()
                .putBoolean(PREF_PENDING, false)
                .remove(PREF_LABEL)
                .remove(PREF_RELEASE)
                .remove("a5_pending_since")
                .apply();
    }

    private boolean blockIfOverridePending() {
        if (!prefs.getBoolean(
                PREF_PENDING,
                false
        )) {
            return false;
        }

        new AlertDialog.Builder(activity)
                .setTitle("A5 override already active")
                .setMessage(
                        "The app already has an A5 mode recorded as ACTIVE or POSSIBLY ACTIVE:\n\n" +
                        prefs.getString(
                                PREF_LABEL,
                                "Unknown A5 control"
                        ) +
                        "\n\nRelease that mode before starting another active-control command."
                )
                .setPositiveButton("OK", null)
                .show();

        return true;
    }

    private void stopStatusCallbacks() {
        if (timeoutReleaseRunnable != null) {
            handler.removeCallbacks(
                    timeoutReleaseRunnable
            );
            timeoutReleaseRunnable = null;
        }

        if (activeStatusRunnable != null) {
            handler.removeCallbacks(
                    activeStatusRunnable
            );
            activeStatusRunnable = null;
        }
    }

    private void resetActiveState() {
        stopStatusCallbacks();

        activeDeadlineElapsedMs = -1L;
        activeMode = -1;
        activeLabel = "";
        activeRelease = "";
        iscRequestedRpm = -1;
        iscStartedElapsedMs = -1L;
        resetIscStats();
    }

    // ---------------------------------------------------------------------
    // Response helpers / UI
    // ---------------------------------------------------------------------

    private boolean positive(
            String response,
            int mode
    ) {
        if (response == null || mode < 0) {
            return false;
        }

        String hex = response
                .toUpperCase(Locale.US)
                .replaceAll(
                        "[^0-9A-F]",
                        ""
                );

        return hex.contains(
                String.format(
                        Locale.US,
                        "E5%02X",
                        mode & 0xFF
                )
        );
    }

    private boolean negativeA5(String response) {
        if (response == null) return false;

        String hex = response
                .toUpperCase(Locale.US)
                .replaceAll(
                        "[^0-9A-F]",
                        ""
                );

        return hex.contains("7FA5");
    }

    private int modeFromCommand(String command) {
        if (command == null) return -1;

        String compact = command
                .toUpperCase(Locale.US)
                .replaceAll(
                        "[^0-9A-F]",
                        ""
                );

        if (compact.length() < 4 ||
                !compact.startsWith("A5")) {
            return -1;
        }

        try {
            return Integer.parseInt(
                    compact.substring(2, 4),
                    16
            );
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void addCard(
            LinearLayout parent,
            String titleText,
            String confidenceText,
            String commandsText,
            String infoText,
            String[] buttonLabels,
            Runnable[] actions
    ) {
        LinearLayout card = cardBase();

        TextView title = text(titleText);
        title.setTextSize(16);
        title.setTypeface(
                null,
                Typeface.BOLD
        );
        card.addView(title);

        TextView confidence =
                text(confidenceText);
        confidence.setTextSize(12);
        card.addView(confidence);

        TextView commands =
                text(commandsText);
        commands.setTypeface(
                Typeface.MONOSPACE
        );
        commands.setTextSize(11);
        commands.setTextIsSelectable(true);
        card.addView(commands);

        Button info = new Button(activity);
        info.setText("INFO");
        info.setOnClickListener(v ->
                new AlertDialog.Builder(activity)
                        .setTitle(titleText)
                        .setMessage(
                                infoText +
                                "\n\n" +
                                commandsText +
                                "\n\nConfidence: " +
                                confidenceText
                        )
                        .setPositiveButton(
                                "OK",
                                null
                        )
                        .show()
        );
        card.addView(info);

        for (int i = 0;
             i < buttonLabels.length;
             i++) {
            Button b =
                    new Button(activity);
            b.setText(buttonLabels[i]);

            final Runnable action =
                    actions[i];

            b.setOnClickListener(
                    v -> action.run()
            );
            card.addView(b);
        }

        parent.addView(card);
    }

    private LinearLayout cardBase() {
        LinearLayout card =
                new LinearLayout(activity);

        card.setOrientation(
                LinearLayout.VERTICAL
        );

        card.setPadding(
                dp(10),
                dp(8),
                dp(10),
                dp(8)
        );

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        lp.topMargin = dp(8);
        lp.bottomMargin = dp(4);
        card.setLayoutParams(lp);

        card.setBackgroundColor(
                Color.argb(20, 0, 0, 0)
        );

        return card;
    }

    private TextView text(String value) {
        TextView t =
                new TextView(activity);

        t.setText(value);
        t.setTextSize(13);

        t.setPadding(
                0,
                dp(3),
                0,
                dp(3)
        );

        return t;
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
                value *
                activity.getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    private static String oneLine(
            String value
    ) {
        if (value == null) return "";

        return value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
    }

    private static String spaced(
            String hex
    ) {
        if (hex == null) return "";

        String compact = hex
                .replaceAll(
                        "[^0-9A-Fa-f]",
                        ""
                )
                .toUpperCase(Locale.US);

        return compact.replaceAll(
                "(.{2})(?!$)",
                "$1 "
        );
    }

    // ---------------------------------------------------------------------
    // Result containers
    // ---------------------------------------------------------------------

    private static final class ControlResult {
        final String response;
        final String verification;

        ControlResult(
                String response,
                String verification
        ) {
            this.response = response;
            this.verification =
                    verification == null
                            ? ""
                            : verification;
        }
    }

    private static final class IscAirResult {
        final String activeResponse;
        final String releaseResponse;
        final GuidedTestSupport.Snapshot before;
        final GuidedTestSupport.Snapshot during;
        final GuidedTestSupport.Snapshot after;

        IscAirResult(
                String activeResponse,
                String releaseResponse,
                GuidedTestSupport.Snapshot before,
                GuidedTestSupport.Snapshot during,
                GuidedTestSupport.Snapshot after
        ) {
            this.activeResponse =
                    activeResponse;
            this.releaseResponse =
                    releaseResponse;
            this.before = before;
            this.during = during;
            this.after = after;
        }
    }

    private static final class IscResetResult {
        final String response;
        final String releaseResponse;
        final GuidedTestSupport.Snapshot before;
        final GuidedTestSupport.Snapshot afterProcedure;
        final GuidedTestSupport.Snapshot afterRelease;

        IscResetResult(
                String response,
                String releaseResponse,
                GuidedTestSupport.Snapshot before,
                GuidedTestSupport.Snapshot afterProcedure,
                GuidedTestSupport.Snapshot afterRelease
        ) {
            this.response = response;
            this.releaseResponse =
                    releaseResponse;
            this.before = before;
            this.afterProcedure =
                    afterProcedure;
            this.afterRelease =
                    afterRelease;
        }
    }
}
