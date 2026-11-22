package com.kevin.legion.service.testturn

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.kevin.legion.service.cascade.EngineBrainSettings

/**
 * The adb door for test turns. Reached with `adb shell content call --uri
 * content://com.kevin.legion.testturn --method <status|turn|audio|end|engine_brain> [--arg ...] [--extra id:s:..]
 * [--extra writes:b:true | --extra allow_tools:s:manage_item,manage_checklist]`; `content call`
 * prints the returned Bundle, which is how the CLI reads status on a non-debuggable build.
 * `allow_tools` (resolved tool names) lets only those tools change anything ([TestWrites]).
 *
 * Exported, because adb's shell is another uid; **only the shell (2000) or root (0) is answered**,
 * checked against the binder caller, which an app cannot forge. Every other caller gets
 * `adb_only` and nothing runs. The Settings switch is checked after that ([TestTurnCommands]).
 */
class TestTurnProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        val ctx: Context? = context
        val result = if (uid != SHELL_UID && uid != ROOT_UID || ctx == null) {
            mapOf("ok" to false, "error" to "adb_only")
        } else {
            answer(ctx, method, arg, extras)
        }
        TestTurns.log("cmd method=$method ok=${result["ok"]}" + (result["error"]?.let { " error=$it" } ?: ""))
        return bundle(result)
    }

    private fun answer(ctx: Context, method: String, arg: String?, extras: Bundle?): Map<String, Any> =
        // The engine brain's developer switch (P1): set over this adb door only, never a Settings row.
        if (method == EngineBrainSettings.ADB_METHOD) {
            EngineBrainSettings.fromAdb(ctx, arg)
        } else {
            TestTurnCommands.handle(
                method = method,
                arg = arg,
                id = extras?.getString("id").orEmpty().ifBlank { "adb" },
                writes = TestWrites.from(extras?.getBoolean("writes", false) == true, extras?.getString("allow_tools")),
                enabled = TestTurnSettings.allowed(ctx),
                barge = extras?.getBoolean("barge", false) == true,
                host = TestTurns.host,
                readClip = { name ->
                    TestTurns.audioFile(ctx, name)?.takeIf { it.isFile }?.let { f ->
                        runCatching { TestMic.parseWav(f.readBytes()) }.getOrNull()
                    }
                },
            )
        }

    private fun bundle(map: Map<String, Any>): Bundle = Bundle().apply {
        for ((k, v) in map) {
            when (v) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is Long -> putLong(k, v)
                else -> putString(k, v.toString())
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        /** `android.os.Process.SHELL_UID` and `ROOT_UID` (the constants are not public on every API). */
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}
