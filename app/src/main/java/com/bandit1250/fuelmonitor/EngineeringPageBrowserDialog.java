package com.bandit1250.fuelmonitor;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.widget.*;

import java.util.*;

/**
 * Read-only browser for firmware-proven Suzuki/Denso service 0x21 pages.
 *
 * Accepted by the analysed 18H00-family application handler:
 *   08, 40-5F, 80, 90, C0
 *
 * Unknown bytes deliberately remain raw. Every request goes through the normal
 * SDS transaction path and is therefore captured in the shared protocol CSV.
 */
public final class EngineeringPageBrowserDialog {
    private final MainActivity activity;

    private AlertDialog dialog;
    private Spinner pageSpinner;
    private TextView result;
    private Button readOnce;
    private Button startPoll;
    private Button stopPoll;

    private volatile boolean polling;
    private final Map<Integer, byte[]> previousByPage = new HashMap<>();

    private static final int[] PAGES = buildPages();

    public EngineeringPageBrowserDialog(MainActivity activity) {
        this.activity = activity;
    }

    public void show() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(box);

        TextView intro = text(
                "Firmware-proven read-only 0x21 engineering/local-data pages.\n\n" +
                "Available: 08, 40-5F, 80, 90, C0. Unknown values stay raw. " +
                "Every TX/RX is saved in the protocol CSV."
        );
        intro.setTextSize(12);
        box.addView(intro);

