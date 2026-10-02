package com.bandit1250.fuelmonitor;

import java.io.IOException;
import java.util.Locale;

public final class SuzukiSds {
    public interface Logger { void log(String s); }

    private final Elm327Client elm;
    private final Logger logger;

    private int missedFrames = 0;
    private int recoveryCount = 0;

    public SuzukiSds(Elm327Client elm, Logger logger) {
        this.elm = elm;
        this.logger = logger;
    }

    public int getMissedFrames() { return missedFrames; }
    public int getRecoveryCount() { return recoveryCount; }

    /**
     * Send one raw KWP/SDS request through the already-initialised ECU session.
     * Used by the fault-code and experimental read-only ECU tools.
     */
    public synchronized String requestRaw(String command, long timeoutMs)
            throws IOException {
        return run(command, timeoutMs);
    }

    private String run(String command, long timeoutMs) throws IOException {
        logger.log("> " + command);
        String response = elm.command(command, timeoutMs);
        logger.log("< " + response.replace('\r', ' ').replace('\n', ' ').trim());
        return response;
    }

    private static String compactUpper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.US)
                .replace("\r", "")
                .replace("\n", "")
                .replace(" ", "");
    }

    private static boolean containsSds2108Response(String response) {
        return compactUpper(response).contains("6108");
    }

    private static boolean initOk(String response) {
        if (response == null) return false;
        String s = response.toUpperCase(Locale.US);
        return s.contains("OK") && !s.contains("ERROR");
    }

    /**
     * Full Bandit / ELM setup.
     *
     * Important reliability details:
     * - AT ST 32 = approx 200 ms maximum response wait. The previous 19 value
     *   was only approx 100 ms, shorter than the 120-140 ms SDS cycle often seen.
     * - AT WM 80 12 F1 01 3E = Suzuki-addressed Tester Present wake-up message.
     * - AT SW 64 = 0x64 * 20 ms = approx 2 seconds between automatic wakeups
     *   when normal requests are not being sent.
     */
    public synchronized void initialise() throws IOException {
        run("ATZ", 4000);
        run("ATD", 2000);
        run("ATE0", 2000);
        run("ATL0", 2000);
        run("ATS0", 2000);
        run("ATH0", 2000);
        run("ATD0", 2000);
        run("ATAL", 2000);

        run("ATIB10", 2000);
        run("ATKW0", 2000);
        run("ATAT0", 2000);
        run("ATCAF1", 2000);
        run("ATCFC1", 2000);
        run("ATFCSM0", 2000);

        run("ATTP5", 2500);

        // Suzuki SDS keepalive / Tester Present.
        run("ATWM8012F1013E", 2000);
        run("ATSW64", 2000);

        // Give the ECU long enough to answer a complete SDS request.
        run("ATST32", 2000);

        fastInitOnly();

        missedFrames = 0;
    }

    /**
     * Re-open just the K-Line diagnostic link without resetting the whole ELM.
     */
    private synchronized void fastInitOnly() throws IOException {
        run("ATSH8112F1", 2000);

        String fastInit = run("ATFI", 7000);
        if (!initOk(fastInit)) {
            throw new IOException("K-Line fast init failed: " + fastInit.trim());
        }

        String keywords = run("ATKW", 3000);
        String kw = compactUpper(keywords);
        if (!(kw.contains("EA") && kw.contains("8F"))) {
            throw new IOException("Unexpected Suzuki keyword response: " + keywords.trim());
        }

        run("ATSH8012F1", 2000);

        // Reassert these after init because some ELM clones alter timing/session state.
        run("ATST32", 2000);
        run("ATWM8012F1013E", 2000);
        run("ATSW64", 2000);
    }

    private String request2108Once() throws IOException {
        String response = run("2108 1", 3500);
        if (containsSds2108Response(response)) return response;
        missedFrames++;
        return null;
    }

    private static void sleepMs(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during SDS recovery", e);
        }
    }

    /**
     * Robust live-data read.
     *
     * 1) Retry ordinary request three times.
     * 2) If still dead, re-run only the K-Line fast init.
     * 3) If that fails, fully reset/reconfigure the ELM and SDS session.
     *
     * The caller only sees an IOException after all three levels fail.
     */
    public synchronized String read2108() throws IOException {
        String response;

        for (int attempt = 1; attempt <= 3; attempt++) {
            response = request2108Once();
            if (response != null) return response;

            logger.log("2108 NO DATA/miss " + attempt + "/3");
            if (attempt < 3) sleepMs(180);
        }

        recoveryCount++;
        logger.log("SDS recovery #" + recoveryCount + ": K-Line fast re-init");
        try {
            fastInitOnly();
            sleepMs(180);

            for (int attempt = 1; attempt <= 2; attempt++) {
                response = request2108Once();
                if (response != null) return response;
                if (attempt < 2) sleepMs(180);
            }
        } catch (IOException softFailure) {
            logger.log("Soft SDS recovery failed: " + softFailure.getMessage());
        }

        recoveryCount++;
        logger.log("SDS recovery #" + recoveryCount + ": full ELM/SDS re-init");
        initialise();
        sleepMs(200);

        for (int attempt = 1; attempt <= 2; attempt++) {
            response = request2108Once();
            if (response != null) return response;
            if (attempt < 2) sleepMs(200);
        }

        throw new IOException("SDS link could not be recovered after retries + re-init");
    }
}
