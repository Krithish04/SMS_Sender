# SMS OTP Gateway

A lightweight Android-based SMS Gateway that turns your phone into an API-driven SMS sender. Useful for local project testing, automated OTP delivery, and custom SMS alerts.

## 🚀 Features
- **REST API**: Send SMS via simple POST requests.
- **AndroidX Compatible**: Built with modern Android standards.
- **Foreground Service**: Runs reliably in the background.
- **Auto-Start**: Automatically starts when the app opens or the phone boots up.
- **SIM Discovery**: Supports multi-SIM devices with automatic default SIM detection.
- **Status Logs**: Real-time logging of sent, delivered, and failed messages.
- **Security**: Protected by a customizable API Key.

## 🛠️ Setup & Installation

1. **Clone the project** into Android Studio.
2. **Build and Run** on an Android device (Physical device recommended for SMS functionality).
3. **Grant Permissions**: Allow SMS, Phone, and Notification permissions when prompted.
4. **Configure API Key**: Set your secret key in `GatewayConfig.kt`.
   ```kotlin
   const val API_KEY = "your_secret_key_here"
   ```

## 📡 API Usage

The gateway listens on the port defined in `GatewayConfig.kt` (default is `8080`).

### Send SMS / OTP
**Endpoint:** `POST http://<YOUR_PHONE_IP>:8080/send-otp`  
**Header:** `x-api-key: <YOUR_API_KEY>`

**Request Body (JSON):**
```json
{
  "phone": "+919428141156",
  "otp": "123456",
  "message": "Your code is 123456" 
}
```
*Note: If `message` is omitted, it defaults to "Your code is {otp}".*

## ⚠️ Troubleshooting "Generic Failure"

If you see "Generic failure" in the logs:
1. **Balance**: Ensure the SIM has an active SMS plan/balance.
2. **Keyword Filtering**: Some carriers block the word "OTP". Try using "code" or "PIN" instead.
3. **Dual SIM**: Ensure the correct SIM is set as "Default for SMS" in Android Settings.
4. **Permissions**: Ensure "READ_PHONE_STATE" and "SEND_SMS" are granted.

## 🔒 Security
Keep your phone on a secure local network. Since this gateway exposes an HTTP endpoint, anyone on the same Wi-Fi with your API Key can send SMS via your phone.

## 📄 License
This project is created for educational and testing purposes.
