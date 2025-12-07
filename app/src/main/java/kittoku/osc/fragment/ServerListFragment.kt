
package kittoku.osc.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kittoku.osc.R
import kittoku.osc.adapter.ServerListAdapter
import kittoku.osc.repository.VpnRepository

class ServerListFragment : Fragment(R.layout.fragment_server_list) {
    private lateinit var serverListAdapter: ServerListAdapter
    private lateinit var tvStatus: TextView
    private val vpnRepository = VpnRepository()

    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra("status") ?: "DISCONNECTED"
            updateStatus(status)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvStatus = view.findViewById(R.id.tvStatus)
        val serversRecyclerView = view.findViewById<RecyclerView>(R.id.servers_recycler_view)
        serversRecyclerView.layoutManager = LinearLayoutManager(context)

        vpnRepository.fetchSstpServers { servers ->
            activity?.runOnUiThread {
                serverListAdapter = ServerListAdapter(servers) { server ->
                    // Handle server click connection logic here
                }
                serversRecyclerView.adapter = serverListAdapter
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("kittoku.osc.action.VPN_STATUS_CHANGED")
        context?.registerReceiver(vpnStatusReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        context?.unregisterReceiver(vpnStatus.receiver)
    }

    private fun updateStatus(status: String) {
        tvStatus.text = status
        when (status) {
            "CONNECTED" -> tvStatus.setTextColor(Color.GREEN)
            "CONNECTING" -> tvStatus.setTextColor(Color.YELLOW)
            else -> tvStatus.setTextColor(Color.GRAY)
        }
    }
}


