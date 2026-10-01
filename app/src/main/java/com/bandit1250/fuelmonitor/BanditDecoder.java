package com.bandit1250.fuelmonitor;

import java.util.*;

public final class BanditDecoder {
    private BanditDecoder(){}

    public static BanditLiveData decode(String raw) {
        byte[] f = extract(raw);

        if (f.length < 35) {
            throw new IllegalArgumentException("2108 frame too short: " + f.length + " bytes");
        }

        // Verified Bandit fields retained from v0.6.
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

        /*
         * Wider 61 08 mapping.
         *
         * These positions match published Suzuki SDS reverse-engineering.
         * Some scale factors vary between model families, so the GUI labels
         * non-verified values as estimates and also preserves the raw frame.
         */
        double ecuSpeedKph = val(f,12) * 2.0;

        // Common Suzuki TPS conversion. Closed throttle is near raw 55.
        double tpsPct = 125.0 * (val(f,15) - 55.0) / (256.0 - 55.0);

        // Common Suzuki/KWP pressure conversion; treated as estimated on Bandit.
        double iap1Kpa = val(f,16) * 4.0 * 0.136;

        double engineTempC = (val(f,17) - 48.0) / 1.6;
        double intakeTempC = (val(f,18) - 48.0) / 1.6;

        int eapRaw = val(f,19);

        // Provisional 0-20 V scaling used in the earlier Bandit mapping work.
        double batteryEstV = val(f,20) * 20.0 / 255.0;

        int o2Raw = val(f,21);
        int gearRaw = val(f,22);
        double iap2Kpa = val(f,23) * 4.0 * 0.136;
        int idleSpeedRaw = val(f,24);
        int iscRaw = val(f,25);

        // Ignition bytes from the established Bandit map when present.
        double ign1 = f.length > 37 ? val(f,37) / 2.0 - 64.0 : Double.NaN;
        double ign2 = f.length > 38 ? val(f,38) / 2.0 - 64.0 : Double.NaN;
        double ign3 = f.length > 39 ? val(f,39) / 2.0 - 64.0 : Double.NaN;
        double ign4 = f.length > 40 ? val(f,40) / 2.0 - 64.0 : Double.NaN;

        double stpsPct = f.length > 42 ? val(f,42) / 2.55 : Double.NaN;

        int s45 = f.length > 45 ? val(f,45) : -1;
        int fan = f.length > 46 ? val(f,46) : -1;
        int exhaust = f.length > 47 ? val(f,47) : -1;
        int clutchStarter = f.length > 48 ? val(f,48) : -1;
        int neutral = f.length > 49 ? val(f,49) : -1;

        return new BanditLiveData(
                f,
                rpm,
                ecuSpeedKph,
                tpsPct,
                iap1Kpa,
                engineTempC,
                intakeTempC,
                eapRaw,
                batteryEstV,
                o2Raw,
                gearRaw,
                iap2Kpa,
                idleSpeedRaw,
                iscRaw,
                i1,i2,i3,i4,
                ign1,ign2,ign3,ign4,
                stpsPct,
                s45,fan,exhaust,clutchStarter,neutral
        );
    }

