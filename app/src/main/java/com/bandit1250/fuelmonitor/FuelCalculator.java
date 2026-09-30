package com.bandit1250.fuelmonitor;

public final class FuelCalculator {
    private FuelCalculator() {}

    /**
     * Simple flow estimate using the ECU-reported electrical pulse width directly.
     *
     * Four-cylinder, four-stroke assumption:
     * each cylinder has one injection event per 720 crank degrees, so events/min/cylinder = RPM/2.
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
}
