package fr.neamar.kiss.broadcast;

import static fr.neamar.kiss.dataprovider.ProviderName.APPS;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.utils.Log;

public class LocaleChangedReceiver extends BroadcastReceiver {

    private static final String TAG = LocaleChangedReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context ctx, Intent intent) {
        // Only handle system broadcasts
        if (!Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
            return;
        }

        // Reload application list (app labels depend on the locale)
        KissApplication.getApplication(ctx).getDataHandler().reload(APPS);
    }
}
