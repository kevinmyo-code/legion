package com.kevin.midnightai.ui.theme

import android.content.Context
import com.kevin.midnightai.vehicle.ActiveVehicle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The night-surface colorway. Users pick between a handful of art-directed
 * Showa palettes (Appearance settings) — never a free color wheel, so every
 * combination stays on-aesthetic. [AriaColors] delegates to the active palette
 * ([AriaPalette.current], a Compose state), so a switch re-skins the whole app
 * live. The aged-paper logbook surface is intentionally NOT part of this:
 * paper is paper regardless of the night outside.
 */
data class NightPalette(
    val key: String,
    val label: String,
    val background: Color,
    val panel: Color,
    val panelBorder: Color,
    val magenta: Color,      // hot accent: active / listening
    val amber: Color,        // instrument readouts
    val cyan: Color,
    val violet: Color,
    val sunsetOrange: Color,
    val sunsetGold: Color,
    val textPrimary: Color,
    val textDim: Color,
    /**
     * True when this palette's accents are too pale to read as TEXT on its own surfaces
     * (a light palette: coral / turquoise / butter on cream and sky all sit under 2.4:1).
     * Accent-role text then falls back to [textPrimary]; rings, fills and bars keep the
     * accent. See [accentText].
     */
    val inkAccentText: Boolean = false,
) {
    /** The colour TEXT in an accent role should use on this palette (WCAG >= 4.5:1). */
    fun accentText(accent: Color): Color = if (inkAccentText) textPrimary else accent

    // Deep "unlit neon" versions of the hot accents (glows, wells).
    val magentaDim: Color get() = lerp(magenta, Color.Black, 0.77f)
    val amberDim: Color get() = lerp(amber, Color.Black, 0.77f)
}

object AriaPalette {
    /** The de-purpled teal-charcoal night that shipped first. */
    val MIDNIGHT_TEAL = NightPalette(
        key = "midnight_teal", label = "MIDNIGHT TEAL",
        background = Color(0xFF0D1418),
        panel = Color(0xFF172229),
        panelBorder = Color(0xFF2A3742),
        magenta = Color(0xFFFF2E97),
        amber = Color(0xFFFFB02E),
        cyan = Color(0xFF35E0E6),
        violet = Color(0xFF8B5CF6),
        sunsetOrange = Color(0xFFFF7A3D),
        sunsetGold = Color(0xFFFFC24B),
        textPrimary = Color(0xFFF0ECE0),
        textDim = Color(0xFF93A0A6),
    )

    /** The classic purple city-pop night — violet sky, hot pink, warm gold. */
    val SHOWA_PURPLE = NightPalette(
        key = "showa_purple", label = "SHOWA PURPLE",
        background = Color(0xFF120E1E),
        panel = Color(0xFF1D1830),
        panelBorder = Color(0xFF342B4E),
        magenta = Color(0xFFFF4FA3),
        amber = Color(0xFFFFB84D),
        cyan = Color(0xFF7FD9FF),
        violet = Color(0xFFA78BFA),
        sunsetOrange = Color(0xFFFF7A5C),
        sunsetGold = Color(0xFFFFD166),
        textPrimary = Color(0xFFF2EDF7),
        textDim = Color(0xFF9D93B3),
    )

    /** Warm dusk — burnt browns, coral, seafoam (the Eizin Suzuki sunset). */
    val SUNSET_DRIVE = NightPalette(
        key = "sunset_drive", label = "SUNSET DRIVE",
        background = Color(0xFF190F0D),
        panel = Color(0xFF261713),
        panelBorder = Color(0xFF41281F),
        magenta = Color(0xFFFF5E62),
        amber = Color(0xFFFFA02E),
        cyan = Color(0xFF64D8C6),
        violet = Color(0xFFB07CD6),
        sunsetOrange = Color(0xFFFF7A3D),
        sunsetGold = Color(0xFFFFC24B),
        textPrimary = Color(0xFFF5EBDD),
        textDim = Color(0xFFA89686),
    )

