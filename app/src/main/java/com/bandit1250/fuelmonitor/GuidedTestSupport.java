package com.bandit1250.fuelmonitor;

import java.util.*;

/**
 * Shared helpers for guided reverse-engineering tests.
 *
 * All ECU access is read-only here. Active-control commands remain in the
 * ActiveControlsDialog / guided test code so the distinction stays obvious.
 */
public final class GuidedTestSupport {
    private GuidedTestSupport() {}

    public static final int PAGE_08 = 0x08;
    public static final int PAGE_80 = 0x80;
    public static final int PAGE_90 = 0x90;
    public static final int PAGE_C0 = 0xC0;

    public static final class Snapshot {
        public final long timeMs;
        public final LinkedHashMap<Integer, byte[]> pages;
        public final String dtcRaw;

        Snapshot(
                long timeMs,
                LinkedHashMap<Integer, byte[]> pages,
                String dtcRaw
        ) {
            this.timeMs = timeMs;
            this.pages = pages;
            this.dtcRaw = dtcRaw;
        }

        public byte[] page(int id) {
            return pages.get(id);
        }
    }

    public static Snapshot captureAll(
            SuzukiSds sds,
            GuidedTestLogger log,
            String phase,
            boolean includeDtc
    ) throws Exception {
        LinkedHashMap<Integer, byte[]> pages = new LinkedHashMap<>();

        String r08 = sds.requestRaw("2108 1", 4500);
        pages.put(PAGE_08, extractPayload(r08, PAGE_08));
        if (log != null) log.record(phase, "PAGE", "21 08", r08, "");

        String r80 = sds.requestRaw("2180 1", 5500);
        pages.put(PAGE_80, extractPayload(r80, PAGE_80));
        if (log != null) log.record(phase, "PAGE", "21 80", r80, "");

        String r90 = sds.requestRaw("2190 1", 5500);
        pages.put(PAGE_90, extractPayload(r90, PAGE_90));
        if (log != null) log.record(phase, "PAGE", "21 90", r90, "");

        String rC0 = sds.requestRaw("21C0 1", 5500);
        pages.put(PAGE_C0, extractPayload(rC0, PAGE_C0));
        if (log != null) log.record(phase, "PAGE", "21 C0", rC0, "");

        String dtc = "";
        if (includeDtc) {
            dtc = sds.requestRaw("18000000 1", 5000);
            if (log != null) log.record(phase, "DTC", "18 00 00 00", dtc, "");
        }

        return new Snapshot(System.currentTimeMillis(), pages, dtc);
    }

    public static byte[] extractPayload(String response, int page) {
        if (response == null) return new byte[0];

        String hex = response.toUpperCase(Locale.US)
                .replaceAll("[^0-9A-F]", "");

        String marker = String.format(Locale.US, "61%02X", page & 0xFF);
        int p = hex.indexOf(marker);
        if (p < 0) return new byte[0];

        String payload = hex.substring(p + marker.length());
        if ((payload.length() & 1) != 0) {
            payload = payload.substring(0, payload.length() - 1);
        }

        byte[] out = new byte[payload.length() / 2];

        try {
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(
                        payload.substring(i * 2, i * 2 + 2),
                        16
                );
            }
        } catch (NumberFormatException e) {
            return new byte[0];
        }

