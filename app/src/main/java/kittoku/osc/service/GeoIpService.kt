package kittoku.osc.service

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Service for fetching real IP and geolocation on VPN connection
 * Uses ip-api.com (free, no API key required)
 */
object GeoIpService {
    private const val TAG = "GeoIpService"
    private const val API_URL = "http://ip-api.com/json"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    /**
     * Geographic information for the current connection
     */
    data class GeoInfo(
        val ip: String,
        val country: String,
        val countryCode: String,
        val city: String,
        val region: String,
        val isp: String,
        val org: String,
        val lat: Double,
        val lon: Double
    ) {
        /**
         * Get formatted location string
         */
        fun getLocationString(): String {
            return if (city.isNotBlank() && country.isNotBlank()) {
                "$city, $country"
            } else if (country.isNotBlank()) {
                country
            } else {
                "Unknown"
            }
        }
    }
    
    /**
     * Fetch geolocation info asynchronously
     * Called after successful VPN connection to display real IP/location
     * 
     * @param onResult Callback with GeoInfo (or null on failure)
     */
    fun fetchGeoInfo(onResult: (GeoInfo?) -> Unit) {
        Thread {
            try {
                val request = Request.Builder()
                    .url(API_URL)
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "GeoIP request failed: ${response.code}")
                    onResult(null)
                    return@Thread
                }
                
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    Log.e(TAG, "Empty response from GeoIP API")
                    onResult(null)
                    return@Thread
                }
                
                val json = JSONObject(body)
                
                if (json.optString("status") != "success") {
                    Log.e(TAG, "GeoIP API returned failure: ${json.optString("message")}")
                    onResult(null)
                    return@Thread
                }
                
                val geoInfo = GeoInfo(
                    ip = json.optString("query", ""),
                    country = json.optString("country", ""),
                    countryCode = json.optString("countryCode", ""),
                    city = json.optString("city", ""),
                    region = json.optString("regionName", ""),
                    isp = json.optString("isp", ""),
                    org = json.optString("org", ""),
                    lat = json.optDouble("lat", 0.0),
                    lon = json.optDouble("lon", 0.0)
                )
                
                Log.d(TAG, "GeoIP result: ${geoInfo.ip} in ${geoInfo.getLocationString()}")
                onResult(geoInfo)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch GeoIP", e)
                onResult(null)
            }
        }.start()
    }
    
    /**
     * Fetch just the IP address (lighter weight call)
     */
    fun fetchIpOnly(onResult: (String?) -> Unit) {
        fetchGeoInfo { geoInfo ->
            onResult(geoInfo?.ip)
        }
    }
}
