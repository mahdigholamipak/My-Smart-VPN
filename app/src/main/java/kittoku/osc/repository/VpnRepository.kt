package kittoku.osc.repository

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// مدل داده اصلاح شده (همه اعداد بزرگ Long شدند)
data class SstpServer(
    val hostName: String,
    val ip: String,
    val country: String,
    val speed: Long,      // قبلاً شاید Int بود که باعث ارور می‌شد
    val sessions: Long,   // این هم Long شد
    val ping: Int,
    val isSstp: Boolean
)

class VpnRepository {
    private val client = OkHttpClient()
    // لینک لیست سرورهای تو
    private val SERVER_URL = "https://raw.githubusercontent.com/mahdigholamipak/vpn-list-mirror/refs/heads/main/server_list.csv"

    fun fetchSstpServers(onResult: (List<SstpServer>) -> Unit) {
        val request = Request.Builder().url(SERVER_URL).build()

        Thread {
            try {
                val response = client.newCall(request).execute()
                val csvData = response.body?.string()
                if (csvData != null) {
                    val servers = parseCsv(csvData)
                    onResult(servers)
                }
            } catch (e: IOException) { e.printStackTrace() }
        }.start()
    }

    private fun parseCsv(data: String): List<SstpServer> {
        val servers = mutableListOf<SstpServer>()
        val lines = data.split("\n")

        for (line in lines) {
            // رد کردن خطوط خالی یا کامنت
            if (line.startsWith("#") || line.isEmpty() || line.startsWith("*")) continue

            val p = line.split(",")

            if (p.size > 12) {
                try {
                    // ستون 12 = SSTP (1 یعنی دارد)
                    // ستون 6 = کد کشور
                    val isSstp = p.size > 12 && p[12] == "1"
                    val countryCode = if (p.size > 6) p[6] else ""

                    // فیلتر: حذف ایران و فقط SSTP
                    if (isSstp && !countryCode.equals("IR", ignoreCase = true)) {

                        // تبدیل ایمن اعداد
                        val speedVal = p[4].toLongOrNull() ?: 0L
                        val sessionsVal = p[7].toLongOrNull() ?: 0L
                        val pingVal = p[3].toIntOrNull() ?: 999

                        servers.add(SstpServer(
                            hostName = p[0],
                            ip = p[1],
                            country = p[5],
                            speed = speedVal,
                            sessions = sessionsVal, // اینجا دلیل ارور قبلی بود
                            ping = pingVal,
                            isSstp = true
                        ))
                    }
                } catch (e: Exception) {
                    // نادیده گرفتن خطوط خراب
                }
            }
        }
        // سورت بر اساس (تعداد سشن بالا + پینگ پایین)
        // اولویت با سشن است چون پایداری را نشان می‌دهد
        return servers.sortedWith(compareByDescending<SstpServer> { it.sessions }.thenBy { it.ping })
    }
}