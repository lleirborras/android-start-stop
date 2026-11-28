package com.furgo.nostartstop;

import android.app.Application;
import android.os.Process;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Before anything touches TWUtil: the boot and watch services open it too.
        String beta = Prefs.withoutAdb(this) ? HiddenApi.exempt() : null;
        if (beta != null) LogFile.append(this, "beta: reach the car without adb failed: " + beta);
        LogFile.append(this, "process started, pid " + Process.myPid() + ", " + DeviceWatch.facts(this));
    }
}
