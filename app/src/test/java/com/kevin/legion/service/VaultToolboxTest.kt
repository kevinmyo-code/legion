package com.kevin.legion.service

import com.kevin.legion.vault.BiometricUnlock
import com.kevin.legion.vault.Revealed
import com.kevin.legion.vault.VaultBackend
import com.kevin.legion.vault.VaultController
import com.kevin.legion.vault.VaultItem
import com.kevin.legion.vault.VaultOutcome
import com.kevin.legion.vault.VaultSecret
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * memory-and-vault ticket 12: `show_vault_item` opens the screen and returns only "Opened on the
 * phone."; it never reveals, so its result can never carry a value.
 */
@RunWith(RobolectricTestRunner::class)
class VaultToolboxTest {

    private val context = RuntimeEnvironment.getApplication()
    private val secretValue = "TEST-VALUE-0000"

    /** A backend that WOULD hand out the value, and counts whether anyone asked. */
    private inner class Backend(var items: VaultOutcome<List<VaultItem>>) : VaultBackend {
        var reveals = 0
        override suspend fun list() = items
        override suspend fun create(label: String, kind: String, value: VaultSecret) = error("not used")
        override suspend fun update(id: String, label: String?, kind: String?, value: VaultSecret?) = error("not used")
        override suspend fun delete(id: String) = error("not used")
        override suspend fun reveal(id: String, unlock: BiometricUnlock): VaultOutcome<Revealed> {
            reveals++
            return VaultOutcome.Ok(Revealed(VaultItem(id, "Passport", "passport"), VaultSecret(secretValue)))
        }
    }

    private val passport = VaultItem("i1", "Passport", "passport")
    private val ssn = VaultItem("i2", "Kevin's SSN", "ssn")
    private val opened = mutableListOf<String>()

    private fun run(backend: Backend, label: String, opens: Boolean = true): JSONObject = runBlocking {
        VaultToolbox.controllerFactory = { VaultController(backend) }
        VaultToolbox.opener = { _, l -> opened += l; opens }
        VaultToolbox.dispatch(context, "show_vault_item", JSONObject().put("label", label))!!
    }

    @After
    fun reset() {
        VaultToolbox.controllerFactory = VaultController::forContext
    }

    @Test
    fun `a match opens the screen and the result is only the opened sentence`() {
        val backend = Backend(VaultOutcome.Ok(listOf(passport, ssn)))
        val out = run(backend, "passport")
        assertTrue(out.getBoolean("success"))
        assertEquals("Opened on the phone.", out.getString("message"))
        assertEquals(setOf("success", "message"), out.keys().asSequence().toSet())
        assertEquals(listOf("Passport"), opened)
        assertEquals(0, backend.reveals)
        assertFalse(out.toString().contains(secretValue))
    }

    @Test
    fun `no match opens nothing and gives the labels, never a value`() {
        val backend = Backend(VaultOutcome.Ok(listOf(passport, ssn)))
        val out = run(backend, "driving licence")
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("Passport, Kevin's SSN"))
        assertTrue(opened.isEmpty())
        assertEquals(0, backend.reveals)
        assertFalse(out.toString().contains(secretValue))
    }

    @Test
    fun `an empty vault and an unreachable engine open nothing and say why`() {
        assertTrue(run(Backend(VaultOutcome.Ok(emptyList())), "passport").getString("message").contains("empty"))
        val offline = run(Backend(VaultOutcome.Unreachable("No engine address saved.")), "passport")
        assertFalse(offline.getBoolean("success"))
        assertTrue(offline.getString("message").contains("nothing was opened"))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a screen Android would not bring up is not called opened`() {
        val out = run(Backend(VaultOutcome.Ok(listOf(passport))), "passport", opens = false)
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").startsWith("Nothing was opened"))
    }

    @Test
    fun `the declaration carries a label and nothing a value could ride in`() {
        val decl = VaultToolbox.declarations().getJSONObject(0)
        assertEquals("show_vault_item", decl.getString("name"))
        val props = decl.getJSONObject("parameters").getJSONObject("properties")
        assertEquals(setOf("label"), props.keys().asSequence().toSet())
    }

    @Test
    fun `match prefers an exact label, then the one label containing it`() {
        val items = listOf(passport, ssn, VaultItem("i3", "BofA checking", "bank_account"))
        assertEquals("i1", VaultController.match(items, "  PASSPORT ")!!.id)
        assertEquals("i2", VaultController.match(items, "ssn")!!.id)
        assertEquals("i3", VaultController.match(items, "bank account")!!.id)
        assertEquals(null, VaultController.match(items, "s"))
    }
}
