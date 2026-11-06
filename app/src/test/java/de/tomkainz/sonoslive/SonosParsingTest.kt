package de.tomkainz.sonoslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SonosParsingTest {

    private val solo = Speaker("Kitchen", "192.168.1.10", "RINCON_A", "One SL")
    private val member = Speaker("Bath", "192.168.1.11", "RINCON_B", "One SL")
    private val stranger = Speaker("Attic", "192.168.1.99", "RINCON_X", "Play:1")

    private val topology = """
        <ZoneGroups>
          <ZoneGroup Coordinator="RINCON_A" ID="g1">
            <ZoneGroupMember UUID="RINCON_A" Location="http://192.168.1.10:1400/xml/device_description.xml" ZoneName="Kitchen"/>
            <ZoneGroupMember UUID="RINCON_B" Location="http://192.168.1.11:1400/xml/device_description.xml" ZoneName="Bath"/>
            <ZoneGroupMember UUID="RINCON_C" Location="http://192.168.1.12:1400/xml/device_description.xml" Invisible="1"/>
          </ZoneGroup>
        </ZoneGroups>
    """.trimIndent()

    @Test fun coordinatorIsItself() = assertEquals(solo.ip, Sonos.coordinatorIp(topology, solo))

    @Test fun memberResolvesToCoordinator() = assertEquals(solo.ip, Sonos.coordinatorIp(topology, member))

    @Test fun unknownSpeakerKeepsItsIp() = assertEquals(stranger.ip, Sonos.coordinatorIp(topology, stranger))

    @Test fun emptyTopologyKeepsIp() = assertEquals(solo.ip, Sonos.coordinatorIp("", solo))

    @Test fun invisibleMembersAreHidden() = assertEquals(setOf("RINCON_C"), Sonos.hiddenUdns(topology))

    @Test fun xmlValueIsTrimmed() = assertEquals("23", Sonos.xmlValue("<CurrentVolume> 23\n</CurrentVolume>", "CurrentVolume"))

    @Test fun xmlValueMissing() = assertNull(Sonos.xmlValue("<a>1</a>", "b"))

    @Test fun escapeRoundTrip() {
        val text = "Tom's <\"Room\"> & &lt;more&gt;"
        assertEquals(text, Sonos.unesc(Sonos.esc(text)))
    }

    @Test fun escapeRemovesMarkup() = assertEquals("a &lt;b&gt; &amp; &quot;c&quot;", Sonos.esc("a <b> & \"c\""))
}
