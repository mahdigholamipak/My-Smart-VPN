package kittoku.osc.adapter

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import kittoku.osc.R
import kittoku.osc.repository.SstpServer
import java.util.Locale

class ServerListAdapter(
    private var servers: MutableList<SstpServer>,
    private val onServerClick: (SstpServer) -> Unit
) : RecyclerView.Adapter<ServerListAdapter.ViewHolder>() {
    
    // Track currently connected server hostname for highlighting
    private var connectedHostname: String? = null
    
    // All servers (unfiltered) for country filtering
    private var allServers: List<SstpServer> = emptyList()
    
    // Current country filter (null = all countries)
    private var currentCountryFilter: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_server_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = servers[position]
        holder.bind(server, server.hostName == connectedHostname)
    }

    override fun getItemCount() = servers.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(newServers: List<SstpServer>) {
        allServers = newServers
        servers.clear()
        
        // Apply current filter
        if (currentCountryFilter != null) {
            servers.addAll(newServers.filter { it.countryCode == currentCountryFilter })
        } else {
            servers.addAll(newServers)
        }
        notifyDataSetChanged()
    }
    
    /**
     * Set the currently connected server hostname for highlighting
     */
    @SuppressLint("NotifyDataSetChanged")
    fun setConnectedServer(hostname: String?) {
        connectedHostname = hostname
        notifyDataSetChanged()
    }
    
    /**
     * Get unique countries from the server list for filtering
     */
    fun getUniqueCountries(): List<Pair<String, String>> {
        return allServers
            .map { it.countryCode to it.country }
            .distinct()
            .sortedBy { it.second }
    }
    
    /**
     * Filter servers by country code
     * @param countryCode Country code to filter by, or null to show all
     */
    @SuppressLint("NotifyDataSetChanged")
    fun filterByCountry(countryCode: String?) {
        currentCountryFilter = countryCode
        servers.clear()
        
        if (countryCode == null) {
            servers.addAll(allServers)
        } else {
            servers.addAll(allServers.filter { it.countryCode == countryCode })
        }
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPing: TextView = itemView.findViewById(R.id.tv_ping)
        private val ivFlag: ImageView = itemView.findViewById(R.id.iv_flag)
        private val tvHostname: TextView = itemView.findViewById(R.id.tv_hostname)
        private val tvDetails: TextView = itemView.findViewById(R.id.tv_details)
        private val tvScoreSpeed: TextView = itemView.findViewById(R.id.tv_score_speed)
        private val cardView: CardView? = itemView.findViewById(R.id.card_server)

        fun bind(server: SstpServer, isConnected: Boolean) {
            tvPing.text = "${server.ping} ms"
            tvHostname.text = server.hostName
            tvDetails.text = "${server.countryCode} - ${server.country}"
            
            // Show score for smart failover info
            tvScoreSpeed.text = String.format(
                Locale.getDefault(), 
                "Score: %,d | %.2f Mbps", 
                server.score, 
                server.speed / 1_000_000.0
            )

            val flagUrl = "https://www.vpn-gate.net/images/flags/${server.countryCode}.png"
            Picasso.get().load(flagUrl).into(ivFlag)
            
            // Highlight connected server
            if (isConnected) {
                cardView?.setCardBackgroundColor(Color.parseColor("#1B3D1B")) // Dark green
                tvHostname.setTextColor(Color.parseColor("#4CAF50")) // Green
                tvPing.text = "● Connected"
                tvPing.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                cardView?.setCardBackgroundColor(Color.parseColor("#2D2D44")) // Default dark
                tvHostname.setTextColor(Color.WHITE)
                tvPing.text = "${server.ping} ms"
                tvPing.setTextColor(Color.parseColor("#888888"))
            }

            itemView.setOnClickListener { onServerClick(server) }
        }
    }
}
