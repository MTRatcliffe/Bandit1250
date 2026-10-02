package com.bandit1250.fuelmonitor;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Experimental READ-ONLY KWP2000 ECU memory reader.
 *
 * This class deliberately avoids destructive or state-changing programming
 * operations. The safe discovery pass may:
 * - read ECU identification records (0x1A)
 * - request a security seed only (0x27 odd request; NEVER sends a key)
 * - test whether DiagnosticSessionControl exists using an intentionally
 *   incomplete request (no diagnostic mode is selected)
 * - try read-only ReadMemoryByAddress (0x23)
 * - try ECU-to-tester RequestUpload (0x35) formats and, only after a positive
 *   upload response, one tiny TransferData read before RequestTransferExit.
 *
 * It NEVER sends ECU reset, programming-session changes, RequestDownload,
 * writes, erase commands, actuator controls, routines or guessed security keys.
 * A completed .bin is created only from confirmed 0x23 reads and only if every
 * requested byte was read successfully.
 */
public final class EcuMemoryReader {
    private EcuMemoryReader() {}

    public interface Progress {
        void onProgress(long done, long total, long address);
    }

    public static final class ProbeResult {
        public final boolean supported;
        public final int addressBytes;
        public final int blockSize;
        public final String request;
        public final String response;
        public final String explanation;

        ProbeResult(
                boolean supported,
                int addressBytes,
                int blockSize,
                String request,
                String response,
                String explanation
        ) {
            this.supported = supported;
            this.addressBytes = addressBytes;
            this.blockSize = blockSize;
            this.request = request;
            this.response = response;
            this.explanation = explanation;
        }
    }

    public static final class SafeDiscoveryResult {
        public final ProbeResult directMemory;
        public final boolean securitySeedSupported;
        public final boolean uploadAccepted;
        public final boolean uploadDataReturned;
        public final String uploadRequest;
        public final String report;

        SafeDiscoveryResult(
                ProbeResult directMemory,
                boolean securitySeedSupported,
                boolean uploadAccepted,
                boolean uploadDataReturned,
                String uploadRequest,
                String report
        ) {
            this.directMemory = directMemory;
            this.securitySeedSupported = securitySeedSupported;
            this.uploadAccepted = uploadAccepted;
            this.uploadDataReturned = uploadDataReturned;
            this.uploadRequest = uploadRequest;
            this.report = report;
        }
    }

    /**
     * Conservative automatic discovery pass.
     *
     * Every request here is either read-only or intentionally incomplete so it
     * cannot select a new diagnostic mode. A positive RequestUpload is followed
     * by at most one tiny TransferData request and RequestTransferExit.
     */
    public static SafeDiscoveryResult safeDiscovery(SuzukiSds sds)
            throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("SAFE ECU DISCOVERY\n");
        report.append("No reset/write/erase/download/actuator/routine/key commands used.\n\n");

        report.append("[1] ECU identification (read-only)\n");
        String[] identRequests = {"1A90", "1A91", "1A92", "1A9B"};
        for (String request : identRequests) {
            appendProbe(report, sds, request, 0x1A, 4000);
        }

        report.append("\n[2] Security service — seed request ONLY\n");
        String securityResponse = safeRequest(sds, "2701", 4500);
        report.append("2701 -> ").append(oneLine(securityResponse)).append("\n");
        String securityNrc = negativeResponseExplanation(securityResponse, 0x27);
        if (securityNrc != null) {
            report.append("    ").append(securityNrc).append("\n");
        }
        boolean securitySupported =
                isPositiveService(securityResponse, 0x67);

        if (securitySupported) {
            report.append("    Seed response received. NO KEY WAS SENT.\n");
        }

