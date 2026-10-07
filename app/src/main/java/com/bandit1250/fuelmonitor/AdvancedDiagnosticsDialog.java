package com.bandit1250.fuelmonitor;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import android.widget.*;

import java.util.*;
import java.util.function.Consumer;

/**
 * High-rate diagnostic workflows added for V0.27.
 *
 * Design rules:
 * - exclusive ownership of the existing initialised SDS session;
 * - no deliberate inter-sample sleep for FAST 21-page captures;
 * - preserve raw TX/RX in ProtocolSessionLogger;
 * - guided CSV records phase summaries without adding I/O to the hot path;
 * - active controls always use a canonical release in a finally block.
 */
public final class AdvancedDiagnosticsDialog {
    private static final long FAST_30S = 30_000L;
    private static final long FAST_60S = 60_000L;

    private final MainActivity activity;
    private TextView result;
    private GuidedTestLogger lastLog;

    public AdvancedDiagnosticsDialog(MainActivity activity) {
        this.activity = activity;
    }

    public void show() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(box);

        TextView intro = text(
                "FAST / ADVANCED DIAGNOSTICS\n\n" +
                "High-rate tests use one SDS request repeatedly and suspend normal polling. " +
                "Raw TX/RX and timings remain in the protocol CSV.\n\n" +
                "Reference firmware: D4FASE80. Actual bike: D4F9SE01. " +
                "Firmware-derived behaviour remains BIKE TEST REQUIRED until confirmed."
        );
        intro.setTextSize(12);
        box.addView(intro);

        addButton(box, "FAST RPM ROUGHNESS + PASSIVE CYLINDER COMMANDS",
                () -> confirmFastRoughness(FAST_30S));

        addButton(box, "FAST CYLINDER CONTRIBUTION",
                () -> new ContributionDialog(false).show());

        addButton(box, "MODE-00 STABILISED CYLINDER CONTRIBUTION",
                () -> new ContributionDialog(true).show());

        addButton(box, "MODE-00 IDLE-HUNT A/B — FAST",
                this::confirmFastIgnitionAb);

        addButton(box, "MODE-00 RPM-WINDOW CHARACTERISATION",
                this::showMode00RpmWindow);

        addButton(box, "ISC / MODE-05 CHARACTERISATION",
                this::confirmIscCharacterisation);

        addButton(box, "FAST 21 90 ADC NOISE TEST",
                () -> runFastPage90(FAST_30S));

        addButton(box, "FAST 21 80 ENGINEERING CAPTURE",
                () -> runFastRawPage(0x80, FAST_30S));

        addButton(box, "FAST 21 C0 ENGINEERING CAPTURE",
                () -> runFastRawPage(0xC0, FAST_30S));

        addButton(box, "ECU VOLTAGE / INJECTOR-SUPPLY CORRELATION",
                this::confirmVoltageCorrelation);

        addButton(box, "DTC DETECTION / RECOVERY LATENCY",
                () -> new DtcLatencyDialog().show());

        TextView existing = text(
                "\nExisting guided/active tests retained in this build:\n" +
                "• Mode 03 / STVA physical direction and ADC6 movement\n" +
                "• Mode 07 optional-output identification\n" +
                "• PAIR / fan / ISC active controls with timed canonical release"
        );
        existing.setTextSize(11);
        box.addView(existing);

        Button share = new Button(activity);
        share.setText("SHARE LAST ADVANCED TEST CSV");
        share.setOnClickListener(v -> {
            if (lastLog == null) {
                Toast.makeText(activity, "No advanced test has run yet", Toast.LENGTH_SHORT).show();
            } else {
                activity.shareGuidedLog(lastLog.getFile());
            }
        });
        box.addView(share);

        Button protocol = new Button(activity);
        protocol.setText("SHARE PROTOCOL CSV");
        protocol.setOnClickListener(v -> activity.shareProtocolLog());
        box.addView(protocol);

        result = text("Select a test.");
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextIsSelectable(true);
        result.setPadding(0, dp(10), 0, 0);
        box.addView(result);

        new AlertDialog.Builder(activity)
                .setTitle("Fast / advanced diagnostics")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------------------------------------------------------------------
    // FAST 21 08 roughness / passive command comparison
    // ---------------------------------------------------------------------

    private void confirmFastRoughness(long durationMs) {
        new AlertDialog.Builder(activity)
                .setTitle("FAST RPM roughness test")
                .setMessage(
                        "Use a fully warm engine at stable idle.\n\n" +
                        "For the capture only 21 08 is requested, with no deliberate inter-sample delay. " +
                        "The result includes RPM roughness, successive drops, approximate periodicity, " +
                        "lead/supporting correlations and per-cylinder injector/ignition command asymmetry.\n\n" +
                        "Command asymmetry alone is NOT labelled as a weak cylinder."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run 30 s", (d, w) ->
                        runLiveStatsTest(
                                "fast_roughness_passive",
                                "FAST ROUGHNESS",
                                durationMs,
                                true
                        )
                )
                .show();
    }

    private void runLiveStatsTest(
            String testId,
            String operation,
            long durationMs,
            boolean detailed
    ) {
        lastLog = new GuidedTestLogger(activity, testId);
        result.setText(operation + " running…");

        activity.runExclusiveSdsTask(
                operation,
                sds -> capture2108(sds, durationMs),
                (stats, error) -> {
                    if (error != null) {
                        result.setText(operation + " failed:\n" + error.getMessage());
                        return;
                    }
                    String report = detailed
                            ? stats.detailedReport(operation)
                            : stats.shortReport(operation);
                    lastLog.record("RESULT", "ANALYSIS", operation, "", report);
                    result.setText(report);
                }
        );
    }

    // ---------------------------------------------------------------------
    // Cylinder contribution
    // ---------------------------------------------------------------------

    private final class ContributionDialog {
        private final boolean mode00;
        private final ArrayList<CutResult> completed = new ArrayList<>();

        private AlertDialog dialog;
        private TextView local;
        private Spinner cylSpinner;
        private Spinner setSpinner;
        private Button baselineButton;
        private Button disabledButton;
        private Button recoveryButton;

        private LiveStats baseline;
        private LiveStats disabled;

        ContributionDialog(boolean mode00) {
            this.mode00 = mode00;
        }

        void show() {
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(18), dp(8), dp(18), dp(18));

            TextView note = text(
                    (mode00
                            ? "MODE-00 STABILISED CONTRIBUTION\n"
                            : "FAST CYLINDER CONTRIBUTION\n") +
                    "Injector LOW-VOLTAGE connector only. Do not pull an HT lead.\n\n" +
                    "Each cylinder uses a fresh 10 s baseline, 5 s disabled capture and 10 s recovery. " +
                    "Repeat complete sets 2–3 times; the report uses median RPM drop per cylinder.\n" +
                    (mode00
                            ? "\nEach capture phase enables A5 00 80 00 00 00, verifies page-08 23–26, " +
                              "and releases A5 00 00 00 00 00 immediately afterwards. " +
                              "Label: ignition timing/correction stabilisation — STRONG FIRMWARE INFERENCE."
                            : "")
            );
            note.setTextSize(12);
            box.addView(note);

