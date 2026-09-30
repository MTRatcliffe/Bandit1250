package com.bandit1250.fuelmonitor;

import java.io.IOException;
import java.util.Locale;

public final class SuzukiSds {
    public interface Logger { void log(String s); }

    private final Elm327Client elm;
    private final Logger logger;

    public SuzukiSds(Elm327Client elm, Logger logger) {
        this.elm = elm;
        this.logger = logger;
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
        String s = compactUpper(response);
        return s.contains("6108");
    }

    /**
     * Exact ELM327 / Suzuki SDS initialisation sequence previously decoded
     * for the 2008 GSF1250SA Bandit.
     *
     * Physical/protocol details:
     *   - single-wire K-Line
     *   - ISO 14230-4 / KWP2000 fast init
     *   - 10,400 baud
     *   - ECU address 0x12
     *   - tester address 0xF1
     *   - init header 81 12 F1
     *   - normal header 80 12 F1
     */
    public void initialise() throws IOException {
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
        run("ATSW00", 2000);
        run("ATAT0", 2000);
        run("ATCAF1", 2000);
        run("ATCFC1", 2000);
        run("ATFCSM0", 2000);
        run("ATTP5", 2500);
        run("ATSH8112F1", 2000);
        run("ATST19", 2000);

        String fastInit = run("ATFI", 7000);
        String fi = fastInit.toUpperCase(Locale.US);
        if (fi.contains("ERROR") || !fi.contains("OK")) {
            throw new IOException("K-Line fast init failed: " + fastInit.trim());
        }

        String keywords = run("ATKW", 3000);
        String kw = compactUpper(keywords);
        if (!(kw.contains("EA") && kw.contains("8F"))) {
            throw new IOException("Unexpected Suzuki keyword response: " + keywords.trim());
        }

        run("ATSH8012F1", 2000);
    }

    /**
     * Read the main Suzuki live-data record.
     *
     * The trailing "1" is an ELM-side instruction to stop after one response;
     * the ECU request itself is still 21 08.
     */
    public String read2108() throws IOException {
        String response = run("2108 1", 3000);
        if (!containsSds2108Response(response)) {
            throw new IOException("No 61 08 SDS response: " + response.trim());
        }
        return response;
    }
}
