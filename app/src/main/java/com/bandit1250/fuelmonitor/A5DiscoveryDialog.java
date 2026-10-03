package com.bandit1250.fuelmonitor;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.*;

import java.util.*;

/**
 * Suzuki/Denso proprietary SDS 0xA5 discovery tooling.
 *
 * Related K8-era Denso firmware identifies service 0xA5 as an active-control
 * dispatcher. The Bandit mapping is not assumed to be identical.
 *
 * This class performs discovery only. It sends deliberately incomplete
 * A5 <ID> probes with P0..P3 omitted, records every response, and never sends
 * a complete known actuator ON/OFF/reset payload.
 */
public final class A5DiscoveryDialog {
    private final MainActivity activity;

    private TextView result;
    private Button scan;

    public A5DiscoveryDialog(MainActivity activity) {
        this.activity = activity;
    }

    public void show() {
        ScrollView scroll = new ScrollView(activity);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(box);

        TextView intro = text(
                "Suzuki/Denso A5 Active-Control Discovery\n\n" +
                "Related DJ0HSE01/DJ0HSE51 firmware implements service 0xA5 and uses " +
                "positive replies beginning E5 <ID>. The Bandit already matches that " +
                "firmware closely for 1A89/91/95/9A and 2108, but its A5 ID map must " +
                "be measured directly."
        );
        box.addView(intro);

        TextView warning = text(
                "Discovery probe: A5 <ID> only, with P0..P3 deliberately omitted. " +
                "This avoids sending any complete known actuator command. It is still " +
                "labelled conservative rather than proven non-state-changing until the " +
                "Bandit's parser behaviour is observed. Run the first sweep with the engine stopped."
        );
        warning.setTextSize(12);
        warning.setTextColor(Color.rgb(155, 85, 0));
        warning.setPadding(0, dp(8), 0, dp(8));
        box.addView(warning);

        scan = new Button(activity);
        scan.setText("SCAN A5 IDS 00–FF");
        scan.setOnClickListener(v -> confirmScan());
        box.addView(scan);

        TextView known = text(
                "Related-firmware reference only — not assumed for Bandit:\n" +
                "00  supported / unknown\n" +
                "01  PAIR solenoid\n" +
                "02  unsupported in DJ0HSE51\n" +
                "03  position/servo control path\n" +
                "04  position/servo control path\n" +
                "05  ISC / idle-control family\n" +
                "06  cooling fan\n" +
                "07  physical binary output\n\n" +
                "Actuator execution remains disabled in this discovery build."
        );
        known.setTypeface(Typeface.MONOSPACE);
        known.setTextSize(12);
        known.setPadding(0, dp(10), 0, dp(8));
        box.addView(known);

        LinearLayout logRow = new LinearLayout(activity);
        logRow.setOrientation(LinearLayout.HORIZONTAL);

        Button share = new Button(activity);
        share.setText("SHARE CSV LOG");
        share.setOnClickListener(v -> activity.shareProtocolLog());
        logRow.addView(share, weight());

        Button clear = new Button(activity);
        clear.setText("CLEAR LOG");
        clear.setOnClickListener(v ->
                new AlertDialog.Builder(activity)
                        .setTitle("Clear protocol log?")
                        .setMessage("This clears the current CSV log only.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Clear", (d, which) -> {
                            activity.clearProtocolLog();
                            Toast.makeText(activity, "Protocol log cleared", Toast.LENGTH_SHORT).show();
                        })
                        .show()
        );
        logRow.addView(clear, weight());
        box.addView(logRow);

        result = text("No A5 discovery sweep performed yet.");
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextIsSelectable(true);
        result.setPadding(0, dp(8), 0, 0);
        box.addView(result);

        new AlertDialog.Builder(activity)
                .setTitle("A5 Discovery")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .show();
    }