        pageSpinner = new Spinner(activity);
        ArrayList<String> labels = new ArrayList<>();
        for (int page : PAGES) {
            labels.add(String.format(Locale.US, "0x%02X", page));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity,
                android.R.layout.simple_spinner_item,
                labels
        );
        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );
        pageSpinner.setAdapter(adapter);
        box.addView(pageSpinner);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        readOnce = new Button(activity);
        readOnce.setText("READ ONCE");
        readOnce.setOnClickListener(v -> readSelectedPage());
        buttons.addView(readOnce, weight());

        startPoll = new Button(activity);
        startPoll.setText("POLL 1 s");
        startPoll.setOnClickListener(v -> startPolling());
        buttons.addView(startPoll, weight());

        stopPoll = new Button(activity);
        stopPoll.setText("STOP");
        stopPoll.setEnabled(false);
        stopPoll.setOnClickListener(v -> stopPolling());
        buttons.addView(stopPoll, weight());

        box.addView(buttons);

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
                        .setMessage("This clears the CSV trace only; it does not change the ECU.")
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
        logRow.addView(clear, weight());
        box.addView(logRow);

        result = text(
                "Select a page and press READ ONCE, or POLL 1 s to highlight " +
                "bytes that change between responses."
        );
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextSize(11);
        result.setTextIsSelectable(true);
        result.setPadding(0, dp(10), 0, dp(8));
        box.addView(result);

        dialog = new AlertDialog.Builder(activity)
                .setTitle("Engineering data pages")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        dialog.setOnDismissListener(d -> {
            polling = false;
            dialog = null;
        });
        dialog.show();
    }

    private void readSelectedPage() {
        if (polling) return;

        final int page = selectedPage();
        setBusy(true);
        result.setText(String.format(Locale.US, "Reading 21 %02X…", page));

        activity.runExclusiveSdsTask(
                String.format(Locale.US, "Engineering page 21 %02X", page),
                sds -> sds.requestRaw(
                        String.format(Locale.US, "21%02X 1", page),
                        5500
                ),
                (response, error) -> {
                    setBusy(false);

                    if (error != null) {
                        result.setText("Read failed:\n" + error.getMessage());
                        return;
                    }

                    render(page, response);
                }
        );
    }

    private void startPolling() {
        if (polling) return;

        final int page = selectedPage();
        polling = true;
        setPollingUi(true);

        result.setText(String.format(
                Locale.US,
                "Polling 21 %02X every ~1 second…\n" +
                "Normal 21 08 polling is paused until STOP.",
                page
        ));

        activity.runExclusiveSdsTask(
                String.format(Locale.US, "Engineering polling 21 %02X", page),
                sds -> {
                    while (polling) {
                        final String response = sds.requestRaw(
                                String.format(Locale.US, "21%02X 1", page),
                                5500
                        );

                        activity.runOnUiThread(() -> {
                            if (dialog != null) render(page, response);
                        });

                        if (!polling) break;

                        try {
                            Thread.sleep(1000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    return null;
                },
                (ignored, error) -> {
                    polling = false;
                    setPollingUi(false);

                    if (error != null && dialog != null) {
                        result.append("\n\nPolling stopped with error: " + error.getMessage());
                    }
                }
        );
    }

    private void stopPolling() {
        polling = false;
        if (result != null) result.append("\n\nStopping…");
    }

    private void setBusy(boolean busy) {
        if (readOnce != null) readOnce.setEnabled(!busy);
        if (startPoll != null) startPoll.setEnabled(!busy);
        if (stopPoll != null) stopPoll.setEnabled(false);
        if (pageSpinner != null) pageSpinner.setEnabled(!busy);
    }

    private void setPollingUi(boolean active) {
        if (readOnce != null) readOnce.setEnabled(!active);
        if (startPoll != null) startPoll.setEnabled(!active);
        if (stopPoll != null) stopPoll.setEnabled(active);
        if (pageSpinner != null) pageSpinner.setEnabled(!active);
    }

    private int selectedPage() {
        int pos = Math.max(0, pageSpinner.getSelectedItemPosition());
        return PAGES[Math.min(pos, PAGES.length - 1)];
    }

    private void render(int page, String response) {
        byte[] payload = extractPayload(response, page);

        if (payload == null) {
            result.setText(
                    String.format(Locale.US, "Page 0x%02X\n", page) +
                    "No matching positive 61 response found.\n\nRaw RX:\n" +
                    oneLine(response)
            );
            return;
        }

        byte[] previous = previousByPage.get(page);

        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.US, "Page 0x%02X  [PROVEN PAGE]\n", page));
        out.append("Payload bytes: ").append(payload.length).append("\n");

        if (page == 0x90 && payload.length >= 8) {
            out.append("Firmware/software ID: ")
                    .append(ascii(payload, 0, 8))
                    .append("  [PROVEN LIVE]\n");

            if (payload.length >= 0x28) {
                out.append("\nADC channels [PROVEN FIRMWARE]\n");
                int[] adc = GuidedTestSupport.page90AdcCounts(payload);
                for (int ch = 0; ch < adc.length; ch++) {
                    out.append(String.format(
                            Locale.US,
                            "  ADC%-2d %4d counts\n",
                            ch,
                            adc[ch]
                    ));
                }
            }
        }

        if (page >= 0x40 && page <= 0x45) {
            out.append("Diagnostic monitor snapshot bank 0 [PROVEN FIRMWARE]\n");
        } else if (page >= 0x50 && page <= 0x55) {
            out.append("Diagnostic monitor snapshot bank 1 [PROVEN FIRMWARE]\n");
        } else if ((page >= 0x46 && page <= 0x4F) ||
                (page >= 0x56 && page <= 0x5F)) {
            out.append("Implemented placeholder page; all-FF is expected [PROVEN FIRMWARE]\n");
        } else if (page == 0x80) {
            out.append("100-byte internal engineering/live-state page [PROVEN FIRMWARE]\n");
        } else if (page == 0xC0) {
            out.append("60-byte curated engineering/live-state page [PROVEN FIRMWARE]\n");
        }

        out.append("\nRaw RX:\n")
                .append(oneLine(response))
                .append("\n\n")
                .append("Ofs  Hex  Dec  Prev  Chg  Meaning\n")
                .append("---  ---  ---  ----  ---  ------------------------------\n");

        for (int i = 0; i < payload.length; i++) {
            int value = payload[i] & 0xFF;
            String prev = "--";
            String changed = "";

            if (previous != null && i < previous.length) {
                int old = previous[i] & 0xFF;
                prev = String.format(Locale.US, "%02X", old);
                if (old != value) changed = "***";
            }

            out.append(String.format(
                    Locale.US,
                    "%02X   %02X   %3d   %2s   %-3s  %s\n",
                    i,
                    value,
                    value,
                    prev,
                    changed,
                    meaning(page, i)
            ));
        }

        out.append(
                "\n*** = changed since the previous read of this page.\n" +
                "Unknown fields are intentionally not given invented meanings."
        );

        previousByPage.put(page, Arrays.copyOf(payload, payload.length));
        result.setText(out.toString());
    }

    private static byte[] extractPayload(String response, int page) {
        if (response == null) return null;

        String hex = response.toUpperCase(Locale.US)
                .replaceAll("[^0-9A-F]", "");

        String marker = String.format(Locale.US, "61%02X", page & 0xFF);
        int p = hex.indexOf(marker);
        if (p < 0) return null;

        String payloadHex = hex.substring(p + marker.length());
        if ((payloadHex.length() & 1) != 0) {
            payloadHex = payloadHex.substring(0, payloadHex.length() - 1);
        }

        byte[] payload = new byte[payloadHex.length() / 2];

        try {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) Integer.parseInt(
                        payloadHex.substring(i * 2, i * 2 + 2),
                        16
                );
            }
        } catch (NumberFormatException e) {
            return null;
        }

        return payload;
    }

    private static String meaning(int page, int offset) {
        if (page == 0x90 && offset < 8) {
            return "Firmware/software ID byte [PROVEN LIVE]";
        }

        if (page == 0x90 && offset >= 0x08 && offset <= 0x27) {
            int ch = (offset - 0x08) / 2;
            boolean high = ((offset - 0x08) & 1) == 0;

            return String.format(
                    Locale.US,
                    "ADC%d %s byte; BE16/64 = count [PROVEN FIRMWARE]",
                    ch,
                    high ? "high" : "low"
            );
        }

        if (page >= 0x46 && page <= 0x4F) {
            return "Placeholder / expected FF";
        }

        if (page >= 0x56 && page <= 0x5F) {
            return "Placeholder / expected FF";
        }

        return "Unknown / raw";
    }

    private static String ascii(byte[] data, int start, int count) {
        StringBuilder s = new StringBuilder();

        for (int i = start; i < start + count && i < data.length; i++) {
            int v = data[i] & 0xFF;
            s.append(v >= 0x20 && v <= 0x7E ? (char) v : '.');
        }

        return s.toString();
    }

    private static int[] buildPages() {
        ArrayList<Integer> values = new ArrayList<>();
        values.add(0x08);
        for (int p = 0x40; p <= 0x5F; p++) values.add(p);
        values.add(0x80);
        values.add(0x90);
        values.add(0xC0);

        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
        return out;
    }

    private static String oneLine(String value) {
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
}
