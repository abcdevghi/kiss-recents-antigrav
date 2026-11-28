package fr.neamar.kiss.result;

import android.content.Context;
import android.text.format.Formatter;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import fr.neamar.kiss.pojo.PojoWithTags;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

public abstract class ResultWithTags<T extends PojoWithTags> extends Result<T> {

    protected ResultWithTags(@NonNull T pojo) {
        super(pojo);
    }

    public static boolean isShowEntryRam(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("show-entry-ram", true);
    }

    /**
     * Tags are not supported: the line under the name shows the memory used by the running app.
     */
    protected void displayTags(Context context, FuzzyScore fuzzyScore, TextView tagsView) {
        if (isShowEntryRam(context)) {
            long bytes = Math.max(0L, pojo.rssKb) * 1024L;
            tagsView.setText(Formatter.formatShortFileSize(context, bytes));
            tagsView.setVisibility(View.VISIBLE);
        } else {
            tagsView.setVisibility(View.GONE);
        }
    }
}
