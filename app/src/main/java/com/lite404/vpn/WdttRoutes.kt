package com.lite404.vpn

internal object WdttRoutes {
    private const val IPv4Size = 1L shl 32

    private fun parse(entry: String): LongRange? {
        val parts = entry.split('/')
        if (parts.size !in 1..2) return null
        val prefix = if (parts.size == 1) 32 else parts[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return null
        val octets = parts[0].split('.')
        if (octets.size != 4) return null
        var address = 0L
        for (octet in octets) {
            val value = octet.toIntOrNull()?.takeIf { it in 0..255 && octet.all(Char::isDigit) } ?: return null
            address = (address shl 8) or value.toLong()
        }
        val size = 1L shl (32 - prefix)
        val start = address / size * size
        return start until start + size
    }

    private fun entries(text: String) = text.lineSequence().flatMap {
        it.substringBefore('#').split(',', ';', ' ', '\t').asSequence()
    }.filter { it.isNotBlank() }

    fun invalidEntries(text: String): List<String> = entries(text).filter { parse(it) == null }.toList()

    private fun merge(ranges: List<LongRange>): List<LongRange> {
        val result = mutableListOf<LongRange>()
        for (range in ranges.sortedBy { it.first }) {
            val last = result.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                result[result.lastIndex] = last.first..maxOf(last.last, range.last)
            } else result += range
        }
        return result
    }

    private fun subtract(base: LongRange, exclusions: List<LongRange>): List<LongRange> {
        val result = mutableListOf<LongRange>()
        var start = base.first
        for (excluded in exclusions) {
            if (excluded.last < start) continue
            if (excluded.first > base.last) break
            if (excluded.first > start) result += start until excluded.first
            start = excluded.last + 1
            if (start > base.last) break
        }
        if (start <= base.last) result += start..base.last
        return result
    }

    fun build(lanDirect: Boolean, direct: String, proxy: String, tunnelDns: List<String>): List<String> {
        val base = if (lanDirect) Lan.PUBLIC_IPV4.mapNotNull { (ip, prefix) -> parse("$ip/$prefix") }
            else listOf(0L until IPv4Size)
        val exclusions = merge(entries(direct).mapNotNull(::parse).toList())
        val forced = entries(proxy).mapNotNull(::parse).toList() + tunnelDns.mapNotNull(::parse)
        return merge(base.flatMap { subtract(it, exclusions) } + forced).flatMap { range ->
            buildList {
                var address = range.first
                while (address <= range.last) {
                    val alignment = if (address == 0L) IPv4Size else java.lang.Long.lowestOneBit(address)
                    val size = minOf(alignment, java.lang.Long.highestOneBit(range.last - address + 1))
                    val prefix = 32 - java.lang.Long.numberOfTrailingZeros(size)
                    val ip = (24 downTo 0 step 8).joinToString(".") { ((address shr it) and 255).toString() }
                    add("$ip/$prefix")
                    address += size
                }
            }
        }
    }
}
