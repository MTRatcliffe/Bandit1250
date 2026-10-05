package com.bandit1250.fuelmonitor;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.*;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Normal day-to-day ECU tools.
 *
 * Experimental protocol work has been moved out to DevToolsDialog so the main
 * ECU tools page stays focused on useful bike functions:
 *
 * - adapter / ECU identity
 * - engine ECU DTC read / clear
 * - protocol-log sharing
 *
 * Deliberately removed from this page:
 * - KWP 0x30 report/service-ID probe
 * - safe ECU discovery
 * - ECU read/flash experiment
 *
 * The remaining experimental tools live behind DEV / EXPERIMENTAL.
 */
public final class EcuToolsDialog {
    public interface SdsAction<T> {
        T run(SuzukiSds sds) throws Exception;
    }

    public interface SdsCallback<T> {
        void done(T result, Exception error);
    }

    private final MainActivity activity;

    private AlertDialog dialog;
    private TextView profileSummary;
    private TextView dtcResult;
    private Button profileRefresh;
    private Button readDtc;
    private Button clearDtc;
    private Button devTools;
    private Button shareProtocolLog;

    public EcuToolsDialog(MainActivity activity) {
        this.activity = activity;
    }

    public void show() {
        ScrollView scroll = new ScrollView(activity);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(box);

        // -------------------------------------------------------------
        // ECU / adapter identity
        // -------------------------------------------------------------
        box.addView(heading("ECU / adapter profile"));

        profileSummary = mono(profileSummaryText());
        box.addView(profileSummary);

        profileRefresh = new Button(activity);
        profileRefresh.setText("REFRESH INFO");
        profileRefresh.setOnClickListener(v -> refreshProfile());
        box.addView(profileRefresh);

        TextView profileNote = body(
                "Refresh reads only adapter identity, selected ELM protocol and the " +
                "known Bandit 1A91 ECU identity."
        );
        profileNote.setTextSize(12);
        profileNote.setPadding(0, dp(2), 0, dp(12));
        box.addView(profileNote);

        addDivider(box);

        // -------------------------------------------------------------
        // DTCs
        // -------------------------------------------------------------
        box.addView(heading("Engine ECU fault codes"));

        TextView engineNote = body(
                "Bike-tested commands: 18 00 00 00 reads engine ECU fault codes; " +
                "14 00 00 clears stored codes. Clearing remains confirmation-gated."
        );
        box.addView(engineNote);

        LinearLayout dtcButtons = row();

        readDtc = new Button(activity);
        readDtc.setText("READ CODES");
        readDtc.setOnClickListener(v -> readDtcs());
        dtcButtons.addView(readDtc, weight());

        clearDtc = new Button(activity);
        clearDtc.setText("CLEAR STORED");
        clearDtc.setOnClickListener(v -> confirmClear());
        dtcButtons.addView(clearDtc, weight());

        box.addView(dtcButtons);

        dtcResult = mono("No fault-code read performed yet.");
        box.addView(dtcResult);

        TextView absNote = body(
                "ABS remains separate; its SDS address/session has not yet been mapped."
        );
        absNote.setTextSize(12);
        absNote.setPadding(0, dp(8), 0, dp(12));
        box.addView(absNote);

        addDivider(box);

        // -------------------------------------------------------------
        // Developer tools entry
        // -------------------------------------------------------------
        box.addView(heading("Developer / experimental"));

        TextView devNote = body(
                "Protocol-development tests are kept on a separate page so they do not " +
                "clutter normal bike diagnostics."
        );
        box.addView(devNote);

        devTools = new Button(activity);
        devTools.setText("DEV / EXPERIMENTAL TOOLS");
        devTools.setOnClickListener(v -> new DevToolsDialog(activity).show());
        box.addView(devTools);

        // -------------------------------------------------------------
        // Protocol CSV
        // -------------------------------------------------------------
        LinearLayout logButtons = row();

        shareProtocolLog = new Button(activity);
        shareProtocolLog.setText("SHARE PROTOCOL LOG");
        shareProtocolLog.setOnClickListener(v -> activity.shareProtocolLog());
        logButtons.addView(shareProtocolLog, weight());

        Button clearProtocolLog = new Button(activity);
        clearProtocolLog.setText("CLEAR LOG");
        clearProtocolLog.setOnClickListener(v ->
                new AlertDialog.Builder(activity)
                        .setTitle("Clear protocol log?")
                        .setMessage(
                                "This clears the current CSV transaction log only. " +
                                "It does not change the ECU."
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
        logButtons.addView(clearProtocolLog, weight());

        box.addView(logButtons);

        dialog = new AlertDialog.Builder(activity)
                .setTitle("Fault codes / ECU tools")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        dialog.setOnDismissListener(d -> dialog = null);
        dialog.show();
    }

    // -----------------------------------------------------------------
    // ECU identity
    // -----------------------------------------------------------------

    private static final class ProfileRead {
        final String adapter;
        final String protocol;
        final String ecuRaw;
        final String ecuAscii;

        ProfileRead(
                String adapter,
                String protocol,
                String ecuRaw,
                String ecuAscii
        ) {
            this.adapter = adapter;
            this.protocol = protocol;
            this.ecuRaw = ecuRaw;
            this.ecuAscii = ecuAscii;
        }
    }

    private void refreshProfile() {
        profileRefresh.setEnabled(false);
        profileSummary.setText("Refreshing adapter / ECU identity…");

        activity.runExclusiveSdsTask(
                "ECU profile refresh",
                sds -> {
                    String adapter = sds.requestRaw("ATI", 2500);
                    String protocol = sds.requestRaw("ATDPN", 2500);
                    String ecu = sds.requestRaw("1A91 1", 4500);

                    return new ProfileRead(
                            oneLine(adapter),
                            oneLine(protocol),
                            oneLine(ecu),
                            extract1AAscii(ecu, 0x91)
                    );
                },
                (info, error) -> {
                    profileRefresh.setEnabled(true);

                    if (error != null) {
                        profileSummary.setText(
                                profileSummaryText() +
                                "\n\nRefresh error: " + error.getMessage()
                        );
                        return;
                    }

                    if (info != null) {
                        prefs().edit()
                                .putString("ecu_profile_adapter", info.adapter)
                                .putString("ecu_profile_protocol", info.protocol)
                                .putString("ecu_profile_id_raw", info.ecuRaw)
                                .putString("ecu_profile_id_ascii", info.ecuAscii)
                                .putLong(
                                        "ecu_profile_updated",
                                        System.currentTimeMillis()
                                )
                                .apply();
                    }

                    profileSummary.setText(profileSummaryText());
                }
        );
    }

    private String profileSummaryText() {
        SharedPreferences p = prefs();

        String adapter = p.getString("ecu_profile_adapter", "not refreshed");
        String protocol = p.getString("ecu_profile_protocol", "not refreshed");
        String ecuAscii = p.getString("ecu_profile_id_ascii", "not refreshed");
        String raw = p.getString("ecu_profile_id_raw", "not refreshed");

        long updated = p.getLong("ecu_profile_updated", 0L);
        String when = updated == 0L
                ? "never"
                : new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.UK
                ).format(new Date(updated));

        return "Adapter: " + adapter + "\n" +
                "ELM protocol: " + protocol + "\n" +
                "ECU ID: " + ecuAscii + "\n" +
                "1A91 raw: " + raw + "\n" +
                "Profile updated: " + when;
    }

    private String extract1AAscii(String response, int localId) {
        String hex = compactHex(response);
        String marker = String.format(Locale.US, "5A%02X", localId & 0xFF);
        int p = hex.indexOf(marker);

        if (p < 0) return "no positive 5A response";

        String payload = hex.substring(p + marker.length());
        StringBuilder out = new StringBuilder();

        for (int i = 0; i + 1 < payload.length(); i += 2) {
            int value;
            try {
                value = Integer.parseInt(payload.substring(i, i + 2), 16);
            } catch (Exception e) {
                break;
            }

            if (value == 0x00 || value == 0xFF) continue;

            if (value >= 0x20 && value <= 0x7E) {
                out.append((char)value);
            } else {
                out.append('.');
            }
        }

        return out.length() == 0 ? "no printable ID" : out.toString();
    }

    // -----------------------------------------------------------------
    // DTC read / clear
    // -----------------------------------------------------------------

    private void readDtcs() {
        setDtcBusy(true);
        dtcResult.setText("Reading engine ECU fault codes…\nTX: 18 00 00 00");

        activity.runExclusiveSdsTask(
                "DTC read",
                sds -> sds.requestRaw("18000000 1", 5000),
                (response, error) -> {
                    setDtcBusy(false);

                    if (error != null) {
                        dtcResult.setText("DTC read failed:\n" + error.getMessage());
                        return;
                    }

                    dtcResult.setText(describeDtcResponse(response));
                }
        );
    }

    private void confirmClear() {
        new AlertDialog.Builder(activity)
                .setTitle("Clear stored ECU fault codes?")
                .setMessage(
                        "This removes diagnostic history from the engine ECU. " +
                        "An active fault will return if the fault is still present."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, which) -> clearDtcs())
                .show();
    }

