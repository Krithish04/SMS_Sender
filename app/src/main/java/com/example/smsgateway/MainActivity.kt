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
import android.text.Editable
import android.text.TextWatcher
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

        // Load and Save Public URL
        val prefs = getSharedPreferences("gateway_prefs", Context.MODE_PRIVATE)
        binding.etPublicUrl.setText(prefs.getString("public_url", ""))
        
        binding.etPublicUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("public_url", s.toString()).apply()
            }
        })

        Intent(this, SmsService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        // Auto-start gateway if permissions are already granted
        if (checkPermissions()) {
            startGateway()
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

        // Generate a random 6-digit code
        val randomCode = (100000..999999).random()

        val etPhone = EditText(this).apply { hint = "Phone (+91...)" }
        val etMessage = EditText(this).apply { 
            hint = "Message"
            setText("Your code is $randomCode")
        }
        
        layout.addView(etPhone)
        layout.addView(etMessage)

        AlertDialog.Builder(this)
            .setTitle("Test SMS (Random Code)")
            .setView(layout)
            .setPositiveButton("Send") { _, _ ->
                val phone = etPhone.text.toString().trim()
                val message = etMessage.text.toString().trim()
                sendTestSms(phone, message)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendTestSms(phone: String, message: String) {
        if (phone.isEmpty() || message.isEmpty()) {
            Toast.makeText(this, "Please enter phone and message", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (isBound) {
            smsService?.sendSms(phone, message)
            addLog("Requested to send '$message' to $phone")
        } else {
            addLog("Error: Service not bound")
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
