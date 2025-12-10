package kittoku.osc.adapter

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
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
        private val cardView: MaterialCardView? = itemView.findViewById(R.id.card_server)

        fun bind(server: SstpServer, isConnected: Boolean) {
            // ISSUE #3 FIX: Hostname as main title for unique identification
            // Truncate long hostnames for readability
            val displayName = server.hostName.let {
                if (it.length > 25) it.take(22) + "..." else it
            }
            tvDetails.text = displayName
            
            // Country as subtitle (secondary info)
            tvHostname.text = "${server.country} (${server.countryCode})"
            
            // Simplified speed display
            tvScoreSpeed.text = String.format(
                Locale.getDefault(), 
                "%.1f Mbps", 
                server.speed / 1_000_000.0
            )

            // ISSUE #4 FIX: Load country flag with proper error handling
            try {
                val flagUrl = "https://flagcdn.com/w40/${server.countryCode.lowercase()}.png"
                Picasso.get()
                    .load(flagUrl)
                    .placeholder(android.R.drawable.ic_menu_mapmode)
                    .error(android.R.drawable.ic_menu_mapmode)
                    .into(ivFlag)
            } catch (e: Exception) {
                ivFlag.setImageResource(android.R.drawable.ic_menu_mapmode)
            }
            
            // Highlight connected server
            if (isConnected) {
                cardView?.setCardBackgroundColor(Color.parseColor("#1B3D1B")) // Dark green
                tvDetails.setTextColor(Color.parseColor("#4CAF50")) // Green title
                tvPing.text = "●"
                tvPing.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                cardView?.setCardBackgroundColor(Color.parseColor("#2D2D44")) // Default dark
                tvDetails.setTextColor(Color.WHITE)
                
                // Display real-time measured ping with clear status
                when {
                    server.realPing == -1L -> {
                        tvPing.text = "—"
                        tvPing.setTextColor(Color.parseColor("#666666"))
                    }
                    server.realPing == 0L -> {
                        tvPing.text = "..."
                        tvPing.setTextColor(Color.parseColor("#888888"))
                    }
                    else -> {
                        tvPing.text = "${server.realPing}"
                        // Color code by latency for quick visual scanning
                        val color = when {
                            server.realPing < 100 -> "#4CAF50"  // Green - Excellent
                            server.realPing < 200 -> "#8BC34A"  // Light Green - Good
                            server.realPing < 500 -> "#FF9800"  // Orange - Fair
                            else -> "#F44336"                   // Red - Poor
                        }
                        tvPing.setTextColor(Color.parseColor(color))
                    }
                }
            }

            itemView.setOnClickListener { onServerClick(server) }
        }
    }
}
