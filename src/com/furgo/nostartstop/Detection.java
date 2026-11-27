package com.furgo.nostartstop;

/**
 * What the head unit is, from its strong signals: TWUtil in the framework for Topway, the vendor CAN service as a
 * system package for FYT and Microntek. Pure Java so it can be tested; {@link DeviceWatch#detect} reads it.
 */
final class Detection {
    enum Platform { TOPWAY, FYT, MICRONTEK, NONE, AMBIGUOUS }

    final boolean topway, fyt, microntek;
    /** Version name of the vendor car app com.tw.car, which names the CAN box profile, or null when it is absent. */
    final String vendorVersion;

    Detection(boolean topway, boolean fyt, boolean microntek, String vendorVersion) {
        this.topway = topway;
        this.fyt = fyt;
        this.microntek = microntek;
        this.vendorVersion = vendorVersion;
    }

    /**
     * The beta link this head unit takes when TWUtil does not open: both FYT and Microntek is AMBIGUOUS. TWUtil is
     * tried first, so {@link #topway} decides before this.
     */
    Platform otherPlatform() {
        if (fyt && microntek) return Platform.AMBIGUOUS;
        if (fyt) return Platform.FYT;
        if (microntek) return Platform.MICRONTEK;
        return Platform.NONE;
    }

    /** For the car setup, see {@link CarSetup#detection}. */
    String carSetup() {
        return CarSetup.detection(vendorVersion);
    }
}
