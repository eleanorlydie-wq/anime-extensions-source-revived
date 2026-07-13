package aniyomi.lib.filemoonextractor

import android.util.Base64
import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonBody
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FilemoonExtractor(private val client: OkHttpClient) {
    private val playlistUtils by lazy { PlaylistUtils(client) }

    // Credit: https://github.com/skoruppa/docchi-stremio-addon/blob/main/app/players/filemoon.py
    fun videosFromUrl(
        url: String,
        prefix: String = "Filemoon - ",
        headers: Headers? = null,
    ): List<Video> {
        return try {
            val httpUrl = url.toHttpUrl()
            val host = httpUrl.host
            val mediaId = if (httpUrl.pathSegments.size > 1 && httpUrl.pathSegments[0] == "e") {
                httpUrl.pathSegments[1]
            } else {
                httpUrl.pathSegments.lastOrNull { it.isNotEmpty() } ?: return emptyList()
            }

            val userAgent = headers?.get("User-Agent") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

            val embedUrl =
                client.newCall(GET("https://$host/api/videos/$mediaId/embed/details"))
                    .execute().bodyString()
                    .substringAfter("embed_frame_url", "")
                    .substringAfter(":")
                    .substringAfter('"')
                    .substringBefore('"')

            if (embedUrl.isBlank()) {
                return emptyList()
            }

            val embedHost = embedUrl.toHttpUrl().host

            // The embed host now fronts everything (settings/captcha/playback) behind a
            // domain-embed check. Any non-blank X-Embed-* triplet satisfies it in practice,
            // so we keep reusing the source `host`/`url` the extension gave us.
            val apiHeaders = (headers?.newBuilder() ?: Headers.Builder()).apply {
                set("Referer", embedUrl)
                set("X-Embed-Origin", host)
                set("X-Embed-Parent", url.encodeUrlPath())
                set("X-Embed-Referer", url)
                set("Accept", "*/*")
                set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                set("Cache-Control", "no-cache")
                set("Pragma", "no-cache")
                set("Priority", "u=1, i")
                set("Sec-Fetch-Dest", "empty")
                set("Sec-Fetch-Mode", "cors")
                set("Sec-Fetch-Site", "same-origin")
                set("Sec-Fetch-Storage-Access", "active")
                set("User-Agent", userAgent)
            }.build()

            // Playback is now gated behind a proof-of-work captcha (sha256-leading-zero-bits
            // per the API, though the actual client-side hash is a custom SHA-256-IV-seeded
            // ChaCha-quarter-round mix, not real SHA-256 - see solveCaptchaPow()). Default to
            // treating it as required if we can't confirm otherwise; solving it when it isn't
            // strictly needed is harmless, just a bit slower.
            val settingsUrl = "https://$embedHost/api/videos/$mediaId/embed/settings"
            val captchaRequired = try {
                client.newCall(GET(settingsUrl, apiHeaders)).execute()
                    .parseAs<EmbedSettings>().captcha_required
            } catch (e: Exception) {
                true
            }

            var captchaToken: String? = null
            if (captchaRequired) {
                val captchaUrl = "https://$embedHost/api/videos/$mediaId/embed/captcha"
                val challenge = client.newCall(POST(captchaUrl, apiHeaders, "{}".toJsonBody()))
                    .execute().parseAs<CaptchaChallenge>()

                val solution = solveCaptchaPow(challenge.pow_nonce, challenge.pow_difficulty)
                    ?: return emptyList()

                val verifyUrl = "https://$embedHost/api/videos/$mediaId/embed/captcha/verify"
                val verifyBody = CaptchaVerifyRequest(challenge.pow_token, solution).toJsonRequestBody()
                val verified = client.newCall(POST(verifyUrl, apiHeaders, verifyBody))
                    .execute().parseAs<CaptchaVerifyResponse>()

                if (verified.status != "ok" || verified.token.isNullOrBlank()) {
                    return emptyList()
                }
                captchaToken = verified.token
            }

            val playbackHeaders = apiHeaders.newBuilder().apply {
                if (captchaToken != null) {
                    set("X-Captcha-Token", captchaToken)
                }
            }.build()

            val apiUrl = "https://$embedHost/api/videos/$mediaId/embed/playback"
            val playbackJson = client.newCall(
                POST(apiUrl, playbackHeaders, PLAYBACK_REQUEST_BODY.toJsonBody()),
            ).execute().parseAs<PlaybackResponse>()

            var finalSources: List<VideoSource>? = null

            if (!playbackJson.sources.isNullOrEmpty()) {
                finalSources = playbackJson.sources
            } else if (playbackJson.playback != null) {
                val pb = playbackJson.playback
                val decryptedData = decrypt(pb)
                val decryptedJson = decryptedData.parseAs<PlaybackResponse>()
                finalSources = decryptedJson.sources
            }

            if (finalSources.isNullOrEmpty()) return emptyList()

            val videoHeaders = (headers?.newBuilder() ?: Headers.Builder()).apply {
                set("Referer", "https://$host/")
                set("User-Agent", userAgent)
                removeAll("Origin")
            }.build()

            finalSources.flatMap { source ->
                val streamUrl = source.url ?: source.file ?: return@flatMap emptyList<Video>()
                val quality = source.label ?: "Unknown"

                playlistUtils.extractFromHls(
                    streamUrl,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    videoNameGen = { "$prefix${it.replace("Video", quality)}p" },
                )
            }
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "Failed to extract video from $url", e)
            emptyList()
        }
    }

    private fun decrypt(input: PlaybackData): String {
        // Only 2 of the (decoy-padded) key_parts are the real key fragments. Which 2 depends
        // on `version`: index n and index (31-n), 1-based, concatenated in that order to make
        // a 32-byte AES-256 key. Out-of-range/unknown versions fall back to using every part
        // concatenated as-is (the old, pre-decoy behavior).
        val selectedParts = selectKeyParts(input.version, input.key_parts)

        val keyBytes = selectedParts
            .map { decodeBase64Url(it) }
            .fold(ByteArray(0)) { acc, bytes -> acc + bytes }

        val ivBytes = decodeBase64Url(input.iv)
        val payloadBytes = decodeBase64Url(input.payload)

        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val decryptedBytes = cipher.doFinal(payloadBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun selectKeyParts(version: String?, keyParts: List<String>): List<String> {
        val n = version?.trim()?.toIntOrNull() ?: return keyParts
        if (n !in 1..20) return keyParts

        val a = n
        val b = 31 - n
        if (a < 1 || b < 1 || a > keyParts.size || b > keyParts.size) return keyParts

        return listOf(keyParts[a - 1], keyParts[b - 1])
    }

    // --- Proof-of-work captcha ------------------------------------------------------------
    //
    // The embed API gates /embed/playback behind a PoW challenge from /embed/captcha:
    // {pow_nonce, pow_difficulty, pow_token}. The client must find a `solution` counter such
    // that hash(pow_nonce + ":" + solution) has at least `pow_difficulty` leading zero bits,
    // then POST {pow_token, solution} to /embed/captcha/verify to receive a short-lived
    // X-Captcha-Token used on the real /embed/playback call.
    //
    // NOTE: despite the API advertising "algorithm":"sha256-leading-zero-bits", the actual
    // client-side hash is NOT NIST SHA-256. It seeds a 4-word state with the SHA-256 IV
    // constants, mixes it with a ChaCha20-style quarter round, and stretches it through a
    // 512-word buffer using FNV/xxHash-style multiplicative constants. Reproduced exactly
    // from the site's client bundle; verified end-to-end against the live captcha/verify
    // endpoint.
    private fun solveCaptchaPow(nonce: String, difficulty: Int, timeoutMs: Long = 20_000L): String? {
        if (difficulty <= 0) return "0"

        val prefix = "$nonce:"
        val start = System.currentTimeMillis()
        var counter = 0L
        while (true) {
            repeat(1024) {
                val hash = captchaHash(asciiBytes(prefix + counter))
                if (leadingZeroBits(hash) >= difficulty) return counter.toString()
                counter++
            }
            if (System.currentTimeMillis() - start > timeoutMs) return null
        }
    }

    private fun asciiBytes(s: String): ByteArray {
        val out = ByteArray(s.length)
        for (i in s.indices) {
            out[i] = (s[i].code and 0xFF).toByte()
        }
        return out
    }

    private fun leadingZeroBits(words: IntArray): Int {
        var total = 0
        for (w in words) {
            if (w == 0) {
                total += 32
                continue
            }
            return total + Integer.numberOfLeadingZeros(w)
        }
        return total
    }

    private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    private fun mixState(t: IntArray) {
        t[0] += t[1]
        t[3] = rotl(t[3] xor t[0], 16)
        t[2] += t[3]
        t[1] = rotl(t[1] xor t[2], 12)
        t[0] += t[1]
        t[3] = rotl(t[3] xor t[0], 8)
        t[2] += t[3]
        t[1] = rotl(t[1] xor t[2], 7)
    }

    private fun captchaHash(msg: ByteArray): IntArray {
        val state = intArrayOf(1779033703, -1150833019, 1013904242, -1521486534) // SHA-256 IV[0..3]
        for (b in msg) {
            state[0] += (b.toInt() and 0xFF)
            state[0] = rotl(state[0], 7)
            mixState(state)
        }
        repeat(8) { mixState(state) }

        val bufSize = 512
        val mask = bufSize - 1
        val buf = IntArray(bufSize)
        for (i in 0 until bufSize) {
            mixState(state)
            buf[i] = state[0] xor state[2]
        }

        repeat(2) {
            for (s in 0 until bufSize) {
                val a = buf[s] and mask
                var c = buf[s] + buf[a]
                c = rotl(c, 13)
                c = c xor (buf[(s + 1) and mask] * PRIME_A)
                buf[s] = c
                state[0] = state[0] xor c
                mixState(state)
            }
        }

        val out = IntArray(8)
        val chunk = bufSize / 8
        for (i in 0 until 8) {
            mixState(state)
            var s = state[0]
            val base = i * chunk
            for (c in 0 until chunk) {
                val d = buf[base + c]
                s += d
                s = rotl(s, 5)
                s = s xor (d * PRIME_B)
            }
            out[i] = s xor state[2]
        }
        return out
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val base64 = input
            .replace('-', '+')
            .replace('_', '/')

        val padding = when (base64.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }

        return Base64.decode(base64 + padding, Base64.DEFAULT)
    }

    @Serializable
    data class PlaybackResponse(
        val sources: List<VideoSource>? = null,
        val playback: PlaybackData? = null,
    )

    @Serializable
    data class PlaybackData(
        val iv: String,
        val key_parts: List<String>,
        val payload: String,
        val version: String? = null,
    )

    @Serializable
    data class VideoSource(
        val file: String? = null,
        val url: String? = null,
        val label: String? = "Default",
    )

    @Serializable
    data class EmbedSettings(
        val captcha_required: Boolean = false,
    )

    @Serializable
    data class CaptchaChallenge(
        val pow_nonce: String,
        val pow_difficulty: Int,
        val pow_token: String,
    )

    @Serializable
    data class CaptchaVerifyRequest(
        val pow_token: String,
        val solution: String,
    )

    @Serializable
    data class CaptchaVerifyResponse(
        val status: String? = null,
        val token: String? = null,
    )

    companion object {
        private const val PLAYBACK_REQUEST_BODY = """{"fingerprint":{}}"""
        private const val PRIME_A = -1640531535 // 2654435761 as signed Int32 (Knuth's multiplicative constant)
        private const val PRIME_B = -2048144777 // 2246822519 as signed Int32 (xxHash PRIME32_2)
    }
}

fun String.encodeUrlPath(): String {
    val uri = URI(this)

    val encodedPath = uri.rawPath
        .split("/")
        .joinToString("/") { segment ->
            if (segment.isEmpty()) {
                ""
            } else {
                URLEncoder.encode(segment, StandardCharsets.UTF_8.toString())
                    .replace("+", "%20")
            }
        }

    return URI(
        uri.scheme,
        uri.rawAuthority,
        encodedPath,
        uri.rawQuery,
        uri.rawFragment,
    ).toString()
}
