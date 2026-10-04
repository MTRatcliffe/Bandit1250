package com.bandit1250.fuelmonitor;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.*;

import java.util.*;

/**
 * ECU read / flash transport capability screen.
 *
 * This revision deliberately does NOT enter the Suzuki/Denso bootloader and
 * does not send any ECU programming, erase, write, security-key or flash-read
 * command. It only interrogates the currently connected ELM/Vgate adapter.
 *
 * Why this matters:
 * ECUeditor's full-flash transport for this Denso generation uses raw K-line at
 * 57,600 baud. Normal Bandit SDS uses KWP/ISO14230 at 10,400 baud. Changing
 * Bluetooth/host serial speed is not the same thing as changing the vehicle
 * side K-line baud rate.
 */
public final class EcuReadFlashDialog {
    private static final String PREF_LAST_REPORT = "ecu_flash_adapter_report";
    private static final String PREF_LAST_RESULT = "ecu_flash_adapter_result";

    private final MainActivity activity;

    private TextView result;
    private Button capabilityTest;
    private Button readBin;
    private Button flashEcu;
    private Button shareLog;

    public EcuReadFlashDialog(MainActivity activity) {
        this.activity = activity;
    }

    public void show() {
        ScrollView scroll = new ScrollView(activity);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(box);

        TextView intro = heading("ECU Read / Flash");
        box.addView(intro);

        TextView note = body(
                "Bandit normal SDS/KWP runs at 10,400 baud. The separate Denso/ECUeditor " +
                "full-flash protocol is expected to require raw vehicle-side K-line at " +
                "57,600 baud. A faster Bluetooth or host serial link does NOT provide that.\n\n" +
                "This page currently performs adapter capability testing only. It sends no " +
                "bootloader entry, erase, write, security-key or programming-mode command."
        );
        box.addView(note);

        TextView testHead = heading("Safe connected-adapter capability test");
        testHead.setPadding(0, dp(12), 0, dp(4));
        box.addView(testHead);

        TextView commands = mono(
                "ATI\n" +
                "AT@1\n" +
                "AT IB10\n" +
                "AT IB96\n" +
                "AT IB12\n" +
                "AT IB15"
        );
        commands.setPadding(dp(8), dp(4), dp(8), dp(4));
        box.addView(commands);

        TextView testNote = body(
                "Only adapter AT commands are tested. No vehicle diagnostic/programming " +
                "payload is transmitted by these commands. Because the IB commands alter " +
                "the adapter's ISO/K-line rate, the app restores AT IB10 at the end and " +
                "then proves/rebuilds the normal Bandit SDS session before live polling resumes.\n\n" +
                "Every adapter response is kept verbatim in the on-screen report and each " +
                "transaction is also added to the protocol CSV."
        );
        testNote.setTextSize(12);
        box.addView(testNote);

        capabilityTest = new Button(activity);
        capabilityTest.setText("TEST CONNECTED ADAPTER");
        capabilityTest.setOnClickListener(v -> confirmCapabilityTest());
        box.addView(capabilityTest);

        result = mono(lastReport());
        result.setTextIsSelectable(true);
        result.setPadding(0, dp(8), 0, dp(10));
        box.addView(result);

        TextView researchHead = heading("57,600 baud interpretation");
        box.addView(researchHead);

        TextView research = body(
                "Standard ELM327 ISO/KWP commands expose fixed vehicle-side rates of " +
                "4,800, 9,600, 10,400, 12,500 and 15,625 baud. AT BRD / PP0C change the " +
                "host-to-adapter serial rate, not K-line. Protocol B/C custom baud controls " +
                "are CAN-specific. No verified iCar Pro arbitrary/raw 57,600-baud K-line " +
                "command has been identified yet.\n\n" +
                "Therefore an adapter that only demonstrates the standard IB commands is " +
                "suitable for normal SDS but should be treated as unsuitable for the " +
                "ECUeditor-style 1 MiB BIN transport until a real 57.6k K-line mode is proven."
        );
        research.setTextSize(12);
        box.addView(research);

        TextView futureHead = heading("Firmware operations");
        futureHead.setPadding(0, dp(12), 0, dp(4));
        box.addView(futureHead);

        LinearLayout futureRow = row();

        readBin = new Button(activity);
        readBin.setText("READ 1 MiB BIN");
        readBin.setEnabled(false);
        futureRow.addView(readBin, weight());

        flashEcu = new Button(activity);
        flashEcu.setText("FLASH ECU");
        flashEcu.setEnabled(false);
        futureRow.addView(flashEcu, weight());

        box.addView(futureRow);

        TextView disabled = body(
                "Disabled in this build. These controls will only be enabled after the " +
                "57,600-baud raw K-line transport and Denso bootloader framing are implemented " +
                "and separately validated."
        );
        disabled.setTextSize(12);
        disabled.setTextColor(Color.rgb(155, 85, 0));
        box.addView(disabled);

        shareLog = new Button(activity);
        shareLog.setText("SHARE PROTOCOL CSV");
        shareLog.setOnClickListener(v -> activity.shareProtocolLog());
        box.addView(shareLog);

        new AlertDialog.Builder(activity)
                .setTitle("ECU Read / Flash")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .show();
    }

