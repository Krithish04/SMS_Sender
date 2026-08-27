package com.example.smsgateway

object GatewayConfig {
    const val PORT = 8080
    const val API_KEY = "sms_gateway_secret_7f8a2b9c3d1e4f5a" // Generated secure key
    
    // Rate limiting settings
    const val MAX_SMS_PER_MINUTE = 5
    const val MAX_OTP_PER_PHONE_5MIN = 3
}
