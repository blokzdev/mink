package com.mink.monitor

/**
 * Pure model and packet helpers for the DNS-flow monitor. No Android APIs here,
 * so every function is unit-testable on the JVM.
 *
 * The monitor observes which server names each app looks up, by acting as a
 * DNS-only local VPN: it routes *only* its sentinel DNS address through the
 * tunnel (all other traffic bypasses it), reads the DNS query, attributes it to
 * the requesting app, then forwards the query to the real resolver so nothing
 * breaks. These helpers do the byte-level parsing and the response synthesis;
 * the Android glue lives in [FlowMonitorService].
 *
 * Honesty note carried through the whole feature: Mink records the *names apps
 * resolve*, on-device only, and forwards every query unchanged. It is not a
 * content proxy and never inspects payloads.
 */

/** One app's lookups of one server name, rolled up with first/last/count. */
data class DnsLookup(
    val uid: Int,
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val host: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val count: Int,
)

/** A point-in-time view of everything observed so far, newest activity first. */
data class DnsFlowReport(
    val lookups: List<DnsLookup>,
    val generatedAtMs: Long,
) {
    val appCount: Int get() = lookups.map { it.uid }.distinct().size
    val hostCount: Int get() = lookups.map { it.host }.distinct().size
}

/** IPv4 protocol number for UDP; the only transport the DNS tunnel handles. */
internal const val IPPROTO_UDP = 17

/** A parsed IPv4 + UDP datagram — only the fields the monitor needs. */
internal class Ipv4Udp(
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

/**
 * Parse an IPv4/UDP datagram, or null if [b] (of length [len]) is not one we
 * handle (IPv6, non-UDP, or truncated). Never throws.
 */
internal fun parseIpv4Udp(b: ByteArray, len: Int): Ipv4Udp? {
    if (len < 20) return null
    if ((b[0].toInt() and 0xF0) shr 4 != 4) return null           // IPv4 only
    val ihl = (b[0].toInt() and 0x0F) * 4
    if (ihl < 20 || len < ihl + 8) return null
    if (b[9].toInt() and 0xFF != IPPROTO_UDP) return null
    val srcIp = b.copyOfRange(12, 16)
    val dstIp = b.copyOfRange(16, 20)
    val srcPort = u16(b, ihl)
    val dstPort = u16(b, ihl + 2)
    val udpLen = u16(b, ihl + 4)
    // UDP length includes the 8-byte header; clamp to the real buffer.
    val payloadOffset = ihl + 8
    val declared = udpLen - 8
    val available = len - payloadOffset
    val payloadLength = when {
        declared < 0 -> 0
        declared > available -> available
        else -> declared
    }
    return Ipv4Udp(srcIp, dstIp, srcPort, dstPort, payloadOffset, payloadLength)
}

/**
 * Extract the first question's QNAME from a DNS message whose header starts at
 * [dnsStart]. Returns "" if there is no readable name. Never throws. Compression
 * pointers are not expected in a query and end parsing.
 */
internal fun parseDnsQuestionName(b: ByteArray, dnsStart: Int, len: Int): String {
    var i = dnsStart + 12                                         // skip the 12-byte DNS header
    val sb = StringBuilder()
    while (i < len) {
        val l = b[i].toInt() and 0xFF
        if (l == 0) break
        if (l and 0xC0 != 0) return ""                            // compression pointer: unexpected in a query
        i++
        if (i + l > len) return ""
        if (sb.isNotEmpty()) sb.append('.')
        for (j in 0 until l) {
            val c = b[i + j].toInt() and 0xFF
            // DNS labels are LDH; anything else means we misparsed — bail rather than emit junk.
            if (c < 0x20 || c > 0x7E) return ""
            sb.append(c.toChar())
        }
        i += l
    }
    return sb.toString().lowercase()
}

/**
 * Build an IPv4/UDP datagram carrying [payload] (first [payloadLen] bytes) from
 * [srcIp]:[srcPort] to [dstIp]:[dstPort]. The IPv4 header checksum is computed;
 * the UDP checksum is left 0, which is valid for IPv4 (RFC 768). Used to hand a
 * DNS *response* back to the app as though it came from the sentinel resolver.
 */
internal fun buildIpv4UdpPacket(
    srcIp: ByteArray,
    srcPort: Int,
    dstIp: ByteArray,
    dstPort: Int,
    payload: ByteArray,
    payloadLen: Int,
): ByteArray {
    val total = 20 + 8 + payloadLen
    val p = ByteArray(total)
    // ---- IPv4 header ----
    p[0] = 0x45                                                   // version 4, IHL 5
    p[1] = 0                                                      // DSCP/ECN
    put16(p, 2, total)                                            // total length
    put16(p, 4, 0)                                               // identification
    put16(p, 6, 0)                                               // flags + fragment offset
    p[8] = 64                                                     // TTL
    p[9] = IPPROTO_UDP.toByte()                                   // protocol
    put16(p, 10, 0)                                              // checksum placeholder
    System.arraycopy(srcIp, 0, p, 12, 4)
    System.arraycopy(dstIp, 0, p, 16, 4)
    put16(p, 10, ipv4HeaderChecksum(p))
    // ---- UDP header ----
    put16(p, 20, srcPort)
    put16(p, 22, dstPort)
    put16(p, 24, 8 + payloadLen)                                 // UDP length
    put16(p, 26, 0)                                             // UDP checksum: 0 = not computed (valid on IPv4)
    // ---- payload ----
    System.arraycopy(payload, 0, p, 28, payloadLen)
    return p
}

/** One's-complement checksum over the 20-byte IPv4 header of [p]. */
internal fun ipv4HeaderChecksum(p: ByteArray): Int {
    var sum = 0
    var i = 0
    while (i < 20) {
        sum += u16(p, i)
        i += 2
    }
    while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
    return sum.inv() and 0xFFFF
}

private fun u16(b: ByteArray, off: Int): Int =
    ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

private fun put16(b: ByteArray, off: Int, value: Int) {
    b[off] = ((value shr 8) and 0xFF).toByte()
    b[off + 1] = (value and 0xFF).toByte()
}
