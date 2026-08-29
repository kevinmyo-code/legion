package com.kevin.legion.engine

import android.content.Context
import android.provider.Settings

/**
 * The scope key [com.kevin.legion.data.local.WidgetInstance.deviceId] stores (aspect-engine ticket
 * 08 answer point 2: "layouts are per-device"). `Settings.Secure.ANDROID_ID` is the right primitive
 * for this specifically because widget layout is explicitly NOT sync'd - unlike a Drive-BYO identity
 * (CLAUDE.md §2's "clone-and-run"), nothing here needs to survive a reinstall or match across
 * Kevin's two phones; a value that is stable for the life of one app install on one device is
 * exactly the granularity a home-screen arrangement is supposed to have. `ANDROID_ID` also asks for
 * no permission and needs no Play Services dependency.
 *
 * **CORRECTED 2026-08-29 (`.scratch/backend-erp/issues/24-*.md`,
 * `.scratch/backend-erp/issues/14-*.md`): this value DOES now leave the device.**
 * [com.kevin.legion.backend.ConversationAuditReconcile] uploads it as `conversation_audit.device_id`
 * so a conversation row can be told apart from the same turn number recorded by the other phone -
 * see that table's own migration comment (`20260829000100_obd_samples_and_conversation_audit.sql`)
 * for why a conversation row is NOT a shared fact the way a vehicle or a place is. The claim below
 * used to be true when this only scoped widget layouts; it is false the moment a conversation row
 * carries it. **What is still true and still the point:** this is a device identifier, not a person
 * identifier, and it only ever reaches the household's OWN Supabase project (ADR 0038) - never a
 * third party, never a Kevin-run backend beyond the one the household already owns. A comment that
 * promises what the code no longer does is the exact shape that has bitten this repo twice
 * ([com.kevin.legion.data.local.EventReplicaDao.upsert]'s defeated guarantee,
 * `GeneratedFormScreen`'s "PHOTO ON FILE") - correcting it here rather than leaving the sentence
 * above as a fossil.
 */
object DeviceId {
    /** Never blank in practice on a real device - `ANDROID_ID` is populated at first boot - but a
     * blank/null read (an emulator misconfiguration, a future OS restriction) falls back to a fixed
     * literal rather than crashing the pager: a shared fallback device id is still a WORSE outcome
     * than a crash-free single-device experience, never a silent data-loss one, since layouts are
     * per-device and unsynced by design (this class's own doc) - the worst case is two profiles on
     * the same broken device sharing one arrangement, not data going anywhere it shouldn't. */
    fun current(context: Context): String {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return if (id.isNullOrBlank()) "unknown-device" else id
    }
}