        return out;
    }

    /**
     * Page 90 payload offsets 0x08..0x27 contain 16 BE16 ADC channels.
     * Firmware analysis establishes each as ADC_count << 6.
     */
    public static int[] page90AdcCounts(byte[] page90) {
        int[] out = new int[16];
        Arrays.fill(out, -1);

        if (page90 == null) return out;

        for (int ch = 0; ch < 16; ch++) {
            int off = 0x08 + ch * 2;
            if (off + 1 >= page90.length) break;

            int raw = ((page90[off] & 0xFF) << 8) |
                    (page90[off + 1] & 0xFF);
            out[ch] = raw / 64;
        }

        return out;
    }

    /**
     * Produce a conservative byte/bit difference report. No semantic labels are
     * invented; the report is designed to be handed back to firmware analysis.
     */
    public static String diff(
            Snapshot baseline,
            Snapshot action,
            Snapshot after
    ) {
        StringBuilder report = new StringBuilder();

        report.append("RAW DIFFERENTIAL REPORT\n");
        report.append("baseline -> action -> return\n\n");

        int[] ids = {PAGE_08, PAGE_80, PAGE_90, PAGE_C0};

        for (int page : ids) {
            byte[] b = baseline == null ? null : baseline.page(page);
            byte[] a = action == null ? null : action.page(page);
            byte[] r = after == null ? null : after.page(page);

            if (b == null || a == null) continue;

            int n = Math.min(b.length, a.length);
            if (r != null) n = Math.min(n, r.length);

            int changed = 0;
            StringBuilder rows = new StringBuilder();

            for (int i = 0; i < n; i++) {
                int bv = b[i] & 0xFF;
                int av = a[i] & 0xFF;
                int rv = r == null ? -1 : r[i] & 0xFF;

                if (bv == av && (r == null || bv == rv)) continue;

                changed++;

                int changedBits = bv ^ av;
                String bits = changedBits == 0
                        ? "-"
                        : bitList(changedBits);

                boolean returned = r != null && Math.abs(rv - bv) <= 1;

                rows.append(String.format(
                        Locale.US,
                        "  %02X: %02X -> %02X -> %s  bits:%s  return:%s\n",
                        i,
                        bv,
                        av,
                        r == null ? "--" : String.format(Locale.US, "%02X", rv),
                        bits,
                        returned ? "yes" : "no"
                ));
            }

            report.append(String.format(
                    Locale.US,
                    "Page %02X: %d changed byte(s)\n",
                    page,
                    changed
            ));
            report.append(rows);
            report.append("\n");
        }

        if (baseline != null && action != null) {
            int[] bAdc = page90AdcCounts(baseline.page(PAGE_90));
            int[] aAdc = page90AdcCounts(action.page(PAGE_90));
            int[] rAdc = after == null
                    ? new int[16]
                    : page90AdcCounts(after.page(PAGE_90));

            report.append("PAGE 90 ADC DELTAS\n");
            for (int ch = 0; ch < 16; ch++) {
                if (bAdc[ch] < 0 || aAdc[ch] < 0) continue;

                int delta = aAdc[ch] - bAdc[ch];
                int back = after == null || rAdc[ch] < 0
                        ? Integer.MIN_VALUE
                        : rAdc[ch] - bAdc[ch];

                if (delta == 0 && back == 0) continue;

                report.append(String.format(
                        Locale.US,
                        "  ADC%-2d %4d -> %4d  delta %+d",
                        ch,
                        bAdc[ch],
                        aAdc[ch],
                        delta
                ));

                if (after != null && rAdc[ch] >= 0) {
                    report.append(String.format(
                            Locale.US,
                            " -> %4d  return error %+d",
                            rAdc[ch],
                            back
                    ));
                }

                report.append("\n");
            }
        }

        return report.toString().trim();
    }

    public static String formatAdcTable(byte[] page90) {
        int[] counts = page90AdcCounts(page90);
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < counts.length; i++) {
            out.append(String.format(
                    Locale.US,
                    "ADC%-2d  %s\n",
                    i,
                    counts[i] < 0 ? "—" : Integer.toString(counts[i])
            ));
        }

        return out.toString().trim();
    }

    private static String bitList(int mask) {
        StringBuilder s = new StringBuilder();

        for (int bit = 0; bit < 8; bit++) {
            if ((mask & (1 << bit)) != 0) {
                if (s.length() > 0) s.append(',');
                s.append(bit);
            }
        }

        return s.toString();
    }
}