    public static String formatAll(BanditLiveData d) {
        StringBuilder s = new StringBuilder();

        s.append(String.format(Locale.UK, "RPM                 %d rpm\n", d.rpm));
        s.append(String.format(Locale.UK, "ECU speed (est.)    %.0f km/h\n", d.ecuSpeedKph));
        s.append(String.format(Locale.UK, "TPS (est.)          %.1f %%\n", d.tpsPct));
        s.append(String.format(Locale.UK, "IAP-1 (est.)        %.1f kPa\n", d.iap1Kpa));
        s.append(String.format(Locale.UK, "Coolant             %.1f °C\n", d.engineTempC));
        s.append(String.format(Locale.UK, "Intake air temp     %.1f °C\n", d.intakeTempC));
        s.append(String.format(Locale.UK, "EAP raw             %d (0x%02X)\n", d.eapRaw, d.eapRaw));
        s.append(String.format(Locale.UK, "Battery (est.)      %.2f V\n", d.batteryEstV));
        s.append(String.format(Locale.UK, "O2 B1 raw           %d (0x%02X)\n", d.o2Raw, d.o2Raw));
        s.append(String.format(Locale.UK, "Gear sensor raw     %d (0x%02X)\n", d.gearRaw, d.gearRaw));
        s.append(String.format(Locale.UK, "IAP-2 (est.)        %.1f kPa\n", d.iap2Kpa));
        s.append(String.format(Locale.UK, "Idle target raw     %d (0x%02X)\n", d.idleSpeedRaw, d.idleSpeedRaw));
        s.append(String.format(Locale.UK, "ISC position raw    %d (0x%02X)\n", d.iscRaw, d.iscRaw));

        s.append("\nInjectors\n");
        s.append(String.format(Locale.UK, "Cyl 1               %.3f ms\n", d.inj1));
        s.append(String.format(Locale.UK, "Cyl 2               %.3f ms\n", d.inj2));
        s.append(String.format(Locale.UK, "Cyl 3               %.3f ms\n", d.inj3));
        s.append(String.format(Locale.UK, "Cyl 4               %.3f ms\n", d.inj4));
        s.append(String.format(Locale.UK, "Average             %.3f ms\n", d.averageMs));

        s.append("\nIgnition / throttle\n");
        s.append(formatMaybe("Ignition 1", d.ign1Deg, "°"));
        s.append(formatMaybe("Ignition 2", d.ign2Deg, "°"));
        s.append(formatMaybe("Ignition 3", d.ign3Deg, "°"));
        s.append(formatMaybe("Ignition 4", d.ign4Deg, "°"));
        s.append(formatMaybe("Secondary TPS", d.secondaryTpsPct, "%"));

        s.append("\nStatus / relay bytes\n");
        s.append(formatRaw("Status 45", d.status45));
        s.append(formatRaw("Cooling fan", d.coolingFanRaw));
        s.append(formatRaw("Exhaust valve", d.exhaustValveRaw));
        s.append(formatRaw("Clutch/starter", d.clutchStarterRaw));
        s.append(formatRaw("Neutral", d.neutralRaw));

        s.append("\nUnmapped frame bytes\n");
        boolean[] mapped = new boolean[d.frame.length];
        int[] mappedIdx = {
                0,1,12,13,14,15,16,17,18,19,20,21,22,23,24,25,
                27,28,29,30,31,32,33,34,37,38,39,40,42,45,46,47,48,49
        };
        for (int i : mappedIdx) {
            if (i >= 0 && i < mapped.length) mapped[i] = true;
        }

        int col = 0;
        for (int i=0; i<d.frame.length; i++) {
            if (mapped[i]) continue;
            s.append(String.format(Locale.US, "%02d:%02X ", i, u8(d.frame[i])));
            col++;
            if (col % 6 == 0) s.append('\n');
        }

        return s.toString().trim();
    }

    private static String formatMaybe(String name, double value, String unit) {
        if (Double.isNaN(value)) return String.format(Locale.UK, "%-20s —\n", name);
        return String.format(Locale.UK, "%-20s %.1f %s\n", name, value, unit);
    }

    private static String formatRaw(String name, int raw) {
        if (raw < 0) return String.format(Locale.UK, "%-20s —\n", name);
        return String.format(Locale.UK, "%-20s %d (0x%02X)\n", name, raw, raw);
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
            out[i] = (byte)Integer.parseInt(hex.substring(i*2, i*2+2),16);
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

    private static int val(byte[] f, int index) {
        return index >= 0 && index < f.length ? u8(f[index]) : 0;
    }

    private static int u8(byte b) {
        return b & 255;
    }

    private static int u16(byte[] f, int hi, int lo) {
        return (u8(f[hi]) << 8) | u8(f[lo]);
    }
}