    /** Deep navy coastal night — Pacific Breeze pinks over harbor blue. */
    val PACIFIC_NIGHT = NightPalette(
        key = "pacific_night", label = "PACIFIC NIGHT",
        background = Color(0xFF0A1220),
        panel = Color(0xFF13202F),
        panelBorder = Color(0xFF27384D),
        magenta = Color(0xFFFF6FB0),
        amber = Color(0xFFFFC24B),
        cyan = Color(0xFF35E0E6),
        violet = Color(0xFF8B5CF6),
        sunsetOrange = Color(0xFFFF8A50),
        sunsetGold = Color(0xFFFFD166),
        textPrimary = Color(0xFFF0F2EA),
        textDim = Color(0xFF8FA1B3),
    )

    /**
     * Lofi summer city-pop, not night. **The default (2026-09-27)** - the whole
     * look moved from cyberpunk night-neon to a bright Hiroshi Nagai / Eizin
     * Suzuki daytime coastal road (Kevin: "lofi summer cartoon vibes rather
     * than cyberpunk dark neon tokyo"), matching the bundled
     * `seed_wallpaper_summer_coast.webp` backdrop and the Uncle Moose avatar
     * in a coral Hawaiian shirt.
     *
     * [NightPalette] is still named for the night surface it was built for,
     * but every field is just a role slot ([AriaColors] delegates to whichever
     * palette is [current]) - reusing it here, rather than inventing a second
     * palette type, is what actually recolors Cruise/ControlPanel with zero
     * screen-code changes. `panel` is cream rather than dark: screens already
     * apply their own alpha on top (e.g. `AriaColors.Panel.copy(alpha = 0.7f)`),
     * so a light panel base reads as a translucent cream card over the bright
     * wallpaper instead of a dark glass one. `textPrimary`/`textDim` are both
     * navy ink so body text stays legible on that cream. `magenta` (the "hot
     * accent: active/listening" role) is coral; `amber` (instrument readouts)
     * is butter yellow; `cyan` (secondary) is pool turquoise.
     *
     * Lights Out is deliberately UNCHANGED - it never reads [AriaColors] at
     * all (its own hardcoded black + [LightsOutSwatch] system), so this
     * palette switch cannot touch the night-driving HUD.
     */
    val SUMMER_COAST = NightPalette(
        key = "summer_coast", label = "SUMMER COAST",
        background = Color(0xFF7EC8E3),   // sky blue
        panel = Color(0xFFFAF4E6),        // cream card, alpha applied at call sites
        panelBorder = Color(0xFF3CC7C9),  // pool turquoise
        magenta = Color(0xFFF2836B),      // coral - active/listening accent
        amber = Color(0xFFF6D776),        // butter yellow - instrument readouts
        cyan = Color(0xFF3CC7C9),         // pool turquoise - secondary accent
        violet = Color(0xFFB8A8D9),       // soft periwinkle, same family as SHOWA_PURPLE's violet
        sunsetOrange = Color(0xFFF2836B), // coral, same as magenta - one warm accent, not two
        sunsetGold = Color(0xFFF6D776),   // butter yellow, same as amber
        // 2026-10-13 (Kevin, in the car: blue and light-orange text unreadable over the
        // wallpaper): ink went neutral near-black, and accent-role text (listening label,
        // kanji stamp, link actions) now uses it via inkAccentText.
        textPrimary = Color(0xFF14161A),  // near-black ink (9.7:1 on sky, 16.5:1 on cream; was navy #0F1A2A)
        textDim = Color(0xFF2F343B),      // dark slate (6.7:1 on sky, 11.4:1 on cream; was blue-navy #34405A)
        inkAccentText = true,
    )

