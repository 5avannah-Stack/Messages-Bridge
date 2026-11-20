// SPDX-License-Identifier: MIT
package cz.teous.messages.bridge.contract

import android.os.Parcel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The contract's parcelables cross between two apps built at different times, so their wire format is the contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParcelTest {
    private fun chat() = Chat().apply {
        network = BridgeContract.WHATSAPP
        id = "10000000002@s.whatsapp.net"
        name = "Synthetic Friend"
        type = Chat.TYPE_DIRECT
        phone = "10000000002"
        lastTimeMs = 1_700_000_000_000
        unread = 2
        endToEnd = true
    }

    @Test
    fun eventRoundTrips() {
        val event = Event().apply {
            seq = 7
            type = Event.TYPE_CHAT
            network = BridgeContract.WHATSAPP
            timeMs = 1_700_000_000_001
            chat = chat()
        }
        val p = Parcel.obtain()
        event.writeToParcel(p, 0)
        p.setDataPosition(0)
        val back = Event.CREATOR.createFromParcel(p)
        p.recycle()
        assertEquals(7, back.seq)
        assertEquals("10000000002", back.chat?.phone)
        assertEquals(2, back.chat?.unread)
        assertNull(back.message)
    }

    // A newer bridge adds fields at the end; an older Messages must read what it knows and skip the rest.
    @Test
    fun olderReaderSkipsNewerFields() {
        val p = Parcel.obtain()
        chat().writeToParcel(p, 0)
        val end = p.dataPosition()
        p.setDataPosition(0)
        val size = p.readInt()
        p.setDataPosition(0)
        p.writeInt(size + 4)
        p.setDataPosition(end)
        p.writeInt(0x0DEAD)
        p.writeInt(42)
        p.setDataPosition(0)
        val back = Chat.CREATOR.createFromParcel(p)
        assertEquals("Synthetic Friend", back.name)
        assertEquals(42, p.readInt())
        p.recycle()
    }

    @Test
    fun hexDecodesDigests() {
        assertArrayEquals(byteArrayOf(0x60, 0xec.toByte(), 0x6c), BridgeContract.hex("60ec6c"))
        assertEquals(32, BridgeContract.hex(BridgeContract.BRIDGE_CERT_SHA256).size)
        assertEquals(32, BridgeContract.hex(BridgeContract.MESSAGES_CERT_SHA256).size)
    }
}
