package com.javcabr.printerbridge.server

import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Source-address allow list for the HTTP and HTTPS listeners. Numeric CIDRs only (IPv4 or
 * IPv6), never host names, so parsing never touches DNS. Loopback (127.0.0.0/8, ::1) is
 * always allowed; an empty rule list allows everything.
 */
class SourceFilter(cidrs: List<String>) {
    private data class Net(val v6: Boolean, val base: BigInteger, val prefix: Int)
    private val nets: List<Net> = cidrs.map { parse(it) }
    val allowAll = nets.isEmpty()

    fun allows(ip: String?): Boolean {
        if (allowAll) return true
        val a = addr(ip ?: return false) ?: return false
        if (a.isLoopbackAddress) return true
        val (v6, n) = toInt(a)
        return nets.any { it.v6 == v6 && mask(n, it.prefix, v6) == it.base }
    }

    companion object {
        const val TAILNET = "100.64.0.0/10"

        /** Throws IllegalArgumentException for anything that isn't a numeric CIDR or address. */
        private fun parse(cidr: String): Net {
            val s = cidr.trim()
            require(s.isNotEmpty()) { "empty CIDR" }
            val (ipPart, pfx) = if ('/' in s) s.substringBefore('/') to s.substringAfter('/') else s to null
            require(Regex("^[0-9a-fA-F:.]+$").matches(ipPart) && (':' in ipPart || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ipPart))) {
                "not a numeric IP/CIDR: $s" }
            if (':' !in ipPart) require(ipPart.split('.').all { it.toInt() in 0..255 }) { "not a valid IPv4 address: $s" }
            val a = try { InetAddress.getByName(ipPart) } catch (e: Exception) { throw IllegalArgumentException("not a valid IP: $s") }
            val (v6, n) = toInt(a)
            val max = if (v6) 128 else 32
            val p = pfx?.toIntOrNull() ?: if (pfx == null) max else throw IllegalArgumentException("bad prefix length: $s")
            require(p in 0..max) { "prefix length out of range: $s" }
            return Net(v6, mask(n, p, v6), p)
        }

        private fun addr(ip: String): InetAddress? = try {
            val clean = ip.trim().removePrefix("/").substringBefore('%')
            if (!Regex("^[0-9a-fA-F:.]+$").matches(clean)) null else {
                val a = InetAddress.getByName(clean)
                // IPv4-mapped IPv6 (::ffff:a.b.c.d) -> IPv4
                if (a is Inet6Address && a.address.take(10).all { it == 0.toByte() } && a.address[10] == 0xff.toByte() && a.address[11] == 0xff.toByte())
                    InetAddress.getByAddress(a.address.copyOfRange(12, 16)) else a
            }
        } catch (e: Exception) { null }

        private fun toInt(a: InetAddress): Pair<Boolean, BigInteger> = (a !is Inet4Address) to BigInteger(1, a.address)

        private fun mask(n: BigInteger, prefix: Int, v6: Boolean): BigInteger {
            val bits = if (v6) 128 else 32
            if (prefix == 0) return BigInteger.ZERO
            val m = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE).shiftRight(bits - prefix).shiftLeft(bits - prefix)
            return n.and(m)
        }

        fun validate(cidrs: List<String>) { cidrs.forEach { parse(it) } }
    }
}