        report.append("\n[3] Diagnostic-session service presence\n");
        report.append("Intentionally incomplete 0x10 request; no mode is selected.\n");
        String sessionResponse = safeRequest(sds, "10", 4000);
        report.append("10 -> ").append(oneLine(sessionResponse)).append("\n");
        String sessionNrc = negativeResponseExplanation(sessionResponse, 0x10);
        if (sessionNrc != null) {
            report.append("    ").append(sessionNrc).append("\n");
        }

        report.append("\n[4] ReadMemoryByAddress 0x23\n");
        ProbeResult direct = probe(sds);
        report.append(direct.response).append("\n");
        report.append("    ").append(direct.explanation).append("\n");

        report.append("\n[5] RequestUpload 0x35 (ECU -> tester only)\n");

        UploadProbe upload = probeSafeUpload(sds, report);

        report.append("\nRESULT\n");
        if (direct.supported) {
            report.append("0x23 direct memory read WORKS.\n");
        } else {
            report.append("0x23 direct memory read not available in this session.\n");
        }

        if (upload.accepted) {
            report.append("0x35 RequestUpload accepted");
            if (upload.request != null) {
                report.append(" using ").append(upload.request);
            }
            report.append(".\n");

            if (upload.dataReturned) {
                report.append("0x36 returned upload data: promising read path found.\n");
            } else {
                report.append("Upload started, but the tiny 0x36 transfer format is not yet confirmed.\n");
            }
        } else {
            report.append("No tested safe 0x35 upload format was accepted.\n");
        }

        if (securitySupported) {
            report.append("SecurityAccess seed request is supported; no unlock was attempted.\n");
        }

