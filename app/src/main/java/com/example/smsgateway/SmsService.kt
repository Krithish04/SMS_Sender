package com.example.smsgateway

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class SmsService : Service() {

    private var server: SmsGatewayServer? = null
    private val binder = LocalBinder()
    private var isRunning = false
    
    var logListener: ((String) -> Unit)? = null
    var statusListener: ((Boolean) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): SmsService = this@SmsService
    }

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val phone = intent?.getStringExtra("phone") ?: "unknown"
            when (resultCode) {
                Activity.RESULT_OK -> logListener?.invoke("SMS to $phone: Sent successfully")
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> logListener?.invoke("SMS to $phone Failed: Generic failure")
                SmsManager.RESULT_ERROR_NO_SERVICE -> logListener?.invoke("SMS to $phone Failed: No service")
                SmsManager.RESULT_ERROR_NULL_PDU -> logListener?.invoke("SMS to $phone Failed: Null PDU")
                SmsManager.RESULT_ERROR_RADIO_OFF -> logListener?.invoke("SMS to $phone Failed: Radio off")
                else -> logListener?.invoke("SMS to $phone Failed: Error code $resultCode")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val filter = IntentFilter("SMS_SENT_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(smsSentReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            stopForegroundService()
        } else {
            startForegroundService()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        if (isRunning) return

        val notification = createNotification("SMS Gateway is running")
        startForeground(1, notification)

        try {
            server = SmsGatewayServer(this, GatewayConfig.PORT) { message ->
                logListener?.invoke(message)
            }
            server?.start()
            isRunning = true
            statusListener?.invoke(true)
            logListener?.invoke("Gateway started on port ${GatewayConfig.PORT}")
        } catch (e: Exception) {
            logListener?.invoke("Failed to start gateway: ${e.message}")
            stopForegroundService()
        }
    }

    private fun stopForegroundService() {
        server?.stop()
        server = null
        isRunning = false
        statusListener?.invoke(false)
        logListener?.invoke("Gateway stopped")
        stopForeground(true)
        stopSelf()
    }

    fun sendSms(phone: String, message: String) {
        try {
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            
            val subId = if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
                // Log all available SIM IDs for debugging
                activeSubscriptions?.forEach { info ->
                    logListener?.invoke("Found SIM: ${info.displayName} (ID: ${info.subscriptionId})")
                }
                activeSubscriptions?.firstOrNull()?.subscriptionId ?: -1
            } else {
                -1
            }

            sendSmsWithSubId(phone, message, subId)
        } catch (e: Exception) {
            logListener?.invoke("SMS Error: ${e.message}")
        }
    }

    private fun sendSmsWithSubId(phone: String, message: String, subId: Int) {
        try {
            val smsManager: SmsManager = if (subId != -1) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    this.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getSmsManagerForSubscriptionId(subId)
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    this.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
            }

            val sentIntent = PendingIntent.getBroadcast(
                this, 
                System.currentTimeMillis().toInt(), // Unique request code per message
                Intent("SMS_SENT_ACTION").apply { putExtra("phone", phone) }, 
                PendingIntent.FLAG_IMMUTABLE
            )

            // Split message if it's too long (though OTPs usually aren't)
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                val sentIntents = ArrayList<PendingIntent>()
                for (i in parts.indices) sentIntents.add(sentIntent)
                smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, null)
                logListener?.invoke("Sending Multipart via SubID: ${if (subId == -1) "Default" else subId}")
            } else {
                smsManager.sendTextMessage(phone, null, message, sentIntent, null)
                logListener?.invoke("Sending via SubID: ${if (subId == -1) "Default" else subId}")
            }
        } catch (e: Exception) {
            logListener?.invoke("SubID $subId Error: ${e.message}")
        }
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, SmsService::class.java).apply { action = "STOP" }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "sms_gateway_channel")
            .setContentTitle("SMS OTP Gateway")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sms_gateway_channel",
                "SMS Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun getIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
        return "No Wi-Fi"
    }

    fun isServerRunning() = isRunning

    override fun onDestroy() {
        server?.stop()
        try {
            unregisterReceiver(smsSentReceiver)
        } catch (e: Exception) {}
        super.onDestroy()
    }
}