            LinearLayout pick = new LinearLayout(activity);
            pick.setOrientation(LinearLayout.HORIZONTAL);

            cylSpinner = new Spinner(activity);
            cylSpinner.setAdapter(new ArrayAdapter<>(
                    activity,
                    android.R.layout.simple_spinner_dropdown_item,
                    new String[]{"Cylinder 1", "Cylinder 2", "Cylinder 3", "Cylinder 4"}
            ));
            pick.addView(cylSpinner, weight());

            setSpinner = new Spinner(activity);
            setSpinner.setAdapter(new ArrayAdapter<>(
                    activity,
                    android.R.layout.simple_spinner_dropdown_item,
                    new String[]{"Set 1", "Set 2", "Set 3"}
            ));
            pick.addView(setSpinner, weight());
            box.addView(pick);

            baselineButton = new Button(activity);
            baselineButton.setText("1 — CAPTURE 10 s BASELINE");
            baselineButton.setOnClickListener(v -> runBaseline());
            box.addView(baselineButton);

            disabledButton = new Button(activity);
            disabledButton.setText("2 — INJECTOR UNPLUGGED: CAPTURE 5 s");
            disabledButton.setEnabled(false);
            disabledButton.setOnClickListener(v -> runDisabled());
            box.addView(disabledButton);

            recoveryButton = new Button(activity);
            recoveryButton.setText("3 — RECONNECTED: CAPTURE 10 s RECOVERY");
            recoveryButton.setEnabled(false);
            recoveryButton.setOnClickListener(v -> runRecovery());
            box.addView(recoveryButton);

            local = text("Select cylinder/set, then capture baseline.");
            local.setTypeface(Typeface.MONOSPACE);
            local.setTextIsSelectable(true);
            box.addView(local);

            dialog = new AlertDialog.Builder(activity)
                    .setTitle(mode00 ? "Mode-00 contribution" : "Fast contribution")
                    .setView(box)
                    .setNegativeButton("Close", null)
                    .create();
            dialog.show();
        }

        private void runBaseline() {
            int cyl = cylSpinner.getSelectedItemPosition() + 1;
            int set = setSpinner.getSelectedItemPosition() + 1;
            lastLog = new GuidedTestLogger(
                    activity,
                    (mode00 ? "mode00_" : "") +
                            "cylinder_contribution_set" + set + "_cyl" + cyl
            );

            setBusy(true);
            local.setText("Baseline capture running… keep all four injectors connected.");

            runContributionPhase("BASELINE", 10_000L, stats -> {
                baseline = stats;
                disabled = null;
                disabledButton.setEnabled(true);
                recoveryButton.setEnabled(false);
                baselineButton.setEnabled(false);
                local.setText(
                        stats.shortReport("BASELINE") +
                        "\n\nNow disconnect injector #" + cyl +
                        " LOW-VOLTAGE electrical connector, then press step 2."
                );
            });
        }

        private void runDisabled() {
            int cyl = cylSpinner.getSelectedItemPosition() + 1;
            setBusy(true);
            local.setText("Disabled capture running… injector #" + cyl + " must remain unplugged.");

            runContributionPhase("CYL" + cyl + "_DISABLED", 5_000L, stats -> {
                disabled = stats;
                recoveryButton.setEnabled(true);
                disabledButton.setEnabled(false);
                local.setText(
                        stats.shortReport("CYL " + cyl + " DISABLED") +
                        "\n\nReconnect injector #" + cyl +
                        ", allow it to fire, then press step 3."
                );
            });
        }

        private void runRecovery() {
            int cyl = cylSpinner.getSelectedItemPosition() + 1;
            int set = setSpinner.getSelectedItemPosition() + 1;
            setBusy(true);
            local.setText("Recovery capture running…");

            runContributionPhase("RECOVERY", 10_000L, recovery -> {
                CutResult cut = new CutResult(set, cyl, baseline, disabled, recovery);
                completed.add(cut);

                String report = cut.report() + "\n\n" + contributionSummary(completed);
                lastLog.record("RESULT", "ANALYSIS", "Cylinder contribution", "", report);
                local.setText(report);

                baseline = null;
                disabled = null;
                baselineButton.setEnabled(true);
                disabledButton.setEnabled(false);
                recoveryButton.setEnabled(false);
            });
        }

        private void runContributionPhase(
                String phase,
                long durationMs,
                Consumer<LiveStats> done
        ) {
            activity.runExclusiveSdsTask(
                    (mode00 ? "Mode00 " : "") + "Contribution " + phase,
                    sds -> {
                        if (!mode00) {
                            return capture2108(sds, durationMs);
                        }

                        String enable = sds.requestRaw("A50080000000 1", 5000);
                        if (lastLog != null) {
                            lastLog.record(phase, "A5", "Mode00 enable", enable,
                                    "TX A5 00 80 00 00 00");
                        }

                        LiveStats stats;
                        String release = "";
                        try {
                            stats = capture2108(sds, durationMs);
                        } finally {
                            release = sds.requestRaw("A50000000000 1", 5000);
                            if (lastLog != null) {
                                lastLog.record(phase, "A5", "Mode00 release", release,
                                        "TX A5 00 00 00 00 00");
                            }
                        }

                        stats.mode00EnableAccepted = positive(enable, 0x00);
                        stats.mode00ReleaseAccepted = positive(release, 0x00);
                        return stats;
                    },
                    (stats, error) -> {
                        setBusy(false);
                        if (error != null) {
                            local.setText(
                                    phase + " failed:\n" + error.getMessage() +
                                    (mode00
                                            ? "\n\nCheck protocol log to confirm canonical Mode-00 release."
                                            : "")
                            );
                            baselineButton.setEnabled(true);
                            return;
                        }
                        if (lastLog != null) {
                            lastLog.record(phase, "SUMMARY", "21 08", "", stats.shortReport(phase));
                        }
                        done.accept(stats);
                    }
            );
        }

