package com.example.smsgateway

object GatewayConfig {
    const val PORT = 8080
    const val API_KEY = "MY_SECRET_KEY" // In a real app, this should be configurable or more secure
    
    // Rate limiting settings
    const val MAX_SMS_PER_MINUTE = 5
    const val MAX_OTP_PER_PHONE_5MIN = 3
}
