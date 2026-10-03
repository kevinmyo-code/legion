package com.kevin.legion.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.navigation.NavDestinations
import com.kevin.legion.navigation.NavFormat
import com.kevin.legion.navigation.NavPhase
import com.kevin.legion.navigation.NavState
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView

/**
 * `settings/nav-spike` - the mapbox-nav spike screen (`.scratch/mapbox-nav/`, ADR 0054). Ugly on
 * purpose: proves the SDK builds and runs one guided route. Not the nav screen of ticket 06, which
 * replaces it. Reached from Settings, Permissions and diagnostics, "Navigation spike".
 *
 * With no Mapbox token the map is NOT created at all: a [MapView] with no token draws a blank
 * surface, and the words in [NavFormat.NOT_SET_UP] are what the screen says instead.
 */
@Composable
fun NavSpikeScreen(onBack: () -> Unit, viewModel: NavSpikeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val routes by viewModel.routes.collectAsStateWithLifecycle()

    // A trip must never outlive its screen (ticket 07). Leaving composition stops the session.
    DisposableEffect(Unit) {
        onDispose { viewModel.onScreenLeft() }
    }

    NavSpikeContent(
        state = state,
        routes = routes,
        onRoute = viewModel::routeToTestDestination,
        onStop = viewModel::stop,
        onBack = onBack,
    )
}

@Composable
fun NavSpikeContent(
    state: NavState,
    routes: List<NavigationRoute>,
    onRoute: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Navigation spike", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text(state.message, style = MaterialTheme.typography.bodyMedium)
        state.progress?.let { p ->
            Text(p.nextManeuver ?: "Waiting for the first instruction.", style = MaterialTheme.typography.titleMedium)
            Text(NavFormat.progressLine(p), style = MaterialTheme.typography.bodyLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canStart = state.phase in setOf(NavPhase.IDLE, NavPhase.ARRIVED, NavPhase.FAILED)
            Button(onClick = onRoute, enabled = canStart) { Text("Route to test destination") }
            OutlinedButton(
                onClick = onStop,
                enabled = state.phase == NavPhase.GUIDING || state.phase == NavPhase.REQUESTING,
            ) { Text("Stop") }
        }
        if (state.phase == NavPhase.NOT_SET_UP) {
            // Never a blank map: say what is missing instead of drawing a surface that cannot load.
            Text(NavFormat.NOT_SET_UP, style = MaterialTheme.typography.bodyMedium)
        } else {
            RouteMap(routes, Modifier.fillMaxWidth().height(MAP_HEIGHT))
        }
    }
}

/** A Maps SDK [MapView] hosted in [AndroidView] (Nav UI widgets are Views; no maps-compose). */
@Composable
private fun RouteMap(routes: List<NavigationRoute>, modifier: Modifier) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val routeLineApi = remember { MapboxRouteLineApi(MapboxRouteLineApiOptions.Builder().build()) }
    val routeLineView = remember { MapboxRouteLineView(MapboxRouteLineViewOptions.Builder(context).build()) }

    DisposableEffect(mapView) {
        val hasFix = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (hasFix) mapView.location.updateSettings { enabled = true }
        val start = NavDestinations.HOUSTON_TEST
        mapView.mapboxMap.setCamera(
            CameraOptions.Builder().center(Point.fromLngLat(start.longitude, start.latitude)).zoom(START_ZOOM).build(),
        )
        onDispose {
            routeLineApi.cancel()
            routeLineView.cancel()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(routes) {
        val style = mapView.mapboxMap.style ?: return@LaunchedEffect
        if (routes.isEmpty()) {
            routeLineApi.clearRouteLine { routeLineView.renderClearRouteLineValue(style, it) }
        } else {
            routeLineApi.setNavigationRoutes(routes) { routeLineView.renderRouteDrawData(style, it) }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private val MAP_HEIGHT = 420.dp
private const val START_ZOOM = 10.0