        private void setBusy(boolean busy) {
            cylSpinner.setEnabled(!busy);
            setSpinner.setEnabled(!busy);
            baselineButton.setEnabled(!busy && baseline == null);
            disabledButton.setEnabled(!busy && baseline != null && disabled == null);
            recoveryButton.setEnabled(!busy && disabled != null);
        }
    }

    private static final class CutResult {
        final int set;
        final int cylinder;
        final LiveStats baseline;
        final LiveStats disabled;
        final LiveStats recovery;

        CutResult(int set, int cylinder, LiveStats baseline, LiveStats disabled, LiveStats recovery) {
            this.set = set;
            this.cylinder = cylinder;
            this.baseline = baseline;
            this.disabled = disabled;
            this.recovery = recovery;
        }

        double drop() {
            return baseline.rpm.mean() - disabled.rpm.mean();
        }

        double dropRate() {
            if (disabled.rpmTimes.isEmpty()) return Double.NaN;
            double first = disabled.rpmValues.get(0);
            double min = disabled.rpm.min();
            long t0 = disabled.rpmTimes.get(0);
            long tm = disabled.timeOfMinRpmMs;
            double dt = Math.max(0.001, (tm - t0) / 1000.0);
            return (first - min) / dt;
        }

        double recoverySeconds() {
            double target = baseline.rpm.mean();
            double tol = Math.max(20.0, Math.abs(target) * 0.03);
            int streak = 0;
            for (int i = 0; i < recovery.rpmValues.size(); i++) {
                if (Math.abs(recovery.rpmValues.get(i) - target) <= tol) {
                    streak++;
                    if (streak >= 3) {
                        int start = i - 2;
                        return (recovery.rpmTimes.get(start) - recovery.rpmTimes.get(0)) / 1000.0;
                    }
                } else {
                    streak = 0;
                }
            }
            return Double.NaN;
        }

        String report() {
            return String.format(
                    Locale.UK,
                    "SET %d / CYLINDER %d\n" +
                    "Baseline RPM %.1f\nDisabled RPM %.1f\nMinimum RPM %.0f\n" +
                    "Contribution drop %.1f rpm\nApprox drop rate %.1f rpm/s\n" +
                    "Disabled RPM SD %.2f\nIAP1 change %+.2f kPa\nIAP2 change %+.2f kPa\n" +
                    "Recovery time %s s\n" +
                    "PW delta 1–4: %+.3f / %+.3f / %+.3f / %+.3f ms\n" +
                    "Ign delta 1–4: %+.2f / %+.2f / %+.2f / %+.2f deg%s",
                    set,
                    cylinder,
                    baseline.rpm.mean(),
                    disabled.rpm.mean(),
                    disabled.rpm.min(),
                    drop(),
                    dropRate(),
                    disabled.rpm.sd(),
                    disabled.iap1.mean() - baseline.iap1.mean(),
                    disabled.iap2.mean() - baseline.iap2.mean(),
                    fmt(recoverySeconds()),
                    disabled.inj[0].mean() - baseline.inj[0].mean(),
                    disabled.inj[1].mean() - baseline.inj[1].mean(),
                    disabled.inj[2].mean() - baseline.inj[2].mean(),
                    disabled.inj[3].mean() - baseline.inj[3].mean(),
                    disabled.ign[0].mean() - baseline.ign[0].mean(),
                    disabled.ign[1].mean() - baseline.ign[1].mean(),
                    disabled.ign[2].mean() - baseline.ign[2].mean(),
                    disabled.ign[3].mean() - baseline.ign[3].mean(),
                    disabled.saw40404040 ? "\nMode00 40/40/40/40 observed: YES" : ""
            );
        }
    }

    private static String contributionSummary(List<CutResult> all) {
        StringBuilder out = new StringBuilder("SESSION MEDIAN CONTRIBUTION\n");
        double[] med = new double[4];
        Arrays.fill(med, Double.NaN);

        for (int cyl = 1; cyl <= 4; cyl++) {
            ArrayList<Double> drops = new ArrayList<>();
            for (CutResult r : all) if (r.cylinder == cyl) drops.add(r.drop());
            if (!drops.isEmpty()) med[cyl - 1] = median(drops);
        }

        double sum = 0.0;
        int n = 0;
        for (double v : med) {
            if (Double.isFinite(v)) {
                sum += v;
                n++;
            }
        }
        double avg = n == 0 ? Double.NaN : sum / n;

        for (int i = 0; i < 4; i++) {
            if (Double.isFinite(med[i])) {
                double rel = Double.isFinite(avg) && Math.abs(avg) > 0.001
                        ? med[i] / avg * 100.0
                        : Double.NaN;
                out.append(String.format(
                        Locale.UK,
                        "Cyl %d  median %.1f rpm  relative %s%%\n",
                        i + 1,
                        med[i],
                        fmt(rel)
                ));
            } else {
                out.append("Cyl ").append(i + 1).append("  not yet tested\n");
            }
        }
        out.append("\nUse repeated cuts; one cut is not definitive.");
        return out.toString().trim();
    }

    // ---------------------------------------------------------------------
    // Mode 00 fast A/B and operating-window checks
    // ---------------------------------------------------------------------

    private void confirmFastIgnitionAb() {
        new AlertDialog.Builder(activity)
                .setTitle("FAST Mode-00 idle-hunt A/B")
                .setMessage(
                        "Warm engine, hands clear. The test runs:\n" +
                        "20 s normal FAST 21 08\n" +
                        "20 s Mode-00 stabilised FAST 21 08\n" +
                        "canonical release\n" +
                        "20 s normal after-release FAST 21 08\n\n" +
                        "Mode 00 is labelled STRONG FIRMWARE INFERENCE. Exact fixed ignition degrees are not claimed."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run A/B", (d, w) -> runFastIgnitionAb())
                .show();
    }

    private void runFastIgnitionAb() {
        lastLog = new GuidedTestLogger(activity, "fast_mode00_idle_hunt_AB");
        result.setText("FAST Mode-00 A/B running…");

        activity.runExclusiveSdsTask(
                "FAST Mode00 idle A/B",
                sds -> {
                    LiveStats before = capture2108(sds, 20_000L);

                    String enable = sds.requestRaw("A50080000000 1", 5000);
                    LiveStats active;
                    String release = "";
                    try {
                        active = capture2108(sds, 20_000L);
                    } finally {
                        release = sds.requestRaw("A50000000000 1", 5000);
                    }

                    active.mode00EnableAccepted = positive(enable, 0x00);
                    active.mode00ReleaseAccepted = positive(release, 0x00);

                    LiveStats after = capture2108(sds, 20_000L);
                    return new Mode00AbResult(before, active, after, enable, release);
                },
                (r, error) -> {
                    if (error != null) {
                        result.setText(
                                "FAST Mode-00 A/B failed:\n" + error.getMessage() +
                                "\nCheck protocol log for the release attempt."
                        );
                        return;
                    }

                    String report = r.report();
                    lastLog.record("RESULT", "ANALYSIS", "Mode00 fast A/B", "", report);
                    result.setText(report);
                }
        );
    }

    private static final class Mode00AbResult {
        final LiveStats before;
        final LiveStats active;
        final LiveStats after;
        final String enable;
        final String release;

        Mode00AbResult(LiveStats before, LiveStats active, LiveStats after,
                       String enable, String release) {
            this.before = before;
            this.active = active;
            this.after = after;
            this.enable = enable;
            this.release = release;
        }

        String report() {
            double ratio = before.rpm.sd() > 0.001
                    ? active.rpm.sd() / before.rpm.sd()
                    : Double.NaN;

            String interpretation;
            if (!active.saw40404040) {
                interpretation = "MODE 00 NOT VERIFIED BY 40 40 40 40";
            } else if (Double.isFinite(ratio) && ratio < 0.70) {
                interpretation = "RPM ROUGHNESS SIGNIFICANTLY REDUCED UNDER MODE 00";
            } else if (Double.isFinite(ratio) && ratio > 1.30) {
                interpretation = "RPM ROUGHNESS INCREASED UNDER MODE 00";
            } else {
                interpretation = "RPM ROUGHNESS LITTLE CHANGED UNDER MODE 00";
            }

            return String.format(
                    Locale.UK,
                    "FAST MODE-00 IDLE-HUNT A/B\n%s\n\n" +
                    "Normal:     RPM %.1f  SD %.2f  p-p %.0f  IAP2 SD %.3f  ISC SD %.3f\n" +
                    "Mode 00:    RPM %.1f  SD %.2f  p-p %.0f  IAP2 SD %.3f  ISC SD %.3f\n" +
                    "Released:   RPM %.1f  SD %.2f  p-p %.0f  IAP2 SD %.3f  ISC SD %.3f\n" +
                    "SD ratio active/normal: %s\n" +
                    "40 40 40 40 observed: %s\nEnable accepted: %s\nRelease accepted: %s\n\n" +
                    "Unchanged roughness does not rule out a physical coil/plug/secondary ignition fault.",
                    interpretation,
                    before.rpm.mean(), before.rpm.sd(), before.rpm.range(), before.iap2.sd(), before.isc.sd(),
                    active.rpm.mean(), active.rpm.sd(), active.rpm.range(), active.iap2.sd(), active.isc.sd(),
                    after.rpm.mean(), after.rpm.sd(), after.rpm.range(), after.iap2.sd(), after.isc.sd(),
                    fmt(ratio),
                    active.saw40404040,
                    positive(enable, 0x00),
                    positive(release, 0x00)
            );
        }
    }

    private void showMode00RpmWindow() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));

        TextView note = text(
                "Hold the requested engine speed manually before pressing a button. " +
                "Each test enables Mode 00 for only 8 seconds, polls only 21 08, " +
                "checks payload 23–26 for 40 40 40 40, then releases Mode 00."
        );
        box.addView(note);

        TextView local = text("No RPM-window captures yet.");
        local.setTypeface(Typeface.MONOSPACE);

        addButton(box, "TEST MODE 00 AT IDLE", () ->
                runMode00Window("IDLE", 0, local));
        addButton(box, "TEST MODE 00 AT ~1500 RPM", () ->
                runMode00Window("~1500 RPM", 1500, local));
        addButton(box, "TEST MODE 00 AT ~2000 RPM", () ->
                runMode00Window("~2000 RPM", 2000, local));

        box.addView(local);

        new AlertDialog.Builder(activity)
                .setTitle("Mode-00 RPM window")
                .setView(box)
                .setNegativeButton("Close", null)
                .show();
    }

    private void runMode00Window(String label, int target, TextView local) {
        lastLog = new GuidedTestLogger(activity, "mode00_window_" + label.replaceAll("[^0-9A-Za-z]+", "_"));
        local.setText("Running " + label + "…");

        activity.runExclusiveSdsTask(
                "Mode00 window " + label,
                sds -> {
                    String enable = sds.requestRaw("A50080000000 1", 5000);
                    LiveStats stats;
                    String release = "";
                    try {
                        stats = capture2108(sds, 8_000L);
                    } finally {
                        release = sds.requestRaw("A50000000000 1", 5000);
                    }
                    stats.mode00EnableAccepted = positive(enable, 0x00);
                    stats.mode00ReleaseAccepted = positive(release, 0x00);
                    return stats;
                },
                (stats, error) -> {
                    if (error != null) {
                        local.setText("Mode00 " + label + " failed:\n" + error.getMessage());
                        return;
                    }
                    String report = String.format(
                            Locale.UK,
                            "MODE-00 WINDOW %s\nRPM mean %.1f / SD %.2f\n" +
                            "40 40 40 40 observed: %s\nEnable accepted: %s\nRelease accepted: %s%s",
                            label,
                            stats.rpm.mean(),
                            stats.rpm.sd(),
                            stats.saw40404040,
                            stats.mode00EnableAccepted,
                            stats.mode00ReleaseAccepted,
                            target > 0 ? "\nRequested manual region: ~" + target + " rpm" : ""
                    );
                    lastLog.record("RESULT", "ANALYSIS", label, "", report);
                    local.setText(report);
                }
        );
    }

    // ---------------------------------------------------------------------
    // Mode 05 / ISC characterisation
    // ---------------------------------------------------------------------

    private void confirmIscCharacterisation() {
        new AlertDialog.Builder(activity)
                .setTitle("ISC / Mode-05 characterisation")
                .setMessage(
                        "Engine must be fully warm and running.\n\n" +
                        "The test requests 1100, 1300 and 1500 rpm for 7 seconds each using the existing firmware-derived Mode-05 target family. " +
                        "Every target has its own canonical release before the next one.\n\n" +
                        "The result compares actual RPM, settling, ISC, IAP, injector and ignition response."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run test", (d, w) -> runIscCharacterisation())
                .show();
    }

    private void runIscCharacterisation() {
        lastLog = new GuidedTestLogger(activity, "isc_mode05_characterisation");
        result.setText("ISC characterisation running…");

        activity.runExclusiveSdsTask(
                "ISC Mode05 characterisation",
                sds -> {
                    int[] targets = {1100, 1300, 1500};
                    ArrayList<IscTargetResult> rows = new ArrayList<>();

                    for (int target : targets) {
                        int vv = rpmToVv(target);
                        String command = String.format(Locale.US, "A5058000%02X00", vv);
                        String enable = sds.requestRaw(command + " 1", 5000);

                        LiveStats stats;
                        String release = "";
                        try {
                            stats = capture2108(sds, 7_000L);
                        } finally {
                            release = sds.requestRaw("A50500000000 1", 5000);
                        }

                        rows.add(new IscTargetResult(
                                target,
                                vv,
                                positive(enable, 0x05),
                                positive(release, 0x05),
                                stats
                        ));

                        Thread.sleep(1000L);
                    }
                    return rows;
                },
                (rows, error) -> {
                    if (error != null) {
                        result.setText(
                                "ISC characterisation failed:\n" + error.getMessage() +
                                "\nCheck protocol log for Mode-05 release."
                        );
                        return;
                    }

                    StringBuilder report = new StringBuilder(
                            "ISC / MODE-05 CHARACTERISATION\n" +
                            "STRONG FIRMWARE INFERENCE / BIKE TEST REQUIRED\n\n"
                    );

                    for (IscTargetResult r : rows) {
                        report.append(r.report()).append("\n\n");
                    }

                    String text = report.toString().trim();
                    lastLog.record("RESULT", "ANALYSIS", "ISC Mode05", "", text);
                    result.setText(text);
                }
        );
    }

    private static final class IscTargetResult {
        final int target;
        final int vv;
        final boolean enableAccepted;
        final boolean releaseAccepted;
        final LiveStats stats;

        IscTargetResult(int target, int vv, boolean enableAccepted,
                        boolean releaseAccepted, LiveStats stats) {
            this.target = target;
            this.vv = vv;
            this.enableAccepted = enableAccepted;
            this.releaseAccepted = releaseAccepted;
            this.stats = stats;
        }

        String report() {
            double settle = stats.settlingTimeSeconds(target, 60.0, 3);
            return String.format(
                    Locale.UK,
                    "Target %d rpm / VV 0x%02X\n" +
                    "Actual %.1f rpm  SD %.2f  settle %s s\n" +
                    "ISC mean %.2f / SD %.2f\nIAP1 %.2f kPa / IAP2 %.2f kPa\n" +
                    "Injector avg %.3f ms / ignition avg %.2f deg\n" +
                    "Enable accepted %s / release accepted %s",
                    target,
                    vv,
                    stats.rpm.mean(),
                    stats.rpm.sd(),
                    fmt(settle),
                    stats.isc.mean(),
                    stats.isc.sd(),
                    stats.iap1.mean(),
                    stats.iap2.mean(),
                    stats.injectorAverageMean(),
                    stats.ignitionAverageMean(),
                    enableAccepted,
                    releaseAccepted
            );
        }
    }

    // ---------------------------------------------------------------------
    // Fast engineering pages
    // ---------------------------------------------------------------------

    private void runFastPage90(long durationMs) {
        lastLog = new GuidedTestLogger(activity, "fast_page90_adc");
        result.setText("FAST 21 90 ADC capture running…");

        activity.runExclusiveSdsTask(
                "FAST 21 90 ADC",
                sds -> capturePage(sds, 0x90, durationMs),
                (cap, error) -> {
                    if (error != null) {
                        result.setText("FAST 21 90 failed:\n" + error.getMessage());
                        return;
                    }
                    String report = cap.adcReport();
                    lastLog.record("RESULT", "ANALYSIS", "FAST 21 90 ADC", "", report);
                    result.setText(report);
                }
        );
    }

    private void runFastRawPage(int page, long durationMs) {
        lastLog = new GuidedTestLogger(
                activity,
                String.format(Locale.US, "fast_page_%02X", page)
        );
        result.setText(String.format(Locale.US, "FAST 21 %02X running…", page));

        activity.runExclusiveSdsTask(
                String.format(Locale.US, "FAST 21 %02X", page),
                sds -> capturePage(sds, page, durationMs),
                (cap, error) -> {
                    if (error != null) {
                        result.setText(String.format(
                                Locale.US,
                                "FAST 21 %02X failed:\n%s",
                                page,
                                error.getMessage()
                        ));
                        return;
                    }
                    String report = cap.rawByteReport();
                    lastLog.record("RESULT", "ANALYSIS",
                            String.format(Locale.US, "FAST 21 %02X", page), "", report);
                    result.setText(report);
                }
        );
    }

    // ---------------------------------------------------------------------
    // Voltage correlation capture
    // ---------------------------------------------------------------------

    private void confirmVoltageCorrelation() {
        new AlertDialog.Builder(activity)
                .setTitle("Voltage correlation")
                .setMessage(
                        "Connect your scope/meter to the physical points you want to compare " +
                        "(battery terminals, ECU supply and/or injector +12 V).\n\n" +
                        "Press Start at the same time as the external recording. " +
                        "The app then records only 21 08 for 60 seconds at maximum practical ELM rate.\n\n" +
                        "The app cannot ingest the external scope trace automatically; protocol timestamps are retained for later alignment."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Start 60 s", (d, w) ->
                        runLiveStatsTest(
                                "voltage_correlation",
                                "VOLTAGE CORRELATION",
                                FAST_60S,
                                true
                        )
                )
                .show();
    }

    // ---------------------------------------------------------------------
    // DTC latency
    // ---------------------------------------------------------------------

    private final class DtcLatencyDialog {
        private String baselineRaw;
        private TextView local;

        void show() {
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(18), dp(8), dp(18), dp(18));

            TextView note = text(
                    "Use ONE controlled low-voltage sensor/injector connector. " +
                    "No DTC clear is performed.\n\n" +
                    "1) Capture baseline DTC response.\n" +
                    "2) Press DETECT then immediately create the fault.\n" +
                    "3) Reconnect the component.\n" +
                    "4) Press RECOVERY to measure when the DTC response returns to the baseline form, if it does."
            );
            box.addView(note);

            addButton(box, "1 — CAPTURE DTC BASELINE", this::captureBaseline);
            addButton(box, "2 — START 30 s FAULT DETECTION", () -> runWatch(false));
            addButton(box, "3 — START 30 s RECOVERY WATCH", () -> runWatch(true));

            local = text("Baseline not captured.");
            local.setTypeface(Typeface.MONOSPACE);
            local.setTextIsSelectable(true);
            box.addView(local);

            new AlertDialog.Builder(activity)
                    .setTitle("DTC detection latency")
                    .setView(box)
                    .setNegativeButton("Close", null)
                    .show();
        }

        private void captureBaseline() {
            lastLog = new GuidedTestLogger(activity, "dtc_latency");
            local.setText("Reading baseline DTC response…");

            activity.runExclusiveSdsTask(
                    "DTC latency baseline",
                    sds -> sds.requestRaw("18000000 1", 5000),
                    (raw, error) -> {
                        if (error != null) {
                            local.setText("Baseline failed:\n" + error.getMessage());
                            return;
                        }
                        baselineRaw = compact(raw);
                        lastLog.record("BASELINE", "DTC", "18 00 00 00", raw, "");
                        local.setText("Baseline captured:\n" + oneLine(raw));
                    }
            );
        }

        private void runWatch(boolean recovery) {
            if (baselineRaw == null) {
                local.setText("Capture the DTC baseline first.");
                return;
            }

            local.setText(recovery
                    ? "Recovery watch running… component should be reconnected."
                    : "Fault watch running… create the controlled fault NOW.");

            activity.runExclusiveSdsTask(
                    recovery ? "DTC recovery latency" : "DTC detection latency",
                    sds -> watchDtc(sds, baselineRaw, recovery, 30_000L),
                    (watch, error) -> {
                        if (error != null) {
                            local.setText("DTC watch failed:\n" + error.getMessage());
                            return;
                        }
                        String report = watch.report(recovery);
                        lastLog.record(
                                recovery ? "RECOVERY" : "FAULT",
                                "ANALYSIS",
                                "DTC latency",
                                watch.lastRaw,
                                report
                        );
                        local.setText(report);
                    }
            );
        }
    }

    private static final class DtcWatchResult {
        long durationMs;
        int requests;
        long transitionMs = -1;
        String lastRaw = "";

        String report(boolean recovery) {
            return String.format(
                    Locale.UK,
                    "%s\nRequests: %d\nElapsed: %.2f s\nTransition: %s\nLast response: %s\n\n" +
                    "Raw-response transition is measured; semantic DTC status evolution remains in protocol CSV.",
                    recovery ? "DTC RECOVERY WATCH" : "DTC DETECTION WATCH",
                    requests,
                    durationMs / 1000.0,
                    transitionMs >= 0 ? String.format(Locale.UK, "%.3f s", transitionMs / 1000.0) : "not observed",
                    oneLine(lastRaw)
            );
        }
    }

    private DtcWatchResult watchDtc(
            SuzukiSds sds,
            String baselineCompact,
            boolean recovery,
            long durationMs
    ) throws Exception {
        DtcWatchResult out = new DtcWatchResult();
        long start = SystemClock.elapsedRealtime();
        long deadline = start + durationMs;

        while (SystemClock.elapsedRealtime() < deadline) {
            String raw = sds.requestRaw("18000000 1", 5000);
            out.requests++;
            out.lastRaw = raw;

            boolean equalsBaseline = compact(raw).equals(baselineCompact);
            boolean transitioned = recovery ? equalsBaseline : !equalsBaseline;

            if (transitioned && out.transitionMs < 0) {
                out.transitionMs = SystemClock.elapsedRealtime() - start;
                break;
            }

            Thread.sleep(100L);
        }

        out.durationMs = SystemClock.elapsedRealtime() - start;
        return out;
    }

    // ---------------------------------------------------------------------
    // Shared high-rate capture engines
    // ---------------------------------------------------------------------

    private LiveStats capture2108(SuzukiSds sds, long durationMs) throws Exception {
        LiveStats out = new LiveStats();
        long start = SystemClock.elapsedRealtime();
        long deadline = start + durationMs;

        while (SystemClock.elapsedRealtime() < deadline) {
            long t0 = SystemClock.elapsedRealtime();
            String response = sds.requestRaw("2108 1", 1500L);
            long t1 = SystemClock.elapsedRealtime();

            try {
                BanditLiveData d = BanditDecoder.decode(response);
                byte[] p = GuidedTestSupport.extractPayload(response, 0x08);
                out.add(d, p, t1, t1 - t0);
            } catch (RuntimeException bad) {
                out.rejected++;
            }
        }

        out.durationMs = SystemClock.elapsedRealtime() - start;
        return out;
    }

    private PageCapture capturePage(SuzukiSds sds, int page, long durationMs)
            throws Exception {
        PageCapture out = new PageCapture(page);
        String command = String.format(Locale.US, "21%02X 1", page & 0xFF);
        long start = SystemClock.elapsedRealtime();
        long deadline = start + durationMs;

        while (SystemClock.elapsedRealtime() < deadline) {
            long t0 = SystemClock.elapsedRealtime();
            String response = sds.requestRaw(command, 2000L);
            long t1 = SystemClock.elapsedRealtime();

            byte[] payload = GuidedTestSupport.extractPayload(response, page);
            if (payload.length == 0) {
                out.rejected++;
            } else {
                out.add(payload, t1 - t0);
            }
        }

        out.durationMs = SystemClock.elapsedRealtime() - start;
        return out;
    }

    private static final class LiveStats {
        long durationMs;
        int rejected;
        int requests;
        long requestTotalMs;
        long requestMinMs = Long.MAX_VALUE;
        long requestMaxMs;
        boolean saw40404040;
        boolean mode00EnableAccepted;
        boolean mode00ReleaseAccepted;

        final Stat rpm = new Stat();
        final Stat tps = new Stat();
        final Stat iap1 = new Stat();
        final Stat iap2 = new Stat();
        final Stat ect = new Stat();
        final Stat iat = new Stat();
        final Stat battery = new Stat();
        final Stat desiredIdle = new Stat();
        final Stat isc = new Stat();
        final Stat stps = new Stat();
        final Stat[] inj = {new Stat(), new Stat(), new Stat(), new Stat()};
        final Stat[] ign = {new Stat(), new Stat(), new Stat(), new Stat()};

        final ArrayList<Double> rpmValues = new ArrayList<>();
        final ArrayList<Long> rpmTimes = new ArrayList<>();
        final ArrayList<Double> iap2Values = new ArrayList<>();
        final ArrayList<Double> iscValues = new ArrayList<>();
        final ArrayList<Double> batteryValues = new ArrayList<>();
        final ArrayList<Double> injAvgValues = new ArrayList<>();
        final ArrayList<Double> ignAvgValues = new ArrayList<>();
        long timeOfMinRpmMs;

        void add(BanditLiveData d, byte[] payload, long timeMs, long requestMs) {
            requests++;
            requestTotalMs += requestMs;
            requestMinMs = Math.min(requestMinMs, requestMs);
            requestMaxMs = Math.max(requestMaxMs, requestMs);

            rpm.add(d.rpm);
            tps.add(d.tpsPct);
            iap1.add(d.iap1Kpa);
            iap2.add(d.iap2Kpa);
            ect.add(d.engineTempC);
            iat.add(d.intakeTempC);
            battery.add(d.batteryEstV);
            desiredIdle.add(d.idleSpeedRaw);
            isc.add(d.iscRaw);
            stps.add(d.secondaryTpsPct);

            inj[0].add(d.inj1);
            inj[1].add(d.inj2);
            inj[2].add(d.inj3);
            inj[3].add(d.inj4);

            ign[0].add(d.ign1Deg);
            ign[1].add(d.ign2Deg);
            ign[2].add(d.ign3Deg);
            ign[3].add(d.ign4Deg);

            rpmValues.add((double)d.rpm);
            rpmTimes.add(timeMs);
            iap2Values.add(d.iap2Kpa);
            iscValues.add((double)d.iscRaw);
            batteryValues.add(d.batteryEstV);
            injAvgValues.add(d.averageMs);
            ignAvgValues.add(avgFinite(d.ign1Deg, d.ign2Deg, d.ign3Deg, d.ign4Deg));

            if (rpm.count == 1 || d.rpm <= rpm.min()) {
                timeOfMinRpmMs = timeMs;
            }

            if (payload != null && payload.length > 0x26 &&
                    (payload[0x23] & 0xFF) == 0x40 &&
                    (payload[0x24] & 0xFF) == 0x40 &&
                    (payload[0x25] & 0xFF) == 0x40 &&
                    (payload[0x26] & 0xFF) == 0x40) {
                saw40404040 = true;
            }
        }

        double hz() {
            return durationMs > 0 ? requests / (durationMs / 1000.0) : 0.0;
        }

        double averageRequestMs() {
            return requests > 0 ? requestTotalMs / (double)requests : Double.NaN;
        }

        double injectorAverageMean() {
            return meanFinite(inj[0].mean(), inj[1].mean(), inj[2].mean(), inj[3].mean());
        }

        double ignitionAverageMean() {
            return meanFinite(ign[0].mean(), ign[1].mean(), ign[2].mean(), ign[3].mean());
        }

        double settlingTimeSeconds(double target, double tolerance, int consecutive) {
            int streak = 0;
            if (rpmTimes.isEmpty()) return Double.NaN;
            for (int i = 0; i < rpmValues.size(); i++) {
                if (Math.abs(rpmValues.get(i) - target) <= tolerance) {
                    streak++;
                    if (streak >= consecutive) {
                        int first = i - consecutive + 1;
                        return (rpmTimes.get(first) - rpmTimes.get(0)) / 1000.0;
                    }
                } else {
                    streak = 0;
                }
            }
            return Double.NaN;
        }

        String shortReport(String label) {
            return String.format(
                    Locale.UK,
                    "%s\n%.1f s • %d valid • %.2f Hz • %d rejected\n" +
                    "RPM mean %.1f / SD %.2f / min %.0f / max %.0f / p-p %.0f\n" +
                    "IAP1 %.2f kPa / IAP2 %.2f kPa / ISC %.2f\n" +
                    "Request %.1f ms avg (%s–%d ms)%s",
                    label,
                    durationMs / 1000.0,
                    requests,
                    hz(),
                    rejected,
                    rpm.mean(), rpm.sd(), rpm.min(), rpm.max(), rpm.range(),
                    iap1.mean(), iap2.mean(), isc.mean(),
                    averageRequestMs(),
                    requestMinMs == Long.MAX_VALUE ? "—" : Long.toString(requestMinMs),
                    requestMaxMs,
                    saw40404040 ? "\nPage08 23–26 reached 40 40 40 40." : ""
            );
        }

        String detailedReport(String label) {
            int over30 = 0;
            int over50 = 0;
            double maxDelta = 0.0;
            ArrayList<Double> deltas = new ArrayList<>();

            for (int i = 1; i < rpmValues.size(); i++) {
                double d = Math.abs(rpmValues.get(i) - rpmValues.get(i - 1));
                deltas.add(d);
                maxDelta = Math.max(maxDelta, d);
                if (d > 30.0) over30++;
                if (d > 50.0) over50++;
            }

            double autoPeriod = bestAutocorrPeriodSeconds(rpmValues, rpmTimes);

            double injMean = injectorAverageMean();
            double ignMean = ignitionAverageMean();

            return String.format(
                    Locale.UK,
                    "%s\n" +
                    "%.1f s • %d valid • %.2f Hz • %d rejected\n" +
                    "Request %.1f ms avg (%s–%d ms)\n\n" +
                    "RPM mean %.1f / SD %.2f / p-p %.0f / min %.0f / max %.0f\n" +
                    "|successive ΔRPM| median %s / max %.0f\n" +
                    "Drops/steps >30 rpm: %d   >50 rpm: %d\n" +
                    "Strongest roughness autocorrelation period: %s s\n\n" +
                    "TPS mean %.2f / SD %.3f\nSTPS mean %.2f / SD %.3f\n" +
                    "IAP1 mean %.2f / SD %.3f\nIAP2 mean %.2f / SD %.3f\n" +
                    "Desired-idle raw mean %.2f / SD %.3f\nISC mean %.2f / SD %.3f\n" +
                    "Battery est mean %.3f V / SD %.4f / range %.3f V\n\n" +
                    "Injector means ms: %.3f / %.3f / %.3f / %.3f\n" +
                    "Injector delta from 4-cyl mean: %+.3f / %+.3f / %+.3f / %+.3f\n" +
                    "Ignition means deg: %.2f / %.2f / %.2f / %.2f\n" +
                    "Ignition delta from 4-cyl mean: %+.2f / %+.2f / %+.2f / %+.2f\n\n" +
                    "Correlation with RPM (same SDS sample; timing causality still requires trace review):\n" +
                    "IAP2 %+.3f   ISC %+.3f   battery %+.3f   inj-avg %+.3f   ign-avg %+.3f\n\n" +
                    "Per-cylinder command asymmetry is supporting evidence only; it is not a weak-cylinder diagnosis.",
                    label,
                    durationMs / 1000.0,
                    requests,
                    hz(),
                    rejected,
                    averageRequestMs(),
                    requestMinMs == Long.MAX_VALUE ? "—" : Long.toString(requestMinMs),
                    requestMaxMs,
                    rpm.mean(), rpm.sd(), rpm.range(), rpm.min(), rpm.max(),
                    fmt(median(deltas)), maxDelta,
                    over30, over50,
                    fmt(autoPeriod),
                    tps.mean(), tps.sd(),
                    stps.mean(), stps.sd(),
                    iap1.mean(), iap1.sd(),
                    iap2.mean(), iap2.sd(),
                    desiredIdle.mean(), desiredIdle.sd(),
                    isc.mean(), isc.sd(),
                    battery.mean(), battery.sd(), battery.range(),
                    inj[0].mean(), inj[1].mean(), inj[2].mean(), inj[3].mean(),
                    inj[0].mean() - injMean, inj[1].mean() - injMean,
                    inj[2].mean() - injMean, inj[3].mean() - injMean,
                    ign[0].mean(), ign[1].mean(), ign[2].mean(), ign[3].mean(),
                    ign[0].mean() - ignMean, ign[1].mean() - ignMean,
                    ign[2].mean() - ignMean, ign[3].mean() - ignMean,
                    correlation(rpmValues, iap2Values),
                    correlation(rpmValues, iscValues),
                    correlation(rpmValues, batteryValues),
                    correlation(rpmValues, injAvgValues),
                    correlation(rpmValues, ignAvgValues)
            );
        }
    }

    private static final class PageCapture {
        final int page;
        final ArrayList<byte[]> samples = new ArrayList<>();
        long durationMs;
        int rejected;
        long requestTotalMs;
        long requestMinMs = Long.MAX_VALUE;
        long requestMaxMs;

        PageCapture(int page) {
            this.page = page;
        }

        void add(byte[] payload, long requestMs) {
            samples.add(payload);
            requestTotalMs += requestMs;
            requestMinMs = Math.min(requestMinMs, requestMs);
            requestMaxMs = Math.max(requestMaxMs, requestMs);
        }

        double hz() {
            return durationMs > 0 ? samples.size() / (durationMs / 1000.0) : 0.0;
        }

        double avgRequestMs() {
            return samples.isEmpty() ? Double.NaN : requestTotalMs / (double)samples.size();
        }

        String adcReport() {
            Stat[] adc = new Stat[16];
            for (int i = 0; i < 16; i++) adc[i] = new Stat();

            for (byte[] p : samples) {
                int[] counts = GuidedTestSupport.page90AdcCounts(p);
                for (int i = 0; i < 16; i++) {
                    if (counts[i] >= 0) adc[i].add(counts[i]);
                }
            }

            StringBuilder out = new StringBuilder();
            out.append(String.format(
                    Locale.UK,
                    "FAST 21 90 ADC\n%.1f s • %d valid • %.2f Hz • %d rejected\n" +
                    "Request %.1f ms avg (%s–%d ms)\n\n",
                    durationMs / 1000.0,
                    samples.size(),
                    hz(),
                    rejected,
                    avgRequestMs(),
                    requestMinMs == Long.MAX_VALUE ? "—" : Long.toString(requestMinMs),
                    requestMaxMs
            ));

            for (int i = 0; i < 16; i++) {
                String label = i == 0
                        ? "main TPS — PROVEN LIVE"
                        : i == 6
                        ? "secondary TPS — STRONG"
                        : "unknown";
                out.append(String.format(
                        Locale.UK,
                        "ADC%-2d %-28s mean %7.2f  SD %6.3f  min %4.0f  max %4.0f  p-p %4.0f\n",
                        i,
                        label,
                        adc[i].mean(),
                        adc[i].sd(),
                        adc[i].min(),
                        adc[i].max(),
                        adc[i].range()
                ));
            }
            return out.toString().trim();
        }

        String rawByteReport() {
            int maxLen = 0;
            for (byte[] p : samples) maxLen = Math.max(maxLen, p.length);
            Stat[] bytes = new Stat[maxLen];
            for (int i = 0; i < maxLen; i++) bytes[i] = new Stat();

            for (byte[] p : samples) {
                for (int i = 0; i < p.length; i++) bytes[i].add(p[i] & 0xFF);
            }

            ArrayList<Integer> order = new ArrayList<>();
            for (int i = 0; i < maxLen; i++) {
                if (bytes[i].count > 0 && bytes[i].range() > 0) order.add(i);
            }
            order.sort((a, b) -> Double.compare(bytes[b].range(), bytes[a].range()));

            StringBuilder out = new StringBuilder();
            out.append(String.format(
                    Locale.UK,
                    "FAST 21 %02X\n%.1f s • %d valid • %.2f Hz • %d rejected\n" +
                    "Request %.1f ms avg (%s–%d ms)\nPayload length max %d bytes\n\n" +
                    "Changing bytes, sorted by peak-to-peak range:\n",
                    page,
                    durationMs / 1000.0,
                    samples.size(),
                    hz(),
                    rejected,
                    avgRequestMs(),
                    requestMinMs == Long.MAX_VALUE ? "—" : Long.toString(requestMinMs),
                    requestMaxMs,
                    maxLen
            ));

            int shown = 0;
            for (int idx : order) {
                out.append(String.format(
                        Locale.UK,
                        "%02X  mean %7.2f  SD %6.3f  min %3.0f  max %3.0f  p-p %3.0f\n",
                        idx,
                        bytes[idx].mean(),
                        bytes[idx].sd(),
                        bytes[idx].min(),
                        bytes[idx].max(),
                        bytes[idx].range()
                ));
                shown++;
                if (shown >= 40) break;
            }

            if (shown == 0) out.append("No byte changed during this capture.\n");
            if (order.size() > shown) {
                out.append("… ").append(order.size() - shown)
                        .append(" additional changing byte(s) omitted from screen; raw protocol CSV retained.");
            }
            return out.toString().trim();
        }
    }

    private static final class Stat {
        int count;
        double sum;
        double sumSq;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        void add(double v) {
            if (!Double.isFinite(v)) return;
            count++;
            sum += v;
            sumSq += v * v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }

        double mean() {
            return count == 0 ? Double.NaN : sum / count;
        }

        double sd() {
            if (count < 2) return 0.0;
            double m = mean();
            return Math.sqrt(Math.max(0.0, sumSq / count - m * m));
        }

        double min() {
            return count == 0 ? Double.NaN : min;
        }

        double max() {
            return count == 0 ? Double.NaN : max;
        }

        double range() {
            return count == 0 ? Double.NaN : max - min;
        }
    }

    // ---------------------------------------------------------------------
    // Math / protocol helpers
    // ---------------------------------------------------------------------

    private static double bestAutocorrPeriodSeconds(
            List<Double> values,
            List<Long> times
    ) {
        int n = Math.min(values.size(), times.size());
        if (n < 12) return Double.NaN;

        double best = 0.25;
        int bestLag = -1;
        int maxLag = Math.min(30, n / 3);

        for (int lag = 2; lag <= maxLag; lag++) {
            ArrayList<Double> a = new ArrayList<>();
            ArrayList<Double> b = new ArrayList<>();
            for (int i = lag; i < n; i++) {
                a.add(values.get(i));
                b.add(values.get(i - lag));
            }
            double c = correlation(a, b);
            if (Double.isFinite(c) && c > best) {
                best = c;
                bestLag = lag;
            }
        }

        if (bestLag < 0) return Double.NaN;
        double meanDt = (times.get(n - 1) - times.get(0)) / (double)(n - 1);
        return bestLag * meanDt / 1000.0;
    }

    private static double correlation(List<Double> x, List<Double> y) {
        int n = Math.min(x.size(), y.size());
        if (n < 3) return Double.NaN;

        double sx = 0.0;
        double sy = 0.0;
        int used = 0;

        for (int i = 0; i < n; i++) {
            double a = x.get(i);
            double b = y.get(i);
            if (!Double.isFinite(a) || !Double.isFinite(b)) continue;
            sx += a;
            sy += b;
            used++;
        }

        if (used < 3) return Double.NaN;
        double mx = sx / used;
        double my = sy / used;

        double num = 0.0;
        double dx2 = 0.0;
        double dy2 = 0.0;

        for (int i = 0; i < n; i++) {
            double a = x.get(i);
            double b = y.get(i);
            if (!Double.isFinite(a) || !Double.isFinite(b)) continue;
            double dx = a - mx;
            double dy = b - my;
            num += dx * dy;
            dx2 += dx * dx;
            dy2 += dy * dy;
        }

        if (dx2 <= 0.0 || dy2 <= 0.0) return 0.0;
        return num / Math.sqrt(dx2 * dy2);
    }

    private static double median(List<Double> values) {
        if (values == null || values.isEmpty()) return Double.NaN;
        ArrayList<Double> sorted = new ArrayList<>();
        for (double v : values) if (Double.isFinite(v)) sorted.add(v);
        if (sorted.isEmpty()) return Double.NaN;
        Collections.sort(sorted);
        int n = sorted.size();
        return (n & 1) == 1
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static double avgFinite(double... values) {
        return meanFinite(values);
    }

    private static double meanFinite(double... values) {
        double sum = 0.0;
        int n = 0;
        for (double v : values) {
            if (Double.isFinite(v)) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    private static int rpmToVv(int rpm) {
        int vv = (int)Math.round(rpm / 12.5);
        return Math.max(0x58, Math.min(0x88, vv));
    }

    private static boolean positive(String response, int mode) {
        if (response == null) return false;
        String hex = compact(response);
        return hex.contains(String.format(Locale.US, "E5%02X", mode & 0xFF));
    }

    private static String compact(String value) {
        return value == null
                ? ""
                : value.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String fmt(double v) {
        return Double.isFinite(v)
                ? String.format(Locale.UK, "%.2f", v)
                : "—";
    }

    // ---------------------------------------------------------------------
    // UI helpers
    // ---------------------------------------------------------------------

    private void addButton(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(activity);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        parent.addView(b);
    }

    private TextView text(String value) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextSize(13);
        t.setPadding(0, dp(4), 0, dp(4));
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
        return (int)(value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
