package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.Command
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.Op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlimClientTest {
    private val codec = CborProtocolCodec()

    @Test fun `CBOR preserves protocol command identity`() {
        val frame = Command(
            cid = 42,
            op = Op.Read(DeviceHandle("opaque-agent-handle"), CharRef("180f", "2a19")),
        )

        assertEquals(frame, codec.decode(codec.encode(frame)))
    }

    @Test fun `CLI hello does not request Kable identifier translation`() {
        val hello = codec.decode(codec.encode(ClientHello())) as ClientHello

        assertNull(hello.identifierFormat)
    }

    @Test fun `CBOR preserves capability-gated management operations`() {
        assertEquals(Op.AgentStatus, codec.decode(codec.encode(Command(43, Op.AgentStatus))).let { (it as Command).op })
        assertEquals(Op.AgentSlots, codec.decode(codec.encode(Command(44, Op.AgentSlots))).let { (it as Command).op })
    }
}
