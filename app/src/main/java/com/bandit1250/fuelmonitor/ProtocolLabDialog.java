package com.bandit1250.fuelmonitor;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.widget.*;

import java.util.Locale;

/**
 * Restricted read-only KWP/SDS command lab.
 *
 * This intentionally does NOT expose a general raw terminal. Only services
 * whose normal purpose is reading diagnostic information are accepted.
 */
public final class ProtocolLabDialog {
    private final MainActivity activity;

    private EditText commandInput;
    private TextView result;
    private Button send;

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
                "Allowed KWP services: 0x18, 0x1A, 0x21, 0x22 and 0x23. " +
                "AT commands, reset/session/security/upload/download/write/routine/" +
                "actuator services are blocked. Requests are limited to 8 data bytes " +
                "for the current V-LINK/ELM path."
        );
        note.setPadding(0, 0, 0, dp(8));
        box.addView(note);

        LinearLayout presets = new LinearLayout(activity);
        presets.setOrientation(LinearLayout.HORIZONTAL);

        presets.addView(preset("1A91"), weight());
        presets.addView(preset("2108"), weight());
        presets.addView(preset("1A89"), weight());
        box.addView(presets);

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

        send.setEnabled(false);
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
                    send.setEnabled(true);

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