    private void clearDtcs() {
        setDtcBusy(true);
        dtcResult.setText("Clearing stored engine ECU fault codes…\nTX: 14 00 00");

        activity.runExclusiveSdsTask(
                "DTC clear",
                sds -> sds.requestRaw("140000 1", 5000),
                (response, error) -> {
                    setDtcBusy(false);

                    if (error != null) {
                        dtcResult.setText("Clear failed:\n" + error.getMessage());
                        return;
                    }

                    String compact = compactHex(response);

                    if (compact.contains("54")) {
                        dtcResult.setText(
                                "ECU acknowledged clear request.\n" +
                                "Re-reading fault codes…"
                        );
                        readDtcs();
                    } else {
                        String nrc = EcuMemoryReader.negativeResponseExplanation(
                                response,
                                0x14
                        );
                        dtcResult.setText(
                                "Clear response was not the expected positive 0x54.\n" +
                                (nrc == null ? "" : nrc + "\n") +
                                "Raw: " + oneLine(response)
                        );
                    }
                }
        );
    }

    private void setDtcBusy(boolean busy) {
        if (readDtc != null) readDtc.setEnabled(!busy);
        if (clearDtc != null) clearDtc.setEnabled(!busy);
        if (profileRefresh != null) profileRefresh.setEnabled(!busy);
        if (devTools != null) devTools.setEnabled(!busy);
        if (shareProtocolLog != null) shareProtocolLog.setEnabled(!busy);
    }

