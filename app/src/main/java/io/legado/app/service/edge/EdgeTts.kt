package io.legado.app.service.edge

import io.legado.app.constant.AppLog
import io.legado.app.utils.printOnDebug
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Edge 在线语音合成客户端（Microsoft Edge 大声朗读的公开接口）。
 *
 * 协议细节对照 rany2/edge-tts 与 Wenku8 Flutter 的实现：
 *  - wss 连接带 TrustedClientToken / ConnectionId / Sec-MS-GEC 查询参数
 *  - 先发 speech.config（JSON），再发 SSML（Path:ssml）
 *  - 服务端返回二进制帧：前 2 字节为大端头部长度，头部含 Path:audio，其后是 MP3 数据
 *  - 文本帧 Path:turn.end 表示本段结束
 *
 * 不依赖账号，也没有 API Key；失败时由调用方回落到系统 TTS。
 */
object EdgeTts {

    private const val HOST = "speech.platform.bing.com"
    private const val ROUTE = "/consumer/speech/synthesize/readaloud"
    private const val VOICES_ROUTE = "$ROUTE/voices/list"
    private const val EDGE_ROUTE = "$ROUTE/edge/v1"

    private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

    private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    /** 单段文本上限（转义后字节数），超过需拆分，否则服务端会截断或报错 */
    const val MAX_TEXT_BYTES = 3500

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 录音/合成缓存时钟偏移 */
    @Volatile
    private var clockSkewMs: Long = 0L

    /** 音色列表缓存 */
    @Volatile
    private var voicesCache: List<Voice>? = null

    @Volatile
    private var voicesLoadedAt: Long = 0L

    data class Voice(
        val shortName: String,
        val locale: String,
        val gender: String,
        val friendlyName: String
    ) {
        val label: String get() = "$friendlyName · $gender"
    }

