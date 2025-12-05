package kittoku.osc.repository

import kittoku.osc.model.VpnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader

class VpnRepository {
    private val client = OkHttpClient()
    private val CSV_URL = "https://raw.githubusercontent.com/mahdigholamipak/vpn-list-mirror/refs/heads/main/server_list.csv"

    suspend fun getVpnServers(): List<VpnServer> {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(CSV_URL).build()
            val response = client.newCall(request).execute()
            val servers = mutableListOf<VpnServer>()

            if (response.isSuccessful) {
                val inputStream = response.body?.byteStream()
                val reader = BufferedReader(InputStreamReader(inputStream))
                reader.useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val columns = line.split(",")
                        if (columns.size > 12 && columns[12] == "1" && columns[6] != "IR") {
                            try {
                                val server = VpnServer(
                                    hostName = columns[0],
                                    ipAddress = columns[1],
                                    ping = columns[3].toIntOrNull() ?: 9999,
                                    speed = columns[4].toLongOrNull() ?: 0,
                                    countryName = columns[5],
                                    countryCode = columns[6],
                                    numVpnSessions = columns[7].toIntOrNull() ?: 0
                                )
                                server.score = (server.numVpnSessions * 2) + (server.speed / 100000) - (server.ping * 5)
                                servers.add(server)
                            } catch (e: Exception) {
                                // Ignore malformed lines
                            }
                        }
                    }
                }
            }

            servers.sortedByDescending { it.score }
        }
    }
}