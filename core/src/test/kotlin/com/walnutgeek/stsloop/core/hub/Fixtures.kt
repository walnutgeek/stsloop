package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.corpus.Json
import java.io.File

/** The repo's `protocol/` (ADR-0006), as the hub's tests read it: fixtures per version, and the two manifests. */
object Fixtures {
    val root: File = File(requireNotNull(System.getProperty("stsloop.protocol")) { "stsloop.protocol is not set; run through Gradle" })

    /** Every fixture version present: the current major and any frozen previous one. */
    fun versions(): List<String> =
        root.resolve("fixtures").listFiles { f -> f.isDirectory && f.name.startsWith("v") }.orEmpty().map { it.name }.sorted()

    /** `<type>/<case>.json` of every fixture in [version]. */
    fun paths(version: String): List<String> =
        root.resolve("fixtures/$version").listFiles { f -> f.isDirectory }.orEmpty()
            .flatMap { dir -> dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { "${dir.name}/${it.name}" } }
            .sorted()

    fun text(version: String, rel: String): String = root.resolve("fixtures/$version/$rel").readText()

    fun json(version: String, rel: String): Any? = Json.parse(text(version, rel))

    @Suppress("UNCHECKED_CAST")
    fun manifest(version: String, name: String): Map<String, Any?> = Json.parseObject(text(version, name))

    /** `equivalents.json`: each fixture with extra fields → the fixture it reads as. */
    @Suppress("UNCHECKED_CAST")
    fun equivalents(version: String): Map<String, String> =
        (manifest(version, "equivalents.json")["equivalents"] as List<Map<String, Any?>>)
            .associate { it["fixture"] as String to it["reads_as"] as String }

    /** One conversation of `exchanges.json`. [authorization] is the header the phone sends; null means none. */
    data class Exchange(val name: String, val authorization: String?, val steps: List<Map<String, Any?>>)

    @Suppress("UNCHECKED_CAST")
    fun exchanges(version: String): List<Exchange> {
        val token = pairingToken(version)
        return (manifest(version, "exchanges.json")["exchanges"] as List<Map<String, Any?>>).map { e ->
            Exchange(
                e["name"] as String,
                if ("authorization" in e) e["authorization"] as String? else "Bearer $token",
                e["steps"] as List<Map<String, Any?>>,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun pairingToken(version: String): String = (json(version, "pairing/basic.json") as Map<String, Any?>)["token"] as String
}
