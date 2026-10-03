package com.kevin.legion.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.location.LocationController
import com.kevin.legion.navigation.NavCameraMode
import com.kevin.legion.navigation.NavFormat
import com.kevin.legion.navigation.NavMapFeed
import com.kevin.legion.navigation.RouteFailure
import kotlinx.coroutines.delay
import com.kevin.legion.navigation.NavPhase
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.SoftColors
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.geojson.utils.PolylineUtils
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.logo.logo
import com.mapbox.maps.plugin.scalebar.scalebar
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraStateChangedObserver
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineColorResources
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView

/** Where the map's own view of the world is, for the words over it (device-run defect 1). */
enum class MapStatus { LOADING, UNREACHABLE, READY }

/**
 * How much of the map the screen's own chrome covers, measured in pixels (device-run defect 3): the
 * top overlay (back button or turn banner) and the bottom sheet. Every camera mode pads by these.
 */
data class NavMapInsets(val topPx: Int = 0, val bottomPx: Int = 0)

/**
 * The nav screen's map: a Maps SDK [MapView] in [AndroidView] (the Nav UI widgets are Views; no
 * maps-compose), drawing from [feed] (the raw routes and live progress the SDK seam publishes) and
 * the controller's phase, selected route and camera mode.
 *
 * What the spike left broken, fixed here (ticket 10's "Spike findings"):
 *  - **The route line is drawn after the style loads and redrawn on every style reload** ([styleGen]
 *    bumps on each load and the draw effect re-keys on it). The spike's draw ran once, found no
 *    style, and never retried.
 *  - **The location puck is on** and the camera is put on the user's fix, not downtown Houston.
 *  - **The camera follows the user while guiding** and frames the whole route in preview and in
 *    overview.
 *  - **The MapView is built with an AppCompat-themed context**: the compass, logo and attribution
 *    views inflate AppCompat widgets and logged `ThemeUtils` errors under the activity's theme.
 *
 * **Device-run fixes (2026-10-03):** the camera pads by the MEASURED banner and sheet ([insets]) in
 * preview fit, overview and follow; the scale bar and compass are off (they drew over the back
 * button, the banner and the overview button); the logo and attribution sit above the sheet instead
 * of under it; a style that fails to load reports [MapStatus] so the screen can say so, and a
 * network-shaped failure is retried; preview alternatives are dashed with butt caps (round caps
 * closed the gaps) and the guided route line's alternatives are grey.
 *
 * Preview draws its own lines (primary solid, alternatives dashed, per the design of record);
 * guiding hands the routes to the SDK's route line, which draws traffic. The two never draw at once.
 */
