package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ProactiveSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The master switch and the five category switches - the thing every unsolicited line passes
 * through (ticket 04, `.scratch/proactive-mode/issues/04-categories-storage-and-surface.md`).
 *
 * **Supersedes [ProactivePreferences], which was one inverted boolean.** That object is now a
 * migration source and nothing else: [seedFrom] reads its `muted` key exactly once, to decide what
 * an existing install turns on, and after that nothing reads it. It is deliberately not deleted -
 * deleting it would throw away the only record of what Kevin's phone was set to before the upgrade.
 *
 * ### The rule the whole map rests on
 *
 * **The master is a TRUE kill switch. Nothing is exempt, safety included** (settled decision 2).
 * [mayRaise] ANDs the master over everything before it looks at anything else, and there is no
 * branch that skips it. Every "X always speaks" sentence on this map means *X always speaks while
 * the master is on* - stated in ticket 05 call 2 precisely because that distinction is the kind
 * that erodes.
 *
 * ### Defaults, and why an upgrade differs from a fresh install
 *
 * A **fresh install is quiet**: master on, every category off. An assistant that speaks first
 * before being invited is the thing people uninstall, and a quiet default is what makes the switch
 * meaningful on day one rather than something you find after being surprised.
 *
 * An **existing install carries its behaviour**: `muted = false` becomes
 * [ProactiveCategory.CARRIED_OVER_ON_UPGRADE] - Safety, Timing and Fleet, exactly the set the
 * eleven existing raises map onto. Nothing Kevin already hears goes away and nothing new appears.
 * `muted = true` becomes master off, which is the same silence he already had.
 *
 * The seed happens on first read rather than in the migration because it depends on
 * SharedPreferences, which a `Migration` cannot see.
 */
object ProactiveSettings {

    private val _master = MutableStateFlow(true)
    val master: StateFlow<Boolean> = _master.asStateFlow()

    private val _categories = MutableStateFlow<Map<ProactiveCategory, Boolean>>(
        ProactiveCategory.entries.associateWith { false }
    )
    val categories: StateFlow<Map<ProactiveCategory, Boolean>> = _categories.asStateFlow()

    @Volatile private var loaded = false

    /**
     * Loads the switches into the flows, seeding them on the very first run. Safe to call more than
     * once; only the first call touches the database.
     *
     * **`hadExistingInstall` is the whole migration.** It is [ProactivePreferences]'s stored `muted`
     * key, read once. A fresh install has never written that key, so [ProactivePreferences.isMuted]
     * returns its default `false` and looks identical to an existing unmuted install - which is why
     * this checks whether the key was ever WRITTEN rather than what it says.
     */
    suspend fun load(context: Context) {
        if (loaded) return
        val dao = CarDatabase.getDatabase(context).proactiveSettingDao()
        var rows = dao.all()
        if (rows.isEmpty()) {
            dao.putAll(seedFrom(context))
            rows = dao.all()
        }
        applyRows(rows)
        loaded = true
    }

    /** The rows a first run writes. See the class doc for the fresh-versus-upgrade split. */
    internal fun seedFrom(context: Context): List<ProactiveSetting> {
        val upgrading = ProactivePreferences.hasStoredValue(context)
        val muted = upgrading && ProactivePreferences.isMuted(context)
        val on = if (upgrading && !muted) ProactiveCategory.CARRIED_OVER_ON_UPGRADE else emptySet()
        return buildList {
            add(ProactiveSetting(ProactiveSetting.MASTER_KEY, !muted))
            ProactiveCategory.entries.forEach { add(ProactiveSetting(it.key, it in on)) }
        }
    }

    private fun applyRows(rows: List<ProactiveSetting>) {
        val byKey = rows.associateBy { it.key }
        _master.value = byKey[ProactiveSetting.MASTER_KEY]?.enabled ?: true
        _categories.value = ProactiveCategory.entries.associateWith { byKey[it.key]?.enabled == true }
    }

    suspend fun setMaster(context: Context, on: Boolean) {
        CarDatabase.getDatabase(context).proactiveSettingDao()
            .put(ProactiveSetting(ProactiveSetting.MASTER_KEY, on))
        _master.value = on
    }

    suspend fun setCategory(context: Context, category: ProactiveCategory, on: Boolean) {
        CarDatabase.getDatabase(context).proactiveSettingDao()
            .put(ProactiveSetting(category.key, on))
        _categories.value = _categories.value + (category to on)
    }

    /**
     * Whether [category] may raise at all, on the switches alone. Reads the in-memory flows, so it
     * is safe from any thread and never blocks - [load] is what makes them true.
     *
     * **This is the switch check only.** Quiet hours, the daily cap, the suppression window and the
     * "is anyone there" checks live in [ProactiveBus], because they are about this moment rather
     * than about a setting.
     */
    fun mayRaise(category: ProactiveCategory): Boolean =
        _master.value && _categories.value[category] == true

    /** Test seam: drops the loaded flag and the cached flows back to defaults. */
    internal fun resetForTest() {
        loaded = false
        _master.value = true
        _categories.value = ProactiveCategory.entries.associateWith { false }
    }

    /**
     * Test seam: sets the switches directly and marks them loaded, so [load] short-circuits and
     * **no Room call happens at all**.
     *
     * Exists because of a regression this file caused on 2026-08-21. `ProactiveBus.speakIfAllowed`
     * did no database work before the switches landed; once it called [load], the existing
     * Robolectric `ProactiveBusTest` started failing with `IllegalStateException: Illegal connection
     * pointer` out of Robolectric's legacy SQLite shim. **That is not flakiness and it is not a
     * pre-existing gap** - it is this file putting a database read inside a gate that used to be
     * pure, surfacing in the one test that drives the gate end to end.
     *
     * The fix is a seam rather than an in-memory database because the thing those tests are about
     * is the ORDER of the gate's checks, not storage. Anything that genuinely needs the stored value
     * is covered by [seedFrom], which is pure and takes a `Context` only for SharedPreferences.
     */
    internal fun overrideForTest(master: Boolean, on: Set<ProactiveCategory>) {
        _master.value = master
        _categories.value = ProactiveCategory.entries.associateWith { it in on }
        loaded = true
    }
}
