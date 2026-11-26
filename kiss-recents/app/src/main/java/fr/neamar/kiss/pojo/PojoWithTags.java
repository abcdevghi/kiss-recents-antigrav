package fr.neamar.kiss.pojo;

import android.text.TextUtils;

import java.util.Locale;

import fr.neamar.kiss.normalizer.StringNormalizer;

public class PojoWithTags extends Pojo {
    // tags normalized, for faster search
    private StringNormalizer.Result normalizedTags = null;
    // Tags assigned to this pojo
    private String tags = "";
    // Resident memory of the running app in kB, 0 when it is not running or unknown
    public long rssKb = 0;

    PojoWithTags(String id) {
        super(id);
    }

    public StringNormalizer.Result getNormalizedTags() {
        return normalizedTags;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        if (!TextUtils.isEmpty(tags)) {
            // Set the actual user-friendly name
            this.tags = tags.trim().toLowerCase(Locale.getDefault());
            this.normalizedTags = StringNormalizer.normalizeWithResult(this.tags, false);
        } else {
            this.tags = null;
            this.normalizedTags = null;
        }
    }
}
