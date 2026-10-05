package com.bandit1250.fuelmonitor;

/**
 * Bandit 1250 fuel-flow model.
 *
 * The SDS 21 08 injector pulse widths are treated as FINAL electrical injector
 * ON-times, including the ECU's battery-voltage injector latency compensation.
 *
 * Firmware analysis of the closely related D4FASE80 / 18H80-family Denso image
 * found one common battery-dependent latency value added ONCE to every injector
 * event. For fuel-flow estimation we therefore subtract that value ONCE from
 * EACH reported injector pulse, clamp at zero, then sum all four cylinders.
 *
 * The table should eventually be verified against the user's exact 32920-18H00
 * firmware, but it is a much stronger basis than the old fixed ~0.600 ms
 * placeholder.
 */
public final class FuelCalculator {
    private FuelCalculator() {}

    // Firmware-derived battery-raw breakpoints.
    private static final int[] INJECTOR_DT_BATT_RAW = {
            83, 115, 147, 179, 211
    };

    // Corresponding injector battery-voltage latency compensation, milliseconds.
    private static final double[] INJECTOR_DT_MS = {
            1.080, 0.656, 0.416, 0.280, 0.192
    };

    /**
     * Interpolate injector battery-voltage latency directly from the raw SDS
     * battery byte. Endpoint clamping is deliberate: do not extrapolate beyond
     * the firmware calibration range.
     */
    public static double getInjectorDeadTimeMs(int batteryRaw) {
        if (batteryRaw <= INJECTOR_DT_BATT_RAW[0]) {
            return INJECTOR_DT_MS[0];
        }

        int last = INJECTOR_DT_BATT_RAW.length - 1;

        if (batteryRaw >= INJECTOR_DT_BATT_RAW[last]) {
            return INJECTOR_DT_MS[last];
        }

        for (int i = 0; i < last; i++) {
            int x0 = INJECTOR_DT_BATT_RAW[i];
            int x1 = INJECTOR_DT_BATT_RAW[i + 1];

            if (batteryRaw >= x0 && batteryRaw <= x1) {
                double y0 = INJECTOR_DT_MS[i];
                double y1 = INJECTOR_DT_MS[i + 1];

                double fraction =
                        (batteryRaw - x0) / (double)(x1 - x0);

                return y0 + fraction * (y1 - y0);
            }
        }

        // Defensive fallback; normal values return inside the loop.
        return INJECTOR_DT_MS[last];
    }

    /**
     * Effective fuel-delivery pulse after removing the ECU-added electrical
     * injector latency compensation from one reported SDS pulse width.
     */
    public static double effectivePulseMs(
            double reportedElectricalPulseMs,
            double injectorDeadTimeMs
    ) {
        return Math.max(
                0.0,
                reportedElectricalPulseMs - Math.max(0.0, injectorDeadTimeMs)
        );
    }

    /**
     * Fuel contribution from ONE injector on a four-stroke engine.
     *
     * One cylinder has one injection event per 720 crank degrees, therefore
     * RPM / 2 injection events per minute.
     */
    public static double oneInjectorLitresPerHour(
            double rpm,
            double effectivePulseMs,
            double injectorFlowCcMin,
            double factor
    ) {
        if (rpm <= 0 ||
                effectivePulseMs <= 0 ||
                injectorFlowCcMin <= 0) {
            return 0.0;
        }

        return injectorFlowCcMin
                * effectivePulseMs
                * rpm
                / 2_000_000.0
                * factor;
    }

    /**
     * Sum of the four effective fuel pulse widths after applying the same
     * battery-derived injector latency independently to every cylinder.
     */
    public static double totalEffectivePulseMs(
            int batteryRaw,
            double inj1Ms,
            double inj2Ms,
            double inj3Ms,
            double inj4Ms
    ) {
        double deadTimeMs = getInjectorDeadTimeMs(batteryRaw);

        return effectivePulseMs(inj1Ms, deadTimeMs)
                + effectivePulseMs(inj2Ms, deadTimeMs)
                + effectivePulseMs(inj3Ms, deadTimeMs)
                + effectivePulseMs(inj4Ms, deadTimeMs);
    }

    /**
     * Preferred Bandit MPG fuel-flow estimate.
     *
     * L/hr =
     * RPM * injectorFlowCcMin * sum(corrected injector pulse widths)
     * / 2,000,000
     *
     * The calibration factor remains available for tank-to-tank correction,
     * while injector static flow remains the main absolute calibration unknown.
     */
    public static double estimatedFourInjectorLitresPerHour(
            double rpm,
            int batteryRaw,
            double inj1Ms,
            double inj2Ms,
            double inj3Ms,
            double inj4Ms,
            double injectorFlowCcMin,
            double factor
    ) {
        if (rpm <= 0 || injectorFlowCcMin <= 0) return 0.0;

        double deadTimeMs = getInjectorDeadTimeMs(batteryRaw);

        double e1 = effectivePulseMs(inj1Ms, deadTimeMs);
        double e2 = effectivePulseMs(inj2Ms, deadTimeMs);
        double e3 = effectivePulseMs(inj3Ms, deadTimeMs);
        double e4 = effectivePulseMs(inj4Ms, deadTimeMs);

        return oneInjectorLitresPerHour(
                        rpm, e1, injectorFlowCcMin, factor)
                + oneInjectorLitresPerHour(
                        rpm, e2, injectorFlowCcMin, factor)
                + oneInjectorLitresPerHour(
                        rpm, e3, injectorFlowCcMin, factor)
                + oneInjectorLitresPerHour(
                        rpm, e4, injectorFlowCcMin, factor);
    }
}
