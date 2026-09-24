package jtap.model

import java.security.MessageDigest

/**
 * Deterministic stable identities. The same logical object (same case id,
 * revision and intra-document coordinates) always maps to the same string id,
 * so export -> delete database -> import preserves identity and provenance.
 */
object StableIds {
    private val NAMESPACE = byteArrayOf(
        0x6a, 0x74, 0x61, 0x70, 0x2d, 0x72, 0x65, 0x70,
        0x6c, 0x61, 0x79, 0x2d, 0x76, 0x31, 0x00, 0x00,
    )

    private fun sha1(vararg parts: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(NAMESPACE)
        parts.forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0) }
        val digest = md.digest()
        val sb = StringBuilder()
        for (i in 0 until 10) sb.append("%02x".format(digest[i]))
        return sb.toString()
    }

    fun edge(caseId: String, sourceRev: Int, edgeIndex: Long) =
        "edge:${sha1(caseId, sourceRev.toString(), edgeIndex.toString())}"

    fun source(caseId: String, kind: String, localId: String, rev: Int) =
        "src:${sha1(caseId, kind, localId, rev.toString())}"

    fun reset(caseId: String, sourceRev: Int, edgeIndex: Long) =
        "rst:${sha1(caseId, sourceRev.toString(), edgeIndex.toString())}"

    fun derivation(caseId: String, ordinal: Long) =
        "der:${sha1(caseId, "derivation", ordinal.toString())}"

    fun display(caseId: String, derivationId: String, kind: String, key: String) =
        "val:${sha1(caseId, derivationId, kind, key)}"

    fun decision(caseId: String, ordinal: Long) =
        "dec:${sha1(caseId, "decision", ordinal.toString())}"

    fun event(caseId: String, rule: String, key: String) =
        "evt:${sha1(caseId, rule, key)}"

    fun candidate(caseId: String, fingerprint: String) =
        "irc:${sha1(caseId, "ircand", fingerprint)}"

    fun chainDevice(caseId: String, derivationId: String, position: Int) =
        "dev:${sha1(caseId, derivationId, position.toString())}"
}
