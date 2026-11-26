package fr.neamar.kiss.settings;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import fr.neamar.kiss.R;

public class SettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);

        Preference importAppearance = findPreference("import-appearance");
        if (importAppearance != null) {
            importAppearance.setOnPreferenceClickListener(preference -> {
                int message = AppearanceImport.importFromClipboard(requireContext());
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                if (message == R.string.import_appearance_done) {
                    // Show this screen with the imported theme
                    requireActivity().recreate();
                }
                return true;
            });
        }
    }
}
