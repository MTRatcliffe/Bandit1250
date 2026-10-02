package com.bandit1250.fuelmonitor;

import android.app.*;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Fault-code and experimental ECU tools popup.
 *
 * The experimental memory reader is intentionally read-only. It only uses
 * KWP service 0x23 and does not send erase/write/download/programming commands.
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
    private TextView dtcResult;
    private TextView experimentalResult;
    private Button readDtc;
    private Button clearDtc;
    private Button probeRead;

    public EcuToolsDialog(MainActivity activity) {
        this.activity = activity;
    }

    public void show() {
        ScrollView scroll = new ScrollView(activity);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(box);

        TextView engineHead = heading("Engine ECU fault codes");
        box.addView(engineHead);

        TextView engineNote = body(
                "Reads the engine ECU using KWP/SDS. Raw ECU responses are " +
                "shown as well as a provisional standard DTC decode."
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
                "ABS is a separate SDS controller. ABS read/clear is not yet " +
                "enabled here until its diagnostic address/session is mapped."
        );
        absNote.setPadding(0, dp(10), 0, dp(14));
        box.addView(absNote);

        View divider = new View(activity);
        divider.setBackgroundColor(Color.rgb(170, 170, 170));
        box.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        ));

        TextView experimentalHead = heading(
                "Experimental ECU memory read — USE AT YOUR OWN RISK"
        );
        experimentalHead.setTextColor(Color.rgb(175, 65, 0));
        experimentalHead.setPadding(0, dp(14), 0, dp(4));
        box.addView(experimentalHead);

        TextView warning = body(
                "READ-ONLY experimental tool. It pauses normal live-data polling " +
                "and probes standard KWP ReadMemoryByAddress (0x23). It does NOT " +
                "send erase, write, download or flash commands.\n\n" +
                "Keep ignition and battery voltage stable. Do not disconnect the " +
                "Bluetooth adapter during a read. The Bandit may require a " +
                "different flashing session/harness, so the probe may simply be rejected."
        );
        box.addView(warning);

        probeRead = new Button(activity);
        probeRead.setText("PROBE / READ ECU");
        probeRead.setOnClickListener(v -> confirmProbe());
        box.addView(probeRead);

        experimentalResult = mono(
                "No ECU memory probe performed yet."
        );
        box.addView(experimentalResult);

        dialog = new AlertDialog.Builder(activity)
                .setTitle("Fault codes / ECU tools")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        dialog.setOnDismissListener(d -> dialog = null);
        dialog.show();
    }

    private void readDtcs() {
        setDtcBusy(true);
        dtcResult.setText("Reading engine ECU fault codes…");

        activity.runExclusiveSdsTask(
                "DTC read",
                sds -> sds.requestRaw("1802FF00 1", 5000),
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
        dtcResult.setText("Clearing stored engine ECU fault codes…");

        activity.runExclusiveSdsTask(
                "DTC clear",
                sds -> sds.requestRaw("14FF00 1", 5000),
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
                        // Re-read automatically so the user sees what remains.
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

    private void confirmProbe() {
        new AlertDialog.Builder(activity)
                .setTitle("Experimental read-only ECU probe")
                .setMessage(
                        "This will pause live SDS polling and send only KWP " +
                        "ReadMemoryByAddress (0x23) requests.\n\n" +
                        "No erase or write commands are used. Continue?"
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, which) -> startProbe())
                .show();
    }

    private void startProbe() {
        probeRead.setEnabled(false);
        experimentalResult.setText(
                "Probing standard KWP memory-read formats at address 0x000000…"
        );

        activity.runExclusiveSdsTask(
                "ECU memory probe",
                EcuMemoryReader::probe,
                (probe, error) -> {
                    probeRead.setEnabled(true);

                    if (error != null) {
                        experimentalResult.setText(
                                "Probe failed:\n" + error.getMessage()
                        );
                        return;
                    }

                    if (probe == null || !probe.supported) {
                        experimentalResult.setText(
                                "Memory read not confirmed.\n\n" +
                                (probe == null ? "" : probe.explanation + "\n\n" +
                                        "Probe responses:\n" + probe.response)
                        );
                        return;
                    }

                    experimentalResult.setText(
                            "Memory read responded positively.\n" +
                            "Address width: " + probe.addressBytes + " bytes\n" +
                            "Usable block size: " + probe.blockSize + " bytes\n" +
                            "Probe request: " + probe.request + "\n" +
                            "Probe response: " + oneLine(probe.response)
                    );

                    chooseDumpSize(probe);
                }
        );
    }

    private void chooseDumpSize(EcuMemoryReader.ProbeResult probe) {
        final String[] labels = {
                "256 KiB",
                "512 KiB",
                "1 MiB"
        };

        final long[] sizes = {
                256L * 1024L,
                512L * 1024L,
                1024L * 1024L
        };

        new AlertDialog.Builder(activity)
                .setTitle("Memory access works")
                .setMessage(
                        "The ECU accepted a standard read request. The exact " +
                        "Bandit flash size is not yet verified in this app. " +
                        "Choose the experimental range to read from address 0x000000.\n\n" +
                        "A .bin will only be kept if the entire selected range completes."
                )
                .setItems(labels, (d, which) ->
                        startFullRead(probe, sizes[which])
                )
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startFullRead(
            EcuMemoryReader.ProbeResult probe,
            long totalBytes
    ) {
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Reading ECU memory");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setCancelable(false);
        progress.setMessage(
                "READ-ONLY experimental operation\nAddress 0x000000"
        );
        progress.show();

        probeRead.setEnabled(false);

        File dir = new File(activity.getFilesDir(), "ecu_dumps");
        String stamp = new SimpleDateFormat(
                "yyyy-MM-dd_HHmmss",
                Locale.UK
        ).format(new Date());

        File output = new File(
                dir,
                "Bandit1250_ECU_" + stamp + ".bin"
        );

        activity.runExclusiveSdsTask(
                "ECU read",
                sds -> EcuMemoryReader.read(
                        sds,
                        output,
                        totalBytes,
                        probe.addressBytes,
                        probe.blockSize,
                        (done, total, address) ->
                                activity.runOnUiThread(() -> {
                                    int pct = total <= 0
                                            ? 0
                                            : (int)Math.min(
                                                    100,
                                                    (done * 100L) / total
                                            );
                                    progress.setProgress(pct);
                                    progress.setMessage(String.format(
                                            Locale.UK,
                                            "READ-ONLY experimental operation\n" +
                                            "Address 0x%08X\n" +
                                            "%,d / %,d bytes",
                                            address,
                                            done,
                                            total
                                    ));
                                })
                ),
                (result, error) -> {
                    probeRead.setEnabled(true);

                    if (progress.isShowing()) {
                        progress.dismiss();
                    }

                    if (error != null) {
                        experimentalResult.setText(
                                "ECU read stopped; no partial .bin was kept.\n\n" +
                                error.getMessage()
                        );
                        return;
                    }

                    experimentalResult.setText(
                            "ECU memory read complete.\n" +
                            "File: " + result.file.getName() + "\n" +
                            "Bytes: " + result.bytes + "\n" +
                            "SHA-256: " + result.sha256
                    );

                    showReadComplete(result);
                }
        );
    }

    private void showReadComplete(EcuMemoryReader.ReadResult result) {
        String size = humanBytes(result.bytes);

        new AlertDialog.Builder(activity)
                .setTitle("ECU read complete")
                .setMessage(
                        "File: " + result.file.getName() + "\n" +
                        "Size: " + size + "\n" +
                        "SHA-256:\n" + result.sha256 + "\n\n" +
                        "Would you like to share the .bin?"
                )
                .setNegativeButton("Close", null)
                .setPositiveButton("Share BIN", (d, which) ->
                        activity.shareEcuBin(result.file)
                )
                .show();
    }

    private void setDtcBusy(boolean busy) {
        if (readDtc != null) readDtc.setEnabled(!busy);
        if (clearDtc != null) clearDtc.setEnabled(!busy);
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

        // KWP implementations commonly prepend a count before 3-byte
        // DTC/status tuples. Handle both count and no-count forms.
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

            // Some ECUs pad empty records with zero.
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
        out.append("\n\nNote: code/status interpretation is provisional until " +
                "verified against the Bandit's Suzuki SDS format.");

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

    private String humanBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(
                    Locale.UK,
                    "%.2f MiB",
                    bytes / (1024.0 * 1024.0)
            );
        }
        return String.format(
                Locale.UK,
                "%.1f KiB",
                bytes / 1024.0
        );
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
