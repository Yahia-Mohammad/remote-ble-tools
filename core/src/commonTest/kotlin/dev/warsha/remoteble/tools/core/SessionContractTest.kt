package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.LeaseHolder
import dev.warsha.remoteble.protocol.OpResult
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionContractTest {
    @Test fun `structured holder survives the embedded codec`() {
        val result = OpResult.Err(AgentError(ErrorKind.PERIPHERAL_BUSY, message = "busy", holder = LeaseHolder("principal", "client-1")))
        val codec = CborProtocolCodec()
        val decoded = codec.decode(codec.encode(dev.warsha.remoteble.protocol.Reply(1, result))) as dev.warsha.remoteble.protocol.Reply
        assertEquals(result, decoded.result)
    }
}
