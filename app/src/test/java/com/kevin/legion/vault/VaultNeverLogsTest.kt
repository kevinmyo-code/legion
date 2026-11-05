package com.kevin.legion.vault

import com.kevin.legion.ui.vault.Shown
import com.kevin.legion.ui.vault.VaultForm
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * memory-and-vault ticket 12's grep test: no vault source file has a logging call at all, so no
 * value can reach logcat from them (ADR 0076: never logged). Broader than "no Log call with the
 * value": a file with no log line cannot log the wrong thing later by accident. Plus the type-level
 * guard: every holder of a value prints `[vault value]` or `[hidden]`, never the value.
 */
class VaultNeverLogsTest {

    private fun source(relative: String): File =
        File("src/main/java/com/kevin/legion/$relative").takeIf { it.exists() }
            ?: File("app/src/main/java/com/kevin/legion/$relative")

    private val vaultSources: List<File>
        get() = listOf("vault", "ui/vault").flatMap { dir ->
            source(dir).listFiles { f -> f.extension == "kt" }.orEmpty().toList()
        } + listOf("backend/engine/DjangoVaultBackend.kt", "service/VaultToolbox.kt").map(::source)

    private val logCall = Regex(
        """\b(Log\.[vdiwe]|Log\.wtf|println\(|printStackTrace\(|MidnightEvents\.|LiveLatency\.|Timber\.)""",
    )

    @Test
    fun `the vault sources exist where this test looks`() {
        val names = vaultSources.filter { it.exists() }.map { it.name }.toSet()
        val expected = setOf(
            "VaultItem.kt", "VaultController.kt", "VaultClipboard.kt", "VaultActivity.kt", "VaultViewModel.kt",
            "VaultScreen.kt", "VaultBiometric.kt", "DjangoVaultBackend.kt", "VaultToolbox.kt",
        )
        assertTrue(names.containsAll(expected))
    }

    @Test
    fun `no vault source file logs anything`() {
        val offenders = vaultSources.filter { it.exists() }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                val logs = logCall.containsMatchIn(code) && !code.trimStart().startsWith("*")
                if (logs) "${file.name}:${i + 1}: $line" else null
            }
        }
        assertEquals("vault code must not log: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `every holder of a value prints a placeholder`() {
        val value = "TEST-VALUE-0000"
        val secret = VaultSecret(value)
        assertFalse(secret.toString().contains(value))
        assertFalse(Revealed(VaultItem("i", "Passport", "passport"), secret).toString().contains(value))
        assertFalse(Shown("i", secret, 0L).toString().contains(value))
        assertFalse(VaultForm(label = "Passport", value = value).toString().contains(value))
        assertEquals(value, secret.reveal())
        secret.wipe()
        assertEquals("", secret.reveal())
    }

    @Test
    fun `the controller refuses before sending`() = kotlinx.coroutines.runBlocking {
        var sent = 0
        val backend = object : VaultBackend {
            override suspend fun list() = VaultOutcome.Ok(emptyList<VaultItem>())
            override suspend fun create(label: String, kind: String, value: VaultSecret): VaultOutcome<VaultItem> {
                sent++
                return VaultOutcome.Ok(VaultItem("x", label, kind))
            }
            override suspend fun update(id: String, label: String?, kind: String?, value: VaultSecret?) =
                error("unused")
            override suspend fun delete(id: String) = error("unused")
            override suspend fun reveal(id: String, unlock: BiometricUnlock) = error("unused")
        }
        val controller = VaultController(backend)
        assertTrue(controller.add(" ", "passport", VaultSecret("x")) is VaultOutcome.Refused)
        assertTrue(controller.add("Passport", "driving_licence", VaultSecret("x")) is VaultOutcome.Refused)
        assertTrue(controller.add("Passport", "passport", VaultSecret("  ")) is VaultOutcome.Refused)
        assertEquals(0, sent)
        assertTrue(controller.add("Passport", "passport", VaultSecret("TEST-1")) is VaultOutcome.Ok)
        assertEquals(1, sent)
    }
}
