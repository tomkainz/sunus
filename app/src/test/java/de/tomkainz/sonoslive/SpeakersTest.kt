package de.tomkainz.sonoslive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SpeakersTest {

    // ---- JSON and Cast messages --------------------------------------------

    @Test
    fun jsonRoundTrip() {
        val text = """{"type":"LOAD","n":1,"x":1.5,"on":true,"off":false,"none":null,"list":[1,"a"],"s":"a\"b\ncä"}"""
        val parsed = MiniJson.parse(text) as Map<*, *>
        assertEquals("LOAD", parsed["type"])
        assertEquals(1L, parsed["n"])
        assertEquals(1.5, parsed["x"])
        assertEquals("a\"b\ncä", parsed["s"])
        assertEquals(parsed, MiniJson.parse(MiniJson.write(parsed)))
    }

    @Test
    fun castMessageMatchesTheProtobufLayout() {
        val m = CastMessage("sender-0", "receiver-0", "urn:x-cast:com.google.cast.tp.heartbeat", """{"type":"PING"}""")
        val encoded = m.encode()
        // version=0, then source: field 2, length 8.
        assertEquals("0800120873656e6465722d30", encoded.take(12).joinToString("") { "%02x".format(it) })
        assertEquals(m, CastMessage.decode(encoded))
    }

    @Test
    fun castMessageKeepsLongPayloads() {
        val long = "x".repeat(70000)
        assertEquals(long, CastMessage.decode(CastMessage("a", "b", "c", long).encode()).payload)
    }

    @Test
    fun castStatusIsRead() {
        val json = """{"type":"RECEIVER_STATUS","status":{"applications":[{"appId":"CC1AD845",
            "sessionId":"s1","transportId":"t1"}],"volume":{"level":0.37,"muted":true}}}"""
        @Suppress("UNCHECKED_CAST") val status = MiniJson.parse(json) as Map<String, Any?>
        assertEquals(listOf(CastStatus.App("CC1AD845", "s1", "t1")), CastStatus.apps(status))
        assertEquals(37 to true, CastStatus.volume(status))
        @Suppress("UNCHECKED_CAST") val media = MiniJson.parse(
            """{"type":"MEDIA_STATUS","status":[{"playerState":"IDLE","idleReason":"FINISHED"}]}"""
        ) as Map<String, Any?>
        assertEquals(CastStatus.Media("IDLE", "FINISHED"), CastStatus.media(media))
    }

    @Test
    fun castFramingFollowsTheModel() {
        assertEquals(StreamFraming.LENGTH, CastStatus.framings("Nest Audio").first())
        assertEquals(StreamFraming.NONE, CastStatus.framings("Google Home Speaker").first())
        assertEquals(StreamFraming.NONE, CastStatus.framings("Google Cast Group").first())
        assertEquals(3, CastStatus.framings("Nest Mini").toSet().size)
    }

    // ---- UPnP --------------------------------------------------------------

    private val wiim = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major></specVersion>
        <device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>K&amp;che</friendlyName><manufacturer>Linkplay</manufacturer><modelName>WiiM Pro</modelName>
        <UDN>uuid:abc-123</UDN>
        <iconList><icon><mimetype>image/png</mimetype><url>/icon.png</url></icon></iconList>
        <serviceList>
        <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
          <SCPDURL>/cm.xml</SCPDURL><controlURL>/upnp/control/cm</controlURL></service>
        <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
          <SCPDURL>/avt.xml</SCPDURL><controlURL>/upnp/control/avt</controlURL></service>
        <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
          <SCPDURL>/rc.xml</SCPDURL><controlURL>upnp/control/rc</controlURL></service>
        </serviceList></device></root>"""

    @Test
    fun rendererDescriptionIsRead() {
        val d = UpnpDescription.parse(wiim, "http://192.168.1.9:49152/description.xml")
        assertNotNull(d)
        d!!
        assertEquals("abc-123", d.udn)
        assertEquals("K&che", d.name)
        assertEquals("WiiM Pro", d.model)
        assertEquals("http://192.168.1.9:49152/upnp/control/avt", d.avTransport)
        assertEquals("http://192.168.1.9:49152/upnp/control/rc", d.rendering)
        assertEquals("http://192.168.1.9:49152/rc.xml", d.renderingScpd)
        assertEquals("http://192.168.1.9:49152/upnp/control/cm", d.connectionManager)
        assertEquals("http://192.168.1.9:49152/icon.png", d.icon)
    }

    @Test
    fun embeddedRendererFindsItsOwnServices() {
        val xml = """<root><device><deviceType>urn:x:device:Basic:1</deviceType><friendlyName>Hub</friendlyName>
            <UDN>uuid:hub</UDN><serviceList><service><serviceType>urn:x:service:AVTransport:1</serviceType>
            <controlURL>/wrong</controlURL></service></serviceList>
            <deviceList><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Inner</friendlyName><modelName>M</modelName><UDN>uuid:inner</UDN>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>/right</controlURL></service></serviceList></device></deviceList></device></root>"""
        val d = UpnpDescription.parse(xml, "http://10.0.0.1:80/d.xml")!!
        assertEquals("inner", d.udn)
        assertEquals("Inner", d.name)
        assertEquals("http://10.0.0.1:80/right", d.avTransport)
    }

    @Test
    fun urlBaseWins() {
        val xml = wiim.replace("<specVersion>", "<URLBase>http://10.1.1.1:1234/</URLBase><specVersion>")
        assertEquals("http://10.1.1.1:1234/upnp/control/avt", UpnpDescription.parse(xml, "http://x/d.xml")!!.avTransport)
    }

    @Test
    fun descriptionInUnusualButLegalForm() {
        // Services before the device type, prefixed tags, attributes, an escaped address.
        val xml = """<u:root xmlns:u="urn:schemas-upnp-org:device-1-0"><u:device>
            <u:serviceList><u:service id="1"><u:serviceType>urn:schemas-upnp-org:service:AVTransport:1</u:serviceType>
            <u:controlURL>/ctl?a=1&amp;b=2</u:controlURL></u:service></u:serviceList>
            <u:deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</u:deviceType>
            <u:friendlyName>Rack</u:friendlyName><u:UDN>uuid:u1</u:UDN></u:device></u:root>"""
        val d = UpnpDescription.parse(xml, "http://10.0.0.2:80/d.xml")!!
        assertEquals("http://10.0.0.2:80/ctl?a=1&b=2", d.avTransport)
        assertEquals("u1", d.udn)
    }

    @Test
    fun valuesWithAttributesAreRead() {
        assertEquals("PLAYING", UpnpDescription.tag("""<CurrentTransportState xmlns:dt="x" dt:dt="string">PLAYING</CurrentTransportState>""", "CurrentTransportState"))
        assertEquals("7", UpnpDescription.tag("<m:CurrentVolume>7</m:CurrentVolume>", "CurrentVolume"))
    }

    @Test
    fun notARendererAndSonosAreLeftOut() {
        assertNull(UpnpDescription.parse("<root><device><deviceType>urn:x:device:Router:1</deviceType></device></root>", "http://a/b"))
        assertTrue(UpnpDescription.isSonos("<deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>"))
        assertTrue(UpnpDescription.isSonos("<manufacturer>Sonos, Inc.</manufacturer>"))
        assertFalse(UpnpDescription.isSonos(wiim))
    }

    @Test
    fun ladderUsesWhatTheRendererAccepts() {
        val l = UpnpProfile.ladder("http-get:*:audio/x-wav:*,http-get:*:audio/L16;rate=48000;channels=2:*,http-get:*:audio/mpeg:*")
        assertEquals("audio/x-wav", l.first().mime)
        assertEquals(StreamFraming.LENGTH, l.first().framing)
        assertEquals(setOf(StreamFraming.LENGTH, StreamFraming.NONE, StreamFraming.CHUNKED), l.map { it.framing }.toSet())
        assertTrue(l.any { it.raw })
        // Nothing said: WAV under its common name, and L16 still worth a try.
        val silent = UpnpProfile.ladder("")
        assertEquals("audio/wav", silent.first().mime)
        assertTrue(silent.any { it.raw })
        // Only other rates of L16: not at 48 kHz.
        assertFalse(UpnpProfile.ladder("http-get:*:audio/L16;rate=44100;channels=2:*").any { it.raw })
    }

    @Test
    fun metadataNamesTheStreamAndEscapes() {
        val profile = UpnpProfile("x", "audio/wav", StreamFraming.NONE, UpnpProfile.Metadata.PLAIN)
        val didl = profile.didl("http://h:1/live.wav?a=1&b=2")
        assertTrue(didl, didl.contains("protocolInfo=\"http-get:*:audio/wav:*\""))
        assertTrue(didl, didl.contains("http://h:1/live.wav?a=1&amp;b=2</res>"))
        assertTrue(didl, didl.contains("object.item.audioItem.musicTrack"))
        assertEquals("", profile.copy(metadata = UpnpProfile.Metadata.NONE).didl("u"))
        val dlna = profile.copy(metadata = UpnpProfile.Metadata.DLNA).protocolInfo()
        assertTrue(dlna, dlna.contains("DLNA.ORG_OP=00"))
    }

    // ---- Stream framing and ranges -----------------------------------------

    @Test
    fun plainRequestAsBefore() {
        assertEquals(StreamPlan(200), LiveStreamServer.plan(StreamFraming.NONE, 100, null, true, false))
    }

    @Test
    fun lengthAndChunked() {
        assertEquals(StreamPlan(200, length = 100), LiveStreamServer.plan(StreamFraming.LENGTH, 100, null, true, false))
        assertEquals(StreamPlan(200, chunked = true), LiveStreamServer.plan(StreamFraming.CHUNKED, 100, null, true, false))
        // HTTP/1.0 has no chunks.
        assertEquals(StreamPlan(200), LiveStreamServer.plan(StreamFraming.CHUNKED, 100, null, false, false))
    }

    @Test
    fun rangeFromTheStart() {
        assertEquals(StreamPlan(200), LiveStreamServer.plan(StreamFraming.NONE, 100, "bytes=0-", true, false))
        val linn = LiveStreamServer.plan(StreamFraming.LENGTH, 100, "bytes=0-", true, false)
        assertEquals(206, linn.code)
        assertEquals("bytes 0-99/100", linn.contentRange)
    }

    @Test
    fun rangeInsideTheHeaderSkipsIt() {
        val mpd = LiveStreamServer.plan(StreamFraming.NONE, 100, "bytes=44-", true, false)
        assertEquals(206, mpd.code)
        assertEquals(44, mpd.skip)
        val partly = LiveStreamServer.plan(StreamFraming.LENGTH, 100, "bytes=10-", true, false)
        assertEquals(10, partly.skip)
        assertEquals(90L, partly.length)
    }

    @Test
    fun rangeBeyondTheHeaderCannotBeServed() {
        assertEquals(416, LiveStreamServer.plan(StreamFraming.LENGTH, 100, "bytes=45-", true, false).code)
        assertEquals(416, LiveStreamServer.plan(StreamFraming.NONE, 100, "bytes=4000-", true, false).code)
        assertEquals(416, LiveStreamServer.plan(StreamFraming.NONE, 100, "bytes=10-", true, true).code)
    }

    @Test
    fun l16IsBigEndian() {
        assertArrayEquals(byteArrayOf(2, 1, 4, 3), LiveStreamServer.swapped(byteArrayOf(1, 2, 3, 4)))
    }

    // ---- Bonjour -----------------------------------------------------------

    /** Builds a DNS answer the way responders do, with a compressed name in it. */
    private fun answer(): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v ushr 8)
            out.write(v)
        }
        fun u32(v: Int) {
            u16(v ushr 16)
            u16(v)
        }
        fun name(n: String) {
            n.split('.').forEach {
                out.write(it.length)
                out.write(it.toByteArray())
            }
            out.write(0)
        }
        u16(0)
        u16(0x8400)
        u16(0)
        u16(4)
        u16(0)
        u16(0)
        val typeAt = out.size()
        name("_googlecast._tcp.local")
        u16(12)
        u16(1)
        u32(120)
        val instance = byteArrayOf(4) + "Nest".toByteArray() + byteArrayOf(0xC0.toByte(), typeAt.toByte())
        u16(instance.size)
        out.write(instance)
        val instanceAt = out.size()
        out.write(instance)
        u16(33)
        u16(0x8001)
        u32(120)
        val host = byteArrayOf(1) + "h".toByteArray() + byteArrayOf(5) + "local".toByteArray() + byteArrayOf(0)
        u16(6 + host.size)
        u16(0)
        u16(0)
        u16(8009)
        out.write(host)
        out.write(byteArrayOf(0xC0.toByte(), instanceAt.toByte()))
        u16(16)
        u16(0x8001)
        u32(120)
        val txt = listOf("fn=Küche", "md=Nest Audio", "id=abc").map { it.toByteArray() }
        u16(txt.sumOf { it.size + 1 })
        txt.forEach {
            out.write(it.size)
            out.write(it)
        }
        out.write(host)
        u16(1)
        u16(0x8001)
        u32(120)
        u16(4)
        out.write(byteArrayOf(192.toByte(), 168.toByte(), 1, 50))
        return out.toByteArray()
    }

    @Test
    fun bonjourAnswerBecomesAService() {
        val c = Mdns.Collector(listOf(Discovery.CAST_TYPE))
        c.add(Mdns.parse(answer()))
        val s = c.services().single()
        assertEquals("Nest", s.instance)
        assertEquals(8009, s.port)
        assertEquals("192.168.1.50", s.ip)
        assertEquals("Küche", s.txt["fn"])
        assertTrue(c.wanted().isEmpty())
    }

    @Test
    fun aDotInsideAnInstanceNameStaysInIt() {
        // A record for "Buero 1.OG" in the type's domain, then the question for its details.
        val out = ByteArrayOutputStream()
        out.write(ByteArray(4))
        out.write(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0))
        fun label(s: String) {
            out.write(s.toByteArray().size)
            out.write(s.toByteArray())
        }
        val type = listOf("_googlecast", "_tcp", "local")
        type.forEach(::label)
        out.write(0)
        out.write(byteArrayOf(0, 12, 0, 1, 0, 0, 0, 120))
        val rdata = ByteArrayOutputStream().apply {
            write(10)
            write("Buero 1.OG".toByteArray())
            write(11)
            write("_googlecast".toByteArray())
            write(4)
            write("_tcp".toByteArray())
            write(5)
            write("local".toByteArray())
            write(0)
        }.toByteArray()
        out.write(byteArrayOf(0, rdata.size.toByte()))
        out.write(rdata)
        val c = Mdns.Collector(listOf(Discovery.CAST_TYPE))
        c.add(Mdns.parse(out.toByteArray()))
        val wanted = c.wanted().first().first
        // The question has one label with the dot in it, not two.
        val question = Mdns.query(listOf(wanted to 33))
        assertEquals(10, question[12].toInt())
        assertEquals("Buero 1.OG", String(question, 13, 10))
        c.add(listOf(Mdns.Record(wanted, 33, 8009 to "h.local"), Mdns.Record("h.local", 1, "1.1.1.1"), Mdns.Record(wanted, 16, emptyMap<String, String>())))
        assertEquals("Buero 1.OG", c.services().single().instance)
    }

    @Test
    fun whatIsMissingIsAskedFor() {
        val c = Mdns.Collector(listOf(Discovery.CAST_TYPE))
        c.add(listOf(Mdns.Record(Discovery.CAST_TYPE, 12, "Nest.${Discovery.CAST_TYPE}")))
        assertEquals(2, c.wanted().size)
        assertTrue(c.services().isEmpty())
    }

    @Test
    fun brokenAnswerDoesNotLoop() {
        // A name that points at itself.
        val loop = ByteArray(14)
        loop[7] = 1
        loop[12] = 0xC0.toByte()
        loop[13] = 12
        assertTrue(runCatching { Mdns.parse(loop) }.isFailure)
    }

    @Test
    fun servicesBecomeSpeakers() {
        val cast = MdnsService(Discovery.CAST_TYPE, "x", "h", 8009, "1.1.1.1", mapOf("fn" to "Küche", "md" to "Nest Audio", "id" to "u1"))
        val air = MdnsService(
            Discovery.AIRPLAY_TYPE, "Bad", "h", 7000, "1.1.1.2",
            mapOf("model" to "AudioAccessory5,1", "features" to "0x4A7FCA00,0x1C340", "deviceid" to "AA")
        )
        val raop = MdnsService(Discovery.RAOP_TYPE, "BB@Flur", "h", 5000, "1.1.1.3", mapOf("am" to "AirPort10,115", "pw" to "true"))
        val both = MdnsService(Discovery.AIRPLAY_TYPE, "Alt", "h", 7000, "1.1.1.4", mapOf("flags" to "0x84"))
        val bothClassic = MdnsService(Discovery.RAOP_TYPE, "CC@Alt", "h", 49152, "1.1.1.4", emptyMap())
        val out = Discovery.fromMdns(listOf(cast, air, raop, both, bothClassic)).associateBy { it.ip }
        val alt = de.tomkainz.sonoslive.airplay.AirPlayFeatures.fromDetails(out["1.1.1.4"]!!.details)
        assertEquals("the classic service has its own port", 49152, alt.classicPort)
        assertTrue("password as a flag", alt.passwordRequired)
        assertEquals("Küche", out["1.1.1.1"]!!.room)
        assertEquals(SpeakerProtocol.CAST, out["1.1.1.1"]!!.protocol)
        assertEquals("HomePod mini", out["1.1.1.2"]!!.model)
        assertEquals("Flur", out["1.1.1.3"]!!.room)
        assertTrue(de.tomkainz.sonoslive.airplay.AirPlayFeatures.fromDetails(out["1.1.1.3"]!!.details).passwordRequired)
    }

    // ---- One row per device ------------------------------------------------

    private fun sp(ip: String, id: String, p: SpeakerProtocol, room: String = id, address: String = "") =
        Speaker(room, ip, id, "m", p, address)

    @Test
    fun sonosStaysSonos() {
        val list = Discovery.merge(
            listOf(sp("1", "airplay:a", SpeakerProtocol.AIRPLAY), sp("1", "rincon", SpeakerProtocol.SONOS))
        )
        assertEquals("rincon", list.single().udn)
        assertTrue(list.single().alternatives.isEmpty())
    }

    @Test
    fun otherDevicesGetAFallback() {
        val row = Discovery.merge(
            listOf(
                sp("2", "airplay:b", SpeakerProtocol.AIRPLAY),
                sp("2", "cast:b", SpeakerProtocol.CAST),
                sp("2", "upnp-b", SpeakerProtocol.UPNP)
            )
        ).single()
        assertEquals(SpeakerProtocol.UPNP, row.protocol)
        assertEquals(listOf(SpeakerProtocol.CAST, SpeakerProtocol.AIRPLAY), row.alternatives.map { it.protocol })
    }

    @Test
    fun castGroupIsItsOwnSpeaker() {
        val list = Discovery.merge(
            listOf(
                sp("3", "cast:one", SpeakerProtocol.CAST, address = "8009"),
                sp("3", "cast:group", SpeakerProtocol.CAST, address = "32187")
            )
        )
        assertEquals(2, list.size)
    }

    @Test
    fun deviceWithANewAddressAppearsOnce() {
        val list = Discovery.merge(
            listOf(sp("9", "cast:same", SpeakerProtocol.CAST), sp("8", "cast:same", SpeakerProtocol.CAST))
        )
        assertEquals("9", list.single().ip)
    }

    @Test
    fun sameSearchTwiceGivesTheSameRow() {
        val first = Discovery.merge(listOf(sp("2", "cast:b", SpeakerProtocol.CAST), sp("2", "upnp-b", SpeakerProtocol.UPNP)))
        val second = Discovery.merge(first + sp("2", "upnp-b", SpeakerProtocol.UPNP))
        assertEquals(first.map { it.udn }, second.map { it.udn })
    }
}
