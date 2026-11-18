package com.furgo.nostartstop;

import android.app.Application;
import android.os.Process;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        LogFile.append(this, "process started, pid " + Process.myPid() + ", " + DeviceWatch.facts(this));
    }
}
