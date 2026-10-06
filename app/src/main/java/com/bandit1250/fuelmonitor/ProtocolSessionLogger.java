package com.bandit1250.fuelmonitor;

import android.content.Context;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CSV logger for the complete connected SDS session.
 *
 * MainActivity attaches this to SuzukiSds before initialisation, so the file
 * contains ELM setup, normal 21 08 polling, ECU tools, engineering pages and
 * guided diagnostic traffic. The operation field adds higher-level phase
 * labels during experiments.
 */
public final class ProtocolSessionLogger {
    private final File file;
    private String operation = "";

    public ProtocolSessionLogger(Context context) {
        File dir = new File(context.getFilesDir(), "protocol_logs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        String stamp = new SimpleDateFormat(
                "yyyy-MM-dd_HHmmss",
                Locale.UK
        ).format(new Date());

        file = new File(dir, "BanditProtocol_" + stamp + ".csv");
        ensureHeader();
    }

    public synchronized void setOperation(String value) {
        operation = value == null ? "" : value;
    }

    public synchronized void record(
            String command,
            String response,
            long durationMs
    ) {
        ensureHeader();

        String timestamp = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.UK
        ).format(new Date());

        String line =
                csv(timestamp) + "," +
                csv(operation) + "," +
                csv(command) + "," +
                csv(oneLine(response)) + "," +
                durationMs + "\n";

        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Logging must never interrupt ECU communication.
        }
    }

    public synchronized File getFile() {
        ensureHeader();
        return file;
    }

    public synchronized void clear() {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(
                    "timestamp,operation,command,response,duration_ms\n"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
        } catch (IOException ignored) {
            // UI will still be able to share whatever file is present.
        }
    }

    private void ensureHeader() {
        if (file.isFile() && file.length() > 0) return;

        clear();
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
