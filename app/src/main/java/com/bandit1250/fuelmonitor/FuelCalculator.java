package com.bandit1250.fuelmonitor;

public final class FuelCalculator {
    private FuelCalculator() {}

    /**
     * Whole-engine flow estimate when one representative/average injector pulse
     * width is used for all four cylinders.
     *
     * Four-cylinder, four-stroke assumption:
     * each cylinder has one injection event per 720 crank degrees, so the four
     * injectors together produce 2 * RPM injection events per minute.
     *
     * qCcMin is one injector's static flow in cc/min.
     */
    public static double rawLitresPerHour(
            double rpm,
            double commandedPulseMs,
            double qCcMin,
            double factor
    ) {
        if (rpm <= 0 || commandedPulseMs <= 0 || qCcMin <= 0) return 0.0;
        return qCcMin * commandedPulseMs * rpm / 500000.0 * factor;
    }

    /**
     * Fuel contribution from ONE injector on a four-stroke engine.
     *
     * One cylinder fires once per 720 crank degrees, therefore one injector has
     * RPM / 2 injection events per minute.
     *
     * The denominator is four times larger than rawLitresPerHour(), because
     * this method represents only one cylinder/injector rather than all four.
     */
    public static double oneInjectorLitresPerHour(
            double rpm,
            double effectivePulseMs,
            double qCcMin,
            double factor
    ) {
        if (rpm <= 0 || effectivePulseMs <= 0 || qCcMin <= 0) return 0.0;
        return qCcMin * effectivePulseMs * rpm / 2000000.0 * factor;
    }

    /**
     * Provisional short-pulse correction.
     *
     * The electrical command begins before the injector has reached useful flow.
     * Fuel continues briefly while the pintle closes, so we use a NET latency:
     *
     * netLatency ~= opening delay - closing-flow equivalent
     *
     * rather than adding opening and closing times to the fuelled duration.
     */
    public static double effectivePulseMs(double commandedPulseMs, double netLatencyMs) {
        return Math.max(0.0, commandedPulseMs - Math.max(0.0, netLatencyMs));
    }

    /**
     * Legacy/representative-pulse whole-engine estimate.
     *
     * Retained for comparisons and any callers that have only one pulse width.
     */
    public static double estimatedLitresPerHour(
            double rpm,
            double commandedPulseMs,
            double qCcMin,
            double netLatencyMs,
            double factor
    ) {
        return rawLitresPerHour(
                rpm,
                effectivePulseMs(commandedPulseMs, netLatencyMs),
                qCcMin,
                factor
        );
    }

    /**
     * Preferred Bandit calculation: correct each of the four ECU-reported
     * injector pulse widths independently, calculate each cylinder's fuel
     * contribution, then sum them.
     *
     * This is mathematically the same as average-first when all four corrected
     * pulse widths remain above zero. It is more accurate at very short pulse
     * widths because each injector gets its own max(0, pulse - latency) clamp.
     */
    public static double estimatedFourInjectorLitresPerHour(
            double rpm,
            double inj1Ms,
            double inj2Ms,
            double inj3Ms,
            double inj4Ms,
            double qCcMin,
            double netLatencyMs,
            double factor
    ) {
        if (rpm <= 0 || qCcMin <= 0) return 0.0;

        double e1 = effectivePulseMs(inj1Ms, netLatencyMs);
        double e2 = effectivePulseMs(inj2Ms, netLatencyMs);
        double e3 = effectivePulseMs(inj3Ms, netLatencyMs);
        double e4 = effectivePulseMs(inj4Ms, netLatencyMs);

        return oneInjectorLitresPerHour(rpm, e1, qCcMin, factor)
                + oneInjectorLitresPerHour(rpm, e2, qCcMin, factor)
                + oneInjectorLitresPerHour(rpm, e3, qCcMin, factor)
                + oneInjectorLitresPerHour(rpm, e4, qCcMin, factor);
    }
}
