package com.kevin.legion.books

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds a tiny valid EPUB 3 on disk for tests: the `mimetype` entry first and stored, as the spec requires. */
object EpubFixture {
    fun write(file: File, title: String = "Fixture Book", author: String = "A. Writer", chapters: Int = 2): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String, stored: Boolean = false) {
                val bytes = text.toByteArray()
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = java.util.zip.CRC32().also { it.update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip", stored = true)
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                </rootfiles></container>""",
            )
            val manifest = (1..chapters).joinToString("") {
                """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>"""
            }
            val spine = (1..chapters).joinToString("") { """<itemref idref="c$it"/>""" }
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="id">urn:uuid:fixture-1</dc:identifier><dc:title>$title</dc:title>
                <dc:creator>$author</dc:creator><dc:language>en</dc:language></metadata>
                <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                $manifest</manifest>
                <spine>$spine</spine></package>""",
            )
            val links = (1..chapters).joinToString("") { """<li><a href="c$it.xhtml">Chapter $it</a></li>""" }
            put(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"
                xmlns:epub="http://www.idpf.org/2007/ops"><head><title>nav</title></head><body>
                <nav epub:type="toc"><ol>$links</ol></nav></body></html>""",
            )
            for (i in 1..chapters) {
                put(
                    "OEBPS/c$i.xhtml",
                    """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml">
                    <head><title>Chapter $i</title></head><body><h1>Chapter $i</h1>
                    <p>First sentence of chapter $i. Second sentence of chapter $i.</p></body></html>""",
                )
            }
        }
        return file
    }
}
