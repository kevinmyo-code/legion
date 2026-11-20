package com.kevin.legion.vehicle

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Which car the OBD adapter is in, and each car's learned print, stay on this phone: never in a cloud
 * backup or a device transfer (2026-11-19 review A4). Reads both Android backup rule files.
 */
class AdapterPrefsBackupTest {
    private val prefs = listOf("car_fingerprint.xml", "dongle_car.xml")

    private fun res(name: String): File =
        listOf("src/main/res/xml", "app/src/main/res/xml").map { File(it, name) }.first { it.isFile }

    /** The sharedpref paths excluded under [section] (a tag name, or the root for the legacy file). */
    private fun excluded(file: File, section: String?): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = if (section == null) doc.documentElement else doc.getElementsByTagName(section).item(0)
        val out = mutableSetOf<String>()
        val nodes = (root as org.w3c.dom.Element).getElementsByTagName("exclude")
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as org.w3c.dom.Element
            if (e.getAttribute("domain") == "sharedpref") out += e.getAttribute("path")
        }
        return out
    }

    @Test
    fun `the adapter and print prefs are excluded from cloud backup and device transfer`() {
        val rules = res("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            assertTrue(section, excluded(rules, section).containsAll(prefs))
        }
    }

    @Test
    fun `the adapter and print prefs are excluded from the legacy auto backup`() {
        assertTrue(excluded(res("backup_rules.xml"), null).containsAll(prefs))
    }
}