    private void confirmCapabilityTest() {
        new AlertDialog.Builder(activity)
                .setTitle("Test the connected adapter?")
                .setMessage(
                        "This test sends only the six listed ELM/Vgate AT commands. " +
                        "It does NOT enter ECU programming mode or send an ECU flash command.\n\n" +
                        "The adapter will temporarily be switched through several ISO/K-line " +
                        "baud presets, then restored to 10,400 baud and the normal Bandit SDS " +
                        "connection will be checked/recovered."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run test", (d, which) -> runCapabilityTest())
                .show();
    }

    private void runCapabilityTest() {
        setBusy(true);
        result.setText(
                "Testing connected adapter…\n" +
                "No ECU programming commands will be sent."
        );

        activity.runExclusiveSdsTask(
                "ECU read-flash adapter capability",
                this::performTest,
                (report, error) -> {
                    setBusy(false);

                    if (error != null) {
                        result.setText(
                                "Adapter capability test failed:\n" +
                                error.getMessage()
                        );
                        return;
                    }

                    if (report == null) {
                        result.setText("Adapter capability test returned no result.");
                        return;
                    }

                    result.setText(report.displayText);

                    prefs().edit()
                            .putString(PREF_LAST_REPORT, report.displayText)
                            .putString(PREF_LAST_RESULT, report.verdict)
                            .apply();
                }
        );
    }

    private CapabilityReport performTest(SuzukiSds sds) throws Exception {
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
                            "Adapter capability test\n" +
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

        // Always restore the known-good Bandit K-line rate, even if one of the
        // intermediate rate commands was rejected by the adapter.
        String restoreResponse;
        try {
            restoreResponse = sds.requestRaw("AT IB10", 3000);
        } catch (Exception e) {
            restoreResponse = "I/O ERROR: " + e.getMessage();
        }

        // Prove/recover normal SDS before the exclusive task hands control back
        // to the continuous 21 08 poll loop. This is a read-only recovery path.
        boolean normalSdsRestored = sds.recoverKnownGoodSession();

        boolean ib10 = isOk(responses.get("AT IB10"));
        boolean ib96 = isOk(responses.get("AT IB96"));
        boolean ib12 = isOk(responses.get("AT IB12"));
        boolean ib15 = isOk(responses.get("AT IB15"));

        String adapterId = cleanSingleLine(responses.get("ATI"));
        String description = cleanSingleLine(responses.get("AT@1"));

        String verdict =
                "Normal SDS/KWP 10,400: " + (ib10 && normalSdsRestored ? "YES" : "NOT PROVEN") + "\n" +
                "Standard alternate ISO rates: " +
                ((ib96 || ib12 || ib15) ? "SUPPORTED" : "NOT PROVEN") + "\n" +
                "Vehicle-side K-line 57,600: NOT DEMONSTRATED\n" +
                "Full 1 MiB BIN read: LIKELY UNSUITABLE via standard ELM path\n" +
                "ECU flashing: NOT ENABLED";

        StringBuilder out = new StringBuilder();
        out.append("CONNECTED ADAPTER CAPABILITY RESULT\n");
        out.append("Adapter ID: ").append(
                adapterId.isEmpty() ? "(no ATI identity)" : adapterId
        ).append("\n");
        out.append("Description: ").append(
                description.isEmpty() ? "(no AT@1 description)" : description
        ).append("\n\n");

        out.append(verdict).append("\n\n");

        out.append("Exact adapter responses as returned to the app\n");
        out.append("---------------------------------------------\n");

        for (String command : commands) {
            out.append("TX: ").append(command).append("\n");
            out.append("RX<<<\n");
            out.append(responses.get(command));
            out.append("\n>>>RX\n");
            out.append("Elapsed: ").append(durations.get(command)).append(" ms\n\n");
        }

        out.append("RESTORE ONLY\n");
        out.append("TX: AT IB10\n");
        out.append("RX<<<\n").append(restoreResponse).append("\n>>>RX\n");
        out.append("Normal SDS identity/recovery: ")
                .append(normalSdsRestored ? "PASS" : "FAILED")
                .append("\n\n");

        out.append(
                "Interpretation: accepting IB96/IB10/IB12/IB15 proves the adapter " +
                "can select the standard ELM ISO/KWP presets. It does not prove raw " +
                "57,600-baud K-line. No ECU bootloader or flash command was sent."
        );

        return new CapabilityReport(out.toString(), verdict);
    }

    private boolean isOk(String response) {
        if (response == null) return false;
        String s = response.toUpperCase(Locale.US);
        return s.contains("OK") &&
                !s.contains("?") &&
                !s.contains("ERROR");
    }

    private String cleanSingleLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
    }

    private String lastReport() {
        String saved = prefs().getString(PREF_LAST_REPORT, null);
        if (saved != null && !saved.trim().isEmpty()) {
            return "Last adapter test:\n\n" + saved;
        }

        return "No adapter capability test has been run yet.";
    }

    private void setBusy(boolean busy) {
        if (capabilityTest != null) capabilityTest.setEnabled(!busy);
        if (shareLog != null) shareLog.setEnabled(!busy);
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(
                "bandit_monitor",
                android.content.Context.MODE_PRIVATE
        );
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
        final String verdict;

        CapabilityReport(String displayText, String verdict) {
            this.displayText = displayText;
            this.verdict = verdict;
        }
    }
}
