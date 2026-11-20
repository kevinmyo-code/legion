package com.kevin.midnightai.ui.cruise

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.kevin.midnightai.location.LocationController
import com.kevin.midnightai.navigation.NavRuntime
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.common.location.toCommonLocation
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.scalebar.scalebar
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions

/**
 * The Mapbox map in the NAV face (HU-1 T5): a Maps SDK [MapView] in [AndroidView], the night traffic
 * style, a puck fed with the phone's fixes ([NavigationLocationProvider]: the map-matched location
 * while guiding, the raw phone fix otherwise; never this unit's own GPS), the route line, and a
 * camera that follows the car until the driver moves the map ([onMovedByHand]; RECENTER follows
 * again). Mapbox's logo and attribution stay (their terms); scale bar and compass are off.
 *
 * Failures go to [onFailed] in words. Building the view arms [NavRuntime]'s crash guard; the style
 * loading, an error or leaving disarms it. The map's own rendering is the approved exception to the
 * frame-clock motion rule (headunit/CLAUDE.md §12).
 */
@Composable
internal fun NavMap(
    following: Boolean,
    stale: Boolean,
    panAllowed: Boolean,
    onMovedByHand: () -> Unit,
    onFailed: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val failed by rememberUpdatedState(onFailed)
    val moved by rememberUpdatedState(onMovedByHand)
    val mapView = remember {
        NavRuntime.mapStarting()
        runCatching { MapView(context) }.onFailure {
            NavRuntime.mapStarted()
            failed("the map view could not be created (${it.javaClass.simpleName})")
        }.getOrNull()
    } ?: return
    val puckSource = remember { NavigationLocationProvider() }
    val routeLineApi = remember { MapboxRouteLineApi(MapboxRouteLineApiOptions.Builder().build()) }
    val routeLineView = remember { MapboxRouteLineView(MapboxRouteLineViewOptions.Builder(context).build()) }
    var styleGen by remember { mutableIntStateOf(0) }

    DisposableEffect(mapView) {
        mapView.location.setLocationProvider(puckSource)
        mapView.location.updateSettings {
            enabled = true
            locationPuck = createDefault2DPuck(withBearing = true)
        }
        mapView.scalebar.updateSettings { enabled = false }
        mapView.compass.updateSettings { enabled = false }
        val moveListener = object : OnMoveListener {
            override fun onMoveBegin(detector: MoveGestureDetector) = moved()
            override fun onMove(detector: MoveGestureDetector): Boolean = false
            override fun onMoveEnd(detector: MoveGestureDetector) = Unit
        }
        mapView.gestures.addOnMoveListener(moveListener)
        val styleLoaded = mapView.mapboxMap.subscribeStyleLoaded {
            NavRuntime.mapStarted()
            styleGen++
        }
        val loadErrors = mapView.mapboxMap.subscribeMapLoadingError { error ->
            NavRuntime.mapStarted()
            if (mapView.mapboxMap.style == null) failed("the map did not load (${error.message})")
        }
        mapView.mapboxMap.loadStyle(Style.TRAFFIC_NIGHT)
        onDispose {
            mapView.gestures.removeOnMoveListener(moveListener)
            styleLoaded.cancel()
            loadErrors.cancel()
            routeLineApi.cancel()
            routeLineView.cancel()
            mapView.onDestroy()
            NavRuntime.mapStarted()
        }
    }

    // The puck and the camera follow the phone's fixes (matched while guiding).
    val raw by LocationController.navFixes.collectAsState()
    val matched by NavRuntime.feed.matched.collectAsState()
    LaunchedEffect(raw, matched, following) {
        val location = matched ?: raw?.let { fix -> runCatching { fix.toCommonLocation() }.getOrNull() } ?: return@LaunchedEffect
        puckSource.changePosition(location, emptyList())
        if (following) {
            mapView.camera.easeTo(
                CameraOptions.Builder()
                    .center(Point.fromLngLat(location.longitude, location.latitude))
                    .zoom(FOLLOW_ZOOM)
                    .pitch(FOLLOW_PITCH)
                    .bearing(location.bearing ?: mapView.mapboxMap.cameraState.bearing)
                    .build(),
                MapAnimationOptions.mapAnimationOptions { duration(CAMERA_MS) },
            )
        }
    }

    // Review item 5: no panning, zooming, tilting or turning the map while the car moves.
    LaunchedEffect(panAllowed) {
        mapView.gestures.updateSettings {
            scrollEnabled = panAllowed
            pinchToZoomEnabled = panAllowed
            doubleTapToZoomInEnabled = panAllowed
            rotateEnabled = panAllowed
            pitchEnabled = panAllowed
        }
    }

    // Review NAV-1: a stale position is drawn faded, never as live.
    LaunchedEffect(stale) {
        mapView.location.updateSettings {
            locationPuck = createDefault2DPuck(withBearing = true).let { if (stale) it.copy(opacity = STALE_PUCK_OPACITY) else it }
        }
    }

    // The route line: the preview's route, then the navigator's.
    val routes by NavRuntime.feed.routes.collectAsState()
    LaunchedEffect(routes, styleGen) {
        val style = mapView.mapboxMap.style ?: return@LaunchedEffect
        if (routes.isEmpty()) {
            routeLineApi.clearRouteLine { routeLineView.renderClearRouteLineValue(style, it) }
        } else {
            routeLineApi.setNavigationRoutes(routes) { routeLineView.renderRouteDrawData(style, it) }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private const val FOLLOW_ZOOM = 15.5
private const val FOLLOW_PITCH = 45.0
private const val CAMERA_MS = 800L
private const val STALE_PUCK_OPACITY = 0.35f
