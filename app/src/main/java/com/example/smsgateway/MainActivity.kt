package com.example.smsgateway

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telephony.SmsManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.smsgateway.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var smsService: SmsService? = null
    private var isBound = false
    private var smsSentCount = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SmsService.LocalBinder
            smsService = binder.getService()
            isBound = true
            
            smsService?.logListener = { message ->
                runOnUiThread { addLog(message) }
            }
            smsService?.statusListener = { running ->
                runOnUiThread { updateUi(running) }
            }
            
            updateUi(smsService?.isServerRunning() ?: false)
            binding.tvIp.text = smsService?.getIpAddress() ?: "0.0.0.0"
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (resultCode) {
                Activity.RESULT_OK -> addLog("SMS Sent successfully")
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> addLog("SMS Failed: Generic failure")
                SmsManager.RESULT_ERROR_NO_SERVICE -> addLog("SMS Failed: No service")
                SmsManager.RESULT_ERROR_NULL_PDU -> addLog("SMS Failed: Null PDU")
                SmsManager.RESULT_ERROR_RADIO_OFF -> addLog("SMS Failed: Radio off")
                else -> addLog("SMS Failed: Unknown error (code $resultCode)")
            }
        }
    }

    private val smsDeliveredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (resultCode) {
                Activity.RESULT_OK -> addLog("SMS Delivered successfully")
                Activity.RESULT_CANCELED -> addLog("SMS Delivery failed")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filterSent = IntentFilter("SMS_SENT")
        val filterDelivered = IntentFilter("SMS_DELIVERED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, filterSent, Context.RECEIVER_EXPORTED)
            registerReceiver(smsDeliveredReceiver, filterDelivered, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(smsSentReceiver, filterSent)
            registerReceiver(smsDeliveredReceiver, filterDelivered)
        }

        binding.btnStart.setOnClickListener {
            if (checkPermissions()) {
                startGateway()
            } else {
                requestPermissions()
            }
        }

        binding.btnStop.setOnClickListener {
            stopGateway()
        }

        binding.btnTestSms.setOnClickListener {
            showTestSmsDialog()
        }

        Intent(this, SmsService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun checkPermissions(): Boolean {
        val sendSms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
        val internet = ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)
        val readPhoneState = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
        return sendSms == PackageManager.PERMISSION_GRANTED && 
               internet == PackageManager.PERMISSION_GRANTED &&
               readPhoneState == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.SEND_SMS, 
                Manifest.permission.INTERNET,
                Manifest.permission.READ_PHONE_STATE
            ),
            101
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startGateway()
            } else {
                Toast.makeText(this, "SMS Permission is required for the gateway to work", Toast.LENGTH_LONG).show()
                addLog("Permission denied: SEND_SMS")
            }
        }
    }

    private fun startGateway() {
        val intent = Intent(this, SmsService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopGateway() {
        val intent = Intent(this, SmsService::class.java).apply { action = "STOP" }
        startService(intent)
    }

    private fun updateUi(running: Boolean) {
        binding.tvStatus.text = if (running) "Running" else "Stopped"
        binding.tvStatus.setTextColor(if (running) 0xFF00FF00.toInt() else 0xFFFF0000.toInt())
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        if (running) {
            binding.tvIp.text = smsService?.getIpAddress() ?: "0.0.0.0"
        }
    }

    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val currentLogs = binding.tvLogs.text.toString()
        binding.tvLogs.text = "[$time] $message\n$currentLogs"
        
        if (message.contains("SMS sent")) {
            smsSentCount++
            binding.tvSentCount.text = smsSentCount.toString()
        }
    }

    private fun showTestSmsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
        }

        val etPhone = EditText(this).apply { hint = "Phone (+91...)" }
        val etOtp = EditText(this).apply { hint = "OTP (e.g. 123456)" }
        
        layout.addView(etPhone)
        layout.addView(etOtp)

        AlertDialog.Builder(this)
            .setTitle("Test SMS")
            .setView(layout)
            .setPositiveButton("Send") { _, _ ->
                val phone = etPhone.text.toString()
                val otp = etOtp.text.toString()
                sendTestSms(phone, otp)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendTestSms(phone: String, otp: String) {
        if (phone.isEmpty() || otp.isEmpty()) {
            Toast.makeText(this, "Please enter phone and OTP", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                this.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }

            val sentIntent = PendingIntent.getBroadcast(this, 0, Intent("SMS_SENT"), PendingIntent.FLAG_IMMUTABLE)
            val deliveredIntent = PendingIntent.getBroadcast(this, 0, Intent("SMS_DELIVERED"), PendingIntent.FLAG_IMMUTABLE)

            smsManager.sendTextMessage(phone, null, "Your OTP is $otp", sentIntent, deliveredIntent)
            addLog("Sending Test SMS to ${phone.take(5)}***")
        } catch (e: Exception) {
            addLog("Test SMS error: ${e.message}")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        try {
            unregisterReceiver(smsSentReceiver)
            unregisterReceiver(smsDeliveredReceiver)
        } catch (e: Exception) {}
        super.onDestroy()
    }
}
