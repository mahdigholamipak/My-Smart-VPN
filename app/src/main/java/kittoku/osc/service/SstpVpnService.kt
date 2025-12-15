package kittoku.osc.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import kittoku.osc.R
import kittoku.osc.SharedBridge
import kittoku.osc.activity.MainActivity
import kittoku.osc.control.Controller
import kittoku.osc.control.LogWriter
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getURIPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.resetReconnectionLife
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setIntPrefValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


internal const val ACTION_VPN_CONNECT = "kittoku.osc.connect"
internal const val ACTION_VPN_DISCONNECT = "kittoku.osc.disconnect"
internal const val ACTION_VPN_STATUS_CHANGED = "kittoku.osc.action.VPN_STATUS_CHANGED"

// Notification channels
internal const val NOTIFICATION_VPN_CHANNEL = "VPN_CONNECTION"  // Main VPN channel (LOW importance)
internal const val NOTIFICATION_ERROR_CHANNEL = "ERROR"
internal const val NOTIFICATION_RECONNECT_CHANNEL = "RECONNECT"
internal const val NOTIFICATION_DISCONNECT_CHANNEL = "DISCONNECT"
internal const val NOTIFICATION_CERTIFICATE_CHANNEL = "CERTIFICATE"

// Notification IDs
internal const val NOTIFICATION_VPN_ID = 100  // Foreground service notification
internal const val NOTIFICATION_ERROR_ID = 1
internal const val NOTIFICATION_RECONNECT_ID = 2
internal const val NOTIFICATION_DISCONNECT_ID = 3
internal const val NOTIFICATION_CERTIFICATE_ID = 4


internal class SstpVpnService : VpnService() {
    private lateinit var prefs: SharedPreferences
    private lateinit var listener: SharedPreferences.OnSharedPreferenceChangeListener
    private lateinit var notificationManager: NotificationManagerCompat
    internal lateinit var scope: CoroutineScope

    internal var logWriter: LogWriter? = null
    private var controller: Controller? = null

    private var jobReconnect: Job? = null
    private var jobTrafficStats: Job? = null  // For periodic traffic updates
    
    // Traffic stats tracking
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastStatsTime = 0L
    private var currentRxSpeed = 0L  // bytes/sec
    private var currentTxSpeed = 0L  // bytes/sec
    
    // Connection info for notification
    private var connectedServerName: String = ""
    private var connectedServerIp: String = ""
    private var isConnected = false

    private fun setRootState(state: Boolean) {
        setBooleanPrefValue(state, OscPrefKey.ROOT_STATE, prefs)
        broadcastVpnStatus(if (state) "CONNECTED" else "DISCONNECTED")
    }

