package com.bandit1250.fuelmonitor;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.widget.*;

import java.util.*;

/**
 * Guided diagnostic workflows built on the firmware-proven SDS pages.
 *
 * Each test uses phase-labelled protocol logging plus a separate guided CSV.
 * Unknown bytes remain raw and are compared rather than guessed.
 */
public final class GuidedDiagnosticsDialog {
    private final MainActivity activity;

    private TextView result;
    private GuidedTestLogger lastLog;

    public GuidedDiagnosticsDialog(MainActivity activity) {
        this.activity = activity;
    }

    public void show() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(box);

        TextView intro = text(
                "GUIDED DIAGNOSTICS\n\n" +
                "Each workflow records raw TX/RX plus phase-labelled 21 08 / 80 / 90 / C0 data. " +
                "Results are intended both for fault-finding and for handing back to firmware analysis."
        );
        intro.setTextSize(12);
        box.addView(intro);

        addButton(box, "TPS SWEEP", this::confirmTpsSweep);
        addButton(box, "SECONDARY THROTTLE / STVA TEST", this::confirmStvaTest);
        addButton(box, "ADC INPUT IDENTIFIER", () ->
                openManualDiff(
                        "ADC_input_identifier",
                        "Manipulate ONE known analogue input",
                        "Capture a stable baseline.",
                        "Move only the chosen input and hold it at the changed position.",
                        "Return the input to its original position.",
                        false
                )
        );
        addButton(box, "DIGITAL SWITCH IDENTIFIER", () ->
                openManualDiff(
                        "digital_switch_identifier",
                        "Identify a switch / digital input",
                        "Do not touch the selected switch.",
                        "Operate and HOLD the selected switch.",
                        "Release it and return to baseline.",
                        false
                )
        );
        addButton(box, "CYLINDER CONTRIBUTION", () ->
                openManualDiff(
                        "cylinder_contribution",
                        "Cylinder contribution comparison",
                        "Warm engine at idle. Capture with all cylinders operating.",
                        "Perform the selected low-voltage injector/coil-primary disconnect procedure and hold long enough to stabilise.",
                        "Reconnect, allow recovery, then capture.",
                        true
                )
        );
        addButton(box, "IGNITION A/B IDLE-HUNT TEST", this::confirmIgnitionAb);
        addButton(box, "COOLING FAN GUIDED TEST", () ->
                confirmOutputTest(
                        "fan_test",
                        "Cooling fan",
                        "A50680800000",
                        "A50600000000",
                        0x06,
                        "This should run the cooling fan. Keep hands and tools clear."
                )
        );
        addButton(box, "PAIR GUIDED TEST", () ->
                confirmOutputTest(
                        "pair_test",
                        "PAIR solenoid",
                        "A50180000000",
                        "A50100000000",
                        0x01,
                        "Listen/feel for the PAIR solenoid click."
                )
        );
        addButton(box, "MODE 07 IDENTIFICATION", () ->
                confirmOutputTest(
                        "mode07_identification",
                        "Mode 07 optional output / likely EVAP",
                        "A50780000000",
                        "A50700000000",
                        0x07,
                        "Experimental. Prefer ignition ON / engine OFF. Note any solenoid or relay click."
                )
        );
        addButton(box, "ENGINE-OFF INPUT MAPPING", () ->
                openManualDiff(
                        "engine_off_input_mapping",
                        "Ignition ON / engine OFF input mapping",
                        "Ignition ON, engine OFF. Do nothing and capture.",
                        "Perform ONE requested action: clutch, throttle, gear position or another switch. Hold it.",
                        "Return to the original state and capture.",
                        false
                )
        );
        addButton(box, "ENGINE-RUNNING SIGNAL MAPPING", () ->
                openManualDiff(
                        "engine_running_signal_mapping",
                        "Engine-running signal identification",
                        "Capture a stable idle baseline.",
                        "Hold ONE controlled engine state, for example ~1500/2000/2500 rpm.",
                        "Return to idle and allow the engine to settle.",
                        false
                )
        );
        addButton(box, "DTC CORRELATION TEST", () ->
                openManualDiff(
                        "dtc_correlation",
                        "Generate / identify one safe DTC",
                        "Read/capture with the selected component connected and no intentional fault.",
                        "Create ONE controlled low-voltage sensor/injector fault and hold it long enough for the ECU to detect.",
                        "Restore the connector/state. Do not clear the code automatically.",
                        true
                )
        );

        LinearLayout logRow = new LinearLayout(activity);
        logRow.setOrientation(LinearLayout.HORIZONTAL);

