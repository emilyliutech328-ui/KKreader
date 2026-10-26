package io.legado.app

import io.legado.app.service.edge.EdgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge TTS 时间证明（Sec-MS-GEC）算法校验。
 *
 * 基准值由独立实现（C:\kkbuild\edge_ref.py，按 rany2/edge-tts 与
 * Wenku8 Flutter 的 edge_protocol.dart 转写）在同一时刻计算得出。
 * 两端一致才说明请求头能被语音服务接受。
 */
class EdgeProtocolTest {

    private val cases = listOf(
        0L to "52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
        1700000000000L to "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
        1730000000000L to "50065ABF67B90F95-49BDAF24D28ECEB38DAEEC84DEAA71E91F92945EA8A47921C58FB4F8F79A4F5E",
        1761000000000L to "9875C7D8BB340E51-4AC084DC23E2DEA93DF952AE15BE8A23A325A2F010C0C277FBC15644D5A006C9",
        1792942090000L to "4579F7202F8140D7-D763FD552648BD179CB992E355428336ADCA1CDB0D122CDCC78DBDDF3307B96E"
    )

    @Test
    fun timeProofMatchesReference() {
        cases.forEach { (millis, expected) ->
            // 每个窗口独立计算，需绕过缓存：这里直接用公开入口，算法自身按窗口缓存
            val actual = EdgeProtocol.timeProof(millis)
            assertEquals("时间戳 $millis 的证明不一致", expected, actual)
        }
    }

    @Test
    fun connectionIdIsHex16() {
        val id = EdgeProtocol.connectionId()
        assertEquals(32, id.length)
        assertTrue("应为十六进制: $id", id.all { it in "0123456789abcdef" })
    }

    @Test
    fun protocolConstants() {
        assertEquals("2-143.0.3650.75", EdgeProtocol.VERSION)
        assertEquals("6A5AA1D4EAFF4E9FB37E23D68491D6F4", EdgeProtocol.CLIENT_ID)
    }
}
