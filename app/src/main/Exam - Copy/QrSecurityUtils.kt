package com.edukreasi.Exam

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

object QrSecurityUtils {
    private const val SECRET_KEY = "683ca9c4102eabfe624c83a63a2965b5cda858de6397c9ef27c96257643269b4"
    private const val HMAC_KEY = "fe0859687d7d4b78b3b7fd133ee0448091727bea83d15e2c67d8bd7ee00effb5"

    /**
     * Decode QR Code dengan dukungan format Compact (baru) dan AES (lama)
     */
    fun decodeEdukreasiQr(scannedText: String): JSONObject? {
        try {
            if (scannedText.isNullOrBlank()) return null

            return when {
                scannedText.startsWith("EDUKREASI-ONLAN|") -> {
                    decodeCompactToken(scannedText.substring(16), "on-lan")
                }
                scannedText.startsWith("EDUKREASI-OFFLINE|") -> {
                    decodeCompactToken(scannedText.substring(18), "semi-offline")
                }
                scannedText.startsWith("EDUKREASI|") -> {
                    // Coba compact dulu, jika gagal coba AES (legacy)
                    val token = scannedText.substring(10)
                    decodeCompactToken(token, null) ?: decodeAesQr(scannedText)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e("QrSecurity", "Global Decode Error: ${e.message}")
            return null
        }
    }

    /**
     * Decode format baru (Compact Format)
     * Format: Base64Url(payload~signature)
     */
    private fun decodeCompactToken(token: String, expectedMode: String?): JSONObject? {
        return try {
            Log.d("QrSecurity", "decodeCompactToken called with expectedMode: $expectedMode")
            Log.d("QrSecurity", "Token: $token")
            
            // 1. Decode Base64 URL Safe
            val decoded = String(Base64.decode(token, Base64.URL_SAFE), StandardCharsets.UTF_8)
            Log.d("QrSecurity", "Decoded: $decoded")
            
            // 2. Split payload dan signature (pemisah '~')
            val parts = decoded.split("~")
            if (parts.size != 2) {
                Log.e("QrSecurity", "Invalid format - expected 2 parts but got ${parts.size}")
                return null
            }
            
            val payload = parts[0]
            val receivedSig = parts[1]
            Log.d("QrSecurity", "Payload: $payload, Signature: $receivedSig")

            // 3. Verifikasi Signature (8 karakter pertama dari HMAC-SHA256)
            val sha256HMAC = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(SECRET_KEY.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
            sha256HMAC.init(secretKey)
            val fullSig = sha256HMAC.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val computedSig = fullSig.substring(0, 8)
            Log.d("QrSecurity", "Computed Signature: $computedSig")

            if (computedSig != receivedSig) {
                Log.e("QrSecurity", "Compact Signature Mismatch! Expected $computedSig but got $receivedSig")
                return null
            }

            // 4. Parse Payload (Format: mode:data1:data2...)
            // Payload format:
            // - L:IP:PORT → modeChar="L", ipPort="IP:PORT"
            // - S:NPSN:SCHOOL → modeChar="S", npsn="NPSN", school="SCHOOL"
            
            val payloadParts = payload.split(":")
            Log.d("QrSecurity", "Payload parts: ${payloadParts.joinToString(",")}")
            
            val modeChar = payloadParts.getOrNull(0)
            
            if (modeChar == null || modeChar.isEmpty()) {
                Log.e("QrSecurity", "Invalid payload - no mode character")
                return null
            }
            
            val result = JSONObject()

            when (modeChar) {
                "L" -> { // ON LAN mode
                    // Payload untuk L adalah "L:IP:PORT", jadi ambil semua setelah "L:"
                    // Jika payload = "L:192.168.1.100:3000", maka ipPort = "192.168.1.100:3000"
                    val ipPort = if (payloadParts.size >= 2) {
                        payload.substring(2) // Ambil semua setelah "L:"
                    } else {
                        ""
                    }
                    result.put("mode", "on-lan")
                    result.put("url", "http://$ipPort/#/login")
                    Log.d("QrSecurity", "ON LAN mode detected, URL: http://$ipPort/#/login")
                }
                "S" -> { // SEMI OFFLINE mode
                    result.put("mode", "semi-offline")
                    // Payload untuk S adalah "S:NPSN:SCHOOL"
                    // payloadParts[0]="S", payloadParts[1]="NPSN", payloadParts[2]="SCHOOL"
                    result.put("npsn", payloadParts.getOrNull(1) ?: "")
                    result.put("school", payloadParts.getOrNull(2) ?: "Sekolah")
                    result.put("api", "https://exam-edukreasi-api.edukreasi.workers.dev/api")
                    Log.d("QrSecurity", "SEMI OFFLINE mode detected, NPSN: ${result.optString("npsn")}, School: ${result.optString("school")}")
                }
                else -> {
                    Log.e("QrSecurity", "Unknown mode character: $modeChar")
                    return null
                }
            }

            result
        } catch (e: Exception) {
            Log.e("QrSecurity", "decodeCompactToken error: ${e.message}", e)
            null
        }
    }

    /**
     * Decode format lama (Full AES Encryption + Full HMAC)
     */
    private fun decodeAesQr(scannedText: String): JSONObject? {
        try {
            val base64Safe = scannedText.substring(10).trim()
            val combined = String(Base64.decode(base64Safe, Base64.URL_SAFE), StandardCharsets.UTF_8)
            val parts = combined.split(".")
            if (parts.size != 2) return null
            
            val encrypted = parts[0]
            val receivedHmac = parts[1]

            // Verifikasi Full HMAC
            val sha256HMAC = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(HMAC_KEY.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
            sha256HMAC.init(secretKey)
            val computedHmac = sha256HMAC.doFinal(encrypted.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            
            if (computedHmac != receivedHmac) return null

            val decryptedJson = decryptAes(encrypted) ?: return null
            val payload = JSONObject(decryptedJson)
            val dataContent = payload.optString("data")
            
            return if (dataContent.startsWith("{")) JSONObject(dataContent) else payload
        } catch (e: Exception) { return null }
    }

    private fun decryptAes(encryptedData: String): String? {
        return try {
            val allBytes = Base64.decode(encryptedData, Base64.DEFAULT)
            if (allBytes.size < 16) return null
            val prefix = String(allBytes, 0, 8)
            val cipherTextBytes: ByteArray
            val key: ByteArray
            val iv: ByteArray

            if (prefix == "Salted__") {
                val salt = allBytes.sliceArray(8 until 16)
                val derived = deriveKeyAndIv(SECRET_KEY.toByteArray(StandardCharsets.UTF_8), salt)
                key = derived.sliceArray(0 until 32)
                iv = derived.sliceArray(32 until 48)
                cipherTextBytes = allBytes.sliceArray(16 until allBytes.size)
            } else {
                key = SECRET_KEY.substring(0, 32).toByteArray(StandardCharsets.UTF_8)
                iv = ByteArray(16) { 0 }
                cipherTextBytes = allBytes
            }

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(cipherTextBytes), StandardCharsets.UTF_8)
        } catch (e: Exception) { null }
    }

    private fun deriveKeyAndIv(password: ByteArray, salt: ByteArray): ByteArray {
        var combined = ByteArray(0)
        var lastHash = ByteArray(0)
        val md = MessageDigest.getInstance("MD5")
        while (combined.size < 48) {
            md.reset()
            md.update(lastHash)
            md.update(password)
            md.update(salt)
            lastHash = md.digest()
            combined += lastHash
        }
        return combined
    }
}
