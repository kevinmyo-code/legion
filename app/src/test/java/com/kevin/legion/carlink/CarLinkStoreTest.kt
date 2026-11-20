// Guard clauses: the document says "refuse otherwise" at each check, and one early return per
// refusal reads closest to it.
@file:Suppress("ReturnCount")

package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.LinkCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TEST STAND-IN for the Android Keystore: XORs with a fixed pad and prefixes a marker, so a test can
 * tell sealed bytes from plain ones and make the "Keystore" fail on demand. It is NOT encryption and
 * exists only in tests; the app uses [KeystoreSecretBox].
 */
class FakeKeystoreStandIn : SecretBox {
    var failSealAfter = Int.MAX_VALUE
    var failOpens = 0
    var seals = 0
    var opens = 0

    override fun seal(plain: ByteArray): ByteArray? {
        if (seals++ >= failSealAfter) return null
        return byteArrayOf(0x7E) + plain.map { (it.toInt() xor 0x5A).toByte() }
    }

    override fun open(blob: ByteArray): ByteArray? {
        opens++
        if (failOpens-- > 0) return null
        if (blob.isEmpty() || blob[0] != 0x7E.toByte()) return null
        return blob.copyOfRange(1, blob.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}

class MemoryRecordFile : RecordFile {
    val values = mutableMapOf<String, String>()
    var failWrites = false

    override fun read(): Map<String, String> = values.toMap()

    override fun writeAll(values: Map<String, String?>): Boolean {
        if (failWrites) return false
        for ((k, v) in values) if (v == null) this.values.remove(k) else this.values[k] = v
        return true
    }
}

class CarLinkStoreTest {
    private val box = FakeKeystoreStandIn()
    private val file = MemoryRecordFile()
    private val redacted = mutableListOf<String>()
    private val store = CarLinkStore(box, file) { redacted += it }
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val old = ByteArray(32) { 0x44 }

    @Test fun `a written pair of keys loads back, and nothing on disk is the plain key`() {
        assertTrue(store.write(key, old))
        val loaded = store.load() as StoredLink.Paired
        assertArrayEquals(key, loaded.current)
        assertArrayEquals(old, loaded.reserve)
        val disk = file.values.values.joinToString()
        assertFalse(disk.contains(LinkCrypto.b64(key)))
        assertFalse(disk.contains(LinkCrypto.b64(old)))
    }

    @Test fun `if the Keystore cannot seal every value, nothing is written`() {
        assertTrue(store.write(old, null))
        val before = file.values.toMap()
        box.failSealAfter = box.seals + 1 // the current key seals, the reserve does not
        assertFalse(store.write(key, old))
        assertEquals(before, file.values)
    }

    @Test fun `a failed commit leaves the previous record exactly as it was`() {
        assertTrue(store.write(old, null))
        val before = file.values.toMap()
        file.failWrites = true
        assertFalse(store.write(key, old))
        assertEquals(before, file.values)
    }

    @Test fun `reads are tried three times`() {
        store.write(key, null)
        box.failOpens = 2
        assertTrue(store.load() is StoredLink.Paired)
    }

    @Test fun `an unreadable key is Broken and deletes nothing`() {
        store.write(key, null)
        val before = file.values.toMap()
        box.failOpens = 3
        assertSame(StoredLink.Broken, store.load())
        assertEquals(before, file.values)
    }

    @Test fun `an unreadable reserve does not break the current key`() {
        store.write(key, old)
        box.failOpens = 0
        val reserveBlob = file.values.getValue("link_reserve")
        file.values["link_reserve"] = reserveBlob.reversed()
        val loaded = store.load() as StoredLink.Paired
        assertArrayEquals(key, loaded.current)
        assertNull(loaded.reserve)
    }

    @Test fun `nothing stored is NotPaired`() {
        assertSame(StoredLink.NotPaired, store.load())
    }

    @Test fun `every key is registered with the log redactor when written and when read`() {
        store.write(key, old)
        assertTrue(redacted.contains(LinkCrypto.b64(key)))
        assertTrue(redacted.contains(LinkCrypto.b64(old)))
        redacted.clear()
        store.load()
        assertTrue(redacted.contains(LinkCrypto.b64(key)))
        assertTrue(redacted.contains(key.joinToString("") { "%02x".format(it) }))
    }

    @Test fun `forgetting clears the keys, the unit token id and both switches`() {
        store.write(key, old)
        store.setUnitDeviceId("dev-1")
        store.setSendLocation(true)
        store.setHandOverGemini(true)
        assertTrue(store.write(null, null))
        assertTrue(file.values.isEmpty())
        assertSame(StoredLink.NotPaired, store.load())
    }

    @Test fun `sending location defaults off`() {
        assertFalse(store.sendLocation())
        assertFalse(store.handOverGemini())
    }

    @Test fun `car_link is excluded from cloud backup, device transfer and legacy backup`() {
        fun excludes(path: String, section: String): Boolean {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
            val parent = doc.getElementsByTagName(section).item(0) ?: return false
            val nodes = (parent as org.w3c.dom.Element).getElementsByTagName("exclude")
            return (0 until nodes.length).any {
                val e = nodes.item(it) as org.w3c.dom.Element
                e.getAttribute("domain") == "sharedpref" && e.getAttribute("path") == "${CarLinkStore.FILE}.xml"
            }
        }
        assertTrue(excludes("src/main/res/xml/data_extraction_rules.xml", "cloud-backup"))
        assertTrue(excludes("src/main/res/xml/data_extraction_rules.xml", "device-transfer"))
        assertTrue(excludes("src/main/res/xml/backup_rules.xml", "full-backup-content"))
    }
}