        Button share = new Button(activity);
        share.setText("SHARE LAST GUIDED CSV");
        share.setOnClickListener(v -> {
            if (lastLog == null) {
                Toast.makeText(activity, "No guided test has been run yet", Toast.LENGTH_SHORT).show();
                return;
            }
            activity.shareGuidedLog(lastLog.getFile());
        });
        logRow.addView(share, weight());

        Button protocol = new Button(activity);
        protocol.setText("SHARE PROTOCOL CSV");
        protocol.setOnClickListener(v -> activity.shareProtocolLog());
        logRow.addView(protocol, weight());

        box.addView(logRow);

        result = text("Select a guided test.");
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextIsSelectable(true);
        result.setPadding(0, dp(10), 0, 0);
        box.addView(result);

        new AlertDialog.Builder(activity)
                .setTitle("Guided diagnostics")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------------------------------------------------------------------
    // Generic manual BASELINE -> ACTION -> RETURN workflow
    // ---------------------------------------------------------------------

    private void openManualDiff(
            String testId,
            String title,
            String baselineInstruction,
            String actionInstruction,
            String returnInstruction,
            boolean includeDtc
    ) {
        lastLog = new GuidedTestLogger(activity, testId);
        new ManualDiffDialog(
                title,
                baselineInstruction,
                actionInstruction,
                returnInstruction,
                includeDtc,
                lastLog
        ).show();
    }

    private final class ManualDiffDialog {
        private final String title;
        private final String baselineInstruction;
        private final String actionInstruction;
        private final String returnInstruction;
        private final boolean includeDtc;
        private final GuidedTestLogger log;

        private GuidedTestSupport.Snapshot baseline;
        private GuidedTestSupport.Snapshot action;
        private GuidedTestSupport.Snapshot after;

        private TextView instruction;
        private TextView localResult;
        private Button captureBaseline;
        private Button captureAction;
        private Button captureReturn;

        ManualDiffDialog(
                String title,
                String baselineInstruction,
                String actionInstruction,
                String returnInstruction,
                boolean includeDtc,
                GuidedTestLogger log
        ) {
            this.title = title;
            this.baselineInstruction = baselineInstruction;
            this.actionInstruction = actionInstruction;
            this.returnInstruction = returnInstruction;
            this.includeDtc = includeDtc;
            this.log = log;
        }

        void show() {
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(18), dp(8), dp(18), dp(18));

            instruction = text("Step 1 — BASELINE\n" + baselineInstruction);
            instruction.setTypeface(null, Typeface.BOLD);
            box.addView(instruction);

            captureBaseline = new Button(activity);
            captureBaseline.setText("CAPTURE BASELINE");
            captureBaseline.setOnClickListener(v -> capturePhase("BASELINE"));
            box.addView(captureBaseline);

            captureAction = new Button(activity);
            captureAction.setText("CAPTURE ACTION");
            captureAction.setEnabled(false);
            captureAction.setOnClickListener(v -> capturePhase("ACTION"));
            box.addView(captureAction);

            captureReturn = new Button(activity);
            captureReturn.setText("CAPTURE RETURN");
            captureReturn.setEnabled(false);
            captureReturn.setOnClickListener(v -> capturePhase("RETURN"));
            box.addView(captureReturn);

            Button share = new Button(activity);
            share.setText("SHARE THIS TEST CSV");
            share.setOnClickListener(v -> activity.shareGuidedLog(log.getFile()));
            box.addView(share);

            localResult = text("No captures yet.");
            localResult.setTypeface(Typeface.MONOSPACE);
            localResult.setTextIsSelectable(true);
            box.addView(localResult);

            new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setView(box)
                    .setNegativeButton("Close", null)
                    .show();
        }

