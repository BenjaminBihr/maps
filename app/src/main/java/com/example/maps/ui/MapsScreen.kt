package com.example.maps.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.maps.R
import com.example.maps.globe.Capitals
import com.example.maps.globe.GlobeLabel
import kotlinx.coroutines.delay

private val LOCATION_PERMISSIONS =
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** The controls slide in once the globe has mostly appeared. */
private const val CONTROLS_DELAY_MS = 1500L

/**
 * Asks for the location first, then plays the globe's appearance, slides the controls in
 * and dives onto the user when the permission was granted.
 */
@Composable
fun MapsScreen(state: MapsState = rememberMapsState()) {
    val context = LocalContext.current
    // Saved so a recreated activity neither asks twice nor hides the globe again.
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    var globeShown by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(false) }

    // At launch the globe appears whatever the answer; only the dive needs the permission.
    // Later, from the location button, a refusal is explained.
    val startupPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        globeShown = true
        if (it.values.any { granted -> granted }) state.locate(automatic = true)
    }
    val retryPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.any { granted -> granted }) state.locate(automatic = false) else state.toast(R.string.location_denied)
    }

    LaunchedEffect(Unit) {
        when {
            context.hasLocationPermission() -> {
                globeShown = true
                state.locate(automatic = true)
            }
            !permissionAsked -> {
                permissionAsked = true
                startupPermission.launch(LOCATION_PERMISSIONS)
            }
            // Otherwise a pending permission result, or the saved globeShown, takes over.
        }
    }
    LaunchedEffect(globeShown) {
        if (!globeShown) return@LaunchedEffect
        state.showGlobe()
        delay(CONTROLS_DELAY_MS)
        controlsVisible = true
    }
    // GLSurfaceView must follow the lifecycle to pause and resume its render thread.
    LifecycleResumeEffect(state.globe) {
        state.globe.onResume()
        onPauseOrDispose { state.globe.onPause() }
    }

    Box(Modifier.fillMaxSize().background(Space)) {
        AndroidView(factory = { state.globe }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            Caption(
                caption = state.caption,
                userPlace = state.userPlace,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
            )
            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                enter = fadeIn(tween(500)) + slideInVertically(tween(500, easing = LinearOutSlowInEasing)) { it },
            ) {
                Controls(
                    touring = state.caption is GlobeLabel.CapitalStop,
                    searching = state.searching,
                    satellite = state.satellite,
                    sunlight = state.sunlight,
                    onTourClick = state::toggleTour,
                    onLocateClick = {
                        if (context.hasLocationPermission()) {
                            state.locate(automatic = false)
                        } else {
                            retryPermission.launch(LOCATION_PERMISSIONS)
                        }
                    },
                    onSatelliteClick = state::toggleSatellite,
                    onSunClick = state::toggleSunlight,
                )
            }
            AnimatedVisibility(
                visible = state.satellite,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 6.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(stringResource(R.string.nasa_credit), color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
            }
        }
    }
}

/** Big title at the top: the current tour stop, or "you are here" with the user's place. */
@Composable
private fun Caption(caption: GlobeLabel?, userPlace: String, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = caption,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(360, delayMillis = 120)) + slideInVertically(tween(360, delayMillis = 120)) { it / 3 }) togetherWith
                (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 3 })
        },
        label = "caption",
    ) { target ->
        when (target) {
            is GlobeLabel.CapitalStop -> CaptionText(target.capital.city, target.capital.country)
            is GlobeLabel.UserHere -> CaptionText(stringResource(R.string.you_are_here), userPlace)
            null -> Box(Modifier)
        }
    }
}

private val TextShadow = Shadow(color = Color(0x99000000), blurRadius = 24f)

@Composable
private fun CaptionText(title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = TextStyle(color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, shadow = TextShadow),
        )
        Text(
            text = subtitle.uppercase(),
            style = TextStyle(color = Gold, fontSize = 15.sp, letterSpacing = 3.sp, shadow = TextShadow),
        )
    }
}

/** Bottom row: tour, locate, and the satellite toggle (with the sun toggle above it). */
@Composable
private fun Controls(
    touring: Boolean,
    searching: Boolean,
    satellite: Boolean,
    sunlight: Boolean,
    onTourClick: () -> Unit,
    onLocateClick: () -> Unit,
    onSatelliteClick: () -> Unit,
    onSunClick: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        TourButton(touring = touring, onClick = onTourClick)
        LocateButton(searching = searching, onClick = onLocateClick)
        // The sun switch only matters in satellite view: it rises above the layers button.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(
                visible = satellite,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
            ) {
                SunButton(sunlight = sunlight, onClick = onSunClick, modifier = Modifier.padding(bottom = 12.dp))
            }
            SatelliteButton(satellite = satellite, onClick = onSatelliteClick)
        }
    }
}

@Composable
private fun TourButton(touring: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(56.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = ControlBlue, contentColor = Color.White),
        border = BorderStroke(1.dp, ControlStroke),
        contentPadding = PaddingValues(horizontal = 28.dp),
    ) {
        AnimatedContent(targetState = touring, label = "tourText") { isTouring ->
            Text(stringResource(if (isTouring) R.string.tour_stop else R.string.tour_start), fontSize = 16.sp)
        }
    }
}

/** Round location button that breathes while a position is being looked up. */
@Composable
private fun LocateButton(searching: Boolean, onClick: () -> Unit) {
    val breathing: State<Float>? = if (searching) {
        rememberInfiniteTransition(label = "searching").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
            label = "alpha",
        )
    } else {
        null
    }
    RoundButton(
        icon = R.drawable.ic_my_location,
        description = stringResource(R.string.locate_me),
        onClick = onClick,
        modifier = Modifier.graphicsLayer { alpha = breathing?.value ?: 1f },
    )
}

/** Switches between the political map and the satellite view; gold while satellite is on. */
@Composable
private fun SatelliteButton(satellite: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(if (satellite) Gold else Color.White, label = "satelliteTint")
    RoundButton(
        icon = R.drawable.ic_layers,
        description = stringResource(if (satellite) R.string.map_view else R.string.satellite_view),
        onClick = onClick,
        tint = tint,
        border = if (satellite) Gold.copy(alpha = 0.6f) else ControlStroke,
    )
}

/** Real sun (day and night) on, or daylight everywhere; gold while the sun is on. */
@Composable
private fun SunButton(sunlight: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint by animateColorAsState(if (sunlight) Gold else Color.White, label = "sunTint")
    RoundButton(
        icon = R.drawable.ic_sun,
        description = stringResource(if (sunlight) R.string.sun_off else R.string.sun_on),
        onClick = onClick,
        modifier = modifier,
        tint = tint,
        border = if (sunlight) Gold.copy(alpha = 0.6f) else ControlStroke,
    )
}

/** The shared look of the round icon buttons. */
@Composable
private fun RoundButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    border: Color = ControlStroke,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        shape = CircleShape,
        color = ControlBlue,
        contentColor = tint,
        border = BorderStroke(1.dp, border),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(26.dp))
        }
    }
}

private fun Context.hasLocationPermission() = LOCATION_PERMISSIONS.any {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

@Preview(showBackground = true, backgroundColor = 0xFF05080F)
@Composable
private fun OverlayPreview() {
    MapsTheme {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(48.dp)) {
            CaptionText(Capitals.TOUR[0].city, Capitals.TOUR[0].country)
            Controls(
                touring = false, searching = false, satellite = true, sunlight = true,
                onTourClick = {}, onLocateClick = {}, onSatelliteClick = {}, onSunClick = {},
            )
        }
    }
}
