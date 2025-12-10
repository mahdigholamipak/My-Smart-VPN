package kittoku.osc.fragment

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kittoku.osc.R
import kittoku.osc.activity.BLANK_ACTIVITY_TYPE_APPS
import kittoku.osc.activity.BlankActivity
import kittoku.osc.activity.EXTRA_KEY_TYPE
import kittoku.osc.preference.IranBypassHelper
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.setURIPrefValue
import kittoku.osc.preference.custom.DirectoryPreference


internal class SettingFragment : PreferenceFragmentCompat() {
    private lateinit var prefs: SharedPreferences

    private lateinit var certDirPref: DirectoryPreference
    private lateinit var logDirPref: DirectoryPreference

    private val certDirLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data?.also {
            requireContext().contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } else null

        setURIPrefValue(uri, OscPrefKey.SSL_CERT_DIR, prefs)

        certDirPref.updateView()
    }

    private val logDirLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data?.also {
            requireContext().contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } else null

        setURIPrefValue(uri, OscPrefKey.LOG_DIR, prefs)

        logDirPref.updateView()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)
        prefs = preferenceManager.sharedPreferences!!

        certDirPref = findPreference(OscPrefKey.SSL_CERT_DIR.name)!!
        logDirPref = findPreference(OscPrefKey.LOG_DIR.name)!!

        setCertDirListener()
        setLogDirListener()
        setAllowedAppsListener()
        setIranBypassListener()
        addViewLogsOption()
        setupAboutListener()  // Issue #7 Fix: Add About page entry point
    }
    
    /**
     * Issue #7 Fix: Add About & Licenses click listener
     */
    private fun setupAboutListener() {
        findPreference<Preference>("ABOUT_LICENSES")?.also {
            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                try {
                    findNavController().navigate(R.id.action_SettingFragment_to_AboutFragment)
                } catch (e: Exception) {
                    // Navigation not defined, try direct fragment
                    android.util.Log.e("SettingFragment", "Navigation to About failed", e)
                }
                true
            }
        }
    }

    private fun setCertDirListener() {
        certDirPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).also { intent ->
                intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                certDirLauncher.launch(intent)
            }

            true
        }
    }

    private fun setLogDirListener() {
        logDirPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).also { intent ->
                intent.flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                logDirLauncher.launch(intent)
            }

            true
        }
    }

    private fun setAllowedAppsListener() {
        findPreference<Preference>(OscPrefKey.ROUTE_ALLOWED_APPS.name)!!.also {
            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                startActivity(Intent(requireContext(), BlankActivity::class.java).putExtra(
                    EXTRA_KEY_TYPE,
                    BLANK_ACTIVITY_TYPE_APPS
                ))

                true
            }
        }
    }
    
    /**
     * Iran Bypass toggle listener
     * ISSUE #5 FIX: Now passes context to enable correct inverted logic
     */
    private fun setIranBypassListener() {
        findPreference<SwitchPreferenceCompat>("IRAN_BYPASS_ENABLED")?.also { pref ->
            pref.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                // ISSUE #5 FIX: Pass context for correct behavior
                IranBypassHelper.applyIranBypass(requireContext(), prefs, enabled)
                true
            }
        }
    }
    
    /**
     * Add a clickable option to view runtime logs
     */
    private fun addViewLogsOption() {
        findPreference<Preference>(OscPrefKey.LOG_DO_SAVE_LOG.name)?.also {
            it.summary = "Tap to view runtime logs in-app"
            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                try {
                    findNavController().navigate(R.id.action_SettingFragment_to_LogViewerFragment)
                } catch (e: Exception) {
                    // Navigation not available, ignore
                }
                true
            }
        }
    }
}