        private void capturePhase(String phase) {
            setButtons(false);
            localResult.setText("Capturing " + phase + "…");

            activity.runExclusiveSdsTask(
                    "Guided " + title + " " + phase,
                    sds -> {
                        activity.setProtocolOperationLabel(
                                "GUIDED " + title + " " + phase
                        );
                        return GuidedTestSupport.captureAll(
                                sds,
                                log,
                                phase,
                                includeDtc
                        );
                    },
                    (snapshot, error) -> {
                        activity.setProtocolOperationLabel("");

                        if (error != null) {
                            localResult.setText("Capture failed:\n" + error.getMessage());
                            setButtons(true);
                            return;
                        }

                        if ("BASELINE".equals(phase)) {
                            baseline = snapshot;
                            captureBaseline.setEnabled(false);
                            captureAction.setEnabled(true);
                            captureReturn.setEnabled(false);
                            instruction.setText(
                                    "Step 2 — ACTION\n" + actionInstruction
                            );
                            localResult.setText(
                                    "Baseline captured.\n\n" +
                                    adcSummary(snapshot)
                            );

                        } else if ("ACTION".equals(phase)) {
                            action = snapshot;
                            captureBaseline.setEnabled(false);
                            captureAction.setEnabled(false);
                            captureReturn.setEnabled(true);
                            instruction.setText(
                                    "Step 3 — RETURN\n" + returnInstruction
                            );
                            localResult.setText(
                                    "Action captured.\n\n" +
                                    GuidedTestSupport.diff(
                                            baseline,
                                            action,
                                            null
                                    )
                            );

                        } else {
                            after = snapshot;
                            captureBaseline.setEnabled(false);
                            captureAction.setEnabled(false);
                            captureReturn.setEnabled(false);
                            instruction.setText(
                                    "Complete — review differences and share the CSV."
                            );

                            String report = GuidedTestSupport.diff(
                                    baseline,
                                    action,
                                    after
                            );
                            localResult.setText(report);

                            log.record(
                                    "RESULT",
                                    "ANALYSIS",
                                    "byte_diff",
                                    "",
                                    report
                            );
                            result.setText(title + " complete. Last guided CSV updated.");
                        }
                    }
            );
        }

