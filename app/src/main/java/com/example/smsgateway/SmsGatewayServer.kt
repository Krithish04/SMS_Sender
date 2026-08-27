package com.example.smsgateway

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SmsGatewayServer(private val service: SmsService, port: Int, private val logListener: (String) -> Unit) : NanoHTTPD(port) {

    private val gson = Gson()
    private val smsRateLimiter = AtomicInteger(0)
    private val phoneRateLimit = ConcurrentHashMap<String, MutableList<Long>>()
    private var lastRateResetTime = System.currentTimeMillis()

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.POST && session.uri == "/send-otp") {
            return handleSendOtp(session)
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"success\": false, \"message\": \"Endpoint not found\"}")
    }

    private fun handleSendOtp(session: IHTTPSession): Response {
        // API Key Check
        val apiKey = session.headers["x-api-key"]
        if (apiKey != GatewayConfig.API_KEY) {
            logListener("Invalid API key attempt")
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"success\": false, \"message\": \"Invalid API key\"}")
        }

        // Parse Body
        val map = HashMap<String, String>()
        try {
            session.parseBody(map)
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\": false, \"message\": \"Failed to parse request\"}")
        }
        
        val postData = map["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\": false, \"message\": \"Empty body\"}")
        
        return try {
            val json = gson.fromJson(postData, JsonObject::class.java)
            val phone = json.get("phone")?.asString ?: ""
            val otp = json.get("otp")?.asString ?: ""

            if (!isValidPhone(phone)) {
                return newErrorResponse("Invalid phone number")
            }
            if (!isValidOtp(otp)) {
                return newErrorResponse("Invalid OTP (must be 4-8 digits)")
            }

            if (checkRateLimit(phone)) {
                service.sendSms(phone, "Your OTP is $otp")
                logListener("SMS request queued for ${phone.take(5)}***")
                newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\": true, \"message\": \"SMS request accepted\"}")
            } else {
                logListener("Rate limit exceeded for $phone")
                newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, "application/json", "{\"success\": false, \"message\": \"Rate limit exceeded\"}")
            }
        } catch (e: Exception) {
            newErrorResponse("Error processing request: ${e.message}")
        }
    }

    private fun isValidPhone(phone: String): Boolean {
        return phone.matches(Regex("^\\+?[1-9]\\d{1,14}$"))
    }

    private fun isValidOtp(otp: String): Boolean {
        return otp.matches(Regex("^\\d{4,8}$"))
    }

    private fun checkRateLimit(phone: String): Boolean {
        val now = System.currentTimeMillis()
        
        // Minute rate limit
        if (now - lastRateResetTime > 60000) {
            smsRateLimiter.set(0)
            lastRateResetTime = now
        }
        if (smsRateLimiter.incrementAndGet() > GatewayConfig.MAX_SMS_PER_MINUTE) {
            return false
        }

        // Phone specific rate limit (5 mins)
        val history = phoneRateLimit.getOrPut(phone) { mutableListOf() }
        history.removeAll { now - it > 5 * 60000 }
        if (history.size >= GatewayConfig.MAX_OTP_PER_PHONE_5MIN) {
            return false
        }
        history.add(now)
        return true
    }

    private fun newErrorResponse(message: String): Response {
        val json = JsonObject()
        json.addProperty("success", false)
        json.addProperty("message", message)
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", json.toString())
    }
}
