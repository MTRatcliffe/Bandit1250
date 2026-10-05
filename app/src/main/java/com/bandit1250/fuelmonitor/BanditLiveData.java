package com.bandit1250.fuelmonitor;

public final class BanditLiveData {
    public final byte[] frame;

    public final int rpm;
    public final double ecuSpeedKph;
    public final double tpsPct;
    public final double iap1Kpa;
    public final double engineTempC;
    public final double intakeTempC;
    public final int eapRaw;
    public final int batteryRaw;
    public final double batteryEstV;
    public final int o2Raw;
    public final int gearRaw;
    public final double iap2Kpa;
    public final int idleSpeedRaw;
    public final int iscRaw;

    public final double inj1, inj2, inj3, inj4, averageMs;

    public final double ign1Deg, ign2Deg, ign3Deg, ign4Deg;
    public final double secondaryTpsPct;

    public final int status45;
    public final int coolingFanRaw;
    public final int exhaustValveRaw;
    public final int clutchStarterRaw;
    public final int neutralRaw;

    public BanditLiveData(
            byte[] frame,
            int rpm,
            double ecuSpeedKph,
            double tpsPct,
            double iap1Kpa,
            double engineTempC,
            double intakeTempC,
            int eapRaw,
            int batteryRaw,
            double batteryEstV,
            int o2Raw,
            int gearRaw,
            double iap2Kpa,
            int idleSpeedRaw,
            int iscRaw,
            double inj1,
            double inj2,
            double inj3,
            double inj4,
            double ign1Deg,
            double ign2Deg,
            double ign3Deg,
            double ign4Deg,
            double secondaryTpsPct,
            int status45,
            int coolingFanRaw,
            int exhaustValveRaw,
            int clutchStarterRaw,
            int neutralRaw
    ) {
        this.frame = frame;
        this.rpm = rpm;
        this.ecuSpeedKph = ecuSpeedKph;
        this.tpsPct = tpsPct;
        this.iap1Kpa = iap1Kpa;
        this.engineTempC = engineTempC;
        this.intakeTempC = intakeTempC;
        this.eapRaw = eapRaw;
        this.batteryRaw = batteryRaw;
        this.batteryEstV = batteryEstV;
        this.o2Raw = o2Raw;
        this.gearRaw = gearRaw;
        this.iap2Kpa = iap2Kpa;
        this.idleSpeedRaw = idleSpeedRaw;
        this.iscRaw = iscRaw;

        this.inj1 = inj1;
        this.inj2 = inj2;
        this.inj3 = inj3;
        this.inj4 = inj4;
        this.averageMs = (inj1 + inj2 + inj3 + inj4) / 4.0;

        this.ign1Deg = ign1Deg;
        this.ign2Deg = ign2Deg;
        this.ign3Deg = ign3Deg;
        this.ign4Deg = ign4Deg;
        this.secondaryTpsPct = secondaryTpsPct;

        this.status45 = status45;
        this.coolingFanRaw = coolingFanRaw;
        this.exhaustValveRaw = exhaustValveRaw;
        this.clutchStarterRaw = clutchStarterRaw;
        this.neutralRaw = neutralRaw;
    }
}