        private void setButtons(boolean enabled) {
            if (captureBaseline != null && baseline == null) captureBaseline.setEnabled(enabled);
            if (captureAction != null && baseline != null && action == null) captureAction.setEnabled(enabled);
            if (captureReturn != null && action != null && after == null) captureReturn.setEnabled(enabled);
        }
    }

    // ---------------------------------------------------------------------
    // TPS timed sweep
    // ---------------------------------------------------------------------

    private void confirmTpsSweep() {
        new AlertDialog.Builder(activity)
                .setTitle("Throttle-position sensor sweep")
                .setMessage(
                        "Default test: ignition ON, engine OFF.\n\n" +
                        "After starting:\n" +
                        "0-2 s: hold throttle fully CLOSED\n" +
                        "2-5 s: slowly OPEN to full\n" +
                        "5-8 s: slowly CLOSE\n" +
                        "8-10 s: hold fully CLOSED\n\n" +
                        "The app samples 21 08 plus all 16 page-90 ADC channels and ranks the ADC channel that best follows TPS."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Start 10 s sweep", (d, w) -> runTpsSweep())
                .show();
    }

    private void runTpsSweep() {
        lastLog = new GuidedTestLogger(activity, "TPS_sweep");
        result.setText("TPS sweep starting… hold throttle CLOSED.");

        activity.runExclusiveSdsTask(
                "Guided TPS sweep",
                sds -> {
                    ArrayList<Double> tSec = new ArrayList<>();
                    ArrayList<Double> tps = new ArrayList<>();
                    ArrayList<Integer> tpsRaw = new ArrayList<>();
                    ArrayList<int[]> adcs = new ArrayList<>();

                    long t0 = System.nanoTime();
                    long end = t0 + 10_000_000_000L;

                    while (System.nanoTime() < end) {
                        double sec = (System.nanoTime() - t0) / 1_000_000_000.0;
                        String phase = sec < 2.0
                                ? "CLOSED_BASELINE"
                                : sec < 5.0
                                ? "OPENING"
                                : sec < 8.0
                                ? "CLOSING"
                                : "CLOSED_RETURN";

                        activity.setProtocolOperationLabel("TPS SWEEP " + phase);

                        String r08 = sds.requestRaw("2108 1", 4500);
                        String r90 = sds.requestRaw("2190 1", 5500);

                        BanditLiveData d = BanditDecoder.decode(r08);
                        byte[] frame = BanditDecoder.extract(r08);
                        int rawTps = frame.length > 15
                                ? frame[15] & 0xFF
                                : -1;

                        int[] adc = GuidedTestSupport.page90AdcCounts(
                                GuidedTestSupport.extractPayload(r90, 0x90)
                        );

                        tSec.add(sec);
                        tps.add(d.tpsPct);
                        tpsRaw.add(rawTps);
                        adcs.add(adc);

                        lastLog.record(
                                phase,
                                "SAMPLE",
                                "TPS",
                                r08 + " | " + r90,
                                String.format(
                                        Locale.US,
                                        "t=%.3f;tps=%.3f;raw=%d",
                                        sec,
                                        d.tpsPct,
                                        rawTps
                                )
                        );

                        final double uiSec = sec;
                        final String uiPhase = phase;
                        activity.runOnUiThread(() ->
                                result.setText(String.format(
                                        Locale.US,
                                        "TPS sweep %.1f / 10.0 s\n%s",
                                        uiSec,
                                        uiPhase
                                ))
                        );

                        Thread.sleep(80L);
                    }

                    return analyseTps(tSec, tps, tpsRaw, adcs);
                },
                (report, error) -> {
                    activity.setProtocolOperationLabel("");

                    if (error != null) {
                        result.setText("TPS sweep failed:\n" + error.getMessage());
                        return;
                    }

                    lastLog.record(
                            "RESULT",
                            "ANALYSIS",
                            "TPS sweep",
                            "",
                            report
                    );
                    result.setText(report);
                }
        );
    }

    private String analyseTps(
            List<Double> time,
            List<Double> tps,
            List<Integer> raw,
            List<int[]> adcs
    ) {
        if (tps.isEmpty()) return "TPS sweep returned no samples.";

        double min = Collections.min(tps);
        double max = Collections.max(tps);
        double span = max - min;
        double largestJump = 0.0;
        int backwardsOpening = 0;
        int backwardsClosing = 0;

        ArrayList<Double> closedStart = new ArrayList<>();
        ArrayList<Double> closedEnd = new ArrayList<>();

        for (int i = 1; i < tps.size(); i++) {
            double dt = Math.abs(tps.get(i) - tps.get(i - 1));
            largestJump = Math.max(largestJump, dt);

            double sec = time.get(i);
            if (sec >= 2.0 && sec < 5.0 && tps.get(i) + 0.25 < tps.get(i - 1)) {
                backwardsOpening++;
            }
            if (sec >= 5.0 && sec < 8.0 && tps.get(i) - 0.25 > tps.get(i - 1)) {
                backwardsClosing++;
            }
        }

        for (int i = 0; i < tps.size(); i++) {
            if (time.get(i) < 2.0) closedStart.add(tps.get(i));
            if (time.get(i) >= 8.0) closedEnd.add(tps.get(i));
        }

        double closedNoise = stddev(closedStart);
        double closedReturn = Math.abs(mean(closedEnd) - mean(closedStart));

        int bestAdc = -1;
        double bestAbsCorr = 0.0;
        int bestSpan = 0;

        for (int ch = 0; ch < 16; ch++) {
            ArrayList<Double> x = new ArrayList<>();
            ArrayList<Double> y = new ArrayList<>();
            int adcMin = Integer.MAX_VALUE;
            int adcMax = Integer.MIN_VALUE;

            for (int i = 0; i < adcs.size(); i++) {
                int v = adcs.get(i)[ch];
                if (v < 0) continue;

                x.add(tps.get(i));
                y.add((double)v);
                adcMin = Math.min(adcMin, v);
                adcMax = Math.max(adcMax, v);
            }

            double corr = correlation(x, y);
            int aSpan = adcMax >= adcMin ? adcMax - adcMin : 0;

            if (Math.abs(corr) > bestAbsCorr && aSpan > 5) {
                bestAbsCorr = Math.abs(corr);
                bestAdc = ch;
                bestSpan = aSpan;
            }
        }

        String judgement;
        if (span < 50.0) {
            judgement = "INSUFFICIENT TPS TRAVEL / CHECK TEST";
        } else if (backwardsOpening > 2 || backwardsClosing > 2 || largestJump > 10.0) {
            judgement = "POSSIBLE DEAD SPOT / IRREGULARITY";
        } else if (closedReturn > 3.0 || closedNoise > 1.5) {
            judgement = "POOR CLOSED-THROTTLE REPEATABILITY";
        } else if (backwardsOpening > 0 || backwardsClosing > 0 || largestJump > 5.0) {
            judgement = "SMALL IRREGULARITY";
        } else {
            judgement = "PASS / SMOOTH SWEEP";
        }

        return String.format(
                Locale.US,
                "TPS SWEEP RESULT\n" +
                "%s\n\n" +
                "Samples: %d\n" +
                "Min: %.2f %%\n" +
                "Max: %.2f %%\n" +
                "Span: %.2f %%\n" +
                "Opening backwards jumps: %d\n" +
                "Closing backwards jumps: %d\n" +
                "Largest single jump: %.2f %%\n" +
                "Closed noise SD: %.3f %%\n" +
                "Return-to-closed error: %.3f %%\n\n" +
                "Best page90 ADC candidate: ADC%s\n" +
                "ADC span: %d counts\n" +
                "|correlation with TPS|: %.3f\n" +
                "Candidate only until repeated.",
                judgement,
                tps.size(),
                min,
                max,
                span,
                backwardsOpening,
                backwardsClosing,
                largestJump,
                closedNoise,
                closedReturn,
                bestAdc < 0 ? "—" : Integer.toString(bestAdc),
                bestSpan,
                bestAbsCorr
        );
    }

    // ---------------------------------------------------------------------
    // STVA and simple active-output guided tests
    // ---------------------------------------------------------------------

    private void confirmStvaTest() {
        new AlertDialog.Builder(activity)
                .setTitle("Secondary throttle / STVA guided test")
                .setMessage(
                        "Use ignition ON and engine OFF. The test captures baseline, commands Extreme A, releases, commands Extreme B, releases, then compares page-90 ADC and all raw engineering pages.\n\n" +
                        "A/B will remain named A/B until physical direction is confirmed."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run", (d, w) -> runStvaTest())
                .show();
    }

    private void runStvaTest() {
        lastLog = new GuidedTestLogger(activity, "STVA_identification");
        result.setText("Running STVA A/B test…");

        activity.runExclusiveSdsTask(
                "Guided STVA identification",
                sds -> {
                    activity.setProtocolOperationLabel("STVA BASELINE");
                    GuidedTestSupport.Snapshot baseline =
                            GuidedTestSupport.captureAll(sds, lastLog, "BASELINE", false);

                    activity.setProtocolOperationLabel("STVA EXTREME A");
                    String aResp = sds.requestRaw("A50380000000 1", 5000);
                    lastLog.record("ACTION_A", "A5", "STVA Extreme A", aResp, "TX A5 03 80 00 00 00");
                    Thread.sleep(800L);
                    GuidedTestSupport.Snapshot a =
                            GuidedTestSupport.captureAll(sds, lastLog, "EXTREME_A", false);

                    activity.setProtocolOperationLabel("STVA RELEASE A");
                    sds.requestRaw("A50300000000 1", 5000);
                    Thread.sleep(700L);

                    activity.setProtocolOperationLabel("STVA EXTREME B");
                    String bResp = sds.requestRaw("A50380800000 1", 5000);
                    lastLog.record("ACTION_B", "A5", "STVA Extreme B", bResp, "TX A5 03 80 80 00 00");
                    Thread.sleep(800L);
                    GuidedTestSupport.Snapshot b =
                            GuidedTestSupport.captureAll(sds, lastLog, "EXTREME_B", false);

                    activity.setProtocolOperationLabel("STVA FINAL RELEASE");
                    String release = sds.requestRaw("A50300000000 1", 5000);
                    Thread.sleep(800L);
                    GuidedTestSupport.Snapshot after =
                            GuidedTestSupport.captureAll(sds, lastLog, "RETURN", false);

                    return new StvaResult(
                            aResp,
                            bResp,
                            release,
                            baseline,
                            a,
                            b,
                            after
                    );
                },
                (r, error) -> {
                    activity.setProtocolOperationLabel("");

                    if (error != null) {
                        result.setText("STVA guided test failed:\n" + error.getMessage());
                        return;
                    }

                    String report =
                            "STVA EXTREME A\n" +
                            GuidedTestSupport.diff(r.baseline, r.a, r.after) +
                            "\n\nSTVA EXTREME B\n" +
                            GuidedTestSupport.diff(r.baseline, r.b, r.after) +
                            "\n\nA response: " + oneLine(r.aResponse) +
                            "\nB response: " + oneLine(r.bResponse) +
                            "\nRelease: " + oneLine(r.releaseResponse) +
                            "\n\nUse the ADC deltas plus physical observation to identify OPEN/CLOSED.";

                    lastLog.record("RESULT", "ANALYSIS", "STVA A/B", "", report);
                    result.setText(report);
                }
        );
    }

    private void confirmOutputTest(
            String testId,
            String label,
            String enable,
            String release,
            int mode,
            String warning
    ) {
        new AlertDialog.Builder(activity)
                .setTitle(label + " guided test")
                .setMessage(
                        warning + "\n\n" +
                        "Enable TX: " + spaced(enable) + "\n" +
                        "Release TX: " + spaced(release) + "\n\n" +
                        "The app captures all engineering pages before/during/after and always attempts the release packet."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run short test", (d, w) ->
                        runOutputTest(testId, label, enable, release, mode)
                )
                .show();
    }

    private void runOutputTest(
            String testId,
            String label,
            String enable,
            String release,
            int mode
    ) {
        lastLog = new GuidedTestLogger(activity, testId);
        result.setText("Running " + label + " guided test…");

        activity.runExclusiveSdsTask(
                "Guided " + label,
                sds -> {
                    GuidedTestSupport.Snapshot baseline = null;
                    GuidedTestSupport.Snapshot action = null;
                    GuidedTestSupport.Snapshot after = null;
                    String enableResp = "";
                    String releaseResp = "";

                    try {
                        activity.setProtocolOperationLabel(label + " BASELINE");
                        baseline = GuidedTestSupport.captureAll(
                                sds,
                                lastLog,
                                "BASELINE",
                                false
                        );

                        activity.setProtocolOperationLabel(label + " ENABLE");
                        enableResp = sds.requestRaw(enable + " 1", 5000);
                        lastLog.record("ACTION", "A5", label, enableResp, "TX " + spaced(enable));

                        Thread.sleep(900L);

                        activity.setProtocolOperationLabel(label + " ACTIVE CAPTURE");
                        action = GuidedTestSupport.captureAll(
                                sds,
                                lastLog,
                                "ACTION",
                                false
                        );

                    } finally {
                        activity.setProtocolOperationLabel(label + " RELEASE");
                        try {
                            releaseResp = sds.requestRaw(release + " 1", 5000);
                            lastLog.record("RETURN", "A5", label + " release", releaseResp, "TX " + spaced(release));
                        } catch (Exception releaseError) {
                            releaseResp = "RELEASE ERROR: " + releaseError.getMessage();
                        }
                    }

                    Thread.sleep(800L);

                    activity.setProtocolOperationLabel(label + " AFTER");
                    after = GuidedTestSupport.captureAll(
                            sds,
                            lastLog,
                            "RETURN",
                            false
                    );

                    return new OutputResult(
                            enableResp,
                            releaseResp,
                            baseline,
                            action,
                            after,
                            positive(enableResp, mode),
                            positive(releaseResp, mode)
                    );
                },
                (r, error) -> {
                    activity.setProtocolOperationLabel("");

                    if (error != null) {
                        result.setText(
                                label + " test failed:\n" +
                                error.getMessage() +
                                "\n\nCheck that the release command was accepted in the protocol log."
                        );
                        return;
                    }

                    String report =
                            label.toUpperCase(Locale.US) + "\n" +
                            "Enable accepted: " + r.enableAccepted + "\n" +
                            "Release accepted: " + r.releaseAccepted + "\n\n" +
                            GuidedTestSupport.diff(r.baseline, r.action, r.after) +
                            "\n\nPhysical operation still requires sensor/user confirmation.";

                    lastLog.record("RESULT", "ANALYSIS", label, "", report);
                    result.setText(report);
                }
        );
    }

    // ---------------------------------------------------------------------
    // Ignition A/B idle-hunt test
    // ---------------------------------------------------------------------

    private void confirmIgnitionAb() {
        new AlertDialog.Builder(activity)
                .setTitle("Ignition correction A/B idle-hunt test")
                .setMessage(
                        "Warm engine at idle. This runs approximately:\n" +
                        "10 s normal baseline\n" +
                        "10 s A5 Mode-00 ignition stabilisation\n" +
                        "10 s released/normal\n\n" +
                        "The app compares RPM variation and verifies page-08 payload bytes 0x23-0x26."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run A/B test", (d, w) -> runIgnitionAb())
                .show();
    }

    private void runIgnitionAb() {
        lastLog = new GuidedTestLogger(activity, "ignition_idle_hunt_AB");
        result.setText("Ignition A/B test: baseline…");

        activity.runExclusiveSdsTask(
                "Guided ignition idle-hunt A/B",
                sds -> {
                    PhaseStats baseline = sampleRpmPhase(
                            sds,
                            "BASELINE",
                            10_000L
                    );

                    activity.setProtocolOperationLabel("IGNITION STABILISE ENABLE");
                    String enable = sds.requestRaw("A50080000000 1", 5000);
                    lastLog.record("ACTION", "A5", "Ignition stabilisation", enable, "TX A5 00 80 00 00 00");

                    PhaseStats active;
                    String release;

                    try {
                        active = sampleRpmPhase(
                                sds,
                                "STABILISED",
                                10_000L
                        );
                    } finally {
                        activity.setProtocolOperationLabel("IGNITION STABILISE RELEASE");
                        release = sds.requestRaw("A50000000000 1", 5000);
                        lastLog.record("RETURN", "A5", "Ignition release", release, "TX A5 00 00 00 00 00");
                    }

                    PhaseStats after = sampleRpmPhase(
                            sds,
                            "AFTER",
                            10_000L
                    );

                    return new IgnitionAbResult(
                            enable,
                            release,
                            baseline,
                            active,
                            after
                    );
                },
                (r, error) -> {
                    activity.setProtocolOperationLabel("");

                    if (error != null) {
                        result.setText("Ignition A/B test failed:\n" + error.getMessage());
                        return;
                    }

                    String judgement;
                    if (!r.active.saw40404040) {
                        judgement = "MODE 00 DID NOT VERIFY AT PAGE-08 0x23-0x26";
                    } else if (r.active.rpmStdDev < r.baseline.rpmStdDev * 0.70) {
                        judgement = "HUNT SIGNIFICANTLY REDUCED WITH IGNITION CORRECTION STABILISED";
                    } else if (r.active.rpmStdDev > r.baseline.rpmStdDev * 1.30) {
                        judgement = "RPM VARIATION INCREASED WITH IGNITION STABILISATION";
                    } else {
                        judgement = "HUNT / RPM VARIATION LITTLE CHANGED";
                    }

                    String report = String.format(
                            Locale.US,
                            "IGNITION A/B IDLE-HUNT RESULT\n%s\n\n" +
                            "Baseline: mean %.1f rpm, SD %.2f, p-p %d\n" +
                            "Stabilised: mean %.1f rpm, SD %.2f, p-p %d\n" +
                            "After: mean %.1f rpm, SD %.2f, p-p %d\n" +
                            "Saw 40 40 40 40 at page08 23-26: %s\n\n" +
                            "Enable RX: %s\nRelease RX: %s",
                            judgement,
                            r.baseline.rpmMean,
                            r.baseline.rpmStdDev,
                            r.baseline.rpmPeakToPeak,
                            r.active.rpmMean,
                            r.active.rpmStdDev,
                            r.active.rpmPeakToPeak,
                            r.after.rpmMean,
                            r.after.rpmStdDev,
                            r.after.rpmPeakToPeak,
                            r.active.saw40404040,
                            oneLine(r.enableResponse),
                            oneLine(r.releaseResponse)
                    );

                    lastLog.record("RESULT", "ANALYSIS", "Ignition A/B", "", report);
                    result.setText(report);
                }
        );
    }

    private PhaseStats sampleRpmPhase(
            SuzukiSds sds,
            String phase,
            long durationMs
    ) throws Exception {
        ArrayList<Double> rpm = new ArrayList<>();
        boolean saw40 = false;

        long start = System.currentTimeMillis();
        long end = start + durationMs;

        while (System.currentTimeMillis() < end) {
            activity.setProtocolOperationLabel("IGNITION AB " + phase);

            String response = sds.requestRaw("2108 1", 4500);
            BanditLiveData d = BanditDecoder.decode(response);
            rpm.add((double)d.rpm);

            byte[] p = GuidedTestSupport.extractPayload(response, 0x08);
            if (p.length > 0x26 &&
                    (p[0x23] & 0xFF) == 0x40 &&
                    (p[0x24] & 0xFF) == 0x40 &&
                    (p[0x25] & 0xFF) == 0x40 &&
                    (p[0x26] & 0xFF) == 0x40) {
                saw40 = true;
            }

            lastLog.record(
                    phase,
                    "SAMPLE",
                    "21 08",
                    response,
                    "rpm=" + d.rpm
            );

            final long elapsed = System.currentTimeMillis() - start;
            activity.runOnUiThread(() ->
                    result.setText(
                            "Ignition A/B — " + phase + "\n" +
                            (elapsed / 1000.0) + " / " + (durationMs / 1000.0) + " s"
                    )
            );

            Thread.sleep(120L);
        }

        return new PhaseStats(
                mean(rpm),
                stddev(rpm),
                peakToPeak(rpm),
                saw40
        );
    }

    // ---------------------------------------------------------------------
    // Math / helpers
    // ---------------------------------------------------------------------

    private String adcSummary(GuidedTestSupport.Snapshot s) {
        if (s == null) return "";
        return "Page90 ADC counts\n" +
                GuidedTestSupport.formatAdcTable(
                        s.page(GuidedTestSupport.PAGE_90)
                );
    }

    private static double mean(List<Double> values) {
        if (values == null || values.isEmpty()) return Double.NaN;
        double sum = 0.0;
        for (double v : values) sum += v;
        return sum / values.size();
    }

    private static double stddev(List<Double> values) {
        if (values == null || values.size() < 2) return 0.0;
        double m = mean(values);
        double ss = 0.0;
        for (double v : values) {
            double d = v - m;
            ss += d * d;
        }
        return Math.sqrt(ss / values.size());
    }

    private static int peakToPeak(List<Double> values) {
        if (values == null || values.isEmpty()) return 0;
        double min = Collections.min(values);
        double max = Collections.max(values);
        return (int)Math.round(max - min);
    }

    private static double correlation(List<Double> x, List<Double> y) {
        int n = Math.min(x.size(), y.size());
        if (n < 3) return 0.0;

        double mx = 0.0;
        double my = 0.0;

        for (int i = 0; i < n; i++) {
            mx += x.get(i);
            my += y.get(i);
        }
        mx /= n;
        my /= n;

        double num = 0.0;
        double dx2 = 0.0;
        double dy2 = 0.0;

        for (int i = 0; i < n; i++) {
            double dx = x.get(i) - mx;
            double dy = y.get(i) - my;
            num += dx * dy;
            dx2 += dx * dx;
            dy2 += dy * dy;
        }

        double den = Math.sqrt(dx2 * dy2);
        return den <= 1e-12 ? 0.0 : num / den;
    }

    private static boolean positive(String response, int mode) {
        if (response == null) return false;
        String hex = response.toUpperCase(Locale.US)
                .replaceAll("[^0-9A-F]", "");
        return hex.contains(String.format(Locale.US, "E5%02X", mode & 0xFF));
    }

    private void addButton(LinearLayout box, String label, Runnable action) {
        Button b = new Button(activity);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        box.addView(b);
    }

    private TextView text(String value) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextSize(13);
        t.setPadding(0, dp(3), 0, dp(3));
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
                value * activity.getResources().getDisplayMetrics().density
        );
    }

    private static String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String spaced(String hex) {
        String compact = hex.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
        return compact.replaceAll("(.{2})(?!$)", "$1 ");
    }

    private static final class StvaResult {
        final String aResponse;
        final String bResponse;
        final String releaseResponse;
        final GuidedTestSupport.Snapshot baseline;
        final GuidedTestSupport.Snapshot a;
        final GuidedTestSupport.Snapshot b;
        final GuidedTestSupport.Snapshot after;

        StvaResult(
                String aResponse,
                String bResponse,
                String releaseResponse,
                GuidedTestSupport.Snapshot baseline,
                GuidedTestSupport.Snapshot a,
                GuidedTestSupport.Snapshot b,
                GuidedTestSupport.Snapshot after
        ) {
            this.aResponse = aResponse;
            this.bResponse = bResponse;
            this.releaseResponse = releaseResponse;
            this.baseline = baseline;
            this.a = a;
            this.b = b;
            this.after = after;
        }
    }

    private static final class OutputResult {
        final String enableResponse;
        final String releaseResponse;
        final GuidedTestSupport.Snapshot baseline;
        final GuidedTestSupport.Snapshot action;
        final GuidedTestSupport.Snapshot after;
        final boolean enableAccepted;
        final boolean releaseAccepted;

        OutputResult(
                String enableResponse,
                String releaseResponse,
                GuidedTestSupport.Snapshot baseline,
                GuidedTestSupport.Snapshot action,
                GuidedTestSupport.Snapshot after,
                boolean enableAccepted,
                boolean releaseAccepted
        ) {
            this.enableResponse = enableResponse;
            this.releaseResponse = releaseResponse;
            this.baseline = baseline;
            this.action = action;
            this.after = after;
            this.enableAccepted = enableAccepted;
            this.releaseAccepted = releaseAccepted;
        }
    }

    private static final class PhaseStats {
        final double rpmMean;
        final double rpmStdDev;
        final int rpmPeakToPeak;
        final boolean saw40404040;

        PhaseStats(
                double rpmMean,
                double rpmStdDev,
                int rpmPeakToPeak,
                boolean saw40404040
        ) {
            this.rpmMean = rpmMean;
            this.rpmStdDev = rpmStdDev;
            this.rpmPeakToPeak = rpmPeakToPeak;
            this.saw40404040 = saw40404040;
        }
    }

    private static final class IgnitionAbResult {
        final String enableResponse;
        final String releaseResponse;
        final PhaseStats baseline;
        final PhaseStats active;
        final PhaseStats after;

        IgnitionAbResult(
                String enableResponse,
                String releaseResponse,
                PhaseStats baseline,
                PhaseStats active,
                PhaseStats after
        ) {
            this.enableResponse = enableResponse;
            this.releaseResponse = releaseResponse;
            this.baseline = baseline;
            this.active = active;
            this.after = after;
        }
    }
}