    class EdgeException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private fun nowMs(): Long = System.currentTimeMillis() + clockSkewMs

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9",
        "Pragma" to "no-cache",
        "Cache-Control" to "no-cache",
        "Origin" to ORIGIN,
        "Cookie" to "muid=${EdgeProtocol.connectionId().uppercase(Locale.US)};"
    )

    private fun timestamp(now: Long): String {
        val fmt = java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(now))
    }

    fun escapeXml(source: String): String = source
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /**
     * 拉取在线音色列表；失败抛异常，由调用方决定是否沿用旧缓存。
     */
    fun loadVoices(force: Boolean = false): List<Voice> {
        val cached = voicesCache
        if (!force && cached != null && System.currentTimeMillis() - voicesLoadedAt < 24 * 3600_000L) {
            return cached
        }
        val url = "https://$HOST$VOICES_ROUTE?trustedclienttoken=${EdgeProtocol.CLIENT_ID}"
        val request = Request.Builder()
            .url(url)
            .apply { baseHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(request).execute().use { response ->
            val date = response.header("Date")
            if (date != null) {
                runCatching {
                    val served = java.text.SimpleDateFormat(
                        "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US
                    ).parse(date)?.time
                    if (served != null) clockSkewMs = served - System.currentTimeMillis()
                }.onFailure { it.printOnDebug() }
            }
            if (!response.isSuccessful) {
                throw EdgeException("音色列表请求失败 HTTP ${response.code}")
            }
            val body = response.body.string()
            val list = parseVoices(body)
            voicesCache = list
            voicesLoadedAt = System.currentTimeMillis()
            return list
        }
    }

    private fun parseVoices(json: String): List<Voice> {
        val arr = runCatching {
            org.json.JSONArray(json)
        }.getOrElse { throw EdgeException("音色列表格式变化") }
        val out = ArrayList<Voice>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val shortName = item.optString("ShortName")
            val locale = item.optString("Locale")
            if (shortName.isEmpty() || locale.isEmpty()) continue
            if (!isValidVoice(shortName, locale)) continue
            val gender = if (item.optString("Gender") == "Female") "女声" else "男声"
            out.add(
                Voice(
                    shortName = shortName,
                    locale = locale,
                    gender = gender,
                    friendlyName = item.optString("FriendlyName", shortName)
                )
            )
        }
        return out
    }

    /** 只保留中文可用+格式合法的音色，避免列表里混入无效项 */
    fun isValidVoice(shortName: String, locale: String): Boolean {
        if (shortName.length > 120 || locale.length > 40) return false
        if (!Regex("^[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8}){1,4}$").matches(locale)) return false
        if (!Regex("^[a-zA-Z]{2,3}-(?:[A-Za-z0-9]+-)+[A-Za-z0-9]+Neural$").matches(shortName)) return false
        return shortName.lowercase(Locale.US).startsWith(locale.lowercase(Locale.US) + "-")
    }

    /**
     * 合成一段文本，返回 MP3 字节流；失败抛 [EdgeException]。
     *
     * @param voiceShortName 例如 zh-CN-XiaoxiaoNeural
     * @param locale 例如 zh-CN
     * @param ssml 自定义 SSML（可为空，为空时按默认模板生成）
     * @param ratePercent 语速百分比，0 为原速（-50 ~ +100）
     * @param pitchHz 音调偏移 Hz
     * @param volumePercent 音量百分比
     */
    fun synthesize(
        text: String,
        voiceShortName: String,
        locale: String,
        ssml: String? = null,
        ratePercent: Int = 0,
        pitchHz: Int = 0,
        volumePercent: Int = 0,
        retryOnClockError: Boolean = true
    ): InputStream {
        val plain = text.trim()
        if (plain.isEmpty()) throw EdgeException("这一段没有可朗读的文本")
        if (!isValidVoice(voiceShortName, locale)) {
            throw EdgeException("在线音色无效：$voiceShortName")
        }
        val escaped = escapeXml(plain)
        if (escaped.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            throw EdgeException("单段文本超过 $MAX_TEXT_BYTES 字节，请缩短段落")
        }

        val now = nowMs()
        val connId = EdgeProtocol.connectionId()
        val url = buildString {
            append("wss://").append(HOST).append(EDGE_ROUTE)
            append("?TrustedClientToken=").append(EdgeProtocol.CLIENT_ID)
            append("&ConnectionId=").append(connId)
            append("&Sec-MS-GEC=").append(EdgeProtocol.timeProof(now))
            append("&Sec-MS-GEC-Version=").append(EdgeProtocol.VERSION)
        }

        val urlWithWss = url
        val request = Request.Builder()
            .url(urlWithWss)
            .apply { baseHeaders().forEach { (k, v) -> header(k, v) } }
            .build()

        val body = ssml?.takeIf { it.isNotBlank() } ?: buildString {
            append("<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\"")
            append(" xml:lang=\"").append(locale).append("\">")
            append("<voice name=\"").append(voiceShortName).append("\">")
            append("<prosody pitch=\"").append(if (pitchHz >= 0) "+" else "").append(pitchHz).append("Hz\"")
            append(" rate=\"").append(if (ratePercent >= 0) "+" else "").append(ratePercent).append("%\"")
            append(" volume=\"").append(if (volumePercent >= 0) "+" else "").append(volumePercent).append("%\">")
            append(escaped)
            append("</prosody></voice></speak>")
        }

        val audio = java.io.ByteArrayOutputStream(64 * 1024)
        val finished = AtomicBoolean(false)
        val failed = arrayOfNulls<Throwable>(1)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    val config = "{\"context\":{\"synthesis\":{\"audio\":{" +
                            "\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
                            "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}"
                    webSocket.send(
                        "X-Timestamp:${timestamp(now)}\r\n" +
                                "Content-Type:application/json; charset=utf-8\r\n" +
                                "Path:speech.config\r\n\r\n" +
                                config + "\r\n"
                    )
                    webSocket.send(
                        "X-RequestId:${EdgeProtocol.connectionId()}\r\n" +
                                "Content-Type:application/ssml+xml\r\n" +
                                "X-Timestamp:${timestamp(now)}Z\r\n" +
                                "Path:ssml\r\n\r\n" +
                                body
                    )
                } catch (e: Throwable) {
                    failed[0] = e
                    runCatching { webSocket.close(1000, "send failed") }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val data = bytes.toByteArray()
                    if (data.size < 2) return
                    val headLen = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
                    if (headLen <= 0 || headLen + 2 > data.size) return
                    val header = String(data, 2, headLen, Charsets.UTF_8)
                    val path = Regex("(?:^|\\r\\n)Path:([^\\r\\n]+)").find(header)?.groupValues?.get(1)
                    if (path != "audio") return
                    val start = headLen + 2
                    audio.write(data, start, data.size - start)
                } catch (e: Throwable) {
                    failed[0] = e
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (Regex("(?:^|\\r\\n)Path:turn\\.end(?:\\r\\n|$)").containsMatchIn(text)) {
                    finished.set(true)
                    runCatching { webSocket.close(1000, "done") }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failed[0] = EdgeException(
                    "在线语音连接失败（${response?.code ?: "无响应"}）", t
                )
                finished.set(true)
            }
        }

        val socket = client.newWebSocket(request, listener)
        try {
            val deadline = System.currentTimeMillis() + 45_000L
            while (!finished.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(40)
            }
            failed[0]?.let { throw it }
            if (!finished.get()) {
                throw EdgeException("在线语音超时，请检查网络后重试")
            }
            val bytes = audio.toByteArray()
            if (bytes.size < 100) {
                throw EdgeException("在线语音没有生成音频（该段可能无可朗读内容）")
            }
            return java.io.ByteArrayInputStream(bytes)
        } catch (e: EdgeException) {
            // 403 常见于时钟偏移，刷新音色列表以校准后重试一次
            val msg = e.message.orEmpty()
            if (retryOnClockError && (msg.contains("403") || msg.contains("连接失败"))) {
                runCatching { loadVoices(force = true) }
                    .onFailure { it.printOnDebug() }
                return synthesize(
                    text, voiceShortName, locale, ssml,
                    ratePercent, pitchHz, volumePercent, retryOnClockError = false
                )
            }
            throw e
        } catch (e: Throwable) {
            AppLog.put("在线语音合成失败\n${e.localizedMessage}", e)
            throw if (e is IOException) e else EdgeException("在线语音合成失败：${e.localizedMessage}", e)
        } finally {
            runCatching { socket.cancel() }
        }
    }

    fun clearCache() {
        voicesCache = null
        voicesLoadedAt = 0L
    }
}