// One effect per concern is the readable shape for an AndroidView map.
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun NavMap(
    feed: NavMapFeed,
    phase: NavPhase,
    selectedRoute: Int,
    camera: NavCameraMode,
    insets: NavMapInsets,
    onAuthFailure: () -> Unit,
    onCameraDetached: () -> Unit,
    onStatus: (MapStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val themed = remember(context) {
        ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
    }
    val mapView = remember { MapView(themed) }
    val routeLineApi = remember { MapboxRouteLineApi(MapboxRouteLineApiOptions.Builder().build()) }
    val routeLineView = remember {
        MapboxRouteLineView(
            MapboxRouteLineViewOptions.Builder(themed)
                .routeLineColorResources(
                    // The SDK's route line cannot dash; a low-contrast grey keeps the alternative clearly
                    // secondary to the blue primary (layout A).
                    RouteLineColorResources.Builder()
                        .alternativeRouteDefaultColor(SoftColors.text3.toArgb())
                        .alternativeRouteUnknownCongestionColor(SoftColors.text3.toArgb())
                        .alternativeRouteLowCongestionColor(SoftColors.text3.toArgb())
                        .alternativeRouteModerateCongestionColor(SoftColors.text3.toArgb())
                        .alternativeRouteHeavyCongestionColor(SoftColors.text3.toArgb())
                        .alternativeRouteSevereCongestionColor(SoftColors.text3.toArgb())
                        .alternativeRouteCasingColor(SoftColors.ground.toArgb())
                        .build(),
                )
                .build(),
        )
    }
    val viewport = remember { MapboxNavigationViewportDataSource(mapView.mapboxMap) }
    val navCamera = remember { NavigationCamera(mapView.mapboxMap, mapView.camera, viewport) }

    val routes by feed.routes.collectAsStateWithLifecycle()
    val progress by feed.progress.collectAsStateWithLifecycle()
    val matched by feed.location.collectAsStateWithLifecycle()
    val deviceFix by LocationController.state.collectAsStateWithLifecycle()
    var styleGen by remember { mutableIntStateOf(0) }
    var positioned by remember { mutableIntStateOf(0) }

    var styleFailGen by remember { mutableIntStateOf(0) }
    val report by rememberUpdatedState(onStatus)

    val marginPx = with(density) { CHROME_MARGIN.toPx().toDouble() }
    val sidePx = with(density) { SIDE_PADDING.toPx().toDouble() }
    val currentInsets by rememberUpdatedState(insets)

    // The camera's padding: what the chrome measured, plus a margin, never more than 60 percent of the
    // map's height from the bottom (a sheet grown by a panel must not squeeze the route to nothing).
    fun edge(): EdgeInsets {
        val maxBottom = if (mapView.height > 0) mapView.height * MAX_BOTTOM_SHARE else Double.MAX_VALUE
        val top = currentInsets.topPx + marginPx
        val bottom = minOf(currentInsets.bottomPx.toDouble(), maxBottom) + marginPx
        return EdgeInsets(top, sidePx, bottom, sidePx)
    }

    DisposableEffect(mapView) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            mapView.location.updateSettings {
                enabled = true
                pulsingEnabled = true
                locationPuck = createDefault2DPuck(withBearing = true)
            }
        }
        // Nothing of the map's own chrome may draw over ours (device-run defect 2): the scale bar sat on
        // the back button and the banner, the compass under the overview button.
        mapView.scalebar.updateSettings { enabled = false }
        mapView.compass.updateSettings { enabled = false }
        mapView.camera.addCameraAnimationsLifecycleListener(NavigationBasicGesturesHandler(navCamera))
        var styleReady = false
        val styleLoaded = mapView.mapboxMap.subscribeStyleLoaded {
            styleReady = true
            styleGen++
            report(MapStatus.READY)
        }
        // Mapbox answers a bad token with a style/tile load error; say so in words, never a blank map.
        // Before the style has loaded, a network-shaped error is the "could not be reached" state and
        // earns a retry (the A25 sat on a black map for 25 s with ERR_NAME_NOT_RESOLVED and no words).
        val loadErrors = mapView.mapboxMap.subscribeMapLoadingError { error ->
            if (NavFormat.isAuthFailure(error.message)) {
                onAuthFailure()
            } else if (!styleReady) {
                if (NavFormat.classifyFailure(null, error.message, null) == RouteFailure.OFFLINE) {
                    report(MapStatus.UNREACHABLE)
                }
                styleFailGen++
            }
        }
        report(MapStatus.LOADING)
        // IDLE is also the camera's state before anything has asked it to follow, so a detach is only
        // reported once it has been engaged (following or overview) and then fell back to IDLE.
        var engaged = false
        val cameraStates = NavigationCameraStateChangedObserver { state ->
            if (state == NavigationCameraState.IDLE) {
                if (engaged) onCameraDetached()
            } else {
                engaged = true
            }
        }
        navCamera.registerNavigationCameraStateChangeObserver(cameraStates)
        mapView.mapboxMap.loadStyle(Style.DARK)
        onDispose {
            navCamera.unregisterNavigationCameraStateChangeObserver(cameraStates)
            styleLoaded.cancel()
            loadErrors.cancel()
            routeLineApi.cancel()
            routeLineView.cancel()
            viewport.onDestroy()
            mapView.onDestroy()
        }
    }

    // Retry a failed style load after a pause. Event-driven (one retry per reported failure) so it
    // never cancels a load that is still in flight.
    LaunchedEffect(styleFailGen) {
        if (styleFailGen == 0) return@LaunchedEffect
        delay(STYLE_RETRY_MS)
        if (mapView.mapboxMap.style == null) mapView.mapboxMap.loadStyle(Style.DARK)
    }

    // Camera padding and the map's own attribution follow the measured chrome (device-run defect 3).
    LaunchedEffect(insets) {
        val e = edge()
        viewport.followingPadding = e
        viewport.overviewPadding = e
        viewport.evaluate()
        // Mapbox's logo and attribution must stay visible (their terms): above the sheet, not under it.
        val lift = (insets.bottomPx + marginPx).toFloat()
        mapView.logo.updateSettings { marginBottom = lift }
        mapView.attribution.updateSettings { marginBottom = lift }
    }

    // Put the idle camera on the user once a fix exists; never downtown Houston, never every fix.
    LaunchedEffect(deviceFix != null, phase) {
        val fix = deviceFix
        val idle = phase != NavPhase.GUIDING && phase != NavPhase.PREVIEW
        if (fix != null && positioned == 0 && idle) {
            mapView.mapboxMap.setCamera(
                CameraOptions.Builder().center(Point.fromLngLat(fix.longitude, fix.latitude)).zoom(IDLE_ZOOM).build(),
            )
            positioned = 1
        } else if (fix == null && positioned == 0) {
            mapView.mapboxMap.setCamera(
                CameraOptions.Builder().center(Point.fromLngLat(US_LNG, US_LAT)).zoom(NO_FIX_ZOOM).build(),
            )
        }
    }

    // Route drawing. Re-keyed on styleGen so a style reload redraws it; skipped (and retried by the
    // next styleGen) only when no style exists yet.
    LaunchedEffect(styleGen, routes, selectedRoute, phase) {
        val style = mapView.mapboxMap.style ?: return@LaunchedEffect
        clearPreview(style)
        when {
            phase == NavPhase.PREVIEW && routes.isNotEmpty() -> {
                routeLineApi.clearRouteLine { routeLineView.renderClearRouteLineValue(style, it) }
                drawPreview(style, routes, selectedRoute)
            }
            phase == NavPhase.GUIDING && routes.isNotEmpty() ->
                routeLineApi.setNavigationRoutes(routes) { routeLineView.renderRouteDrawData(style, it) }
            else -> routeLineApi.clearRouteLine { routeLineView.renderClearRouteLineValue(style, it) }
        }
    }

    // Guided camera: viewport data in, camera mode out.
    LaunchedEffect(routes, phase) {
        if (phase == NavPhase.GUIDING && routes.isNotEmpty()) {
            viewport.onRouteChanged(routes.first())
        } else {
            viewport.clearRouteData()
        }
        viewport.evaluate()
    }
    LaunchedEffect(progress) {
        val p = progress ?: return@LaunchedEffect
        viewport.onRouteProgressChanged(p)
        val style = mapView.mapboxMap.style
        if (style != null) routeLineApi.updateWithRouteProgress(p) { routeLineView.renderRouteLineUpdate(style, it) }
        viewport.evaluate()
    }
    LaunchedEffect(matched) {
        val l = matched ?: return@LaunchedEffect
        viewport.onLocationChanged(l)
        viewport.evaluate()
    }
    LaunchedEffect(camera, phase, routes.isNotEmpty()) {
        if (phase != NavPhase.GUIDING) return@LaunchedEffect
        when (camera) {
            NavCameraMode.FOLLOWING -> navCamera.requestNavigationCameraToFollowing()
            NavCameraMode.OVERVIEW -> navCamera.requestNavigationCameraToOverview()
            NavCameraMode.FREE -> Unit
        }
    }
    // Preview fit and preview overview are one framing: the whole selected route, inside the measured
    // chrome. Re-run when the chrome's size changes (its first measurement lands after the first frame)
    // after a short pause so a sheet growing a row does not chase the camera.
    LaunchedEffect(phase, routes, selectedRoute, camera, insets) {
        if (phase == NavPhase.PREVIEW && routes.isNotEmpty()) {
            delay(FRAME_SETTLE_MS)
            frame(mapView, routes.getOrNull(selectedRoute) ?: routes.first(), edge())
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun clearPreview(style: Style) {
    for (i in 0 until PREVIEW_SLOTS) {
        style.removeStyleLayer("$PREVIEW_LINE_ID$i")
        style.removeStyleLayer("$PREVIEW_CASING_ID$i")
        style.removeStyleSource("$PREVIEW_SOURCE_ID$i")
    }
    style.removeStyleLayer(PREVIEW_DEST_ID)
    style.removeStyleSource(PREVIEW_DEST_ID)
}

/** Alternatives dashed in grey first, then the selected route's casing and line on top (design of record: layout A). */
private fun drawPreview(style: Style, routes: List<NavigationRoute>, selected: Int) {
    val shown = routes.take(PREVIEW_SLOTS)
    val order = shown.indices.filter { it != selected } + shown.indices.filter { it == selected }
    for (i in order) {
        val geometry = shown[i].directionsRoute.geometry() ?: continue
        val line = LineString.fromPolyline(geometry, POLYLINE_PRECISION)
        style.addSource(geoJsonSource("$PREVIEW_SOURCE_ID$i") { geometry(line) })
        if (i == selected) {
            style.addLayer(
                lineLayer("$PREVIEW_CASING_ID$i", "$PREVIEW_SOURCE_ID$i") {
                    lineColor(AreaAccent.FLEET.container.toArgb())
                    lineWidth(CASING_WIDTH)
                    lineCap(LineCap.ROUND)
                    lineJoin(LineJoin.ROUND)
                },
            )
            style.addLayer(
                lineLayer("$PREVIEW_LINE_ID$i", "$PREVIEW_SOURCE_ID$i") {
                    lineColor(AreaAccent.FLEET.onContainer.toArgb())
                    lineWidth(LINE_WIDTH)
                    lineCap(LineCap.ROUND)
                    lineJoin(LineJoin.ROUND)
                },
            )
        } else {
            style.addLayer(
                lineLayer("$PREVIEW_LINE_ID$i", "$PREVIEW_SOURCE_ID$i") {
                    lineColor(SoftColors.text3.toArgb())
                    lineWidth(ALT_LINE_WIDTH)
                    lineDasharray(listOf(DASH, DASH_GAP))
                    // Butt, not round: a round cap grows each dash by half the width and closes the gap.
                    lineCap(LineCap.BUTT)
                    lineJoin(LineJoin.ROUND)
                },
            )
        }
    }
    val end = shown.getOrNull(selected)?.directionsRoute?.geometry()
        ?.let { PolylineUtils.decode(it, POLYLINE_PRECISION).lastOrNull() }
    if (end != null) {
        style.addSource(geoJsonSource(PREVIEW_DEST_ID) { geometry(end) })
        style.addLayer(
            circleLayer(PREVIEW_DEST_ID, PREVIEW_DEST_ID) {
                circleColor(SoftColors.primary.toArgb())
                circleRadius(DEST_RADIUS)
                circleStrokeColor(SoftColors.ground.toArgb())
                circleStrokeWidth(DEST_STROKE)
            },
        )
    }
}

/** Fit the camera to [route] with room for the banner and the bottom sheet. */
private fun frame(mapView: MapView, route: NavigationRoute, padding: EdgeInsets) {
    val geometry = route.directionsRoute.geometry() ?: return
    val points = PolylineUtils.decode(geometry, POLYLINE_PRECISION)
    if (points.size < 2) return
    // The callback form: the synchronous overload is a delicate API (it reads the map size before layout).
    mapView.mapboxMap.cameraForCoordinates(
        points,
        CameraOptions.Builder().build(),
        padding,
        null,
        null,
    ) { camera -> mapView.mapboxMap.setCamera(camera) }
}

private const val PREVIEW_SLOTS = 3
private const val PREVIEW_SOURCE_ID = "nav-preview-src-"
private const val PREVIEW_LINE_ID = "nav-preview-line-"
private const val PREVIEW_CASING_ID = "nav-preview-casing-"
private const val PREVIEW_DEST_ID = "nav-preview-dest"
private const val POLYLINE_PRECISION = 6
private const val LINE_WIDTH = 7.0
private const val CASING_WIDTH = 13.0
private const val ALT_LINE_WIDTH = 6.0
private const val DASH = 2.0
private const val DASH_GAP = 1.5
private const val STYLE_RETRY_MS = 5_000L
private const val FRAME_SETTLE_MS = 150L
private const val MAX_BOTTOM_SHARE = 0.6
private const val DEST_RADIUS = 9.0
private const val DEST_STROKE = 3.0
private const val IDLE_ZOOM = 13.0
private const val NO_FIX_ZOOM = 3.0
private const val US_LNG = -98.5
private const val US_LAT = 39.8
private val CHROME_MARGIN = 16.dp

/** Wide enough to clear the round buttons down the right edge. */
private val SIDE_PADDING = 64.dp