    /**
     * The same summer look after dark (2026-09-27, Kevin: "same art style but
     * dark so that it minimizes light pollution"). Not a pickable colorway:
     * the Cruise day/night button swaps to it on top of whichever palette is
     * [current], and it pairs with the night coast-road wallpaper.
     *
     * Purple scheme (2026-10-13, Kevin: night mode should switch the palette to
     * purple, not just the wallpaper). Deep violet grounds, lavender / orchid /
     * periwinkle accents, light lilac text that is still not pure white. Every
     * text role and accent clears 4.5:1 on background, panel and the deck card
     * (PaletteContrastTest), so accent-role text keeps its colour here.
     */
    val SUMMER_NIGHT = NightPalette(
        key = "summer_night", label = "SUMMER NIGHT",
        background = Color(0xFF140F24),   // deep violet
        panel = Color(0xFF221A38),
        panelBorder = Color(0xFF3A2E5C),
        magenta = Color(0xFFD08CFF),      // orchid violet - idle/speaking accent (7.0:1 on panel)
        amber = Color(0xFFE9B8FF),        // pale lilac - readouts/thinking (10.0:1)
        cyan = Color(0xFFA9B4FF),         // periwinkle - listening (8.4:1)
        violet = Color(0xFF9B7BEA),
        sunsetOrange = Color(0xFFF29AC8), // rose
        sunsetGold = Color(0xFFE9C98A),   // muted gold, the one warm counterpoint
        textPrimary = Color(0xFFE2D9F0),  // light lilac, never pure white
        textDim = Color(0xFFB3A6CC),      // 7.3:1 on panel
    )

    val ALL = listOf(SUMMER_COAST, MIDNIGHT_TEAL, SHOWA_PURPLE, SUNSET_DRIVE, PACIFIC_NIGHT)

    private const val PREFS = "theme"
    private const val KEY = "night_palette"

    /** The active palette; a Compose state, so reads inside composition re-skin live. */
    var current by mutableStateOf(SUMMER_COAST)
        private set

    /** Night wallpaper mode (the Cruise day/night button). Global, not per car. */
    var isNight by mutableStateOf(false)
        private set

    /** What [AriaColors] actually reads: [SUMMER_NIGHT] at night, else [current]. */
    val active: NightPalette get() = resolve(night = isNight, picked = current)

    /** Night mode overrides any picked colorway with the purple [SUMMER_NIGHT]. */
    fun resolve(night: Boolean, picked: NightPalette): NightPalette = if (night) SUMMER_NIGHT else picked

    private const val KEY_NIGHT = "night_wallpaper"

    fun setNight(context: Context, night: Boolean) {
        isNight = night
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NIGHT, night).apply()
        // The wallpaper is read in remember(bgVersion) blocks; bump it so they reload.
        com.kevin.midnightai.ui.AppBackground.notifyChanged()
    }

    // --- Per-car keying (car manager, 2026-07-16) ---------------------------
    //
    // The colorway belongs to the CAR, not the install: §1 makes the paid companion
    // the car itself, so the XJ running amber while the Outlander runs SHOWA PURPLE
    // is one authored look with colorways, not a theme system. This deliberately
    // does NOT decide ticket 05 - §12 already sanctions a curated Showa colorway
    // picker and bans a free colour picker, so this only scopes an existing,
    // already-approved feature per car.
    //
    // Device ergonomics (cassette variant, backdrop mode, gauge picks, saver
    // timeout) stay GLOBAL in CruiseSettings: your head unit's gauge choices
    // shouldn't follow you into another car on your phone.

    private fun key(vehicleId: String) = "$KEY:$vehicleId"

    /**
     * Reads a car's palette key, falling back to the LEGACY FLAT KEY.
     *
     * Same permanent read-through as [com.kevin.midnightai.ai.CompanionProfile]'s
     * identity keys, and for the same reason: a one-shot migration would have to
     * pick a vehicle id, and at boot the dongle often hasn't connected, so it would
     * land on `default` and the driver's palette would change under them the moment
     * the dongle came up.
     */
    private fun savedKey(context: Context, vehicleId: String): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(key(vehicleId), null) ?: p.getString(KEY, null)
    }

    /** Loads the active car's saved choice. Call once early, and again on a car switch. */
    fun init(context: Context) {
        val saved = savedKey(context, ActiveVehicle.current(context))
        current = ALL.firstOrNull { it.key == saved } ?: SUMMER_COAST
        isNight = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NIGHT, false)
    }

    /** Saves the palette for the ACTIVE car. Never writes the legacy flat key. */
    fun set(context: Context, palette: NightPalette) {
        current = palette
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key(ActiveVehicle.current(context)), palette.key).apply()
    }
}
