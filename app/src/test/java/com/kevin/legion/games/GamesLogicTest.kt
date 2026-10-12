package com.kevin.legion.games

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Retro-games ticket 03's pure seams: mapping, collapsing, paths, intents, box-art URLs, cache, filter. */
class GamesLogicTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(name: String) = RomEntry("primary:ROMs/x/$name", name)

    private fun game(system: GameSystem, file: String, id: String = "primary:ROMs/${system.folder}/$file") =
        Game(system, titleOf(file), file, id)

    // ---- system mapping by folder and extension

    @Test
    fun `folder names map to systems case-insensitively and unknown folders to null`() {
        assertEquals(GameSystem.PSX, GameSystem.fromFolder("psx"))
        assertEquals(GameSystem.GBA, GameSystem.fromFolder("GBA"))
        assertEquals(GameSystem.GENESIS, GameSystem.fromFolder(" genesis "))
        assertNull(GameSystem.fromFolder("Pictures"))
    }

    @Test
    fun `every system maps to the emulator and core in the brief`() {
        assertEquals("gambatte", GameSystem.GB.core)
        assertEquals("gambatte", GameSystem.GBC.core)
        assertEquals("mgba", GameSystem.GBA.core)
        assertEquals("fceumm", GameSystem.NES.core)
        assertEquals("snes9x", GameSystem.SNES.core)
        assertEquals("genesis_plus_gx", GameSystem.GENESIS.core)
        assertEquals("mupen64plus_next_gles3", GameSystem.N64.core)
        assertEquals("pcsx_rearmed", GameSystem.PSX.core)
        assertEquals(Emulator.MELONDS, GameSystem.NDS.emulator)
        assertEquals(Emulator.PPSSPP, GameSystem.PSP.emulator)
        assertNull(GameSystem.NDS.core)
    }

    @Test
    fun `only a systems own extensions are games`() {
        val games = RomScan.games(
            GameSystem.GBA,
            listOf(entry("Emerald.gba"), entry("Emerald.sav"), entry("notes.txt"), entry("Fire.GBA")),
        )
        assertEquals(listOf("Emerald", "Fire"), games.map { it.title })
    }

    // ---- cover beside the ROM

    @Test
    fun `an image named for a game is its cover and is not listed itself`() {
        val games = RomScan.games(
            GameSystem.GBA,
            listOf(
                entry("Emerald++ (v10.1).gba"),
                entry("emerald++ (V10.1).PNG"),
                entry("Fire.gba"),
                entry("Other.jpg"),
            ),
        )
        assertEquals(listOf("Emerald++ (v10.1)", "Fire"), games.map { it.title })
        assertEquals("primary:ROMs/x/emerald++ (V10.1).PNG", games[0].coverDocumentId)
        assertNull(games[1].coverDocumentId)
    }

    // ---- cue/bin collapsing

    @Test
    fun `a bin with a matching cue is not listed, the cue is`() {
        val games = RomScan.games(GameSystem.PSX, listOf(entry("Harvest Moon.bin"), entry("Harvest Moon.cue")))
        assertEquals(listOf("Harvest Moon.cue"), games.map { it.fileName })
    }

    @Test
    fun `multi-track bins named for their cue are hidden too`() {
        val games = RomScan.games(
            GameSystem.PSX,
            listOf(
                entry("Wipeout.cue"), entry("Wipeout (Track 1).bin"), entry("Wipeout (Track 2).bin"),
                entry("Other.cue"),
            ),
        )
        assertEquals(listOf("Other.cue", "Wipeout.cue"), games.map { it.fileName })
    }

    @Test
    fun `a lone bin for psx is a game`() {
        val games = RomScan.games(GameSystem.PSX, listOf(entry("Lone.bin"), entry("Other.cue"), entry("Other.bin")))
        assertEquals(listOf("Lone.bin", "Other.cue"), games.map { it.fileName })
    }

    @Test
    fun `chd and pbp are psx games`() {
        val games = RomScan.games(GameSystem.PSX, listOf(entry("A.chd"), entry("B.pbp")))
        assertEquals(2, games.size)
    }

    @Test
    fun `a genesis bin is a game, the collapse is psx only`() {
        val games = RomScan.games(GameSystem.GENESIS, listOf(entry("Sonic.bin"), entry("Sonic.cue")))
        assertEquals(listOf("Sonic.bin"), games.map { it.fileName })
    }

    // ---- scan over a fake tree

    @Test
    fun `scan reads system folders, one level of subfolder, ignores other folders`() {
        val tree = mapOf(
            "root" to listOf(
                TreeChild("r/gba", "gba", true), TreeChild("r/psx", "psx", true),
                TreeChild("r/Pictures", "Pictures", true),
            ),
            "r/gba" to listOf(TreeChild("r/gba/a.gba", "a.gba", false)),
            "r/psx" to listOf(TreeChild("r/psx/HM", "HM", true)),
            "r/psx/HM" to listOf(
                TreeChild("r/psx/HM/HM.cue", "HM.cue", false), TreeChild("r/psx/HM/HM.bin", "HM.bin", false),
            ),
            "r/Pictures" to listOf(TreeChild("r/Pictures/x.gba", "x.gba", false)),
        )
        val result = RomLibrary { id -> tree[id] ?: emptyList() }.scan("root")
        assertEquals(2, result.systemFoldersSeen)
        assertEquals(listOf("a.gba", "HM.cue"), result.games.map { it.fileName })
    }

    @Test
    fun `an unlistable root is reported as unreadable, not as empty`() {
        val result = RomLibrary { error("provider gone") }.scan("root")
        assertTrue(result.unreadable)
        assertTrue(result.games.isEmpty())
    }

    // ---- path from document id

    @Test
    fun `primary volume maps to emulated storage`() {
        assertEquals("/storage/emulated/0/ROMs/psx/x.cue", RomPaths.filesystemPath("primary:ROMs/psx/x.cue"))
    }

    @Test
    fun `an sd card volume maps under storage by its id`() {
        assertEquals("/storage/1A2B-3C4D/ROMs/gba/e.gba", RomPaths.filesystemPath("1A2B-3C4D:ROMs/gba/e.gba"))
    }

    @Test
    fun `an id with no volume or no path gives no path rather than a guess`() {
        assertNull(RomPaths.filesystemPath("ROMs/psx/x.cue"))
        assertNull(RomPaths.filesystemPath("primary:"))
    }

    @Test
    fun `folder labels read as the user would say them`() {
        assertEquals("/sdcard/ROMs", folderLabel("primary:ROMs"))
        assertEquals("1A2B-3C4D/ROMs", folderLabel("1A2B-3C4D:ROMs"))
    }

    // ---- intent extras per emulator

    @Test
    fun `retroarch gets ROM LIBRETRO CONFIGFILE, explicit component, clear task`() {
        val g = game(GameSystem.PSX, "Harvest Moon.cue")
        val spec = LaunchSpecs.forGame(g, "content://unused").getOrThrow()
        assertEquals("com.retroarch.aarch64", spec.packageName)
        assertEquals("com.retroarch.browser.retroactivity.RetroActivityFuture", spec.className)
        assertEquals("/storage/emulated/0/ROMs/psx/Harvest Moon.cue", spec.extras["ROM"])
        assertEquals("pcsx_rearmed", spec.extras["LIBRETRO"])
        assertEquals(
            "/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg",
            spec.extras["CONFIGFILE"],
        )
        assertEquals(3, spec.extras.size)
        assertTrue(spec.clearTask)
        assertNull(spec.dataUri)
    }

    @Test
    fun `retroarch core follows the system`() {
        val spec = LaunchSpecs.forGame(game(GameSystem.GBA, "e.gba"), "u").getOrThrow()
        assertEquals("mgba", spec.extras["LIBRETRO"])
    }

    @Test
    fun `retroarch with an unmappable document id fails instead of guessing`() {
        val g = game(GameSystem.GBA, "e.gba", id = "no-volume")
        assertTrue(LaunchSpecs.forGame(g, "u").isFailure)
    }

    @Test
    fun `melonds gets LAUNCH_ROM with the uri extra and a read grant`() {
        val spec = LaunchSpecs.forGame(game(GameSystem.NDS, "p.nds"), "content://doc/1").getOrThrow()
        assertEquals("me.magnum.melonds.LAUNCH_ROM", spec.action)
        assertEquals("me.magnum.melonds", spec.packageName)
        assertEquals("me.magnum.melonds.ui.emulator.EmulatorActivity", spec.className)
        assertEquals(mapOf("uri" to "content://doc/1"), spec.extras)
        assertTrue(spec.grantRead)
        assertTrue(!spec.clearTask)
    }

    @Test
    fun `ppsspp gets a VIEW of the document uri with a read grant`() {
        val spec = LaunchSpecs.forGame(game(GameSystem.PSP, "c.iso"), "content://doc/2").getOrThrow()
        assertEquals("android.intent.action.VIEW", spec.action)
        assertEquals("org.ppsspp.ppsspp", spec.packageName)
        assertEquals("content://doc/2", spec.dataUri)
        assertTrue(spec.grantRead)
        assertTrue(spec.extras.isEmpty())
    }

    // ---- launcher honesty

    @Test
    fun `a started launch says Opening, and only then`() {
        val g = game(GameSystem.PSX, "Harvest Moon.cue")
        val ok = GameLauncher(isInstalled = { true }, start = {}).launch(g, "u")
        assertEquals("Opening Harvest Moon in RetroArch", ok.message())
    }

    @Test
    fun `a missing emulator is named and nothing is started`() {
        var started = false
        val out = GameLauncher(isInstalled = { false }, start = { started = true })
            .launch(game(GameSystem.PSX, "Harvest Moon.cue"), "u")
        assertEquals("RetroArch isn't installed, so Harvest Moon can't start.", out.message())
        assertTrue(!started)
    }

    @Test
    fun `a launch that throws is a failure, never Opening`() {
        val out = GameLauncher(isInstalled = { true }, start = { throw IllegalStateException("denied") })
            .launch(game(GameSystem.GBA, "e.gba"), "u")
        assertTrue(out is LaunchOutcome.Failed)
        assertEquals("e didn't start: denied", out.message())
    }

    @Test
    fun `a native game launches by package and says Opening with no emulator`() {
        val app = AndroidGame("com.flowdrive.phone", "Flow Drive")
        val out = GameLauncher(isInstalled = { true }, start = {}, startApp = { true }).launchApp(app)
        assertEquals("Opening Flow Drive", out.message())
    }

    @Test
    fun `a native game with no launch intent is reported as not installed`() {
        val app = AndroidGame("com.flowdrive.phone", "Flow Drive")
        val out = GameLauncher(isInstalled = { true }, start = {}, startApp = { false }).launchApp(app)
        assertTrue(out is LaunchOutcome.Missing)
    }

    @Test
    fun `a native game launch that throws says so`() {
        val app = AndroidGame("com.flowdrive.phone", "Flow Drive")
        val out = GameLauncher(isInstalled = { true }, start = {}, startApp = { throw SecurityException("no") })
            .launchApp(app)
        assertEquals("Flow Drive didn't start: no", out.message())
    }

    // ---- android games filter

    @Test
    fun `game category with a launcher is shown, without a launcher or not a game is hidden`() {
        val apps = listOf(
            InstalledApp("com.flowdrive.phone", "Flow Drive", isGameCategory = true, hasLauncher = true),
            InstalledApp("com.engine.nolaunch", "Engine", isGameCategory = true, hasLauncher = false),
            InstalledApp("com.calc", "Calculator", isGameCategory = false, hasLauncher = true),
            InstalledApp("com.a", "Alpha", isGameCategory = true, hasLauncher = true),
        )
        assertEquals(listOf("Alpha", "Flow Drive"), AndroidGames.select(apps).map { it.label })
    }

    @Test
    fun `emulators that file themselves under games are not listed as games`() {
        val apps = listOf(
            InstalledApp("me.magnum.melonds", "melonDS", isGameCategory = true, hasLauncher = true),
            InstalledApp("org.ppsspp.ppsspp", "PPSSPP", isGameCategory = true, hasLauncher = true),
            InstalledApp("com.retroarch.aarch64", "RetroArch", isGameCategory = true, hasLauncher = true),
            InstalledApp("com.swordfish.lemuroid", "Lemuroid", isGameCategory = true, hasLauncher = true),
            InstalledApp("com.flowdrive.phone", "Flowdrive", isGameCategory = true, hasLauncher = true),
        )
        assertEquals(listOf("Flowdrive"), AndroidGames.select(apps).map { it.label })
    }

    @Test
    fun `no game apps gives an empty list so the shelf is hidden`() {
        assertTrue(AndroidGames.select(listOf(InstalledApp("a", "A", false, true))).isEmpty())
    }

    @Test
    fun `last played keys keep apps apart from rom document ids`() {
        val key = AndroidGames.key(AndroidGame("com.flowdrive.phone", "Flow Drive"))
        assertEquals("com.flowdrive.phone", AndroidGames.packageOf(key))
        assertNull(AndroidGames.packageOf("primary:ROMs/psx/x.cue"))
    }

    // ---- box art URL and escaping

    @Test
    fun `box art url follows the libretro thumbnails layout`() {
        val url = BoxArtUrl.forGame(game(GameSystem.PSX, "Harvest Moon - Back to Nature (USA).cue"))
        assertEquals(
            "https://raw.githubusercontent.com/libretro-thumbnails/Sony_-_PlayStation/master/Named_Boxarts/" +
                "Harvest%20Moon%20-%20Back%20to%20Nature%20%28USA%29.png",
            url,
        )
    }

    @Test
    fun `libretro forbidden characters become underscores before encoding`() {
        assertEquals("Q_bert _ Part 2_ Redux_", BoxArtUrl.libretroName("Q*bert & Part 2: Redux?"))
        assertEquals("a_b_c_d_e_f_g_h_i_j_k_l", BoxArtUrl.libretroName("a&b*c/d:e`f<g>h?i\\j|k\"l"))
        assertEquals("say _hi_", BoxArtUrl.libretroName("say \"hi\""))
    }

    @Test
    fun `non ascii is utf8 percent encoded and spaces are never plus`() {
        assertEquals("Pok%C3%A9mon%20Emerald", BoxArtUrl.encode("Pokémon Emerald"))
        assertEquals("a%2Bb", BoxArtUrl.encode("a+b"))
    }

    @Test
    fun `every system names its libretro thumbnail repo`() {
        assertEquals("Nintendo_-_Game_Boy_Advance", GameSystem.GBA.thumbnailRepo)
        assertEquals("Sega_-_Mega_Drive_-_Genesis", GameSystem.GENESIS.thumbnailRepo)
        assertEquals("Sony_-_PlayStation_Portable", GameSystem.PSP.thumbnailRepo)
        assertEquals("Nintendo_-_Super_Nintendo_Entertainment_System", GameSystem.SNES.thumbnailRepo)
    }

    // ---- cache

    private class FakeFetcher(var result: FetchResult) : BoxArtFetcher {
        var calls = 0
        override fun fetch(url: String): FetchResult {
            calls++
            return result
        }
    }

    @Test
    fun `a fetched cover is cached and not fetched again`() = runBlocking {
        val fetcher = FakeFetcher(FetchResult.Found(byteArrayOf(1, 2, 3)))
        val cache = BoxArtCache(File(tmp.root, "art"), fetcher)
        val g = game(GameSystem.GBA, "e.gba")
        assertNotNull(cache.get(g))
        assertNotNull(cache.get(g))
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun `a 404 is recorded and not refetched until a week passes`() = runBlocking {
        var now = 1_000L
        val fetcher = FakeFetcher(FetchResult.NotFound)
        val cache = BoxArtCache(File(tmp.root, "art"), fetcher, now = { now })
        val g = game(GameSystem.GBA, "e.gba")
        assertNull(cache.get(g))
        assertNull(cache.get(g))
        assertEquals(1, fetcher.calls)
        now += BoxArtCache.NOT_FOUND_RETRY_MS + 1
        fetcher.result = FetchResult.Found(byteArrayOf(9))
        assertNotNull(cache.get(g))
        assertEquals(2, fetcher.calls)
    }

    @Test
    fun `being offline is retried after an hour, not after a week`() = runBlocking {
        var now = 1_000L
        val fetcher = FakeFetcher(FetchResult.Unreachable)
        val cache = BoxArtCache(File(tmp.root, "art"), fetcher, now = { now })
        val g = game(GameSystem.GBA, "e.gba")
        assertNull(cache.get(g))
        assertNull(cache.get(g))
        assertEquals(1, fetcher.calls)
        now += BoxArtCache.UNREACHABLE_RETRY_MS + 1
        assertNull(cache.get(g))
        assertEquals(2, fetcher.calls)
    }

    // ---- last played label

    @Test
    fun `ago labels`() {
        val now = 10L * 24 * 60 * 60 * 1000
        assertEquals("just now", agoLabel(now, now - 5_000))
        assertEquals("35 min ago", agoLabel(now, now - 35 * 60_000L))
        assertEquals("2 h ago", agoLabel(now, now - 2 * 60 * 60_000L))
        assertEquals("yesterday", agoLabel(now, now - 30 * 60 * 60_000L))
        assertEquals("3 days ago", agoLabel(now, now - 3 * 24 * 60 * 60_000L))
    }
}
