package com.furgo.nostartstop;

/**
 * The stop-start state the app assumes. The car does not report it, so it is a model: the car turns stop-start on at
 * every engine start, each switch sent flips it, and so does each "Car shows the other state" report. It is unknown before the
 * app has seen a cycle start, from ignition off until the next one, and while the automatic switch is off (then
 * nothing watches the ignition). While it is unknown the user can set it by hand from the instrument cluster.
 * Plain fields, so Prefs can store and reload them; times are elapsedRealtime.
 */
final class AssumedState {
    static final int UNKNOWN = 0, ON = 1, OFF = 2;
    /** A battery or power state value older than this is printed with its age. */
    static final long FRESH_MS = 10_000;

    /** The cycle the model belongs to, or null before the first one. */
    String cycle;
    /** Ignition off, or the watch stopped, since the cycle started. */
    boolean ended;
    int wake = -1;
    String source;
    /** When the cycle started, or the last engine start within it. */
    long startAt;
    /** startAt is an engine start, not an ignition on. */
    boolean engineStart;
    int switches;
    int reports;
    /** The state before any switch or report: ON as the car sets it, or what the user set by hand. */
    int base = ON;

    /** A new ignition cycle: stop-start is on. */
    void newCycle(String cycle, int wake, String source, boolean engineStart, long now) {
        this.cycle = cycle;
        this.wake = wake;
        this.source = source;
        this.engineStart = engineStart;
        ended = false;
        startAt = now;
        switches = 0;
        reports = 0;
        base = ON;
    }

    /**
     * The user says what the instrument cluster shows. Only while the state is unknown; enabled: the watch service
     * follows the ignition, so that the next ignition off or engine start still ends it. Returns true if it was set.
     */
    boolean setByHand(String current, boolean enabled, int wake, int state, long now) {
        if (!enabled || state == UNKNOWN || state(current, enabled) != UNKNOWN) return false;
        newCycle(current, wake, "set by hand", false, now);
        base = state;
        return true;
    }

    /**
     * An engine start that stays in the current cycle: the car turned stop-start on again. A switch sent during the
     * crank came after that and still counts; earlier switches and reports no longer matter.
     */
    void sameCycleEngineStart(String cycle, int wake, boolean switchedDuringCrank, long now) {
        newCycle(cycle, wake, "engine start, same cycle", true, now);
        switches = switchedDuringCrank ? 1 : 0;
    }

    /** Ignition off, or nothing watching the ignition any more. Returns true if the state was known. */
    boolean end(String current, boolean enabled) {
        if (state(current, enabled) == UNKNOWN) return false;
        ended = true;
        return true;
    }

    /** A switch command that went out for the given cycle. Returns true if it changed the state. */
    boolean switchSent(String switchCycle, String current, boolean enabled) {
        if (state(current, enabled) == UNKNOWN || !current.equals(switchCycle)) return false;
        switches++;
        return true;
    }

    /** A "Car shows the other state" report. Refused (false) while the state is unknown. */
    boolean report(String current, boolean enabled) {
        if (state(current, enabled) == UNKNOWN) return false;
        reports++;
        return true;
    }

    /** enabled: the automatic switch is on, so the watch service follows the ignition. */
    int state(String current, boolean enabled) {
        if (!enabled || cycle == null || !cycle.equals(current) || ended) return UNKNOWN;
        return (switches + reports) % 2 == 0 ? base : base == ON ? OFF : ON;
    }

    static String name(int state) {
        return state == ON ? "on" : state == OFF ? "off" : "unknown";
    }

    /** For example "assumed: off (switch sent, automatic)". */
    static String change(int state, String why) {
        return "assumed: " + name(state) + " (" + why + ")";
    }

    /**
     * The line logged when the user reports the shown state as wrong, before it is counted. Ages and values below 0
     * are not known and left out.
     */
    String reportLine(String current, boolean enabled, long now, long lastSwitchAt, boolean lastAutomatic,
            int centivolts, long centivoltsAt, int powerState, long powerStateAt) {
        int assumed = state(current, enabled);
        StringBuilder sb = new StringBuilder("report: car shows ")
                .append(assumed == ON ? "OFF" : "ON")
                .append(", app assumed ").append(assumed == ON ? "ON" : "OFF");
        if (wake >= 0) sb.append(", wake ").append(wake).append(source == null ? "" : " (" + source + ")");
        sb.append(", ").append(switches).append(switches == 1 ? " switch" : " switches").append(" sent");
        sb.append(", ").append(reports).append(reports == 1 ? " report" : " reports");
        sb.append(engineStart ? ", engine start " + (now - startAt) / 1000 + " s ago"
                : ", cycle " + (now - startAt) / 1000 + " s old");
        if (lastSwitchAt >= 0) {
            sb.append(", last switch ").append((now - lastSwitchAt) / 1000).append(" s ago (")
                    .append(lastAutomatic ? "automatic" : "tap").append(')');
        }
        if (centivolts >= 0) {
            sb.append(", battery ").append(Protocol.volts(centivolts)).append(" V").append(age(now, centivoltsAt));
        }
        if (powerState >= 0) sb.append(", power state ").append(powerState).append(age(now, powerStateAt));
        return sb.toString();
    }

    private static String age(long now, long at) {
        return now - at > FRESH_MS ? " " + (now - at) / 1000 + " s ago" : "";
    }
}