    private String describeDtcResponse(String response) {
        String nrc = EcuMemoryReader.negativeResponseExplanation(response, 0x18);

        if (nrc != null) {
            return "DTC request rejected: " + nrc + "\n" +
                    "Raw: " + oneLine(response);
        }

        String hex = compactHex(response);
        int p = hex.indexOf("58");

        if (p < 0) {
            return "No positive 0x58 DTC response found.\n" +
                    "Raw: " + oneLine(response);
        }

        String payload = hex.substring(p + 2);

        if ((payload.length() & 1) != 0) {
            payload = payload.substring(0, payload.length() - 1);
        }

        ArrayList<Integer> bytes = new ArrayList<>();

        try {
            for (int i = 0; i + 1 < payload.length(); i += 2) {
                bytes.add(Integer.parseInt(payload.substring(i, i + 2), 16));
            }
        } catch (NumberFormatException e) {
            return "Could not decode DTC payload.\nRaw: " + oneLine(response);
        }

        int offset = 0;
        Integer reportedCount = null;

        if (bytes.size() >= 1 && ((bytes.size() - 1) % 3 == 0)) {
            reportedCount = bytes.get(0);
            offset = 1;
        }

        StringBuilder out = new StringBuilder();
        out.append("Engine ECU DTC response\n");

        if (reportedCount != null) {
            out.append("Reported count: ")
                    .append(reportedCount)
                    .append("\n");
        }

        int decoded = 0;

        for (int i = offset; i + 2 < bytes.size(); i += 3) {
            int hi = bytes.get(i);
            int lo = bytes.get(i + 1);
            int status = bytes.get(i + 2);
            int code = (hi << 8) | lo;

            if (code == 0 && status == 0) continue;

            out.append(decodeStandardDtc(code))
                    .append("   raw ")
                    .append(String.format(Locale.US, "%02X %02X", hi, lo))
                    .append("   status ")
                    .append(String.format(Locale.US, "%02X", status))
                    .append("\n");
            decoded++;
        }

        if (decoded == 0) {
            out.append("No decodable DTC tuples in this response.\n");
        }

        out.append("\nRaw: ").append(oneLine(response));
        out.append(
                "\n\nNote: code/status interpretation remains provisional " +
                "until fully verified against the Bandit's Suzuki SDS format."
        );

        return out.toString();
    }

    private String decodeStandardDtc(int code) {
        char[] family = {'P', 'C', 'B', 'U'};
        char prefix = family[(code >> 14) & 0x03];
        int firstDigit = (code >> 12) & 0x03;
        int rest = code & 0x0FFF;

        return String.format(
                Locale.US,
                "%c%d%03X",
                prefix,
                firstDigit,
                rest
        );
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(
                "bandit_monitor",
                android.content.Context.MODE_PRIVATE
        );
    }

    private String compactHex(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.US)
                .replaceAll("[^0-9A-F]", "");
    }

    private String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
    }

    private void addDivider(LinearLayout box) {
        android.view.View divider = new android.view.View(activity);
        divider.setBackgroundColor(Color.rgb(170, 170, 170));
        box.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        ));
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
