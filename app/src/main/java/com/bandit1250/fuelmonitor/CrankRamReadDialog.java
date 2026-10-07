package com.bandit1250.fuelmonitor;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.widget.*;

import java.util.Locale;

/**
 * Deliberately gated RAM/crank read scaffold.
 *
 * Reference BIN: D4FASE80
 * Actual bike:   D4F9SE01
 *
 * The addresses are firmware-derived and useful, but the diagnostic/debug
 * request format has not yet been proven. Therefore this screen ships with
 * transmission disabled until a concrete candidate packet is compiled in.
 */
public final class CrankRamReadDialog {
    private final MainActivity activity;

    /*
     * DO NOT guess this.
     *
     * When the firmware work proves a candidate read format, replace the empty
     * template with a hard-coded builder implementation and set
     * CANDIDATE_READ_ENABLED true. The UI is already intentionally restricted
     * to the whitelist below.
     */
    private static final boolean CANDIDATE_READ_ENABLED = false;

    private static final Target[] TARGETS = {
            new Target(0xFFFF9FD0L, 2,
                    "21 08 RPM mirror — first validation target"),
            new Target(0xFFFF8EA0L, 2,
                    "underlying normal RPM quantity"),
            new Target(0xFFFF8158L, 2,
                    "latest CKP interval / 8"),
            new Target(0xFFFF815AL, 2,
                    "latest 3 CKP intervals / 8"),
            new Target(0xFFFF815CL, 2,
                    "latest 6 CKP intervals / 8"),
            new Target(0xFFFF815EL, 2,
                    "latest 12 CKP intervals / 8"),
            new Target(0xFFFF8166L, 1,
                    "crank / 720-degree phase state"),
            new Target(0xFFFF8ED0L, 2,
                    "processed 3-event crank-period copy"),
            new Target(0xFFFF8ED2L, 2,
                    "longer crank-period quantity"),
            new Target(0xFFFF8ED4L, 1,
                    "phase/state copy")
    };

    public CrankRamReadDialog(MainActivity activity) {
        this.activity = activity;
    }

    public void show() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        scroll.addView(box);

        TextView intro = text(
                "EXPERIMENTAL RAM / CRANK READ\n\n" +
                "Reference BIN: D4FASE80\n" +
                "Bike ECU: D4F9SE01\n\n" +
                "The firmware-derived RAM targets are known, but the live diagnostic/debug read packet is not yet proven. " +
                "This build therefore includes the whitelist and validation workflow but DOES NOT transmit a guessed memory-read command.\n\n" +
                "Validation order once a candidate packet is compiled in:\n" +
                "1. Read FFFF9FD0 and compare with the simultaneous 21 08 RPM raw value.\n" +
                "2. Read FFFF8158 and verify rapid speed-dependent CKP-period change.\n" +
                "3. Read FFFF8166 and verify repeating phase values including 04 / 10 / 1C / 28.\n" +
                "4. Prefer an atomic block read spanning FFFF8158…FFFF8166 if the protocol supports it."
        );
        intro.setTextSize(12);
        box.addView(intro);

        TextView whitelist = new TextView(activity);
        whitelist.setTypeface(Typeface.MONOSPACE);
        whitelist.setTextSize(12);
        whitelist.setText(buildWhitelistText());
        whitelist.setTextIsSelectable(true);
        box.addView(whitelist);

        Button known = new Button(activity);
        known.setText("READ KNOWN RPM MIRROR — FFFF9FD0");
        known.setEnabled(CANDIDATE_READ_ENABLED);
        known.setOnClickListener(v -> runRead(TARGETS[0]));
        box.addView(known);

        Button ckp = new Button(activity);
        ckp.setText("READ LATEST CKP PERIOD — FFFF8158");
        ckp.setEnabled(CANDIDATE_READ_ENABLED);
        ckp.setOnClickListener(v -> runRead(TARGETS[2]));
        box.addView(ckp);

        Button phase = new Button(activity);
        phase.setText("READ CRANK PHASE — FFFF8166");
        phase.setEnabled(CANDIDATE_READ_ENABLED);
        phase.setOnClickListener(v -> runRead(TARGETS[6]));
        box.addView(phase);

        TextView gate = text(
                CANDIDATE_READ_ENABLED
                        ? "Candidate read path compiled in — BIKE TEST REQUIRED."
                        : "TRANSMIT GATE: disabled. Firmware chat must supply a concrete read request/response format before these buttons can send anything."
        );
        gate.setTypeface(null, Typeface.BOLD);
        box.addView(gate);

        new AlertDialog.Builder(activity)
                .setTitle("Whitelisted RAM / crank read")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .show();
    }

    private void runRead(Target target) {
        if (!CANDIDATE_READ_ENABLED) {
            Toast.makeText(
                    activity,
                    "RAM read transmission is intentionally disabled in this build.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        /*
         * This branch intentionally cannot execute until the firmware-derived
         * request builder is added. Keeping it explicit prevents a generic
         * arbitrary-address service from silently appearing in a production
         * build.
         */
        new AlertDialog.Builder(activity)
                .setTitle("Candidate packet not compiled")
                .setMessage(
                        String.format(
                                Locale.US,
                                "Target %08X (%d byte%s) is whitelisted, but no candidate read packet builder is compiled into this source.",
                                target.address,
                                target.length,
                                target.length == 1 ? "" : "s"
                        )
                )
                .setPositiveButton("OK", null)
                .show();
    }

    private String buildWhitelistText() {
        StringBuilder s = new StringBuilder("\nWhitelisted reference-BIN targets\n");
        for (Target t : TARGETS) {
            s.append(String.format(
                    Locale.US,
                    "%08X  %d B  %s\n",
                    t.address,
                    t.length,
                    t.label
            ));
        }

        s.append("\nNo arbitrary address entry, write, erase, programming or service sweep is provided.");
        return s.toString();
    }

    private static final class Target {
        final long address;
        final int length;
        final String label;

        Target(long address, int length, String label) {
            this.address = address;
            this.length = length;
            this.label = label;
        }
    }

    private TextView text(String value) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextSize(13);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private int dp(int value) {
        return (int)(value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