    private void confirmScan() {
        new AlertDialog.Builder(activity)
                .setTitle("Run full A5 ID discovery?")
                .setMessage(
                        "This sends A5 00, A5 01, ... A5 FF with P0..P3 omitted. " +
                        "It continues through all 256 IDs regardless of gaps or NRCs.\n\n" +
                        "No complete known actuator payload is sent. Every request and raw " +
                        "response is logged to the protocol CSV."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run scan", (d, which) -> startScan())
                .show();
    }

    private void startScan() {
        scan.setEnabled(false);
        result.setText(
                "A5 discovery\n0 / 256\n" +
                "Probe form: A5 <ID> (P0..P3 omitted)"
        );

        activity.runExclusiveSdsTask(
                "A5 short ID discovery",
                this::scanIds,
                (scanResult, error) -> {
                    scan.setEnabled(true);

                    if (error != null) {
                        result.setText("A5 discovery failed:\n" + error.getMessage());
                        return;
                    }

                    result.setText(
                            scanResult == null
                                    ? "A5 discovery returned no result."
                                    : scanResult.report
                    );
                }
        );
    }

    private ScanResult scanIds(SuzukiSds sds) throws Exception {
        StringBuilder rows = new StringBuilder();
        LinkedHashMap<Integer, Integer> nrcCounts = new LinkedHashMap<>();
        ArrayList<String> positives = new ArrayList<>();

        int negative = 0;
        int other = 0;

        rows.append("ID  request  result     NRC  ms    RXB  raw\n");

        for (int id = 0; id <= 0xFF; id++) {
            String command = String.format(Locale.US, "A5%02X", id);

            long start = System.nanoTime();
            String response;

            try {
                response = sds.requestRaw(command + " 1", 3000);
            } catch (Exception e) {
                response = "I/O ERROR: " + e.getMessage();
            }

            long elapsedMs = Math.max(
                    0L,
                    (System.nanoTime() - start) / 1_000_000L
            );

            int nrc = negativeResponseCode(response);
            boolean positive = hasPositive(response, id);
            String classification;

            if (positive) {
                classification = "POSITIVE";
                positives.add(String.format(Locale.US, "%02X", id));
            } else if (nrc >= 0) {
                classification = "NEGATIVE";
                negative++;
                nrcCounts.put(nrc, nrcCounts.getOrDefault(nrc, 0) + 1);
            } else {
                classification = classifyOther(response);
                other++;
            }

            int responseBytes = responseByteCount(response);

            rows.append(String.format(
                    Locale.US,
                    "%02X  %-7s  %-9s  %-4s  %-4d  %-3s  %s\n",
                    id,
                    spaced(command),
                    classification,
                    nrc >= 0 ? String.format(Locale.US, "%02X", nrc) : "--",
                    elapsedMs,
                    responseBytes >= 0 ? Integer.toString(responseBytes) : "--",
                    oneLine(response)
            ));

            final int done = id + 1;
            final int currentId = id;
            activity.runOnUiThread(() -> {
                if (result != null) {
                    result.setText(String.format(
                            Locale.UK,
                            "A5 discovery\n%d / 256\nCurrent ID 0x%02X\n" +
                            "All transactions are being written to CSV.",
                            done,
                            currentId
                    ));
                }
            });

            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("A5 discovery interrupted", e);
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("A5 SHORT-ID DISCOVERY COMPLETE\n");
        report.append("Requests: 256\n");
        report.append("Positive E5<ID>: ").append(positives.size()).append("\n");
        report.append("Negative 7F A5 xx: ").append(negative).append("\n");
        report.append("Other / timeout: ").append(other).append("\n");

        report.append("Positive IDs: ");
        if (positives.isEmpty()) {
            report.append("none\n");
        } else {
            report.append("0x").append(String.join(", 0x", positives)).append("\n");
        }

        if (!nrcCounts.isEmpty()) {
            report.append("\nNRC distribution\n");
            for (Map.Entry<Integer, Integer> e : nrcCounts.entrySet()) {
                report.append(String.format(
                        Locale.US,
                        "0x%02X: %d\n",
                        e.getKey(),
                        e.getValue()
                ));
            }
        }

        report.append(
                "\nImportant: if every ID returns the same invalid-format NRC, " +
                "the ECU may be checking payload length before the ID. That result would " +
                "not prove the IDs unsupported; it would tell us the next probe must use " +
                "a complete six-byte frame under controlled conditions.\n\n"
        );

        report.append(rows);

        return new ScanResult(report.toString().trim());
    }

    private boolean hasPositive(String response, int id) {
        String hex = compactHex(response);
        return hex.contains(String.format(Locale.US, "E5%02X", id & 0xFF));
    }

    private int negativeResponseCode(String response) {
        String hex = compactHex(response);
        int p = hex.indexOf("7FA5");
        if (p < 0 || p + 6 > hex.length()) return -1;

        try {
            return Integer.parseInt(hex.substring(p + 4, p + 6), 16);
        } catch (Exception e) {
            return -1;
        }
    }

    private String classifyOther(String response) {
        if (response == null) return "TIMEOUT";
        String upper = response.toUpperCase(Locale.US);

        if (upper.contains("NO DATA")) return "NO DATA";
        if (upper.contains("TIMEOUT")) return "TIMEOUT";
        if (upper.contains("ERROR") || upper.contains("?")) return "ADAPTER";
        return "OTHER";
    }

    private int responseByteCount(String response) {
        if (response == null) return -1;

        String upper = response.toUpperCase(Locale.US);
        if (upper.contains("NO DATA") ||
                upper.contains("ERROR") ||
                upper.contains("UNABLE") ||
                upper.contains("TIMEOUT") ||
                upper.trim().equals("?")) {
            return -1;
        }

        String hex = upper.replaceAll("[^0-9A-F]", "");
        return (hex.length() & 1) == 0 ? hex.length() / 2 : -1;
    }

    private String compactHex(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
    }

    private String spaced(String hex) {
        return hex.replaceAll("(.{2})(?!$)", "$1 ");
    }

    private String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
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

    private static final class ScanResult {
        final String report;
        ScanResult(String report) {
            this.report = report;
        }
    }
}
