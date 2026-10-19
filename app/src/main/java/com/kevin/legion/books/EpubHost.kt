@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.books

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnAttach
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl

private const val NAVIGATOR_TAG = "legion-epub-navigator"

/** Pure-ish: the Readium preferences for a [ReaderSettings] and [ReaderPalette]. Publisher styles are off so the font and colours apply. */
@OptIn(ExperimentalReadiumApi::class)
internal fun epubPreferences(settings: ReaderSettings, palette: ReaderPalette) = EpubPreferences(
    backgroundColor = ReadiumColor(palette.page.toArgb()),
    textColor = ReadiumColor(palette.text.toArgb()),
    fontFamily = FontFamily.SERIF,
    fontSize = settings.epubFontScale,
    publisherStyles = false,
)

/**
 * Readium's `EpubNavigatorFragment` as a Compose page. The fragment needs a fragment factory set on
 * the host activity BEFORE it is added, and a container that is already in the window, so it is
 * added once the container attaches. [onControl] hands the view model the [EpubControl] to drive it,
 * and null when the page leaves composition.
 */
@OptIn(ExperimentalReadiumApi::class)
@Composable
fun EpubNavigatorHost(
    activity: FragmentActivity,
    session: EpubSession,
    startLocatorJson: String?,
    settings: ReaderSettings,
    palette: ReaderPalette,
    onLocator: (Locator) -> Unit,
    onControl: (EpubControl?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var container by remember { mutableStateOf<FragmentContainerView?>(null) }
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val currentPalette by rememberUpdatedState(palette)
    val currentOnLocator by rememberUpdatedState(onLocator)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            FragmentContainerView(context).apply {
                id = View.generateViewId()
                doOnAttach { container = this }
            }
        },
    )

    LaunchedEffect(container) {
        val view = container ?: return@LaunchedEffect
        val start = startLocatorJson?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        val fragments = activity.supportFragmentManager
        fragments.fragmentFactory = EpubNavigatorFactory(session.publication).createFragmentFactory(
            initialLocator = start,
            initialPreferences = epubPreferences(settings, palette),
            listener = object : EpubNavigatorFragment.Listener {
                // An external link in a book is not followed: nothing is fetched (ADR 0061).
                override fun onExternalLinkActivated(url: AbsoluteUrl) = Unit
            },
        )
        fragments.beginTransaction()
            .add(view.id, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
            .commitNow()
        navigator = fragments.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
    }

    val nav = navigator
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(nav) {
        nav?.currentLocator?.collect { currentOnLocator(it) }
    }
    LaunchedEffect(nav, settings, palette) {
        nav?.submitPreferences(epubPreferences(settings, palette))
    }
    DisposableEffect(nav) {
        onControl(nav?.let { EpubFragmentControl(it, scope) { currentPalette } })
        onDispose { onControl(null) }
    }
}

/** [EpubControl] over the live navigator fragment. */
@OptIn(ExperimentalReadiumApi::class)
private class EpubFragmentControl(
    private val navigator: EpubNavigatorFragment,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val palette: () -> ReaderPalette,
) : EpubControl {
    override fun goToLink(link: Link) {
        navigator.go(link, animated = false)
    }

    override fun goToLocator(locatorJson: String) {
        runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull()?.let { navigator.go(it, animated = false) }
    }

    override suspend fun firstVisibleLocatorJson(): String? = navigator.firstVisibleElementLocator()?.toJSON()?.toString()

    override fun highlight(locatorJson: String?) {
        val locator = locatorJson?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        val decorations = if (locator == null) {
            emptyList()
        } else {
            listOf(
                Decoration(
                    id = "tts",
                    locator = locator,
                    style = Decoration.Style.Highlight(tint = palette().ttsMark.toArgb()),
                ),
            )
        }
        scope.launch { (navigator as DecorableNavigator).applyDecorations(decorations, "tts") }
        locator?.let { navigator.go(it, animated = false) }
    }

    override fun applySettings(settings: ReaderSettings) {
        navigator.submitPreferences(epubPreferences(settings, palette()))
    }
}
