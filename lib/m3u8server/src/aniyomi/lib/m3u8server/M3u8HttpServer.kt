package aniyomi.lib.m3u8server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Small loopback HTTP server for M3U8 processing, built on plain sockets so it needs no
 * third-party dependency. Playlists are fetched and their segment urls rewritten to point back
 * here, segments are fetched and stripped of any wrapper (PNG, JPEG...) before being served.
 */
class M3u8HttpServer(
    private val client: OkHttpClient,
    port: Int = 0, // 0 means random port
) {
    private val requestedPort = port
    private var serverSocket: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "m3u8-server").apply { isDaemon = true }
    }

    val port: Int
        get() = serverSocket?.localPort ?: requestedPort

    private val tag by lazy { javaClass.simpleName }

    @Volatile
    private var isRunning = false

    @Synchronized
    fun start() {
        if (isRunning) return
        try {
            val socket = ServerSocket(requestedPort, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            isRunning = true
            Thread({ acceptLoop(socket) }, "m3u8-server-accept").apply { isDaemon = true }.start()
            Log.d(tag, "M3U8 HTTP Server started on port $port")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server: ${e.message}")
            throw e
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        Log.d(tag, "M3U8 HTTP Server stopped")
    }

    fun isRunning(): Boolean = isRunning

    private fun acceptLoop(socket: ServerSocket) {
        while (isRunning && !socket.isClosed) {
            try {
                val client = socket.accept()
                workers.execute { client.use(::serve) }
            } catch (e: IOException) {
                if (isRunning) Log.e(tag, "Accept failed: ${e.message}")
            }
        }
    }

    private class HttpResult(val status: Int, val contentType: String, val body: ByteArray)

    private fun serve(socket: Socket) {
        socket.soTimeout = 30_000
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)

        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        Log.d(tag, "Received request: $method $target")

        val result = try {
            handle(target, forwardedHeaders(headers))
        } catch (e: Exception) {
            Log.e(tag, "Error handling $target: ${e.message}", e)
            HttpResult(500, "text/plain", "Error: ${e.message}".toByteArray())
        }

        val reason = when (result.status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val out = socket.getOutputStream()
        out.write(
            (
                "HTTP/1.1 ${result.status} $reason\r\n" +
                    "Content-Type: ${result.contentType}\r\n" +
                    "Content-Length: ${result.body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.ISO_8859_1),
        )
        if (method != "HEAD") out.write(result.body)
        out.flush()
    }

    private fun handle(target: String, headers: Map<String, String>): HttpResult {
        val path = target.substringBefore('?')
        val url = target.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("url=") }
            ?.substringAfter("url=")
            ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }

        return when {
            path.startsWith("/m3u8") || path.startsWith("/segment") -> {
                if (url.isNullOrBlank()) {
                    return HttpResult(400, "text/plain", "Missing url parameter".toByteArray())
                }
                if (path.startsWith("/m3u8")) {
                    val content = runBlocking { processM3u8Content(url, headers) }
                    HttpResult(200, "application/vnd.apple.mpegurl", content.toByteArray())
                } else {
                    HttpResult(200, "video/mp2t", runBlocking { processSegmentUrl(url, headers) })
                }
            }

            path.startsWith("/health") -> HttpResult(200, "text/plain", getHealthStatus().toByteArray())
            else -> HttpResult(404, "text/plain", "Not Found".toByteArray())
        }
    }

    /**
     * Keep the common headers that might be needed for video requests
     */
    private fun forwardedHeaders(headers: Map<String, String>): Map<String, String> = headers.filterKeys {
        it in setOf(
            "user-agent",
            "referer",
            "origin",
            "accept",
            "accept-language",
            "cache-control",
            "pragma",
        )
    }

    /**
     * Process M3U8 content through the server
     */
    private suspend fun processM3u8Content(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Fetching M3U8 content from: $url with headers: $headers")
            val m3u8Content = fetchM3u8Content(url, headers)
            Log.d(tag, "Original M3U8 content length: ${m3u8Content.length}")

            val modifiedContent = modifyM3u8Content(m3u8Content, url, port)
            Log.d(tag, "Modified M3U8 content length: ${modifiedContent.length}")
            Log.d(tag, "M3U8 processing completed successfully")

            modifiedContent
        } catch (e: Exception) {
            Log.e(tag, "Error processing M3U8 URL: ${e.message}", e)
            throw IOException("Error processing m3u8: ${e.message}")
        }
    }

    /**
     * Process segment with automatic detection
     */
    suspend fun processSegmentUrl(url: String, headers: Map<String, String> = emptyMap()): ByteArray = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Fetching segment from: $url with headers: $headers")
            val segmentData = fetchSegmentWithAutoDetection(url, headers)
            Log.d(tag, "Segment processing completed, final size: ${segmentData.size} bytes")
            segmentData
        } catch (e: Exception) {
            Log.e(tag, "Error processing segment URL: ${e.message}", e)
            throw IOException("Error processing segment: ${e.message}")
        }
    }

    /**
     * Health check
     */
    fun getHealthStatus(): String = if (isRunning) {
        "M3U8 HTTP Server is running on port $port"
    } else {
        "M3U8 HTTP Server is not running"
    }

    private suspend fun fetchM3u8Content(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        Log.d(tag, "Making HTTP request to fetch M3U8 content with headers: $headers")

        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            Log.d(tag, "M3U8 HTTP response code: ${response.code}")

            if (!response.isSuccessful) {
                Log.e(tag, "Failed to fetch M3U8 content, HTTP code: ${response.code}")
                throw IOException("Failed to fetch m3u8: ${response.code}")
            }

            val bytes = response.body.bytes()
            val content = String(PngContainer.unwrap(bytes) ?: bytes, Charsets.UTF_8)
            if (content.isBlank()) {
                Log.e(tag, "Empty M3U8 response body")
                throw IOException("Empty response body")
            }

            Log.d(tag, "Successfully fetched M3U8 content")
            content
        }
    }

    private suspend fun fetchSegmentWithAutoDetection(url: String, headers: Map<String, String> = emptyMap()): ByteArray = withContext(Dispatchers.IO) {
        Log.d(tag, "Making HTTP request to fetch segment with headers: $headers")

        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            Log.d(tag, "Segment HTTP response code: ${response.code}")

            if (!response.isSuccessful) {
                Log.e(tag, "Failed to fetch segment, HTTP code: ${response.code}")
                throw IOException("Failed to fetch segment: ${response.code}")
            }

            val body = response.body.bytes()
            PngContainer.unwrap(body)?.let {
                Log.d(tag, "Unwrapped PNG container, final size: ${it.size} bytes")
                return@use it
            }

            val inputStream = ByteArrayInputStream(body)
            val outputStream = ByteArrayOutputStream()

            // Read first 4KB to detect format
            val buffer = ByteArray(4096)
            val bytesRead = inputStream.read(buffer)
            Log.d(tag, "Read $bytesRead bytes from segment for format detection")

            if (bytesRead > 0) {
                val skipBytes = AutoDetector.detectSkipBytes(buffer.copyOf(bytesRead))
                Log.d(tag, "AutoDetector determined skip bytes: $skipBytes")

                // Write data from detected offset
                val validBytes = bytesRead - skipBytes
                outputStream.write(buffer, skipBytes, validBytes)
                Log.d(tag, "Wrote $validBytes bytes from detected offset")

                // Copy remaining data
                val remainingBytes = inputStream.copyTo(outputStream)
                Log.d(tag, "Copied $remainingBytes remaining bytes")
            }

            inputStream.close()
            val finalData = outputStream.toByteArray()
            outputStream.close()
            Log.d(tag, "Final segment data size: ${finalData.size} bytes")
            finalData
        }
    }

    /**
     * Creates a local M3U8 URL by encoding the original URL and redirecting to the local server.
     * It can either be segment URL or a direct M3U8 URL (not a playlist).
     */
    fun createLocalUrl(m3u8Url: String): String {
        val encodedUrl = URLEncoder.encode(m3u8Url, Charsets.UTF_8.name())
        return "http://127.0.0.1:$port/m3u8?url=$encodedUrl"
    }

    private fun modifyM3u8Content(content: String, originalUrl: String, serverPort: Int): String {
        Log.d(tag, "Modifying M3U8 content for server port: $serverPort")
        val lines = content.lines().toMutableList()
        val modifiedLines = mutableListOf<String>()
        var segmentCount = 0

        // Determine base URL from the original URL
        val baseHttpUrl = originalUrl.toHttpUrlOrNull()

        for (line in lines) {
            when {
                line.startsWith("#") -> {
                    // Keep comments and headers
                    modifiedLines.add(line)
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    // This is a segment URL, resolve against base URL
                    val resolvedUrl = baseHttpUrl?.resolve(line)?.toString() ?: line
                    val encodedUrl = URLEncoder.encode(resolvedUrl, Charsets.UTF_8.name())
                    val localUrl = "http://127.0.0.1:$serverPort/segment?url=$encodedUrl"
                    modifiedLines.add(localUrl)
                    segmentCount++
                }
                else -> {
                    // Keep empty lines
                    modifiedLines.add(line)
                }
            }
        }

        Log.d(tag, "Modified M3U8 content: $segmentCount segments redirected to local server")
        return modifiedLines.joinToString("\n")
    }
}