        return new SafeDiscoveryResult(
                direct,
                securitySupported,
                upload.accepted,
                upload.dataReturned,
                upload.request,
                report.toString().trim()
        );
    }

    private static final class UploadProbe {
        boolean accepted;
        boolean dataReturned;
        String request;
    }

    private static UploadProbe probeSafeUpload(
            SuzukiSds sds,
            StringBuilder report
    ) {
        UploadProbe result = new UploadProbe();

        ArrayList<String> candidates = new ArrayList<>();

        // Parameters are optional in ISO 14230, so try a bare request first.
        candidates.add("35");

        // Common manufacturer-specific shapes: address followed by a tiny
        // byte-count. Every candidate requests only ONE byte from address zero.
        int[] addressWidths = {2, 3, 4};
        int[] lengthWidths = {1, 2, 3, 4};

        for (int addressBytes : addressWidths) {
            for (int lengthBytes : lengthWidths) {
                candidates.add(
                        "35" +
                        zeroBytes(addressBytes) +
                        unsignedFixed(1L, lengthBytes)
                );
            }
        }

        // UDS-style dataFormatIdentifier + address/length-format byte is also
        // used by some later KWP-derived implementations. Still upload/read only.
        for (int addressBytes : addressWidths) {
            for (int lengthBytes : lengthWidths) {
                int alfid = ((lengthBytes & 0x0F) << 4) |
                        (addressBytes & 0x0F);

                candidates.add(
                        "3500" +
                        String.format(Locale.US, "%02X", alfid) +
                        zeroBytes(addressBytes) +
                        unsignedFixed(1L, lengthBytes)
                );
            }
        }

        for (String request : candidates) {
            String response = safeRequest(sds, request, 5000);

            report.append(request)
                    .append(" -> ")
                    .append(oneLine(response))
                    .append("\n");

            String nrc = negativeResponseExplanation(response, 0x35);
            if (nrc != null) {
                report.append("    ").append(nrc).append("\n");

                // Explicit service-not-supported means format changes cannot
                // make 0x35 work in this session, so stop the upload sweep.
                if (nrc.startsWith("NRC 0x11")) {
                    break;
                }
            }

            if (!isPositiveService(response, 0x75)) {
                continue;
            }

            result.accepted = true;
            result.request = request;

            report.append("    Positive 0x75 RequestUpload response.\n");
            report.append("    Trying one tiny read-only TransferData request…\n");

            try {
                String transfer = safeRequest(sds, "36", 5000);
                report.append("36 -> ")
                        .append(oneLine(transfer))
                        .append("\n");

                if (!isPositiveService(transfer, 0x76)) {
                    String transferNrc =
                            negativeResponseExplanation(transfer, 0x36);
                    if (transferNrc != null) {
                        report.append("    ")
                                .append(transferNrc)
                                .append("\n");
                    }

                    // Block sequence counter 1 is another common read-side
                    // transfer form. Still no tester-to-ECU payload is sent.
                    transfer = safeRequest(sds, "3601", 5000);
                    report.append("3601 -> ")
                            .append(oneLine(transfer))
                            .append("\n");
                }

                result.dataReturned =
                        isPositiveService(transfer, 0x76);

                if (result.dataReturned) {
                    report.append("    Positive 0x76 upload data response.\n");
                }

            } finally {
                // Always attempt to leave any accepted upload transfer cleanly.
                String exit = safeRequest(sds, "37", 5000);
                report.append("37 (exit) -> ")
                        .append(oneLine(exit))
                        .append("\n");
            }

            // One accepted upload format is enough; do not keep changing formats.
            break;
        }

        return result;
    }

    private static void appendProbe(
            StringBuilder report,
            SuzukiSds sds,
            String request,
            int service,
            long timeoutMs
    ) {
        String response = safeRequest(sds, request, timeoutMs);

        report.append(request)
                .append(" -> ")
                .append(oneLine(response))
                .append("\n");

        String nrc = negativeResponseExplanation(response, service);
        if (nrc != null) {
            report.append("    ").append(nrc).append("\n");
        }
    }

    private static String safeRequest(
            SuzukiSds sds,
            String request,
            long timeoutMs
    ) {
        try {
            return sds.requestRaw(request + " 1", timeoutMs);
        } catch (IOException e) {
            return "I/O ERROR: " + e.getMessage();
        }
    }

    private static boolean isPositiveService(String response, int service) {
        if (response == null) return false;

        String upper = response.toUpperCase(Locale.US);
        if (upper.contains("NO DATA") ||
                upper.contains("ERROR") ||
                upper.contains("UNABLE") ||
                upper.contains("STOPPED")) {
            return false;
        }

        String hex = upper.replaceAll("[^0-9A-F]", "");
        String marker = String.format(Locale.US, "%02X", service);

        return hex.startsWith(marker) || hex.contains(marker);
    }

    private static String zeroBytes(int count) {
        StringBuilder s = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) s.append("00");
        return s.toString();
    }

    private static String unsignedFixed(long value, int bytes) {
        String format = "%0" + (bytes * 2) + "X";
        return String.format(Locale.US, format, value);
    }

    public static final class ElmLengthTestResult {
        public final int largestEcuSeenBytes;
        public final int firstQuestionMarkBytes;
        public final boolean questionMarkAlsoWithoutResponseCount;
        public final String report;

        ElmLengthTestResult(
                int largestEcuSeenBytes,
                int firstQuestionMarkBytes,
                boolean questionMarkAlsoWithoutResponseCount,
                String report
        ) {
            this.largestEcuSeenBytes = largestEcuSeenBytes;
            this.firstQuestionMarkBytes = firstQuestionMarkBytes;
            this.questionMarkAlsoWithoutResponseCount =
                    questionMarkAlsoWithoutResponseCount;
            this.report = report;
        }
    }

    /**
     * Diagnose whether long-request "?" replies are generated by the ELM/V-LINK
     * rather than the Suzuki ECU.
     *
     * Safety: the test uses only Suzuki local-identifier read service 0x1A,
     * identifier 0x91, which is already known to return the ECU family ID.
     * Extra zero bytes deliberately make the request malformed but do not turn
     * it into a write, routine, actuator, reset or programming operation.
     */
    public static ElmLengthTestResult testElmMessageLength(SuzukiSds sds)
            throws IOException {
        StringBuilder report = new StringBuilder();

        report.append("ELM / K-LINE MESSAGE LENGTH TEST\n");
        report.append("Uses read-only ECU ID request 1A91 with zero padding.\n\n");

        // Record adapter identity/protocol and explicitly re-assert Allow Long.
        String ati = sds.requestRaw("ATI", 2500);
        report.append("ATI -> ").append(oneLine(ati)).append("\n");

        String atdpn = sds.requestRaw("ATDPN", 2500);
        report.append("ATDPN -> ").append(oneLine(atdpn)).append("\n");

        String atal = sds.requestRaw("ATAL", 2500);
        report.append("ATAL -> ").append(oneLine(atal)).append("\n\n");

        // Prove the known-good ECU ID read before doing any padding.
        String baseline = timedRequestReport(
                report,
                sds,
                "1A91",
                true
        );

        if (!containsPositiveLocalIdentifier(baseline, 0x91)) {
            report.append("\nBaseline 1A91 did not return the expected 5A91. ");
            report.append("Length test aborted so an unrelated link fault is not ");
            report.append("mistaken for an adapter limit.");

            return new ElmLengthTestResult(
                    0,
                    0,
                    false,
                    report.toString().trim()
            );
        }

        int largestEcuSeen = 2;
        int firstQuestion = 0;
        boolean questionWithoutCount = false;

        report.append("\nPadded request sweep\n");
        report.append("bytes  request/result\n");

        // 2 bytes = normal 1A 91. Sweep beyond the region where the previous
        // 0x35 discovery first produced '?'.
        for (int totalBytes = 2; totalBytes <= 16; totalBytes++) {
            String request = padded1A91(totalBytes);

            long t0 = System.nanoTime();
            String response;

            try {
                response = sds.requestRaw(request + " 1", 4500);
            } catch (IOException e) {
                response = "I/O ERROR: " + e.getMessage();
            }

            long elapsedMs = Math.max(
                    0L,
                    (System.nanoTime() - t0) / 1_000_000L
            );

            report.append(String.format(
                    Locale.US,
                    "%2d B  %s  -> %s  (%d ms)\n",
                    totalBytes,
                    request,
                    oneLine(response),
                    elapsedMs
            ));

            boolean ecuSeen =
                    containsPositiveLocalIdentifier(response, 0x91) ||
                    containsNegativeForService(response, 0x1A);

            if (ecuSeen) {
                largestEcuSeen = totalBytes;
            }

            if (isQuestionMark(response)) {
                if (firstQuestion == 0) {
                    firstQuestion = totalBytes;
                }

                // Critical cross-check: retry the exact same raw data without
                // ELM's trailing "expect one response" count. If '?' remains,
                // the evidence for a local adapter/parser limit is much stronger.
                long t1 = System.nanoTime();
                String retry;

                try {
                    retry = sds.requestRaw(request, 4500);
                } catch (IOException e) {
                    retry = "I/O ERROR: " + e.getMessage();
                }

                long retryMs = Math.max(
                        0L,
                        (System.nanoTime() - t1) / 1_000_000L
                );

                report.append(String.format(
                        Locale.US,
                        "      same data WITHOUT trailing response-count -> %s  (%d ms)\n",
                        oneLine(retry),
                        retryMs
                ));

                if (isQuestionMark(retry)) {
                    questionWithoutCount = true;
                }
            }
        }

        // Finish with another known-good request. This proves the ECU/K-Line
        // connection still functions after the long-message tests.
        report.append("\nPost-test link check\n");
        String finalBaseline = timedRequestReport(
                report,
                sds,
                "1A91",
                true
        );

        report.append("\nINTERPRETATION\n");

        if (firstQuestion > 0 &&
                largestEcuSeen > 0 &&
                firstQuestion == largestEcuSeen + 1 &&
                questionWithoutCount &&
                containsPositiveLocalIdentifier(finalBaseline, 0x91)) {
            report.append(
                    "Strong evidence that '?' is generated locally by the " +
                    "ELM/V-LINK at the request-length boundary, not by the ECU.\n"
            );
            report.append("Largest padded request seen answering from ECU: ")
                    .append(largestEcuSeen)
                    .append(" bytes.\n");
            report.append("First local '?' request length: ")
                    .append(firstQuestion)
                    .append(" bytes.\n");
        } else if (firstQuestion > 0) {
            report.append(
                    "A '?' boundary was observed, but the pattern is not clean " +
                    "enough to call it conclusively local. Review the timings and " +
                    "with/without-response-count retries above.\n"
            );
        } else {
            report.append(
                    "No '?' boundary was found up to 16 request bytes. The earlier " +
                    "question marks were therefore not reproduced by this read-only test.\n"
            );
        }

        return new ElmLengthTestResult(
                largestEcuSeen,
                firstQuestion,
                questionWithoutCount,
                report.toString().trim()
        );
    }

    private static String timedRequestReport(
            StringBuilder report,
            SuzukiSds sds,
            String request,
            boolean addResponseCount
    ) {
        long t0 = System.nanoTime();
        String response;

        try {
            response = sds.requestRaw(
                    addResponseCount ? request + " 1" : request,
                    4500
            );
        } catch (IOException e) {
            response = "I/O ERROR: " + e.getMessage();
        }

        long elapsedMs = Math.max(
                0L,
                (System.nanoTime() - t0) / 1_000_000L
        );

        report.append(request)
                .append(addResponseCount ? " 1" : "")
                .append(" -> ")
                .append(oneLine(response))
                .append(" (")
                .append(elapsedMs)
                .append(" ms)\n");

        return response;
    }

    private static String padded1A91(int totalBytes) {
        if (totalBytes < 2) {
            throw new IllegalArgumentException("1A91 needs at least 2 bytes");
        }

        StringBuilder s = new StringBuilder(totalBytes * 2);
        s.append("1A91");

        for (int i = 2; i < totalBytes; i++) {
            s.append("00");
        }

        return s.toString();
    }

    private static boolean containsPositiveLocalIdentifier(
            String response,
            int identifier
    ) {
        if (response == null) return false;

        String hex = response.toUpperCase(Locale.US)
                .replaceAll("[^0-9A-F]", "");

        return hex.contains(
                String.format(Locale.US, "5A%02X", identifier & 0xFF)
        );
    }

    private static boolean containsNegativeForService(
            String response,
            int service
    ) {
        if (response == null) return false;

        String hex = response.toUpperCase(Locale.US)
                .replaceAll("[^0-9A-F]", "");

        return hex.contains(
                String.format(Locale.US, "7F%02X", service & 0xFF)
        );
    }

    private static boolean isQuestionMark(String response) {
        if (response == null) return false;

        return response.trim().equals("?") ||
                response.trim().endsWith("?");
    }

    public static final class ReadResult {
        public final File file;
        public final long bytes;
        public final int addressBytes;
        public final int blockSize;
        public final String sha256;

        ReadResult(File file, long bytes, int addressBytes, int blockSize, String sha256) {
            this.file = file;
            this.bytes = bytes;
            this.addressBytes = addressBytes;
            this.blockSize = blockSize;
            this.sha256 = sha256;
        }
    }

    public static ProbeResult probe(SuzukiSds sds) throws IOException {
        // Most motorcycle Denso KWP implementations use 3- or 4-byte addresses.
        // Two-byte is included as a final harmless read-only fallback.
        int[] widths = {3, 4, 2};
        StringBuilder evidence = new StringBuilder();

        for (int width : widths) {
            String request = requestFor(0L, 1, width);
            String response;

            try {
                response = sds.requestRaw(request + " 1", 4500);
            } catch (IOException e) {
                evidence.append(request)
                        .append(" -> ")
                        .append(e.getMessage())
                        .append("\n");
                continue;
            }

            evidence.append(request)
                    .append(" -> ")
                    .append(oneLine(response))
                    .append("\n");

            byte[] data = positiveReadData(response);

            if (data != null && data.length >= 1) {
                int block = probeBlockSize(sds, width);
                return new ProbeResult(
                        true,
                        width,
                        block,
                        request,
                        response,
                        "Positive KWP 0x63 ReadMemoryByAddress response."
                );
            }

            String nrc = negativeResponseExplanation(response, 0x23);
            if (nrc != null) {
                evidence.append("KWP response: ").append(nrc).append("\n");
            }
        }

        return new ProbeResult(
                false,
                0,
                0,
                "",
                evidence.toString().trim(),
                "No positive 0x63 memory-read response was found. " +
                        "The normal SDS session may not expose flash memory, " +
                        "or a different session/security/flashing connection may be required."
        );
    }

    private static int probeBlockSize(SuzukiSds sds, int addressBytes) {
        int[] sizes = {64, 32, 16, 8, 4, 1};

        for (int size : sizes) {
            try {
                String response = sds.requestRaw(
                        requestFor(0L, size, addressBytes) + " 1",
                        5000
                );
                byte[] data = positiveReadData(response);
                if (data != null && data.length >= size) return size;
            } catch (Exception ignored) {
                // Try the next smaller read length.
            }
        }

        return 1;
    }

    public static ReadResult read(
            SuzukiSds sds,
            File output,
            long totalBytes,
            int addressBytes,
            int blockSize,
            Progress progress
    ) throws Exception {
        if (totalBytes <= 0) {
            throw new IllegalArgumentException("Read size must be greater than zero");
        }

        long maxAddress = addressBytes >= 4
                ? 0xFFFFFFFFL
                : (1L << (addressBytes * 8)) - 1L;

        if (totalBytes - 1L > maxAddress) {
            throw new IOException(
                    "Selected dump size exceeds the " +
                    addressBytes + "-byte address range"
            );
        }

        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create ECU dump directory");
        }

        MessageDigest sha = MessageDigest.getInstance("SHA-256");

        boolean complete = false;

        try (BufferedOutputStream out =
                     new BufferedOutputStream(new FileOutputStream(output))) {

            long address = 0L;

            while (address < totalBytes) {
                int amount = (int)Math.min(blockSize, totalBytes - address);
                byte[] data = readBlockWithRetries(
                        sds,
                        address,
                        amount,
                        addressBytes
                );

                if (data.length < amount) {
                    throw new IOException(String.format(
                            Locale.US,
                            "Short memory response at 0x%08X: wanted %d bytes, got %d",
                            address,
                            amount,
                            data.length
                    ));
                }

                out.write(data, 0, amount);
                sha.update(data, 0, amount);
                address += amount;

                if (progress != null) {
                    progress.onProgress(address, totalBytes, address);
                }
            }

            out.flush();
            complete = true;

        } finally {
            // Never leave a partial file looking like a complete ECU .bin.
            if (!complete && output.exists()) {
                //noinspection ResultOfMethodCallIgnored
                output.delete();
            }
        }

        return new ReadResult(
                output,
                totalBytes,
                addressBytes,
                blockSize,
                toHex(sha.digest())
        );
    }

    private static byte[] readBlockWithRetries(
            SuzukiSds sds,
            long address,
            int amount,
            int addressBytes
    ) throws IOException {
        IOException last = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String response = sds.requestRaw(
                        requestFor(address, amount, addressBytes) + " 1",
                        6000
                );

                byte[] data = positiveReadData(response);

                if (data != null && data.length >= amount) {
                    return data;
                }

                String nrc = negativeResponseExplanation(response, 0x23);
                throw new IOException(
                        nrc != null
                                ? nrc
                                : "No positive 0x63 memory response: " + oneLine(response)
                );

            } catch (IOException e) {
                last = e;

                if (attempt < 3) {
                    try {
                        Thread.sleep(120L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("ECU read interrupted", ie);
                    }
                }
            }
        }

        throw last == null
                ? new IOException("Memory read failed")
                : last;
    }

    static String requestFor(long address, int length, int addressBytes) {
        if (length < 1 || length > 255) {
            throw new IllegalArgumentException("KWP read length must be 1..255");
        }

        String addressFormat;

        switch (addressBytes) {
            case 2:
                addressFormat = "%04X";
                break;
            case 3:
                addressFormat = "%06X";
                break;
            case 4:
                addressFormat = "%08X";
                break;
            default:
                throw new IllegalArgumentException("Unsupported address width");
        }

        return "23" +
                String.format(Locale.US, addressFormat, address) +
                String.format(Locale.US, "%02X", length);
    }

    static byte[] positiveReadData(String response) {
        if (response == null) return null;

        String upper = response.toUpperCase(Locale.US);

        if (upper.contains("NO DATA") ||
                upper.contains("ERROR") ||
                upper.contains("UNABLE") ||
                upper.contains("STOPPED") ||
                upper.contains("?")) {
            return null;
        }

        String hex = upper.replaceAll("[^0-9A-F]", "");
        int p = hex.indexOf("63");

        if (p < 0) return null;

        String payload = hex.substring(p + 2);

        if ((payload.length() & 1) != 0) {
            payload = payload.substring(0, payload.length() - 1);
        }

        byte[] out = new byte[payload.length() / 2];

        try {
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte)Integer.parseInt(
                        payload.substring(i * 2, i * 2 + 2),
                        16
                );
            }
        } catch (NumberFormatException e) {
            return null;
        }

        return out;
    }

    static String negativeResponseExplanation(String response, int service) {
        if (response == null) return null;

        String hex = response.toUpperCase(Locale.US)
                .replaceAll("[^0-9A-F]", "");

        String marker = String.format(Locale.US, "7F%02X", service);
        int p = hex.indexOf(marker);

        if (p < 0 || p + 8 > hex.length()) return null;

        int nrc;

        try {
            nrc = Integer.parseInt(hex.substring(p + 6, p + 8), 16);
        } catch (NumberFormatException e) {
            return null;
        }

        String meaning;

        switch (nrc) {
            case 0x10: meaning = "general reject"; break;
            case 0x11: meaning = "service not supported"; break;
            case 0x12: meaning = "sub-function not supported / invalid format"; break;
            case 0x21: meaning = "busy - repeat request"; break;
            case 0x22: meaning = "conditions not correct"; break;
            case 0x31: meaning = "request out of range"; break;
            case 0x33: meaning = "security access denied"; break;
            case 0x35: meaning = "invalid key"; break;
            case 0x36: meaning = "security attempts exceeded"; break;
            case 0x37: meaning = "required time delay not expired"; break;
            case 0x40: meaning = "download not accepted"; break;
            case 0x50: meaning = "upload not accepted"; break;
            case 0x71: meaning = "transfer suspended"; break;
            case 0x72: meaning = "transfer aborted"; break;
            case 0x74: meaning = "illegal address in block transfer"; break;
            case 0x75: meaning = "illegal byte count in block transfer"; break;
            case 0x76: meaning = "illegal block transfer type"; break;
            case 0x77: meaning = "block transfer checksum error"; break;
            case 0x78: meaning = "response pending"; break;
            case 0x79: meaning = "incorrect byte count during block transfer"; break;
            case 0x80: meaning = "service not supported in active session"; break;
            default:
                meaning = "negative response";
        }

        return String.format(
                Locale.US,
                "NRC 0x%02X (%s)",
                nrc,
                meaning
        );
    }

    private static String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            s.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        return s.toString();
    }
}
