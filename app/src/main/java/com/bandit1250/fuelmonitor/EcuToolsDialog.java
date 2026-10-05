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
    private TextView profileRaw;
    private TextView dtcResult;
    private Button profileRefresh;
    private Button profileRawToggle;
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
        box.addView(heading("ECU information"));

        profileSummary = mono(profileSummaryText());
        box.addView(profileSummary);

        profileRefresh = new Button(activity);
        profileRefresh.setText("REFRESH INFO");
        profileRefresh.setOnClickListener(v -> refreshProfile());
        box.addView(profileRefresh);

        profileRawToggle = new Button(activity);
        profileRawToggle.setText("RAW ID DATA");
        profileRawToggle.setOnClickListener(v -> {
            boolean show = profileRaw.getVisibility() != android.view.View.VISIBLE;
            profileRaw.setVisibility(
                    show ? android.view.View.VISIBLE : android.view.View.GONE
            );
            profileRawToggle.setText(show ? "HIDE RAW ID DATA" : "RAW ID DATA");
        });
        box.addView(profileRawToggle);

        profileRaw = mono(profileRawText());
        profileRaw.setVisibility(android.view.View.GONE);
        profileRaw.setTextSize(11);
        box.addView(profileRaw);

        TextView profileNote = body(
                "Refresh reads only known read-only identification records: " +
                "1A89, 1A91, 1A95, 1A9A and 21 90. The software ID is decoded " +
                "from the first 8 data bytes of 21 90; unknown records remain raw."
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

        final String id89Raw;
        final String id91Raw;
        final String id95Raw;
        final String id9aRaw;
        final String id90Raw;

        final String ecuPart;
        final String ecuPartAlt;
        final String softwareId;

        ProfileRead(
                String adapter,
                String protocol,
                String id89Raw,
                String id91Raw,
                String id95Raw,
                String id9aRaw,
                String id90Raw,
                String ecuPart,
                String ecuPartAlt,
                String softwareId
        ) {
            this.adapter = adapter;
            this.protocol = protocol;
            this.id89Raw = id89Raw;
            this.id91Raw = id91Raw;
            this.id95Raw = id95Raw;
            this.id9aRaw = id9aRaw;
            this.id90Raw = id90Raw;
            this.ecuPart = ecuPart;
            this.ecuPartAlt = ecuPartAlt;
            this.softwareId = softwareId;
        }
    }

    private void refreshProfile() {
        profileRefresh.setEnabled(false);
        if (profileRawToggle != null) profileRawToggle.setEnabled(false);
        profileSummary.setText("Refreshing ECU identification…");

        activity.runExclusiveSdsTask(
                "ECU information refresh",
                sds -> {
                    String adapter = sds.requestRaw("ATI", 2500);
                    String protocol = sds.requestRaw("ATDPN", 2500);

                    // All of these are already-proven read-only identification
                    // / local-data requests on this Bandit ECU family.
                    String id89 = sds.requestRaw("1A89 1", 4500);
                    String id91 = sds.requestRaw("1A91 1", 4500);
                    String id95 = sds.requestRaw("1A95 1", 4500);
                    String id9a = sds.requestRaw("1A9A 1", 4500);
                    String id90 = sds.requestRaw("2190 1", 5500);

                    return new ProfileRead(
                            oneLine(adapter),
                            oneLine(protocol),
                            oneLine(id89),
                            oneLine(id91),
                            oneLine(id95),
                            oneLine(id9a),
                            oneLine(id90),
                            extract1AAscii(id91, 0x91),
                            extract1AAscii(id9a, 0x9A),
                            extract21AsciiPrefix(id90, 0x90, 8)
                    );
                },
                (info, error) -> {
                    profileRefresh.setEnabled(true);
                    if (profileRawToggle != null) profileRawToggle.setEnabled(true);

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
                                .putString("ecu_profile_1a89_raw", info.id89Raw)
                                .putString("ecu_profile_1a91_raw", info.id91Raw)
                                .putString("ecu_profile_1a95_raw", info.id95Raw)
                                .putString("ecu_profile_1a9a_raw", info.id9aRaw)
                                .putString("ecu_profile_2190_raw", info.id90Raw)
                                .putString("ecu_profile_part", info.ecuPart)
                                .putString("ecu_profile_part_alt", info.ecuPartAlt)
                                .putString("ecu_profile_software", info.softwareId)
                                // Preserve old keys for compatibility with any
                                // previously stored profile data.
                                .putString("ecu_profile_id_raw", info.id91Raw)
                                .putString("ecu_profile_id_ascii", info.ecuPart)
                                .putLong(
                                        "ecu_profile_updated",
                                        System.currentTimeMillis()
                                )
                                .apply();
                    }

                    profileSummary.setText(profileSummaryText());
                    if (profileRaw != null) {
                        profileRaw.setText(profileRawText());
                    }
                }
        );
    }

    private String profileSummaryText() {
        SharedPreferences p = prefs();

        String adapter = p.getString("ecu_profile_adapter", "not refreshed");
        String protocol = p.getString("ecu_profile_protocol", "not refreshed");

        String ecuPart = p.getString(
                "ecu_profile_part",
                p.getString("ecu_profile_id_ascii", "not refreshed")
        );
        String ecuPartAlt = p.getString(
                "ecu_profile_part_alt",
                "not refreshed"
        );
        String softwareId = p.getString(
                "ecu_profile_software",
                "not refreshed"
        );

        long updated = p.getLong("ecu_profile_updated", 0L);
        String when = updated == 0L
                ? "never"
                : new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.UK
                ).format(new Date(updated));

        return "Adapter: " + adapter + "\n" +
                "ELM protocol: " + protocol + "\n\n" +
                "Suzuki ECU ID: " + ecuPart + "\n" +
                "Alternate ECU ID: " + ecuPartAlt + "\n" +
                "Software / firmware ID: " + softwareId + "\n" +
                "Hardware/version fields: not decoded yet\n\n" +
                "Updated: " + when;
    }

    private String profileRawText() {
        SharedPreferences p = prefs();

        return "1A89: " +
                p.getString("ecu_profile_1a89_raw", "not refreshed") + "\n" +
                "1A91: " +
                p.getString(
                        "ecu_profile_1a91_raw",
                        p.getString("ecu_profile_id_raw", "not refreshed")
                ) + "\n" +
                "1A95: " +
                p.getString("ecu_profile_1a95_raw", "not refreshed") + "\n" +
                "1A9A: " +
                p.getString("ecu_profile_1a9a_raw", "not refreshed") + "\n" +
                "2190: " +
                p.getString("ecu_profile_2190_raw", "not refreshed");
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

    /**
     * Decode a fixed number of data bytes from a positive 0x21 local-ID
     * response. For 21 90 the BIN analysis proves that the first eight data
     * bytes map to the ROM software ID at 0x3FFF0..0x3FFF7.
     */
    private String extract21AsciiPrefix(
            String response,
            int localId,
            int byteCount
    ) {
        String hex = compactHex(response);
        String marker = String.format(Locale.US, "61%02X", localId & 0xFF);
        int p = hex.indexOf(marker);

        if (p < 0) return "no positive 61 response";

        int start = p + marker.length();
        int wantedChars = byteCount * 2;

        if (hex.length() < start + wantedChars) {
            return "short 21 response";
        }

        String payload = hex.substring(start, start + wantedChars);
        StringBuilder out = new StringBuilder();

        for (int i = 0; i + 1 < payload.length(); i += 2) {
            int value;

            try {
                value = Integer.parseInt(payload.substring(i, i + 2), 16);
            } catch (NumberFormatException e) {
                return "invalid 21 response";
            }

            if (value >= 0x20 && value <= 0x7E) {
                out.append((char)value);
            } else {
                out.append('.');
            }
        }

        return out.toString();
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

                    // Firmware proves the normal clear-all positive response is
                    // exactly 54 00 00. Do not accept an unrelated 0x54 byte.
                    if (compact.contains("540000")) {
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
        if (profileRawToggle != null) profileRawToggle.setEnabled(!busy);
        if (devTools != null) devTools.setEnabled(!busy);
        if (shareProtocolLog != null) shareProtocolLog.setEnabled(!busy);
    }

    /**
     * Decode the Bandit/Denso service 0x18 response using the record format
     * proven from the 18H00-family firmware:
     *
     *   58 NN [HH LL SS]...
     *
     * NN is the number of records. Each record is exactly three bytes:
     * a literal 16-bit Suzuki diagnostic number plus a Suzuki/Denso status
     * byte. The number is NOT generic SAE P/C/B/U packed-bit encoding.
     *
     * Status interpretation currently proven:
     *   bit 6 (0x40) set -> fault condition is currently active
     *   bits 1:0        -> subtype value 0..2; exact human meaning not proven
     */
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

        if (payload.length() < 2) {
            return "Short 0x58 response: missing DTC count byte.\n" +
                    "Raw: " + oneLine(response);
        }

        final int count;
        try {
            count = Integer.parseInt(payload.substring(0, 2), 16);
        } catch (NumberFormatException e) {
            return "Could not decode DTC count byte.\nRaw: " + oneLine(response);
        }

        int availableDataBytes = Math.max(0, (payload.length() - 2) / 2);
        int availableRecords = availableDataBytes / 3;
        int recordsToDecode = Math.min(count, availableRecords);
        int expectedDataBytes = count * 3;

        StringBuilder out = new StringBuilder();
        out.append("Engine ECU fault codes\n");
        out.append("Reported count: ").append(count).append("\n");

        if (count == 0) {
            out.append("No stored/reportable engine ECU fault codes.\n");
        }

        for (int record = 0; record < recordsToDecode; record++) {
            int charIndex = 2 + (record * 6);

            try {
                int hi = Integer.parseInt(
                        payload.substring(charIndex, charIndex + 2), 16
                );
                int lo = Integer.parseInt(
                        payload.substring(charIndex + 2, charIndex + 4), 16
                );
                int status = Integer.parseInt(
                        payload.substring(charIndex + 4, charIndex + 6), 16
                );

                int code = (hi << 8) | lo;
                boolean current = (status & 0x40) != 0;
                int subtype = status & 0x03;

                out.append("\n")
                        .append(formatSuzukiDtc(code))
                        .append("\n")
                        .append("  State: ")
                        .append(current
                                ? "Current"
                                : "Stored / not currently active")
                        .append("\n")
                        .append("  Raw DTC: ")
                        .append(String.format(Locale.US, "%04X", code))
                        .append("\n")
                        .append("  Raw status: ")
                        .append(String.format(Locale.US, "%02X", status))
                        .append("\n")
                        .append("  Subtype: ")
                        .append(subtype)
                        .append(" [Experimental]\n");

            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                out.append("\nRecord ")
                        .append(record + 1)
                        .append(": decode error\n");
                break;
            }
        }

        if (availableDataBytes != expectedDataBytes) {
            out.append("\nWARNING: firmware format predicts ")
                    .append(expectedDataBytes)
                    .append(" DTC data bytes for count ")
                    .append(count)
                    .append(", but ")
                    .append(availableDataBytes)
                    .append(" complete byte(s) were present after the count.");
        }

        if (count > availableRecords) {
            out.append("\nWARNING: response is too short for all reported records.");
        }

        out.append("\n\nRaw: ").append(oneLine(response));
        out.append(
                "\n\nDecoder basis: firmware-proven 58 NN [HH LL SS] records. " +
                "Status bit 0x40 = currently active. The low two status bits are " +
                "shown as a subtype, but subtype 1/2 meanings are not yet proven."
        );

        return out.toString();
    }

    /**
     * The firmware stores the diagnostic number literally (for example
     * 0x0105, 0x0335, 0x1750). "P" is the human display/correlation prefix.
     */
    private String formatSuzukiDtc(int code) {
        return String.format(Locale.US, "P%04X", code & 0xFFFF);
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
