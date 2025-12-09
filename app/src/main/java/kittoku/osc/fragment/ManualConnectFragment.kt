package kittoku.osc.fragment

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.textfield.TextInputEditText
import kittoku.osc.R
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.SstpVpnService

/**
 * Fragment for manual VPN server connection
 * Allows users to enter custom hostname, username, and password
 */
class ManualConnectFragment : Fragment(R.layout.fragment_manual_connect) {
    
    private lateinit var prefs: SharedPreferences
    private lateinit var etHostname: TextInputEditText
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    
    private val preparationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
            findNavController().navigateUp()
        }
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        
        etHostname = view.findViewById(R.id.et_hostname)
        etUsername = view.findViewById(R.id.et_username)
        etPassword = view.findViewById(R.id.et_password)
        
        val btnConnect = view.findViewById<Button>(R.id.btn_connect_manual)
        val btnCancel = view.findViewById<Button>(R.id.btn_cancel)
        
        btnConnect.setOnClickListener {
            val hostname = etHostname.text?.toString()?.trim() ?: ""
            val username = etUsername.text?.toString()?.trim() ?: ""
            val password = etPassword.text?.toString() ?: ""
            
            if (hostname.isEmpty()) {
                Toast.makeText(context, "Please enter a hostname", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            if (username.isEmpty()) {
                Toast.makeText(context, "Please enter a username", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Save to preferences
            setStringPrefValue(hostname, OscPrefKey.HOME_HOSTNAME, prefs)
            setStringPrefValue(username, OscPrefKey.HOME_USERNAME, prefs)
            setStringPrefValue(password, OscPrefKey.HOME_PASSWORD, prefs)
            
            // Start connection
            VpnService.prepare(requireContext())?.also {
                preparationLauncher.launch(it)
            } ?: run {
                startVpnService()
                findNavController().navigateUp()
            }
        }
        
        btnCancel.setOnClickListener {
            findNavController().navigateUp()
        }
    }
    
    private fun startVpnService() {
        val intent = Intent(requireContext(), SstpVpnService::class.java)
            .setAction(ACTION_VPN_CONNECT)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }
}
