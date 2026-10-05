package com.bandit1250.fuelmonitor;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.widget.*;

import java.util.Locale;

/**
 * Development-only protocol tools.
 *
 * These are intentionally separated from the normal ECU tools page because
 * they are useful for reverse engineering but not for routine bike use.
 *
 * Current contents:
 * - ELM message-length limit test
 * - Denso / K-line read-only compatibility test
 * - Protocol Lab
 *
 * Legacy dead ends such as the 0x30 service/ID sweep, safe ECU discovery and
 * the old ECU read/flash screen are not exposed here.
 */
public final class DevToolsDialog {
    private final MainActivity activity;

    private AlertDialog dialog;
    private TextView result;
    private Button elmLengthTest;
    private Button densoReadOnlyTest;
    private Button protocolLab;
    private Button shareLog;

    public DevToolsDialog(MainActivity activity) {
        this.activity = activity;
    }

    public void show() {
        ScrollView scroll = new ScrollView(activity);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(box);

        box.addView(heading("Developer / experimental tools"));

        TextView note = body(
                "These tests are retained for protocol reverse engineering. " +
                "They are not required for normal MPG monitoring or fault-code use."
        );
        note.setTextSize(12);
        box.addView(note);

        elmLengthTest = new Button(activity);
        elmLengthTest.setText("ELM MESSAGE LENGTH TEST");
        elmLengthTest.setOnClickListener(v -> confirmElmLengthTest());
        box.addView(elmLengthTest);

        densoReadOnlyTest = new Button(activity);
        densoReadOnlyTest.setText("K-LINE READ-ONLY TEST");
        densoReadOnlyTest.setOnClickListener(v -> confirmDensoReadOnlyTest());
        box.addView(densoReadOnlyTest);

        protocolLab = new Button(activity);
        protocolLab.setText("PROTOCOL LAB");
        protocolLab.setOnClickListener(v -> new ProtocolLabDialog(activity).show());
        box.addView(protocolLab);

        result = mono(lastDevSummary());
        result.setPadding(0, dp(8), 0, dp(10));
        box.addView(result);

        shareLog = new Button(activity);
        shareLog.setText("SHARE PROTOCOL LOG");
        shareLog.setOnClickListener(v -> activity.shareProtocolLog());
        box.addView(shareLog);

        dialog = new AlertDialog.Builder(activity)
                .setTitle("Developer tools")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        dialog.setOnDismissListener(d -> dialog = null);
        dialog.show();
    }

    // -----------------------------------------------------------------
    // ELM message-length test
    // -----------------------------------------------------------------

    private void confirmElmLengthTest() {
        new AlertDialog.Builder(activity)
                .setTitle("Run ELM message-length test?")
                .setMessage(
                        "This uses the known read-only 1A91 ECU-ID request and adds " +
                        "zero padding to increasing request lengths. It is intended to " +
                        "measure the V-LINK/ELM transmit-length limit.\n\n" +
                        "No ECU write, erase, reset, routine or actuator command is sent."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run test", (d, which) -> startElmLengthTest())
                .show();
    }

    private void startElmLengthTest() {
        setBusy(true);
        result.setText(
                "Running ELM message-length test…\n" +
                "Live 21 08 polling is temporarily paused."
        );

        activity.runExclusiveSdsTask(
                "ELM length test",
                EcuMemoryReader::testElmMessageLength,
                (test, error) -> {
                    setBusy(false);

                    if (error != null) {
                        result.setText(
                                "ELM length test failed:\n" + error.getMessage()
                        );
                        return;
                    }

                    if (test == null) {
                        result.setText("ELM length test returned no result.");
                        return;
                    }

                    result.setText(test.report);

                    prefs().edit()
                            .putInt(
                                    "ecu_profile_max_tx",
                                    test.largestEcuSeenBytes
                            )
                            .putInt(
                                    "ecu_profile_first_question",
                                    test.firstQuestionMarkBytes
                            )
                            .apply();
                }
        );
    }

    // -----------------------------------------------------------------
    // Denso / K-line read-only compatibility test
    // -----------------------------------------------------------------

    private void confirmDensoReadOnlyTest() {
        new AlertDialog.Builder(activity)
                .setTitle("Run K-line read-only tests?")
                .setMessage(
                        "This performs additional read-only Suzuki/Denso identifier/data " +
                        "queries and temporarily tests alternate KWP initialisation at " +
                        "10,400 and 9,600 baud. The normal Bandit SDS session is then " +
                        "restored automatically.\n\n" +
                        "It does not select a programming session, send a security key, " +
                        "reset the ECU, write/erase memory or operate actuators."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run tests", (d, which) -> startDensoReadOnlyTest())
                .show();
    }

    private void startDensoReadOnlyTest() {
        setBusy(true);
        result.setText(
                "Running Denso / Suzuki K-line read-only compatibility tests…"
        );

        activity.runExclusiveSdsTask(
                "Denso read-only test",
                EcuMemoryReader::testDensoReadOnlyPaths,
                (test, error) -> {
                    setBusy(false);

                    if (error != null) {
                        result.setText(
                                "K-line compatibility test failed:\n" +
                                error.getMessage()
                        );
                        return;
                    }

                    if (test == null) {
                        result.setText(
                                "K-line compatibility test returned no result."
                        );
                        return;
                    }

                    result.setText(test.report);

                    prefs().edit()
                            .putString("ecu_profile_1a", test.supported1A)
                            .putString("ecu_profile_21", test.supported21)
                            .putBoolean(
                                    "ecu_profile_normal_restored",
                                    test.normalSdsRestored
                            )
                            .apply();
                }
        );
    }

    private String lastDevSummary() {
        SharedPreferences p = prefs();

        int maxTx = p.getInt("ecu_profile_max_tx", 0);
        int firstQuestion = p.getInt("ecu_profile_first_question", 0);
        String ids1a = p.getString("ecu_profile_1a", "not tested");
        String ids21 = p.getString("ecu_profile_21", "not tested");

        String restore;
        if (p.contains("ecu_profile_normal_restored")) {
            restore = p.getBoolean("ecu_profile_normal_restored", false)
                    ? "yes"
                    : "NO / not proven";
        } else {
            restore = "not tested";
        }

        return "Stored dev results\n" +
                "Largest ELM TX reaching ECU: " +
                (maxTx > 0 ? maxTx + " bytes" : "not tested") + "\n" +
                "First local '?' length: " +
                (firstQuestion > 0
                        ? firstQuestion + " bytes"
                        : "not tested") + "\n" +
                "Positive 0x1A IDs: " + ids1a + "\n" +
                "Positive 0x21 IDs: " + ids21 + "\n" +
                "Normal SDS restored after alternate init: " + restore;
    }

    private void setBusy(boolean busy) {
        if (elmLengthTest != null) elmLengthTest.setEnabled(!busy);
        if (densoReadOnlyTest != null) densoReadOnlyTest.setEnabled(!busy);
        if (protocolLab != null) protocolLab.setEnabled(!busy);
        if (shareLog != null) shareLog.setEnabled(!busy);
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(
                "bandit_monitor",
                android.content.Context.MODE_PRIVATE
        );
    }

    private TextView heading(String text) {
        TextView t = body(text);
        t.setTextSize(17);
        t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private TextView body(String text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView mono(String text) {
        TextView t = body(text);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    private int dp(int value) {
        return Math.round(
                value * activity.getResources().getDisplayMetrics().density
        );
    }
}
