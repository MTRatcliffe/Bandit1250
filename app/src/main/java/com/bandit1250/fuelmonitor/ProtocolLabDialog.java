package com.bandit1250.fuelmonitor;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.widget.*;

import java.util.*;

/**
 * Restricted read-only KWP/SDS command lab.
 *
 * This intentionally does NOT expose a general raw terminal. Only services
 * whose normal purpose is reading diagnostic information are accepted.
 *
 * V0.17 adds structured read-only sweeps so bike testing can map the ECU
 * efficiently without manually typing hundreds of identifiers.
 */
public final class ProtocolLabDialog {
    private final MainActivity activity;

    private EditText commandInput;
    private TextView result;
    private Button send;
    private Button dtcSweep;
    private Button scan1a;
    private Button scan21;
    private Button scan22;
    private Button allReadOnly;

    public ProtocolLabDialog(MainActivity activity) {
        this.activity = activity;
    }

    public void show() {
        ScrollView scroll = new ScrollView(activity);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(box);

        TextView note = text(
                "Read-only Protocol Lab\n\n" +
                "Allowed KWP read services: 0x12, 0x13, 0x17, 0x18, 0x1A, " +
                "0x21, 0x22 and 0x23. AT commands, reset/session/security/" +
                "upload/download/write/routine/actuator services are blocked. " +
                "Requests are limited to 8 data bytes for the current V-LINK/ELM path.\n\n" +
                "Every sweep is written to the protocol CSV, including negative replies."
        );
        note.setPadding(0, 0, 0, dp(8));
        box.addView(note);

        LinearLayout presets1 = new LinearLayout(activity);
        presets1.setOrientation(LinearLayout.HORIZONTAL);
        presets1.addView(preset("1A91"), weight());
        presets1.addView(preset("2108"), weight());
        presets1.addView(preset("18000000"), weight());
        box.addView(presets1);

        LinearLayout presets2 = new LinearLayout(activity);
        presets2.setOrientation(LinearLayout.HORIZONTAL);
        presets2.addView(preset("13"), weight());
        presets2.addView(preset("17"), weight());
        presets2.addView(preset("18"), weight());
        presets2.addView(preset("12"), weight());
        box.addView(presets2);

        commandInput = new EditText(activity);
        commandInput.setHint("Hex request, e.g. 1A91");
        commandInput.setSingleLine(true);
        commandInput.setTypeface(Typeface.MONOSPACE);
        commandInput.setInputType(
                InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        );
        box.addView(commandInput);

        send = new Button(activity);
        send.setText("SEND READ-ONLY REQUEST");
        send.setOnClickListener(v -> sendRequest());
        box.addView(send);

        TextView sweepHead = text("Automated read-only discovery");
        sweepHead.setTypeface(null, Typeface.BOLD);
        sweepHead.setPadding(0, dp(12), 0, dp(4));
        box.addView(sweepHead);

        TextView sweepNote = text(
                "• DTC FORMS tests several standard read-only DTC/freeze-frame request shapes.\n" +
                "• 1A scans ECU-identification local IDs 00..FF.\n" +
                "• 21 scans data local IDs 00..FF.\n" +
                "• 22 TARGETS scans low common IDs 0000..00FF plus F180..F19F.\n" +
                "• ALL READ-ONLY runs all four groups and includes representative 0x23 read probes."
        );
        sweepNote.setTextSize(12);
        box.addView(sweepNote);

        LinearLayout sweepRow1 = new LinearLayout(activity);
        sweepRow1.setOrientation(LinearLayout.HORIZONTAL);

        dtcSweep = new Button(activity);
        dtcSweep.setText("DTC FORMS");
        dtcSweep.setOnClickListener(v -> runSweep(SweepKind.DTC));
        sweepRow1.addView(dtcSweep, weight());

        scan1a = new Button(activity);
        scan1a.setText("SCAN 1A");
        scan1a.setOnClickListener(v -> runSweep(SweepKind.ID_1A));
        sweepRow1.addView(scan1a, weight());

        box.addView(sweepRow1);

        LinearLayout sweepRow2 = new LinearLayout(activity);
        sweepRow2.setOrientation(LinearLayout.HORIZONTAL);

        scan21 = new Button(activity);
        scan21.setText("SCAN 21");
        scan21.setOnClickListener(v -> runSweep(SweepKind.ID_21));
        sweepRow2.addView(scan21, weight());

        scan22 = new Button(activity);
        scan22.setText("SCAN 22 TARGETS");
        scan22.setOnClickListener(v -> runSweep(SweepKind.ID_22));
        sweepRow2.addView(scan22, weight());

        box.addView(sweepRow2);

        allReadOnly = new Button(activity);
        allReadOnly.setText("RUN ALL READ-ONLY DISCOVERY");
        allReadOnly.setOnClickListener(v ->
                new AlertDialog.Builder(activity)
                        .setTitle("Run full read-only protocol discovery?")
                        .setMessage(
                                "This sends the DTC read forms, all 256 0x1A IDs, " +
                                "all 256 0x21 IDs, the selected 0x22 common-ID ranges, " +
                                "and representative 0x23 read probes.\n\n" +
                                "No clear/write/reset/session/security/routine/actuator " +
                                "or download command is sent. All replies are logged."
                        )
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Run", (d, which) -> runSweep(SweepKind.ALL))
                        .show()
        );
        box.addView(allReadOnly);

        LinearLayout logButtons = new LinearLayout(activity);
        logButtons.setOrientation(LinearLayout.HORIZONTAL);

        Button share = new Button(activity);
        share.setText("SHARE CSV LOG");
        share.setOnClickListener(v -> activity.shareProtocolLog());
        logButtons.addView(share, weight());

        Button clear = new Button(activity);
        clear.setText("CLEAR LOG");
        clear.setOnClickListener(v ->
                new AlertDialog.Builder(activity)
                        .setTitle("Clear protocol log?")
                        .setMessage(
                                "This clears the current app-session protocol CSV. " +
                                "It does not change anything in the ECU."
                        )
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Clear", (d, which) -> {
                            activity.clearProtocolLog();
                            Toast.makeText(
                                    activity,
                                    "Protocol log cleared",
                                    Toast.LENGTH_SHORT
                            ).show();
                        })
                        .show()
        );
        logButtons.addView(clear, weight());
        box.addView(logButtons);

        result = text("No request sent yet.");
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextIsSelectable(true);
        result.setPadding(0, dp(8), 0, 0);
        box.addView(result);

        new AlertDialog.Builder(activity)
                .setTitle("Protocol Lab")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .show();
    }

    private enum SweepKind {
        DTC,
        ID_1A,
        ID_21,
        ID_22,
        ALL
    }

    private Button preset(String value) {
        Button b = new Button(activity);
        b.setText(value);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(v -> commandInput.setText(value));
        return b;
    }

    private void sendRequest() {
        final String raw = commandInput.getText().toString();
        final String command;

        try {
            command = validateReadOnly(raw);
        } catch (IllegalArgumentException e) {
            result.setText("BLOCKED\n" + e.getMessage());
            return;
        }

        setBusy(true);
        result.setText("TX " + spaced(command) + "\nWaiting for ECU…");

        activity.runExclusiveSdsTask(
                "Protocol Lab",
                sds -> {
                    long start = System.nanoTime();
                    String response = sds.requestRaw(command + " 1", 5000);
                    long ms = Math.max(
                            0L,
                            (System.nanoTime() - start) / 1_000_000L
                    );
                    return new LabResult(response, ms);
                },
                (labResult, error) -> {
                    setBusy(false);

                    if (error != null) {
                        result.setText(
                                "TX " + spaced(command) + "\n" +
                                "ERROR: " + error.getMessage()
                        );
                        return;
                    }

                    result.setText(describe(command, labResult));
                }
        );
    }

    private void runSweep(SweepKind kind) {
        setBusy(true);
        result.setText("Preparing " + sweepName(kind) + "…");

        activity.runExclusiveSdsTask(
                "Protocol Lab " + sweepName(kind),
                sds -> runReadOnlySweep(sds, kind),
                (sweep, error) -> {
                    setBusy(false);

                    if (error != null) {
                        result.setText(
                                sweepName(kind) + " failed:\n" + error.getMessage()
                        );
                        return;
                    }

                    result.setText(
                            sweep == null
                                    ? "Sweep returned no result."
                                    : sweep.report
                    );
                }
        );
    }

    private SweepResult runReadOnlySweep(
            SuzukiSds sds,
            SweepKind kind
    ) throws Exception {
        ArrayList<String> commands = buildSweepCommands(kind);

        int positives = 0;
        int negatives = 0;
        int other = 0;
        ArrayList<String> hits = new ArrayList<>();
        LinkedHashMap<Integer, Integer> nrcCounts = new LinkedHashMap<>();

        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);

            final int done = i + 1;
            final int total = commands.size();
            activity.runOnUiThread(() -> {
                if (result != null) {
                    result.setText(
                            sweepName(kind) + "\n" +
                            done + " / " + total + "\n" +
                            "TX " + spaced(command) + "\n\n" +
                            "Every transaction is being written to the CSV log."
                    );
                }
            });

            String response;
            try {
                response = sds.requestRaw(command + " 1", 3000);
            } catch (Exception e) {
                response = "I/O ERROR: " + e.getMessage();
            }

            int service = Integer.parseInt(command.substring(0, 2), 16);
            int nrc = negativeResponseCode(response, service);

            if (nrc >= 0) {
                negatives++;
                nrcCounts.put(nrc, nrcCounts.getOrDefault(nrc, 0) + 1);
            } else if (isExpectedPositive(command, response)) {
                positives++;
                hits.add(command + " -> " + oneLine(response));
            } else {
                other++;
            }

            try {
                Thread.sleep(15L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("Protocol sweep interrupted", e);
            }
        }

        StringBuilder report = new StringBuilder();
        report.append(sweepName(kind)).append("\n");
        report.append("Requests sent: ").append(commands.size()).append("\n");
        report.append("Positive responses: ").append(positives).append("\n");
        report.append("Negative KWP responses: ").append(negatives).append("\n");
        report.append("Other / no standard marker: ").append(other).append("\n");

        if (!nrcCounts.isEmpty()) {
            report.append("\nNRC counts\n");
            for (Map.Entry<Integer, Integer> entry : nrcCounts.entrySet()) {
                report.append(String.format(
                        Locale.US,
                        "0x%02X: %d\n",
                        entry.getKey(),
                        entry.getValue()
                ));
            }
        }

        report.append("\nPositive hits\n");
        if (hits.isEmpty()) {
            report.append("none\n");
        } else {
            for (String hit : hits) {
                report.append(hit).append("\n");
            }
        }

        report.append(
                "\nFull positive AND negative transaction detail is in the protocol CSV."
        );

        return new SweepResult(report.toString().trim());
    }

    private ArrayList<String> buildSweepCommands(SweepKind kind) {
        ArrayList<String> commands = new ArrayList<>();

        if (kind == SweepKind.DTC || kind == SweepKind.ALL) {
            // KWP stored-data read services. No clear command (0x14) is included.
            Collections.addAll(
                    commands,
                    "12",
                    "1200",
                    "13",
                    "17",
                    "18",
                    "18000000",
                    "1800FF00",
                    "1802FF00"
            );
        }

        if (kind == SweepKind.ID_1A || kind == SweepKind.ALL) {
            for (int id = 0x00; id <= 0xFF; id++) {
                commands.add(String.format(Locale.US, "1A%02X", id));
            }
        }

        if (kind == SweepKind.ID_21 || kind == SweepKind.ALL) {
            for (int id = 0x00; id <= 0xFF; id++) {
                commands.add(String.format(Locale.US, "21%02X", id));
            }
        }

        if (kind == SweepKind.ID_22 || kind == SweepKind.ALL) {
            // Low common-ID range: useful for manufacturer-specific DIDs.
            for (int did = 0x0000; did <= 0x00FF; did++) {
                commands.add(String.format(Locale.US, "22%04X", did));
            }

            // Common KWP/UDS identification area around ECU/VIN/software IDs.
            for (int did = 0xF180; did <= 0xF19F; did++) {
                commands.add(String.format(Locale.US, "22%04X", did));
            }
        }

        if (kind == SweepKind.ALL) {
            // Representative ReadMemoryByAddress formats already used by the
            // safe discovery tool. These are reads only.
            commands.add("2300000001");
            commands.add("230000000001");
            commands.add("23000001");
        }

        return commands;
    }

    private String sweepName(SweepKind kind) {
        switch (kind) {
            case DTC: return "DTC / freeze-frame read forms";
            case ID_1A: return "0x1A ID sweep 00..FF";
            case ID_21: return "0x21 local-ID sweep 00..FF";
            case ID_22: return "0x22 targeted common-ID sweep";
            case ALL: return "ALL READ-ONLY DISCOVERY";
            default: return "Protocol sweep";
        }
    }

    private void setBusy(boolean busy) {
        if (send != null) send.setEnabled(!busy);
        if (dtcSweep != null) dtcSweep.setEnabled(!busy);
        if (scan1a != null) scan1a.setEnabled(!busy);
        if (scan21 != null) scan21.setEnabled(!busy);
        if (scan22 != null) scan22.setEnabled(!busy);
        if (allReadOnly != null) allReadOnly.setEnabled(!busy);
    }

    private String validateReadOnly(String value) {
        String hex = value == null
                ? ""
                : value.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");

        if (hex.length() < 2 || (hex.length() & 1) != 0) {
            throw new IllegalArgumentException(
                    "Enter a complete even-length hexadecimal request."
            );
        }

        int bytes = hex.length() / 2;
        if (bytes > 8) {
            throw new IllegalArgumentException(
                    "Request is " + bytes + " bytes; current tested ELM limit is 8."
            );
        }

        int service;
        try {
            service = Integer.parseInt(hex.substring(0, 2), 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid hexadecimal service byte.");
        }

        boolean allowed =
                service == 0x12 ||
                service == 0x13 ||
                service == 0x17 ||
                service == 0x18 ||
                service == 0x1A ||
                service == 0x21 ||
                service == 0x22 ||
                service == 0x23;

        if (!allowed) {
            throw new IllegalArgumentException(String.format(
                    Locale.US,
                    "Service 0x%02X is not on the read-only allow-list.",
                    service
            ));
        }

        return hex;
    }

    private boolean isExpectedPositive(String command, String response) {
        if (response == null) return false;

        String compact = response.toUpperCase(Locale.US)
                .replaceAll("[^0-9A-F]", "");

        if (compact.contains("NODATA") ||
                compact.contains("ERROR") ||
                compact.contains("UNABLE")) {
            return false;
        }

        int service = Integer.parseInt(command.substring(0, 2), 16);
        String positive = String.format(Locale.US, "%02X", (service + 0x40) & 0xFF);

        if (service == 0x1A || service == 0x21) {
            if (command.length() < 4) return compact.contains(positive);
            return compact.contains(positive + command.substring(2, 4));
        }

        if (service == 0x22) {
            if (command.length() < 6) return compact.contains(positive);
            return compact.contains(positive + command.substring(2, 6));
        }

        return compact.contains(positive);
    }

    private int negativeResponseCode(String response, int service) {
        if (response == null) return -1;

        String compact = response.toUpperCase(Locale.US)
                .replaceAll("[^0-9A-F]", "");
        String marker = String.format(Locale.US, "7F%02X", service & 0xFF);

        int p = compact.indexOf(marker);
        if (p < 0 || p + 6 > compact.length()) return -1;

        try {
            return Integer.parseInt(compact.substring(p + 4, p + 6), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String describe(String command, LabResult lab) {
        int service = Integer.parseInt(command.substring(0, 2), 16);
        int positive = (service + 0x40) & 0xFF;

        String response = oneLine(lab.response);
        String compact = response.toUpperCase(Locale.US)
                .replaceAll("[^0-9A-F]", "");

        String nrc = EcuMemoryReader.negativeResponseExplanation(
                lab.response,
                service
        );

        String interpretation;
        if (nrc != null) {
            interpretation = nrc;
        } else if (compact.contains(String.format(Locale.US, "%02X", positive))) {
            interpretation = String.format(
                    Locale.US,
                    "Positive service 0x%02X detected.",
                    positive
            );
        } else {
            interpretation = "No standard positive/negative KWP service marker detected.";
        }

        return "TX: " + spaced(command) + "\n" +
                "TX bytes: " + (command.length() / 2) + "\n" +
                "RX: " + response + "\n" +
                "Response time: " + lab.durationMs + " ms\n" +
                "Interpretation: " + interpretation + "\n\n" +
                "This transaction has been added to the protocol CSV log.";
    }

    private static final class LabResult {
        final String response;
        final long durationMs;

        LabResult(String response, long durationMs) {
            this.response = response;
            this.durationMs = durationMs;
        }
    }

    private static final class SweepResult {
        final String report;

        SweepResult(String report) {
            this.report = report;
        }
    }

    private String spaced(String hex) {
        return hex.replaceAll("(.{2})(?!$)", "$1 ");
    }

    private String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
    }

    private TextView text(String value) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextSize(13);
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
}
