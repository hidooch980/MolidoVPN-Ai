package ai.molido.vpn.engine.singbox

/**
 * Builds a sing-box (1.11+) JSON configuration for one [ProxyNode].
 * - TUN inbound with strict_route acts as the kill switch: traffic cannot bypass the tunnel.
 * - DNS goes over DoH through the proxy, so queries do not leak to the local network.
 * - includePackages / excludePackages implement per-app VPN.
 * The output contains secrets: never log or persist it in plaintext.
 */
object SingBoxConfigBuilder {
    const val PROXY_TAG = "proxy"

    data class Options(
        val dohUrl: String = "https://1.1.1.1/dns-query",
        val killSwitch: Boolean = true,
        val includePackages: List<String> = emptyList(),
        val excludePackages: List<String> = emptyList(),
        val mtu: Int = 1500
    ) {
        init {
            require(dohUrl.startsWith("https://")) { "DoH URL must use https" }
            require(includePackages.isEmpty() || excludePackages.isEmpty()) {
                "Use either an include list or an exclude list, not both"
            }
            require(mtu in 1280..9000) { "MTU out of range" }
        }
    }

    fun build(node: ProxyNode, options: Options = Options()): String = Json.encode(
        obj(
            "log" to obj("level" to "warn"),
            "dns" to obj(
                "servers" to listOf(
                    obj("tag" to "remote", "address" to options.dohUrl, "detour" to PROXY_TAG),
                    obj("tag" to "local", "address" to "local", "detour" to "direct")
                ),
                "rules" to listOf(obj("outbound" to "any", "server" to "local")),
                "final" to "remote",
                "strategy" to "prefer_ipv4"
            ),
            "inbounds" to listOf(
                obj(
                    "type" to "tun",
                    "tag" to "tun-in",
                    "address" to listOf("172.19.0.1/30"),
                    "mtu" to options.mtu,
                    "auto_route" to true,
                    "strict_route" to options.killSwitch,
                    "stack" to "mixed",
                    "include_package" to options.includePackages.ifEmpty { null },
                    "exclude_package" to options.excludePackages.ifEmpty { null }
                )
            ),
            "outbounds" to listOf(outbound(node), obj("type" to "direct", "tag" to "direct")),
            "route" to obj(
                "auto_detect_interface" to true,
                "final" to PROXY_TAG,
                "rules" to listOf(
                    obj("action" to "sniff"),
                    obj("protocol" to "dns", "action" to "hijack-dns")
                )
            )
        )
    )

    fun outbound(node: ProxyNode): Map<String, Any?> {
        val p = node.params
        val base = arrayOf<Pair<String, Any?>>(
            "type" to typeName(node.protocol),
            "tag" to PROXY_TAG,
            "server" to node.host,
            "server_port" to node.port
        )
        return when (node.protocol) {
            ProxyProtocol.VLESS -> {
                val security = p["security"]?.lowercase()
                val reality = if (security == "reality") obj(
                    "enabled" to true,
                    "public_key" to p["pbk"].nz(),
                    "short_id" to p["sid"].nz()
                ) else null
                obj(
                    *base,
                    "uuid" to node.credential,
                    "flow" to p["flow"].nz(),
                    "tls" to tls(security == "tls" || security == "reality", p["sni"] ?: p["host"], p["fp"] ?: if (reality != null) "chrome" else null, p["alpn"], p["allowInsecure"] == "1", reality),
                    "transport" to transport(p["type"], p["path"], p["host"], p["serviceName"])
                )
            }
            ProxyProtocol.VMESS -> obj(
                *base,
                "uuid" to node.credential,
                "security" to (p["scy"].nz() ?: "auto"),
                "alter_id" to (p["aid"]?.toIntOrNull() ?: 0),
                "tls" to tls(p["tls"] == "tls", p["sni"] ?: p["host"], p["fp"], p["alpn"], false),
                "transport" to transport(p["net"], p["path"], p["host"], p["path"])
            )
            ProxyProtocol.TROJAN -> obj(
                *base,
                "password" to node.credential,
                "tls" to tls(p["security"] != "none", p["sni"] ?: p["peer"] ?: node.host, p["fp"], p["alpn"], p["allowInsecure"] == "1"),
                "transport" to transport(p["type"], p["path"], p["host"], p["serviceName"])
            )
            ProxyProtocol.SHADOWSOCKS -> obj(
                *base,
                "method" to node.secondary,
                "password" to node.credential
            )
            ProxyProtocol.HYSTERIA2 -> obj(
                *base,
                "password" to node.credential,
                "obfs" to p["obfs"].nz()?.let { obj("type" to it, "password" to p["obfs-password"].nz()) },
                "tls" to tls(true, p["sni"] ?: node.host, null, p["alpn"], p["insecure"] == "1")
            )
            ProxyProtocol.TUIC -> obj(
                *base,
                "uuid" to node.credential,
                "password" to node.secondary,
                "congestion_control" to (p["congestion_control"].nz() ?: "bbr"),
                "tls" to tls(true, p["sni"] ?: node.host, null, p["alpn"] ?: "h3", p["allow_insecure"] == "1" || p["insecure"] == "1")
            )
        }
    }

    private fun typeName(protocol: ProxyProtocol) = when (protocol) {
        ProxyProtocol.VLESS -> "vless"
        ProxyProtocol.VMESS -> "vmess"
        ProxyProtocol.TROJAN -> "trojan"
        ProxyProtocol.SHADOWSOCKS -> "shadowsocks"
        ProxyProtocol.HYSTERIA2 -> "hysteria2"
        ProxyProtocol.TUIC -> "tuic"
    }

    private fun tls(
        enabled: Boolean,
        sni: String?,
        fingerprint: String?,
        alpn: String?,
        insecure: Boolean,
        reality: Map<String, Any?>? = null
    ): Map<String, Any?>? = if (!enabled) null else obj(
        "enabled" to true,
        "server_name" to sni.nz(),
        "insecure" to (if (insecure) true else null),
        "alpn" to alpn.nz()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() },
        "utls" to fingerprint.nz()?.let { obj("enabled" to true, "fingerprint" to it) },
        "reality" to reality
    )

    private fun transport(type: String?, path: String?, host: String?, service: String?): Map<String, Any?>? =
        when (type?.lowercase()) {
            "ws" -> obj("type" to "ws", "path" to (path.nz() ?: "/"), "headers" to host.nz()?.let { obj("Host" to it) })
            "grpc" -> obj("type" to "grpc", "service_name" to (service.nz() ?: ""))
            "httpupgrade" -> obj("type" to "httpupgrade", "path" to (path.nz() ?: "/"), "host" to host.nz())
            "h2", "http" -> obj("type" to "http", "path" to path.nz(), "host" to host.nz()?.let { listOf(it) })
            else -> null
        }

    private fun String?.nz(): String? = this?.takeIf { it.isNotBlank() }
    private fun obj(vararg pairs: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*pairs)
}

/** Minimal JSON writer (no Android dependency, so it is unit-testable). Null map values are omitted. */
internal object Json {
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> string(sb, value)
            is Boolean, is Int, is Long -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((key, item) in value) {
                    if (item == null) continue
                    if (!first) sb.append(',')
                    first = false
                    string(sb, key.toString())
                    sb.append(':')
                    write(sb, item)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { i, item -> if (i > 0) sb.append(','); write(sb, item) }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("Unsupported JSON value type")
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
