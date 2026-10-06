package com.bandit1250.fuelmonitor;

import android.content.Context;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Per-test CSV used by guided diagnostics.
 *
 * The normal ProtocolSessionLogger still records every actual ELM/SDS
 * transaction. This file adds the higher-level test phase and interpretation
 * context so the captured data can later be correlated with firmware analysis.
 */
public final class GuidedTestLogger {
    private final File file;
    private final String testName;

    public GuidedTestLogger(Context context, String testName) {
        this.testName = sanitise(testName);

        File dir = new File(context.getFilesDir(), "guided_logs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        String stamp = new SimpleDateFormat(
                "yyyy-MM-dd_HHmmss",
                Locale.UK
        ).format(new Date());

        file = new File(
                dir,
                "BanditGuided_" + this.testName + "_" + stamp + ".csv"
        );

        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(
                    "timestamp,test,phase,event,name,raw,notes\n"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
        } catch (IOException ignored) {
            // Guided testing must still work if secondary logging fails.
        }
    }

    public synchronized void record(
            String phase,
            String event,
            String name,
            String raw,
            String notes
    ) {
        String timestamp = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.UK
        ).format(new Date());

        String line =
                csv(timestamp) + "," +
                csv(testName) + "," +
                csv(phase) + "," +
                csv(event) + "," +
                csv(name) + "," +
                csv(oneLine(raw)) + "," +
                csv(notes) + "\n";

        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Never interrupt diagnostic communication because of log I/O.
        }
    }

    public File getFile() {
        return file;
    }

    private static String sanitise(String value) {
        String s = value == null ? "test" : value.trim();
        s = s.replaceAll("[^A-Za-z0-9_-]+", "_");
        if (s.isEmpty()) s = "test";
        return s;
    }

    private static String csv(String value) {
        String s = value == null ? "" : value;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
    }
}
