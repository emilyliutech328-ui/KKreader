package io.legado.app.service.edge

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.config.AppConfig
import java.io.InputStream
import java.util.Locale

/**
 * Edge 在线语音引擎适配层。
 *
 * 设计取舍：复用 legado 现有的「在线朗读」服务体系（ExoPlayer 播放、
 * SimpleCache 预缓存、MediaSession 锁屏控制、进度与睡眠定时），
 * 只把「合成方式」从 URL 规则换成 WebSocket，因此把它实现成一个
 * 特殊的 [HttpTTS] 引擎，用约定 URL 标记，不改动 HttpReadAloudService 的
 * 分段/缓存/播放逻辑。
 *
 * 标记：httpTTS.url == [ENGINE_TAG]
 */
object EdgeTtsEngine {

    /** 引擎标记 URL，用来识别内置 Edge 音色 */
    const val ENGINE_TAG = "edge://tts"

    const val ENGINE_NAME = "Edge 在线音色"

    /** 默认音色：晓晓（中文女声，通用） */
    const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"
    const val DEFAULT_LOCALE = "zh-CN"

    /** 备用音色：云希（中文男声） */
    const val ALTERNATE_VOICE = "zh-CN-YunxiNeural"

    /** 常用内置音色，避免每次都得联网拉列表 */
    val FALLBACK_VOICES = listOf(
        EdgeTts.Voice(DEFAULT_VOICE, "zh-CN", "女声", "晓晓 · 温和自然"),
        EdgeTts.Voice(ALTERNATE_VOICE, "zh-CN", "男声", "云希 · 年轻活泼"),
        EdgeTts.Voice("zh-CN-XiaoyiNeural", "zh-CN", "女声", "晓伊 · 温柔可爱"),
        EdgeTts.Voice("zh-CN-YunjianNeural", "zh-CN", "男声", "云健 · 沉稳大气"),
        EdgeTts.Voice("zh-CN-YunyangNeural", "zh-CN", "男声", "云扬 · 专业播音"),
        EdgeTts.Voice("zh-CN-XiaochenNeural", "zh-CN", "女声", "晓辰 · 亲切"),
        EdgeTts.Voice("zh-CN-XiaohanNeural", "zh-CN", "女声", "晓涵 · 知性"),
        EdgeTts.Voice("zh-CN-XiaomengNeural", "zh-CN", "女声", "晓梦 · 活泼"),
        EdgeTts.Voice("zh-CN-XiaomoNeural", "zh-CN", "女声", "晓墨 · 多情绪"),
        EdgeTts.Voice("zh-CN-XiaoruiNeural", "zh-CN", "女声", "晓睿 · 成熟"),
        EdgeTts.Voice("zh-CN-XiaoshuangNeural", "zh-CN", "女声", "晓双 · 童声"),
        EdgeTts.Voice("zh-CN-XiaoxuanNeural", "zh-CN", "女声", "晓萱 · 干练"),
        EdgeTts.Voice("zh-CN-XiaoyanNeural", "zh-CN", "女声", "晓颜 · 温暖"),
        EdgeTts.Voice("zh-CN-XiaozhenNeural", "zh-CN", "女声", "晓甄 · 认真"),
        EdgeTts.Voice("zh-CN-YunfengNeural", "zh-CN", "男声", "云枫 · 磁性"),
        EdgeTts.Voice("zh-CN-YunhaoNeural", "zh-CN", "男声", "云皓 · 爽朗"),
        EdgeTts.Voice("zh-CN-YunxiaNeural", "zh-CN", "男声", "云夏 · 少年"),
        EdgeTts.Voice("zh-CN-YunzeNeural", "zh-CN", "男声", "云泽 · 浑厚"),
        EdgeTts.Voice("zh-HK-HiuMaanNeural", "zh-HK", "女声", "曉曼 · 粤语女声"),
        EdgeTts.Voice("zh-HK-WanLungNeural", "zh-HK", "男声", "雲龍 · 粤语男声"),
        EdgeTts.Voice("zh-TW-HsiaoChenNeural", "zh-TW", "女声", "曉臻 · 台湾女声"),
        EdgeTts.Voice("zh-TW-YunJheNeural", "zh-TW", "男声", "雲哲 · 台湾男声")
    )

    val isBuiltin: Boolean get() = AppConfig.edgeTtsEnabled

    fun isEdgeEngine(httpTTS: HttpTTS?): Boolean {
        return httpTTS != null && httpTTS.url.trim() == ENGINE_TAG
    }

    /**
     * 生成内置 Edge 引擎实体，供朗读引擎选择列表使用。
     * 固定 id，便于偏好设置持久引用。
     */
    fun buildEngine(): HttpTTS {
        return HttpTTS(
            id = ENGINE_ID,
            name = ENGINE_NAME,
            url = ENGINE_TAG,
            contentType = "audio/mpeg",
            concurrentRate = "0",
            enabledCookieJar = false,
            lastUpdateTime = 0L
        )
    }

    const val ENGINE_ID = 2026102600001L

    /** 当前选择的音色 */
    var voice: String
        get() = AppConfig.edgeTtsVoice.ifBlank { DEFAULT_VOICE }
        set(value) = run { AppConfig.edgeTtsVoice = value }

    fun localeOf(voiceShortName: String): String {
        val idx = voiceShortName.indexOf("Neural")
        if (idx <= 0) return DEFAULT_LOCALE
        val parts = voiceShortName.substring(0, idx).split('-')
        return if (parts.size >= 2) "${parts[0]}-${parts[1]}" else DEFAULT_LOCALE
    }

    /**
     * 合成一段文本。
     *
     * @param speechRate legado 的语速值（-5..5 为主），映射到 Edge 的 ±百分比
     */
    fun synthesize(text: String, speechRate: Float = 0f): InputStream {
        val voiceName = voice
        val locale = localeOf(voiceName)
        val ratePercent = (speechRate * 12).toInt().coerceIn(-50, 100)
        val ssml = AppConfig.edgeTtsCustomSsml
        return EdgeTts.synthesize(
            text = text,
            voiceShortName = voiceName,
            locale = locale,
            ssml = ssml.takeIf { it.isNotBlank() },
            ratePercent = ratePercent
        )
    }

    /**
     * 在线音色列表：联网失败时回落到内置常用表，保证朗读可用。
     */
    fun voices(): List<EdgeTts.Voice> {
        return runCatching { EdgeTts.loadVoices() }
            .onFailure {
                AppLog.put("Edge 音色列表获取失败，使用内置音色\n${it.localizedMessage}", it)
            }
            .getOrElse { FALLBACK_VOICES }
            .ifEmpty { FALLBACK_VOICES }
    }

    /** 按语言前缀过滤音色（用于设置页分组） */
    fun voicesOf(localePrefix: String): List<EdgeTts.Voice> =
        voices().filter { it.locale.lowercase(Locale.US).startsWith(localePrefix.lowercase(Locale.US)) }
}
