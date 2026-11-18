package com.furgo.nostartstop;

/**
 * The stop-start state the app assumes. The car does not report it, so it is a model: the car turns stop-start on at
 * every engine start, each switch sent flips it, and so does each "status is wrong" report. It is unknown before the
 * app has seen a cycle start, and from ignition off until the next one.
 */
final class AssumedState {
    static final int UNKNOWN = 0, ON = 1, OFF = 2;

    private AssumedState() {
    }

    /**
     * tracked: a cycle was started (ignition on or engine start) and it is the current one. ended: ignition off was
     * seen since it started. flips: switches sent plus reports in that cycle.
     */
    static int state(boolean tracked, boolean ended, int flips) {
        if (!tracked || ended) return UNKNOWN;
        return flips % 2 == 0 ? ON : OFF;
    }

    static String name(int state) {
        return state == ON ? "on" : state == OFF ? "off" : "unknown";
    }

    /** For example "assumed: off (switch sent, automatic)". */
    static String change(int state, String why) {
        return "assumed: " + name(state) + " (" + why + ")";
    }

    /**
     * The line logged when the user reports the shown state as wrong, before it is flipped. Values below 0 are not
     * known and left out.
     */
    static String report(int assumed, int wake, String source, int switches, int reports, long cycleAgeMs,
            long lastSwitchAgoMs, boolean lastAutomatic, int centivolts, int powerState) {
        StringBuilder sb = new StringBuilder("report: car shows ")
                .append(assumed == ON ? "OFF" : "ON")
                .append(", app assumed ").append(assumed == ON ? "ON" : "OFF");
        if (wake >= 0) sb.append(", wake ").append(wake).append(source == null ? "" : " (" + source + ")");
        sb.append(", ").append(switches).append(switches == 1 ? " switch" : " switches").append(" sent");
        sb.append(", ").append(reports).append(reports == 1 ? " report" : " reports");
        if (cycleAgeMs >= 0) sb.append(", cycle ").append(cycleAgeMs / 1000).append(" s old");
        if (lastSwitchAgoMs >= 0) {
            sb.append(", last switch ").append(lastSwitchAgoMs / 1000).append(" s ago (")
                    .append(lastAutomatic ? "automatic" : "tap").append(')');
        }
        if (centivolts >= 0) sb.append(", battery ").append(Protocol.volts(centivolts)).append(" V");
        if (powerState >= 0) sb.append(", power state ").append(powerState);
        return sb.toString();
    }
}
