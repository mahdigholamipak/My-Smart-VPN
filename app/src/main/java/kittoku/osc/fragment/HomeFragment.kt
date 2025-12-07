package kittoku.osc.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import kittoku.osc.R
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.ACTION_VPN_STATUS_CHANGED
import kittoku.osc.service.SstpVpnService

class HomeFragment : Fragment(R.layout.fragment_home) {
    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button

    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra("status") ?: "DISCONNECTED"
            updateStatus(status)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvStatus = view.findViewById(R.id.tv_status)
        btnConnect = view.findViewById(R.id.btn_connect)
        val btnServerList = view.findViewById<Button>(R.id.btn_server_list)

        btnConnect.setOnClickListener {
            val intent = Intent(context, SstpVpnService::class.java)
            if (btnConnect.text == "CONNECT") {
                intent.action = ACTION_VPN_CONNECT
            } else {
                intent.action = ACTION_VPN_DISCONNECT
            }
            context?.startService(intent)
        }

        btnServerList.setOnClickListener {
            findNavController().navigate(R.id.action_HomeFragment_to_ServerListFragment)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ACTION_VPN_STATUS_CHANGED)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(vpnStatusReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(vpnStatusReceiver)
    }

    private fun updateStatus(status: String) {
        tvStatus.text = status
        when {
            status.equals("CONNECTED", ignoreCase = true) -> {
                btnConnect.text = "DISCONNECT"
                tvStatus.setTextColor(Color.GREEN)
            }
            status.startsWith("CONNECTING", ignoreCase = true) -> {
                btnConnect.text = "CONNECTING..."
                tvStatus.setTextColor(Color.YELLOW)
            }
            else -> {
                btnConnect.text = "CONNECT"
                tvStatus.setTextColor(Color.GRAY)
            }
        }
    }
}
