package com.bandit1250.fuelmonitor;

public final class FuelCalculator {
    private FuelCalculator() {}
    public static double litresPerHour(double rpm, double pwMs, double qCcMin, double factor) {
        if (rpm <= 0 || pwMs <= 0 || qCcMin <= 0) return 0.0;
        return qCcMin * pwMs * rpm / 500000.0 * factor;
    }
}
