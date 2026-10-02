package com.bandit1250.fuelmonitor;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Experimental READ-ONLY KWP2000 ECU memory reader.
 *
 * This class deliberately sends only service 0x23 (ReadMemoryByAddress).
 * It does not change diagnostic sessions, request security access, erase,
 * download, transfer-to-ECU or write memory.
 *
 * Suzuki/Denso flashing protocol details vary by ECU. We therefore probe a
 * few standard KWP address widths at address 0 and only continue if the ECU
 * gives a positive 0x63 response. A completed .bin is created only if every
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
            case 0x78: meaning = "response pending"; break;
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
