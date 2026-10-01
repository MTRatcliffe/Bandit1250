package com.bandit1250.fuelmonitor;

import java.util.*;

public final class BanditDecoder {
    private BanditDecoder(){}

    public static BanditLiveData decode(String raw) {
        byte[] f = extract(raw);

        if (f.length < 35) {
            throw new IllegalArgumentException("2108 frame too short: " + f.length + " bytes");
        }

        /*
         * GSF1250 21 08 / 61 08 mapping established from the earlier
         * Bandit decoder work and cross-checked against SZ Viewer.
         *
         * Byte numbering starts at the complete response:
         *   61 = byte 0
         *   08 = byte 1
         *
         * RPM:
         *   bytes 13-14, unsigned 16-bit big-endian
         *   rpm = raw * 100 / 256
         *
         * Injector commanded pulse widths:
         *   C1 bytes 27-28
         *   C2 bytes 29-30
         *   C3 bytes 31-32
         *   C4 bytes 33-34
         *
         * Injector raw value is microseconds, so /1000 = milliseconds.
         */
        int rpmRaw = u16(f,13,14);
        int rpm = (int)Math.round(rpmRaw * 100.0 / 256.0);

        double i1 = u16(f,27,28) / 1000.0;
        double i2 = u16(f,29,30) / 1000.0;
        double i3 = u16(f,31,32) / 1000.0;
        double i4 = u16(f,33,34) / 1000.0;

        if (rpm < 0 || rpm > 15000) {
            throw new IllegalArgumentException("Implausible RPM: " + rpm);
        }

        if (!plausiblePulse(i1) || !plausiblePulse(i2) ||
            !plausiblePulse(i3) || !plausiblePulse(i4)) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    "Implausible injector PW: %.3f %.3f %.3f %.3f ms",
                    i1,i2,i3,i4));
        }

        return new BanditLiveData(rpm,i1,i2,i3,i4);
    }

    public static byte[] extract(String raw) {
        String hex = raw == null ? "" :
                raw.toUpperCase(Locale.US).replaceAll("[^0-9A-F]","");

        int p = hex.indexOf("6108");

        if (p < 0) {
            throw new IllegalArgumentException("No 61 08 SDS response found");
        }

        hex = hex.substring(p);

        if ((hex.length() & 1) != 0) {
            hex = hex.substring(0, hex.length()-1);
        }

        byte[] out = new byte[hex.length()/2];

        for (int i=0; i<out.length; i++) {
            out[i] = (byte)Integer.parseInt(
                    hex.substring(i*2, i*2+2),
                    16
            );
        }

        return out;
    }

    public static String indexedHex(String raw) {
        byte[] f = extract(raw);
        StringBuilder s = new StringBuilder();

        for (int i=0; i<f.length; i++) {
            if (i>0) s.append(i%8==0 ? '\n' : ' ');
            s.append(String.format(Locale.US,"%02d:%02X",i,u8(f[i])));
        }

        return s.toString();
    }

    private static boolean plausiblePulse(double ms) {
        return ms >= 0.0 && ms <= 20.0;
    }

    private static int u8(byte b) {
        return b & 255;
    }

    private static int u16(byte[] f, int hi, int lo) {
        return (u8(f[hi]) << 8) | u8(f[lo]);
    }
}