    private fun broadcastVpnStatus(status: String) {
        val intent = Intent(ACTION_VPN_STATUS_CHANGED).putExtra("status", status)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun requestTileListening() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            TileService.requestListeningState(this,
                ComponentName(this, SstpTileService::class.java)
            )
        }
    }

    /**
     * ISSUE #3 FIX: Called by Controller when connection is truly established
     * Only now do we broadcast CONNECTED status
     */
    internal fun onConnectionEstablished() {
        logWriter?.write("Connection established successfully")
        isConnected = true
        
        // Get server info for notification
        connectedServerName = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
        
        // Update notification to show connected state
        updateNotification()
        
        // Start traffic stats monitoring
        startTrafficStatsMonitoring()
        
        setRootState(true)
    }

    override fun onCreate() {
        notificationManager = NotificationManagerCompat.from(this)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == OscPrefKey.ROOT_STATE.name) {
                val newState = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)

                setBooleanPrefValue(newState, OscPrefKey.HOME_CONNECTOR, prefs)
                requestTileListening()
            }
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        // Create notification channels
        createNotificationChannels()
    }
    
    /**
     * Create notification channels for Android 8.0+
     * Uses LOW importance for VPN channel to prevent sounds on updates
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channels = mutableListOf<NotificationChannel>()
            
            // Main VPN channel - DEFAULT importance for visibility
            channels.add(NotificationChannel(
                NOTIFICATION_VPN_CHANNEL,
                "VPN Connection",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows VPN connection status and traffic"
                setShowBadge(false)
                setSound(null, null)  // No sound on updates
            })
            
            // Error channel - DEFAULT importance
            channels.add(NotificationChannel(
                NOTIFICATION_ERROR_CHANNEL,
                "Errors",
                NotificationManager.IMPORTANCE_DEFAULT
            ))
            
            // Reconnect channel
            channels.add(NotificationChannel(
                NOTIFICATION_RECONNECT_CHANNEL,
                "Reconnection",
                NotificationManager.IMPORTANCE_LOW
            ))
            
            // Other channels
            channels.add(NotificationChannel(
                NOTIFICATION_DISCONNECT_CHANNEL,
                "Disconnect",
                NotificationManager.IMPORTANCE_LOW
            ))
            
            channels.add(NotificationChannel(
                NOTIFICATION_CERTIFICATE_CHANNEL,
                "Certificate",
                NotificationManager.IMPORTANCE_DEFAULT
            ))
            
            notificationManager.createNotificationChannels(channels)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_VPN_CONNECT -> {
                controller?.kill(false, null)
                isConnected = false
                broadcastVpnStatus("CONNECTING")

                beForegrounded()
                resetReconnectionLife(prefs)
                if (getBooleanPrefValue(OscPrefKey.LOG_DO_SAVE_LOG, prefs)) {
                    prepareLogWriter()
                }

                logWriter?.write("Establish VPN connection")

                initializeClient()

                // ISSUE #3 FIX: Do NOT set CONNECTED here!
                // The Controller will call setRootState(true) after successful handshake
                // This prevents "fake connected" state before actual connection
                
                // Only set the connector flag, not the root state
                setBooleanPrefValue(true, OscPrefKey.HOME_CONNECTOR, prefs)

                Service.START_STICKY
            }

            else -> {
                // ensure that reconnection has been completely canceled or done
                runBlocking { jobReconnect?.cancelAndJoin() }

                // Stop traffic monitoring
                stopTrafficStatsMonitoring()
                
                isConnected = false
                controller?.disconnect()
                controller = null

                close()

                Service.START_NOT_STICKY
            }
        }
    }

    private fun initializeClient() {
        controller = Controller(SharedBridge(this)).also {
            it.launchJobMain()
        }
    }

    private fun prepareLogWriter() {
        val currentDateTime = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())
        val filename = "log_osc_${currentDateTime}.txt"

        val prefURI = getURIPrefValue(OscPrefKey.LOG_DIR, prefs)
        
        // REQUIREMENT #10: Default fallback path when no directory selected
        if (prefURI == null) {
            // Use app's external files directory as fallback
            try {
                val fallbackDir = getExternalFilesDir("Logs")
                if (fallbackDir != null) {
                    if (!fallbackDir.exists()) fallbackDir.mkdirs()
                    val logFile = java.io.File(fallbackDir, filename)
                    logWriter = LogWriter(java.io.FileOutputStream(logFile))
                    logWriter?.write("Using fallback log directory: ${fallbackDir.absolutePath}")
                    return
                }
            } catch (e: Exception) {
                notifyError("LOG: Failed to use fallback directory - ${e.message}")
            }
            notifyError("LOG: ERR_NULL_PREFERENCE - Please select a log directory in Settings")
            return
        }

        val dirURI = DocumentFile.fromTreeUri(this, prefURI)
        if (dirURI == null) {
            notifyError("LOG: ERR_NULL_DIRECTORY")
            return
        }

        val fileURI = dirURI.createFile("text/plain", filename)
        if (fileURI == null) {
            notifyError("LOG: ERR_NULL_FILE")
            return
        }

        val stream = contentResolver.openOutputStream(fileURI.uri, "wa")
        if (stream == null) {
            notifyError("LOG: ERR_NULL_STREAM")
            return
        }

        logWriter = LogWriter(stream)
    }

    internal fun launchJobReconnect() {
        jobReconnect = scope.launch {
            try {
                getIntPrefValue(OscPrefKey.RECONNECTION_LIFE, prefs).also {
                    val life = it - 1
                    setIntPrefValue(life, OscPrefKey.RECONNECTION_LIFE, prefs)

                    val message = "Reconnection will be tried (LIFE = $life)"
                    notifyMessage(message, NOTIFICATION_RECONNECT_ID, NOTIFICATION_RECONNECT_CHANNEL)
                    logWriter?.report(message)
                }

                delay(getIntPrefValue(OscPrefKey.RECONNECTION_INTERVAL, prefs) * 1000L)

                initializeClient()
            } catch (_: CancellationException) { }
            finally {
                cancelNotification(NOTIFICATION_RECONNECT_ID)
            }
        }
    }

    /**
     * Start foreground service with enhanced notification
     */
    private fun beForegrounded() {
        val notification = buildNotification(isConnecting = true)
        startForeground(NOTIFICATION_VPN_ID, notification)
    }
    
    /**
     * Build the VPN notification with dynamic content
     * 
     * @param isConnecting True if still connecting, false if connected
     */
    private fun buildNotification(isConnecting: Boolean = false): Notification {
        // Intent to open MainActivity when notification is clicked
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Disconnect action button
        val disconnectIntent = Intent(this, SstpVpnService::class.java).apply {
            action = ACTION_VPN_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build notification content
        val title: String
        val content: String
        val icon: Int
        
        if (isConnecting) {
            title = "Connecting..."
            content = "Establishing secure connection"
            icon = R.drawable.ic_baseline_vpn_lock_24
        } else if (isConnected) {
            // Extract country from hostname (e.g., vpn123.opengw.net -> show server name)
            val serverDisplay = if (connectedServerName.isNotEmpty()) {
                connectedServerName.take(20)
            } else {
                "VPN Server"
            }
            title = "Connected to $serverDisplay 🔒"
            
            // Show traffic stats if available
            content = if (currentRxSpeed > 0 || currentTxSpeed > 0) {
                "↓ ${formatSpeed(currentRxSpeed)}  ↑ ${formatSpeed(currentTxSpeed)}"
            } else {
                connectedServerIp.ifEmpty { "Secure connection active" }
            }
            icon = R.drawable.ic_baseline_vpn_lock_24
        } else {
            title = "VPN Disconnected"
            content = "Tap to reconnect"
            icon = R.drawable.ic_baseline_vpn_lock_24
        }
        
        return NotificationCompat.Builder(this, NOTIFICATION_VPN_CHANNEL)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)  // Prevent sound/vibration on updates
            .setContentIntent(openAppPendingIntent)
            .addAction(
                R.drawable.ic_baseline_close_24,
                "Disconnect",
                disconnectPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)  // Higher priority for visibility
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)  // Android 12+
            .build()
    }
    
    /**
     * Update the notification with current connection info
     */
    internal fun updateNotification() {
        val notification = buildNotification(isConnecting = false)
        tryNotify(notification, NOTIFICATION_VPN_ID)
    }
    
    /**
     * Start periodic traffic stats monitoring
     * Updates notification every 2 seconds with download/upload speeds
     */
    private fun startTrafficStatsMonitoring() {
        // Initialize baseline
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        lastStatsTime = System.currentTimeMillis()
        
        jobTrafficStats = scope.launch {
            while (isConnected) {
                delay(2000)  // Update every 2 seconds
                
                try {
                    val currentRxBytes = TrafficStats.getTotalRxBytes()
                    val currentTxBytes = TrafficStats.getTotalTxBytes()
                    val currentTime = System.currentTimeMillis()
                    
                    val timeDelta = (currentTime - lastStatsTime) / 1000.0  // seconds
                    if (timeDelta > 0) {
                        currentRxSpeed = ((currentRxBytes - lastRxBytes) / timeDelta).toLong()
                        currentTxSpeed = ((currentTxBytes - lastTxBytes) / timeDelta).toLong()
                    }
                    
                    lastRxBytes = currentRxBytes
                    lastTxBytes = currentTxBytes
                    lastStatsTime = currentTime
                    
                    // Update notification with new speeds
                    if (isConnected) {
                        updateNotification()
                    }
                } catch (e: Exception) {
                    // Ignore traffic stats errors
                }
            }
        }
    }
    
    /**
     * Stop traffic stats monitoring
     */
    private fun stopTrafficStatsMonitoring() {
        jobTrafficStats?.cancel()
        jobTrafficStats = null
        currentRxSpeed = 0
        currentTxSpeed = 0
    }
    
    /**
     * Format bytes/sec to human readable speed (KB/s, MB/s)
     */
    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec < 1024 -> "$bytesPerSec B/s"
            bytesPerSec < 1024 * 1024 -> "${bytesPerSec / 1024} KB/s"
            else -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        }
    }

    internal fun notifyMessage(message: String, id: Int, channel: String) {
        NotificationCompat.Builder(this, channel).also {
            it.setSmallIcon(R.drawable.ic_baseline_vpn_lock_24)
            it.setContentText(message)
            it.priority = NotificationCompat.PRIORITY_DEFAULT
            it.setAutoCancel(true)

            tryNotify(it.build(), id)
        }
    }

    internal fun notifyError(message: String) {
        // DON'T create separate notification - only broadcast for HomeFragment to handle
        // This prevents multiple notifications cluttering the notification shade
        logWriter?.report("ERROR: $message")
        broadcastVpnStatus("ERROR: $message")
    }

    internal fun tryNotify(notification: Notification, id: Int) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify(id, notification)
        }
    }

    internal fun cancelNotification(id: Int) {
        notificationManager.cancel(id)
    }

    internal fun close() {
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        logWriter?.write("Terminate VPN connection")
        logWriter?.close()
        logWriter = null

        stopTrafficStatsMonitoring()
        
        controller?.kill(false, null)
        controller = null

        scope.cancel()

        isConnected = false
        setRootState(false)
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
