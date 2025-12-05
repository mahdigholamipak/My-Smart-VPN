package kittoku.osc.model

data class VpnServer(
    val hostName: String,
    val ipAddress: String,
    val ping: Int,
    val speed: Long,
    val countryName: String,
    val countryCode: String,
    val numVpnSessions: Int,
    var score: Int = 0
)