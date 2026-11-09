package de.tomkainz.sonoslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against what really is on the network, read-only: nothing here plays or changes anything.
 * Off unless SUNUS_LIVE_IP names the computer's own address. SUNUS_SONOS_IP adds a real Sonos,
 * SUNUS_RENDERER=1 expects fake_renderer.py with FAKE_MULTICAST=1 and FAKE_LOCATION_HOST set,
 * and a service published as _sunustest._tcp with avahi-publish.
 */
class LiveNetworkTest {

    private val localIp: String? = System.getenv("SUNUS_LIVE_IP")
    private val sonosIp: String? = System.getenv("SUNUS_SONOS_IP")

    @Test
    fun bonjourFindsARealDevice() {
        assumeTrue(localIp != null && sonosIp != null)
        val found = Mdns.browse(listOf("_sonos._tcp.local"), localIp)
        println("mDNS _sonos: $found")
        val sonos = found.firstOrNull { it.ip == sonosIp }
        assertNotNull("The Sonos answered nothing: $found", sonos)
        assertEquals(1443, sonos!!.port)
        assertTrue(sonos.txt.isNotEmpty())
    }

    @Test
    fun bonjourFindsAPublishedService() {
        assumeTrue(localIp != null && System.getenv("SUNUS_RENDERER") != null)
        val found = Mdns.browse(listOf("_sunustest._tcp.local"), localIp)
        println("mDNS _sunustest: $found")
        val s = found.single { it.instance == "Sunus Test" }
        assertEquals(47123, s.port)
        assertEquals("Testraum", s.txt["fn"])
        assertEquals(localIp, s.ip)
    }

    /**
     * A real upmpdcli (libnpupnp) on this computer, SUNUS_UPMPDCLI=1. It ignores a search whose
     * HOST is neither its own IP nor the multicast address, so asking 127.0.0.1 only works
     * because the probe also sends the multicast form.
     */
    @Test
    fun upnpProbeFindsRealUpmpdcliByLoopback() {
        assumeTrue(System.getenv("SUNUS_UPMPDCLI") != null)
        val found = Discovery.probeUpnp("127.0.0.1")
        println("probeUpnp 127.0.0.1: $found")
        assertNotNull("upmpdcli did not answer", found)
        assertEquals(SpeakerProtocol.UPNP, found!!.protocol)
        assertEquals("UpMPD", found.model)
    }

    @Test
    fun ssdpFindsWhatIsThere() {
        assumeTrue(localIp != null)
        val found = Ssdp.search()
        println("SSDP: $found")
        // Only where a firewall lets the answers in: they come to a port nothing was sent from.
        System.getenv("SUNUS_SSDP_REAL")?.let {
            val ip = sonosIp!!
            val sonos = found.firstOrNull { it.ip == ip }
            assertNotNull("The Sonos did not answer: $found", sonos)
            assertEquals(SpeakerProtocol.SONOS, sonos!!.protocol)
        }
        if (System.getenv("SUNUS_RENDERER") != null) {
            val renderer = found.firstOrNull { it.protocol == SpeakerProtocol.UPNP && it.udn == "fake-renderer-0001" }
            assertNotNull("The renderer did not answer: $found", renderer)
        }
    }

    /** A real device's description and what it accepts, seen by the generic UPnP code. */
    @Test
    fun aRealRendererIsUnderstood() {
        assumeTrue(sonosIp != null)
        val location = "http://$sonosIp:1400/xml/device_description.xml"
        val xml = UpnpDescription.get(location)
        assertTrue(UpnpDescription.isSonos(xml))
        val device = UpnpDescription.parse(xml, location)
        println("Parsed: $device")
        assertNotNull(device)
        assertEquals("http://$sonosIp:1400/MediaRenderer/AVTransport/Control", device!!.avTransport)
        assertEquals("http://$sonosIp:1400/MediaRenderer/RenderingControl/Control", device.rendering)
        val renderer = UpnpRenderer(device)
        val sink = renderer.sinkProtocols()
        println("Sink: ${sink.take(400)}")
        assertTrue(sink.contains("audio/"))
        val ladder = UpnpProfile.ladder(sink)
        println("Ladder: ${ladder.map { it.name + " " + it.mime }}")
        assertTrue(ladder.first().mime.contains("wav"))
        println("State: ${renderer.transportState()}, URI: ${renderer.currentUri()}")
        val control = UpnpControl(Speaker("x", sonosIp!!, device.udn, device.model, SpeakerProtocol.UPNP, location))
        // UpnpDescription.fetch leaves Sonos to its own driver, so this only says it will not be taken for a renderer.
        assertTrue(runCatching { control.volume() }.isFailure)
    }
}
