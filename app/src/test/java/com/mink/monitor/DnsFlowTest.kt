package com.mink.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the DNS-flow packet helpers. No Android APIs are touched:
 * every function under test is byte-level parsing/synthesis, so packets are
 * hand-built here and asserted directly.
 */
class DnsFlowTest {

    // ---- packet builders ----

    /** A minimal IPv4/UDP datagram carrying [payload] from 10.0.0.2:port to 10.111.222.1:dstPort. */
    private fun udpPacket(
        srcPort: Int = 40000,
        dstPort: Int = 53,
        proto: Int = IPPROTO_UDP,
        version: Int = 4,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val total = 20 + 8 + payload.size
        val b = ByteArray(total)
        b[0] = ((version shl 4) or 5).toByte()
        b[9] = proto.toByte()
        b[12] = 10; b[13] = 0; b[14] = 0; b[15] = 2                 // src 10.0.0.2
        b[16] = 10; b[17] = 111.toByte(); b[18] = 222.toByte(); b[19] = 1  // dst 10.111.222.1
        put16(b, 20, srcPort)
        put16(b, 22, dstPort)
        put16(b, 24, 8 + payload.size)
        System.arraycopy(payload, 0, b, 28, payload.size)
        return b
    }

    /** A DNS query message (header + one question) for [host]. */
    private fun dnsQuery(host: String): ByteArray {
        val labels = host.split('.')
        val body = ArrayList<Byte>()
        // 12-byte header (id + flags + counts); only QDCOUNT=1 matters to the parser.
        repeat(12) { body.add(0) }
        body[5] = 1                                                 // QDCOUNT low byte
        for (label in labels) {
            body.add(label.length.toByte())
            for (c in label) body.add(c.code.toByte())
        }
        body.add(0)                                                 // root label
        put16At(body, 0)                                            // qtype placeholder (2 bytes)
        put16At(body, 1)                                            // qclass placeholder (2 bytes)
        return body.toByteArray()
    }

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v shr 8) and 0xFF).toByte(); b[off + 1] = (v and 0xFF).toByte()
    }

    private fun put16At(list: ArrayList<Byte>, @Suppress("UNUSED_PARAMETER") ignored: Int) {
        list.add(0); list.add(0)
    }

    // ---- parseIpv4Udp ----

    @Test
    fun parsesPortsIpsAndPayloadWindow() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val pkt = parseIpv4Udp(udpPacket(srcPort = 12345, dstPort = 53, payload = payload), 20 + 8 + 4)
        assertNotNull(pkt)
        pkt!!
        assertEquals(12345, pkt.srcPort)
        assertEquals(53, pkt.dstPort)
        assertEquals(28, pkt.payloadOffset)
        assertEquals(4, pkt.payloadLength)
        assertEquals(10, pkt.srcIp[0].toInt())
        assertEquals(1, pkt.dstIp[3].toInt())
    }

    @Test
    fun rejectsIpv6TcpAndTruncated() {
        assertNull(parseIpv4Udp(udpPacket(version = 6), 28))
        assertNull(parseIpv4Udp(udpPacket(proto = 6), 28))          // TCP
        assertNull(parseIpv4Udp(ByteArray(10), 10))                 // too short
    }

    @Test
    fun clampsPayloadToActualBuffer() {
        // Declared UDP length says 100 bytes of payload but the buffer only has 4.
        val b = udpPacket(payload = byteArrayOf(9, 9, 9, 9))
        put16(b, 24, 8 + 100)                                       // lie about UDP length
        val pkt = parseIpv4Udp(b, b.size)
        assertNotNull(pkt)
        assertEquals(4, pkt!!.payloadLength)                        // clamped, never over-reads
    }

    // ---- parseDnsQuestionName ----

    @Test
    fun extractsQnameLowercased() {
        val dns = dnsQuery("Example.COM")
        val pkt = udpPacket(payload = dns)
        val parsed = parseIpv4Udp(pkt, pkt.size)!!
        assertEquals("example.com", parseDnsQuestionName(pkt, parsed.payloadOffset, pkt.size))
    }

    @Test
    fun extractsMultiLabelHost() {
        val dns = dnsQuery("mobileconfiguration-pa.googleapis.com")
        val pkt = udpPacket(payload = dns)
        val parsed = parseIpv4Udp(pkt, pkt.size)!!
        assertEquals(
            "mobileconfiguration-pa.googleapis.com",
            parseDnsQuestionName(pkt, parsed.payloadOffset, pkt.size),
        )
    }

    @Test
    fun returnsEmptyOnCompressionPointer() {
        // A name that begins with a 0xC0 compression pointer (invalid in a query).
        val body = ArrayList<Byte>()
        repeat(12) { body.add(0) }
        body.add(0xC0.toByte()); body.add(0x0C)
        val dns = body.toByteArray()
        val pkt = udpPacket(payload = dns)
        val parsed = parseIpv4Udp(pkt, pkt.size)!!
        assertEquals("", parseDnsQuestionName(pkt, parsed.payloadOffset, pkt.size))
    }

    // ---- buildIpv4UdpPacket / checksum ----

    @Test
    fun builtPacketRoundTrips() {
        val payload = byteArrayOf(5, 6, 7, 8, 9)
        val built = buildIpv4UdpPacket(
            srcIp = byteArrayOf(10, 111.toByte(), 222.toByte(), 1), srcPort = 53,
            dstIp = byteArrayOf(10, 0, 0, 2), dstPort = 40000,
            payload = payload, payloadLen = payload.size,
        )
        val pkt = parseIpv4Udp(built, built.size)
        assertNotNull(pkt)
        pkt!!
        assertEquals(53, pkt.srcPort)
        assertEquals(40000, pkt.dstPort)
        assertEquals(payload.size, pkt.payloadLength)
        assertEquals(5, built[pkt.payloadOffset].toInt())
    }

    @Test
    fun ipHeaderChecksumVerifiesToZero() {
        val built = buildIpv4UdpPacket(
            srcIp = byteArrayOf(1, 2, 3, 4), srcPort = 53,
            dstIp = byteArrayOf(5, 6, 7, 8), dstPort = 1234,
            payload = byteArrayOf(0, 0), payloadLen = 2,
        )
        // Summing all header words (checksum field included) must fold to 0xFFFF.
        var sum = 0
        var i = 0
        while (i < 20) {
            sum += ((built[i].toInt() and 0xFF) shl 8) or (built[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        assertEquals(0xFFFF, sum)
    }

    // ---- DnsFlowHub ----

    @Test
    fun hubRollsUpByUidAndHostAndCaps() {
        DnsFlowHub.clear()
        DnsFlowHub.record(10201, "com.mink", "Mink", false, "example.com", 100L)
        DnsFlowHub.record(10201, "com.mink", "Mink", false, "example.com", 200L)
        DnsFlowHub.record(10128, "com.google", "Google", true, "mtalk.google.com", 150L)

        val report = DnsFlowHub.report.value
        assertEquals(2, report.lookups.size)                        // two distinct (uid, host)
        val mink = report.lookups.first { it.host == "example.com" }
        assertEquals(2, mink.count)
        assertEquals(100L, mink.firstSeenMs)
        assertEquals(200L, mink.lastSeenMs)
        assertEquals(2, report.appCount)
        // Newest activity first.
        assertTrue(report.lookups.first().lastSeenMs >= report.lookups.last().lastSeenMs)
        DnsFlowHub.clear()
        assertEquals(0, DnsFlowHub.report.value.lookups.size)
    }

    @Test
    fun rollupEntryRoundTrips() {
        val lookup = DnsLookup(10201, "com.mink", "Mink", false, "example.com", 100L, 200L, 3)
        val back = lookup.toEntry().toLookup()
        assertEquals(lookup, back)
    }

    @Test
    fun hubEvictsOldestBeyondTheCap() {
        DnsFlowHub.clear()
        // Record more than the cap (500) distinct hosts with strictly increasing time.
        val total = 620
        for (i in 0 until total) {
            DnsFlowHub.record(10000, "com.app", "App", false, "host$i.example", i.toLong())
        }
        val report = DnsFlowHub.report.value
        assertEquals(500, report.lookups.size)                     // capped
        // The earliest-seen hosts are gone; the most recent survive.
        val survivingHosts = report.lookups.map { it.host }.toSet()
        assertFalse(survivingHosts.contains("host0.example"))       // evicted
        assertTrue(survivingHosts.contains("host${total - 1}.example")) // kept
        DnsFlowHub.clear()
    }
}
