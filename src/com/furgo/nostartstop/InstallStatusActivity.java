package com.furgo.nostartstop;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;

/**
 * Receives the package installer's status for an update started by Updater. It is not exported,
 * so only this app and the system can reach it: another app must not be able to make us launch
 * an intent of its choosing.
 */
public class InstallStatusActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
        finish();
    }

    private void handle(Intent intent) {
        int session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        if (session == -1 || session != Updater.pendingSession) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) startActivity(confirm);
            return;
        }
        Updater.pendingSession = -1;
        if (status != PackageInstaller.STATUS_SUCCESS) {
            LogFile.append(this, "install failed (" + status + "): "
                    + printable(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)));
        }
    }

    private static String printable(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length() && i < 200; i++) {
            char c = s.charAt(i);
            out.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return out.toString();
    }
}
