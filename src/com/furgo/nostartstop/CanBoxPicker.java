package com.furgo.nostartstop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;

/**
 * The CAN box dropdown of the car setup and the settings: the choices this build offers, see
 * {@link CarSetup#choices}. A pick that differs from what the head unit reports asks first, with a warning.
 */
final class CanBoxPicker {
    interface Done {
        void picked(String choice);
    }

    private CanBoxPicker() {
    }

    static void show(final Activity a, final String detection, String current, final Done done) {
        final String[] choices = CarSetup.choices(detection, CanBox.betaBuild(a));
        String[] labels = new String[choices.length];
        int checked = -1;
        for (int i = 0; i < choices.length; i++) {
            labels[i] = CarSetup.choiceLabel(choices[i]);
            if (choices[i].equals(current)) checked = i;
        }
        new AlertDialog.Builder(a)
                .setTitle("CAN box")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        confirmMismatch(a, detection, choices[which], done);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void confirmMismatch(Activity a, String detection, final String choice, final Done done) {
        String warning = CarSetup.mismatch(detection, choice);
        if (warning == null) {
            done.picked(choice);
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Not what this head unit reports")
                .setMessage(warning + "\n\nUse it only if you know the head unit is set up wrong.")
                .setPositiveButton("Use it anyway", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        done.picked(choice);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
