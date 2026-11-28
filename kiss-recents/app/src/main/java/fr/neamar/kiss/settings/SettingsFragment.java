package fr.neamar.kiss.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.TwoStatePreference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import fr.neamar.kiss.R;
import fr.neamar.kiss.utils.DrawableUtils;

public class SettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        ConfigSlots.migrateLegacyPrefsIfNeeded(prefs);

        setPreferencesFromResource(R.xml.preferences, rootKey);

        for (int i = 0; i < ConfigSlots.SLOT_COUNT; i++) {
            final int slotIndex = i;
            Preference slotPref = findPreference(ConfigSlots.SLOT_PREF_KEYS[i]);
            if (slotPref != null) {
                slotPref.setOnPreferenceClickListener(preference -> {
                    showSlotActionsDialog(slotIndex);
                    return true;
                });
            }
        }
        refreshSlotSummaries();

        Preference resetEmpty = findPreference("reset-empty-config");
        if (resetEmpty != null) {
            resetEmpty.setOnPreferenceClickListener(preference -> {
                showResetEmptyDialog();
                return true;
            });
        }

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

    private void showSlotActionsDialog(final int slotIndex) {
        Context context = requireContext();
        ConfigSlots.SlotInfo info = ConfigSlots.getSlotInfo(context, slotIndex);
        String displayTitle = formatSlotTitle(context, info);

        if (info.empty) {
            String[] actions = {
                    getString(R.string.config_slot_action_save),
                    getString(R.string.config_slot_action_load_empty)
            };
            new MaterialAlertDialogBuilder(context)
                    .setTitle(displayTitle)
                    .setItems(actions, (dialog, which) -> {
                        if (which == 0) {
                            showSaveNameDialog(slotIndex, null);
                        } else {
                            applySlot(slotIndex, getString(R.string.config_slot_default_title, slotIndex + 1));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            String[] actions = {
                    getString(R.string.config_slot_action_load),
                    getString(R.string.config_slot_action_overwrite),
                    getString(R.string.config_slot_action_rename),
                    getString(R.string.config_slot_action_clear)
            };
            new MaterialAlertDialogBuilder(context)
                    .setTitle(displayTitle)
                    .setItems(actions, (dialog, which) -> {
                        if (which == 0) {
                            applySlot(slotIndex, displayTitle);
                        } else if (which == 1) {
                            if (ConfigSlots.saveCurrentToSlot(context, slotIndex, info.name)) {
                                refreshSlotSummaries();
                                Toast.makeText(context, getString(R.string.config_slot_saved, displayTitle), Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(context, R.string.config_slot_error, Toast.LENGTH_SHORT).show();
                            }
                        } else if (which == 2) {
                            showRenameDialog(slotIndex, info.name);
                        } else if (which == 3) {
                            if (ConfigSlots.clearSlot(context, slotIndex)) {
                                refreshSlotSummaries();
                                Toast.makeText(context, getString(R.string.config_slot_cleared, displayTitle), Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(context, R.string.config_slot_error, Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    private void showSaveNameDialog(final int slotIndex, @Nullable String initialName) {
        Context context = requireContext();
        EditText input = createDialogInput(context, initialName, getString(R.string.config_slot_name_hint));
        FrameLayout container = wrapDialogInput(context, input);
        String defaultTitle = getString(R.string.config_slot_default_title, slotIndex + 1);

        new MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.config_slot_action_save))
                .setView(container)
                .setPositiveButton(R.string.config_slot_save_action, (dialog, which) -> {
                    String entered = input.getText().toString().trim();
                    if (ConfigSlots.saveCurrentToSlot(context, slotIndex, entered)) {
                        refreshSlotSummaries();
                        String label = entered.isEmpty() ? defaultTitle : entered;
                        Toast.makeText(context, getString(R.string.config_slot_saved, label), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, R.string.config_slot_error, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showRenameDialog(final int slotIndex, @Nullable String currentName) {
        Context context = requireContext();
        EditText input = createDialogInput(context, currentName, getString(R.string.config_slot_name_hint));
        FrameLayout container = wrapDialogInput(context, input);
        String defaultTitle = getString(R.string.config_slot_default_title, slotIndex + 1);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.config_slot_action_rename)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String entered = input.getText().toString().trim();
                    if (ConfigSlots.renameSlot(context, slotIndex, entered)) {
                        refreshSlotSummaries();
                        String label = entered.isEmpty() ? defaultTitle : entered;
                        Toast.makeText(context, getString(R.string.config_slot_renamed, label), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, R.string.config_slot_error, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showResetEmptyDialog() {
        Context context = requireContext();
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.reset_empty_config_title)
                .setMessage(R.string.reset_empty_config_confirm)
                .setPositiveButton(R.string.reset_empty_config_action, (dialog, which) -> {
                    if (ConfigSlots.resetToEmptyConfig(context)) {
                        syncSwitchWidgets();
                        Toast.makeText(context, R.string.reset_empty_config_done, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, R.string.config_slot_error, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applySlot(int slotIndex, String displayTitle) {
        Context context = requireContext();
        ConfigSlots.SlotInfo info = ConfigSlots.getSlotInfo(context, slotIndex);
        if (ConfigSlots.loadSlot(context, slotIndex)) {
            syncSwitchWidgets();
            int msgRes = info.empty ? R.string.reset_empty_config_done : R.string.config_slot_loaded;
            String msg = info.empty ? getString(msgRes) : getString(msgRes, displayTitle);
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, R.string.config_slot_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void syncSwitchWidgets() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        for (String key : ConfigSlots.CONFIG_KEYS) {
            Preference pref = findPreference(key);
            if (pref instanceof TwoStatePreference) {
                ((TwoStatePreference) pref).setChecked(prefs.getBoolean(key, false));
            }
        }
    }

    private void refreshSlotSummaries() {
        Context context = requireContext();
        for (int i = 0; i < ConfigSlots.SLOT_COUNT; i++) {
            Preference pref = findPreference(ConfigSlots.SLOT_PREF_KEYS[i]);
            if (pref == null) {
                continue;
            }
            ConfigSlots.SlotInfo info = ConfigSlots.getSlotInfo(context, i);
            pref.setTitle(formatSlotTitle(context, info));
            if (info.empty) {
                pref.setSummary(R.string.config_slot_empty_summary);
            } else if (info.savedAtMs > 0L) {
                String when = DateUtils.formatDateTime(context, info.savedAtMs,
                        DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
                pref.setSummary(getString(R.string.config_slot_saved_summary,
                        info.enabledCount, ConfigSlots.CONFIG_KEYS.length, when));
            } else {
                pref.setSummary(getString(R.string.config_slot_saved_no_time,
                        info.enabledCount, ConfigSlots.CONFIG_KEYS.length));
            }
        }
    }

    @NonNull
    private String formatSlotTitle(@NonNull Context context, @NonNull ConfigSlots.SlotInfo info) {
        if (info.empty) {
            return context.getString(R.string.config_slot_empty_title, info.index + 1);
        }
        String base = context.getString(R.string.config_slot_default_title, info.index + 1);
        if (info.name != null && !info.name.isEmpty()) {
            return base + " · " + info.name;
        }
        return base;
    }

    @NonNull
    private static EditText createDialogInput(@NonNull Context context, @Nullable String initialText, @NonNull String hint) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint(hint);
        if (initialText != null) {
            input.setText(initialText);
            input.setSelection(initialText.length());
        }
        return input;
    }

    @NonNull
    private static FrameLayout wrapDialogInput(@NonNull Context context, @NonNull EditText input) {
        FrameLayout container = new FrameLayout(context);
        int padH = DrawableUtils.dpToPx(context, 22f);
        int padTop = DrawableUtils.dpToPx(context, 8f);
        container.setPadding(padH, padTop, padH, 0);
        container.addView(input);
        return container;
    }
}
