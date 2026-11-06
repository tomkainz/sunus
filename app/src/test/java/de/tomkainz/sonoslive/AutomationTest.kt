package de.tomkainz.sonoslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTest {

    private val kitchen = Speaker("Kitchen", "192.168.1.10", "RINCON_A", "One SL")
    private val bath = Speaker("Bath", "192.168.1.11", "RINCON_B", "Play:1")
    private val all = listOf(kitchen, bath)

    @Test fun findsRoomIgnoringCase() = assertEquals(bath, Automation.findSpeaker(all, "bAtH"))

    @Test fun findsIp() = assertEquals(kitchen, Automation.findSpeaker(all, "192.168.1.10"))

    @Test fun unknownRoom() = assertNull(Automation.findSpeaker(all, "Attic"))

    @Test fun privateAddressesAreLocal() {
        assertTrue(Automation.isLocalAddress("192.168.1.10"))
        assertTrue(Automation.isLocalAddress("10.0.2.2"))
    }

    @Test fun publicAddressIsNotLocal() = assertFalse(Automation.isLocalAddress("8.8.8.8"))

    @Test fun hostnameIsNotAnAddress() = assertFalse(Automation.isLocalAddress("example.com"))
}
