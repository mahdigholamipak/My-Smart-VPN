package kittoku.osc.fragment

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kittoku.osc.R
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.preference.checkPreferences
import kittoku.osc.preference.toastInvalidSetting
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.SstpVpnService

class HomeFragment : Fragment() {
    private lateinit var prefs: SharedPreferences
    private lateinit var hostnameEdit: TextInputEditText
    private lateinit var usernameEdit: TextInputEditText
    private lateinit var passwordEdit: TextInputEditText

    private val preparationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(ACTION_VPN_CONNECT)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        hostnameEdit = view.findViewById(R.id.hostname_edit)
        usernameEdit = view.findViewById(R.id.username_edit)
        passwordEdit = view.findViewById(R.id.password_edit)

        view.findViewById<MaterialButton>(R.id.connect_button).setOnClickListener {
            savePreferences()

            checkPreferences(prefs)?.also {
                toastInvalidSetting(it, requireContext())
                return@setOnClickListener
            }

            VpnService.prepare(requireContext())?.also {
                preparationLauncher.launch(it)
            } ?: startVpnService(ACTION_VPN_CONNECT)
        }

        loadPreferences()
    }

    private fun savePreferences() {
        setStringPrefValue(hostnameEdit.text.toString(), OscPrefKey.HOME_HOSTNAME, prefs)
        setStringPrefValue(usernameEdit.text.toString(), OscPrefKey.HOME_USERNAME, prefs)
        setStringPrefValue(passwordEdit.text.toString(), OscPrefKey.HOME_PASSWORD, prefs)
    }

    private fun loadPreferences() {
        hostnameEdit.setText(getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs))
        usernameEdit.setText(getStringPrefValue(OscPrefKey.HOME_USERNAME, prefs))
        passwordEdit.setText(getStringPrefValue(OscPrefKey.HOME_PASSWORD, prefs))
    }

    private fun startVpnService(action: String) {
        val intent = Intent(requireContext(), SstpVpnService::class.java).setAction(action)

        if (action == ACTION_VPN_CONNECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }
}
