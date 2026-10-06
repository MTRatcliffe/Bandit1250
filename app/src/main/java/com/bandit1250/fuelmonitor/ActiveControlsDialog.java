package com.bandit1250.fuelmonitor;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.*;

import java.io.File;
import java.util.Locale;

/**
 * Guided Suzuki/Denso A5 active controls.
 *
 * Only command forms supplied by the firmware reverse-engineering work are
 * exposed. Unknown A5 combinations are deliberately not scanned.
 */
public final class ActiveControlsDialog {
    private static final String PREF_PENDING = "a5_pending_release";
    private static final String PREF_LABEL = "a5_pending_label";
    private static final String PREF_RELEASE = "a5_pending_release_cmd";

    private final MainActivity activity;
    private final SharedPreferences prefs;

    private AlertDialog dialog;
    private TextView banner;
    private TextView result;
    private GuidedTestLogger lastGuidedLog;

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
                "These are manufacturer-specific ECU output/diagnostic overrides. " +
                "Every command is logged. Unknown combinations are not probed. " +
                "Where a physical meaning is still uncertain it remains labelled Experimental."
        );
        intro.setTextSize(12);
        box.addView(intro);

        addCard(
                box,
                "IGNITION TIMING STABILISATION",
                "Firmware strongly identified / Experimental",
                "Enable: A5 00 80 00 00 00\nRelease: A5 00 00 00 00 00",
                "Temporarily suppresses normal dynamic ignition corrections in the low-RPM service window. " +
                        "The firmware is expected to drive four page-08 payload bytes at 0x23-0x26 toward 0x40.",
                new String[]{"ENABLE", "RELEASE"},
                new Runnable[]{
                        () -> confirmStart(
                                "Ignition timing stabilisation",
                                "A50080000000",
                                "A50000000000",
                                0x00,
                                "Engine should be running at idle / low RPM. The app will verify the four firmware-identified timing bytes after enabling.",
                                true
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
                "ON: A5 01 80 00 00 00\nRelease: A5 01 00 00 00 00",
                "Temporarily overrides the ECU PAIR-solenoid command. You may hear or feel the solenoid click. No permanent calibration change is expected.",
                new String[]{"ON", "RELEASE"},
                new Runnable[]{
                        () -> confirmStart(
                                "PAIR solenoid",
                                "A50180000000",
                                "A50100000000",
                                0x01,
                                "Short electrical output test. Confirm the motorcycle is in a safe state.",
                                false
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
                "Extreme A: A5 03 80 00 00 00\nExtreme B: A5 03 80 80 00 00\nRelease: A5 03 00 00 00 00",
                "Commands the secondary-throttle target to one of two end ranges. Default test condition: ignition ON, engine OFF.",
                new String[]{"EXTREME A", "EXTREME B", "RELEASE"},
                new Runnable[]{
                        () -> confirmStart(
                                "STVA Extreme A",
                                "A50380000000",
                                "A50300000000",
                                0x03,
                                "Use ignition ON and engine OFF. This drives the STVA toward an end of travel.",
                                false
                        ),
                        () -> confirmStart(
                                "STVA Extreme B",
                                "A50380800000",
                                "A50300000000",
                                0x03,
                                "Use ignition ON and engine OFF. This drives the STVA toward the opposite end of travel.",
                                false
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
                "ON: A5 06 80 80 00 00\nForced OFF: A5 06 80 00 00 00\nRelease: A5 06 00 00 00 00",
                "RELEASE and FORCED OFF are different: release returns control to normal ECU fan logic; forced OFF leaves the diagnostic override active.",
                new String[]{"ON", "FORCE OFF", "RELEASE TO ECU"},
                new Runnable[]{
                        () -> confirmStart(
                                "Cooling fan ON",
                                "A50680800000",
                                "A50600000000",
                                0x06,
                                "This should energise the ECU cooling-fan output. Keep hands/tools clear of the fan.",
                                false
                        ),
                        () -> confirmStart(
                                "Cooling fan forced OFF",
                                "A50680000000",
                                "A50600000000",
                                0x06,
                                "This leaves fan override active while commanding OFF. Do not use if the engine needs cooling.",
                                false
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
                "Enable: A5 07 80 00 00 00\nRelease: A5 07 00 00 00 00",
                "Firmware proves this reaches SH7058 PE11. Physical identity is not confirmed on this market calibration. Use ignition ON / engine OFF and only short controlled activation.",
                new String[]{"SHORT ENABLE", "RELEASE"},
                new Runnable[]{
                        () -> confirmStart(
                                "Mode 07 optional output",
                                "A50780000000",
                                "A50700000000",
                                0x07,
                                "Experimental physical-output test. Prefer ignition ON, engine OFF. Note whether any solenoid/relay clicks.",
                                false
                        ),
                        () -> sendRelease(
                                "Mode 07 optional output",
                                "A50700000000",
                                0x07
                        )
                }
        );

        Button releaseAll = new Button(activity);
        releaseAll.setText("RELEASE ALL KNOWN A5 OVERRIDES");
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
                Toast.makeText(activity, "No guided control log yet", Toast.LENGTH_SHORT).show();
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

        dialog.show();
    }

    private void addIscCard(LinearLayout box) {
        LinearLayout card = cardBase();

        TextView title = text("ISC / IDLE CONTROL");
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);

        TextView confidence = text("Mixed confidence — firmware mapped");
        confidence.setTextSize(12);
        card.addView(confidence);

        TextView info = text(
                "RPM control packet: A5 05 80 00 VV 00, VV 0x58..0x88 and target = VV × 12.5 rpm.\n" +
                "Air-volume candidate: A5 05 08 00 70 00.\n" +
                "Learned-state reset/preset: A5 05 20 00 70 00."
        );
        info.setTextSize(12);
        card.addView(info);

        SeekBar rpmSeek = new SeekBar(activity);
        rpmSeek.setMax(12); // 1100..1700 in 50-rpm steps
        rpmSeek.setProgress(2); // 1200
        card.addView(rpmSeek);

        TextView rpmPreview = text("");
        rpmPreview.setTypeface(Typeface.MONOSPACE);
        card.addView(rpmPreview);

        Runnable refresh = () -> {
            int rpm = 1100 + rpmSeek.getProgress() * 50;
            int vv = (int)Math.round(rpm / 12.5);
            vv = Math.max(0x58, Math.min(0x88, vv));

            rpmPreview.setText(String.format(
                    Locale.US,
                    "Target %d rpm  ->  A5 05 80 00 %02X 00",
                    rpm,
                    vv
            ));
        };

        rpmSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                refresh.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        refresh.run();

        Button rpmLocked = new Button(activity);
        rpmLocked.setText("RPM OVERRIDE — RELEASE PACKET NOT YET PROVEN");
        rpmLocked.setEnabled(false);
        card.addView(rpmLocked);

        Button airLocked = new Button(activity);
        airLocked.setText("ISC AIR VOLUME — RELEASE PACKET NOT YET PROVEN");
        airLocked.setEnabled(false);
        card.addView(airLocked);

        TextView lockedNote = text(
                "The control packets are retained and displayed, but execution is intentionally locked until the correct Mode-05 release/normal-control packet is proven. This avoids leaving an idle override active silently."
        );
        lockedNote.setTextSize(11);
        card.addView(lockedNote);

        Button reset = new Button(activity);
        reset.setText("ISC LEARNED VALUE RESET / PRESET");
        reset.setOnClickListener(v -> confirmIscLearnReset());
        card.addView(reset);

        box.addView(card);
    }

    private void confirmIscLearnReset() {
        new AlertDialog.Builder(activity)
                .setTitle("ISC learned-value reset / preset")
                .setMessage(
                        "This is the firmware-identified A5 05 20 00 70 00 procedure. " +
                        "It does not directly write 0x70 to EEPROM; it asks the ECU to " +
                        "enter its ISC reset/reinitialisation state and MAY result in retained learned values changing.\n\n" +
                        "The app will capture 21 08 / 80 / 90 / C0 before and after.\n\n" +
                        "Only continue if you intentionally want to perform an ISC relearn/reset."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("RESET / PRESET ISC", (d, w) -> runIscLearnReset())
                .show();
    }

    private void runIscLearnReset() {
        lastGuidedLog = new GuidedTestLogger(activity, "ISC_learn_reset");
        result.setText("Capturing ISC reset baseline…");

        activity.runExclusiveSdsTask(
                "Guided ISC learn reset",
                sds -> {
                    activity.setProtocolOperationLabel("ISC RESET BASELINE");
                    GuidedTestSupport.Snapshot before =
                            GuidedTestSupport.captureAll(sds, lastGuidedLog, "BASELINE", false);

                    activity.setProtocolOperationLabel("ISC RESET COMMAND");
                    String response = sds.requestRaw("A50520007000 1", 5000);
                    lastGuidedLog.record(
                            "ACTION",
                            "A5",
                            "ISC learned-state reset",
                            response,
                            "TX A5 05 20 00 70 00"
                    );

                    Thread.sleep(1500L);

                    activity.setProtocolOperationLabel("ISC RESET AFTER");
                    GuidedTestSupport.Snapshot after =
                            GuidedTestSupport.captureAll(sds, lastGuidedLog, "AFTER", false);

                    return new IscResetResult(response, before, after);
                },
                (r, error) -> {
                    activity.setProtocolOperationLabel("");

                    if (error != null) {
                        result.setText("ISC reset procedure failed:\n" + error.getMessage());
                        return;
                    }

                    result.setText(
                            "ISC reset/preset response: " + oneLine(r.response) + "\n\n" +
                            "Before/after raw comparison:\n" +
                            GuidedTestSupport.diff(r.before, r.after, r.after) + "\n\n" +
                            "Interpret retained changes only after a key-cycle follow-up."
                    );
                }
        );
    }

    private void confirmStart(
            String label,
            String command,
            String release,
            int mode,
            String warning,
            boolean verifyIgnitionBytes
    ) {
        new AlertDialog.Builder(activity)
                .setTitle(label)
                .setMessage(
                        warning + "\n\n" +
                        "TX: " + spaced(command) + "\n" +
                        "Expected positive response: E5 " +
                        String.format(Locale.US, "%02X", mode) + "\n" +
                        "Release: " + spaced(release) + "\n\n" +
                        "A positive E5 response proves ECU command acceptance, not physical actuator operation."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (d, w) ->
                        sendStart(
                                label,
                                command,
                                release,
                                mode,
                                verifyIgnitionBytes
                        )
                )
                .show();
    }

    private void sendStart(
            String label,
            String command,
            String release,
            int mode,
            boolean verifyIgnitionBytes
    ) {
        result.setText("Sending " + label + "…");

        activity.runExclusiveSdsTask(
                "A5 " + label,
                sds -> {
                    sds.requestRaw("3E 1", 3000);
                    String response = sds.requestRaw(command + " 1", 5000);

                    String verify = "";
                    if (verifyIgnitionBytes && positive(response, mode)) {
                        Thread.sleep(250L);
                        String live = sds.requestRaw("2108 1", 4500);
                        byte[] p = GuidedTestSupport.extractPayload(live, 0x08);

                        if (p.length > 0x26) {
                            verify = String.format(
                                    Locale.US,
                                    "21 08 payload 23-26: %02X %02X %02X %02X",
                                    p[0x23] & 0xFF,
                                    p[0x24] & 0xFF,
                                    p[0x25] & 0xFF,
                                    p[0x26] & 0xFF
                            );
                        } else {
                            verify = "21 08 response too short for offsets 23-26";
                        }
                    }

                    return new ControlResult(response, verify);
                },
                (r, error) -> {
                    if (error != null) {
                        result.setText(label + " failed:\n" + error.getMessage());
                        return;
                    }

                    if (positive(r.response, mode)) {
                        prefs.edit()
                                .putBoolean(PREF_PENDING, true)
                                .putString(PREF_LABEL, label)
                                .putString(PREF_RELEASE, release)
                                .putLong("a5_pending_since", System.currentTimeMillis())
                                .apply();
                    }

                    refreshBanner();

                    result.setText(
                            label + "\n" +
                            "TX: " + spaced(command) + "\n" +
                            "RX: " + oneLine(r.response) + "\n" +
                            "Accepted: " + (positive(r.response, mode) ? "YES" : "NO") +
                            (r.verification.isEmpty()
                                    ? ""
                                    : "\n" + r.verification)
                    );
                }
        );
    }

    private void sendRelease(String label, String command, int mode) {
        result.setText("Releasing " + label + "…");

        activity.runExclusiveSdsTask(
                "A5 release " + label,
                sds -> {
                    sds.requestRaw("3E 1", 3000);
                    return sds.requestRaw(command + " 1", 5000);
                },
                (response, error) -> {
                    if (error != null) {
                        result.setText("Release failed:\n" + error.getMessage());
                        return;
                    }

                    boolean accepted = positive(response, mode);
                    if (accepted) {
                        clearPending();
                    }

                    refreshBanner();
                    result.setText(
                            "Release " + label + "\n" +
                            "TX: " + spaced(command) + "\n" +
                            "RX: " + oneLine(response) + "\n" +
                            "Accepted: " + (accepted ? "YES" : "NO")
                    );
                }
        );
    }

    private void confirmReleaseAll() {
        new AlertDialog.Builder(activity)
                .setTitle("Release all known A5 overrides?")
                .setMessage(
                        "This sends the known normal-control packets for Modes 00, 01, 03, 06 and 07. " +
                        "It does not send the Mode-05 learned-value reset."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Release all", (d, w) -> releaseAllKnown())
                .show();
    }

    private void releaseAllKnown() {
        activity.runExclusiveSdsTask(
                "Release all known A5 overrides",
                sds -> {
                    StringBuilder r = new StringBuilder();
                    String[] commands = {
                            "A50000000000",
                            "A50100000000",
                            "A50300000000",
                            "A50600000000",
                            "A50700000000"
                    };

                    for (String command : commands) {
                        String response = sds.requestRaw(command + " 1", 5000);
                        r.append(spaced(command))
                                .append(" -> ")
                                .append(oneLine(response))
                                .append("\n");
                    }

                    return r.toString().trim();
                },
                (report, error) -> {
                    if (error != null) {
                        result.setText("Release-all failed:\n" + error.getMessage());
                        return;
                    }

                    clearPending();
                    refreshBanner();
                    result.setText("Release-all complete\n\n" + report);
                }
        );
    }

    private void refreshBanner() {
        if (banner == null) return;

        if (prefs.getBoolean(PREF_PENDING, false)) {
            banner.setText(
                    "⚠ ACTIVE OVERRIDE RECORDED\n" +
                    prefs.getString(PREF_LABEL, "Unknown A5 control") +
                    "\nRelease: " +
                    spaced(prefs.getString(PREF_RELEASE, ""))
            );
            banner.setTextColor(Color.rgb(190, 0, 0));
        } else {
            banner.setText("No A5 override recorded as active");
            banner.setTextColor(Color.rgb(0, 130, 0));
        }
    }

    private void clearPending() {
        prefs.edit()
                .putBoolean(PREF_PENDING, false)
                .remove(PREF_LABEL)
                .remove(PREF_RELEASE)
                .remove("a5_pending_since")
                .apply();
    }

    private boolean positive(String response, int mode) {
        if (response == null) return false;

        String hex = response.toUpperCase(Locale.US)
                .replaceAll("[^0-9A-F]", "");

        return hex.contains(String.format(
                Locale.US,
                "E5%02X",
                mode & 0xFF
        ));
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
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);

        TextView confidence = text(confidenceText);
        confidence.setTextSize(12);
        card.addView(confidence);

        TextView commands = text(commandsText);
        commands.setTypeface(Typeface.MONOSPACE);
        commands.setTextSize(11);
        commands.setTextIsSelectable(true);
        card.addView(commands);

        Button info = new Button(activity);
        info.setText("INFO");
        info.setOnClickListener(v ->
                new AlertDialog.Builder(activity)
                        .setTitle(titleText)
                        .setMessage(
                                infoText + "\n\n" +
                                commandsText + "\n\n" +
                                "Confidence: " + confidenceText
                        )
                        .setPositiveButton("OK", null)
                        .show()
        );
        card.addView(info);

        for (int i = 0; i < buttonLabels.length; i++) {
            Button b = new Button(activity);
            b.setText(buttonLabels[i]);
            final Runnable action = actions[i];
            b.setOnClickListener(v -> action.run());
            card.addView(b);
        }

        parent.addView(card);
    }

    private LinearLayout cardBase() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(4);
        card.setLayoutParams(lp);

        card.setBackgroundColor(Color.argb(20, 0, 0, 0));
        return card;
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
        if (hex == null) return "";
        String compact = hex.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
        return compact.replaceAll("(.{2})(?!$)", "$1 ");
    }

    private static final class ControlResult {
        final String response;
        final String verification;

        ControlResult(String response, String verification) {
            this.response = response;
            this.verification = verification == null ? "" : verification;
        }
    }

    private static final class IscResetResult {
        final String response;
        final GuidedTestSupport.Snapshot before;
        final GuidedTestSupport.Snapshot after;

        IscResetResult(
                String response,
                GuidedTestSupport.Snapshot before,
                GuidedTestSupport.Snapshot after
        ) {
            this.response = response;
            this.before = before;
            this.after = after;
        }
    }
}
