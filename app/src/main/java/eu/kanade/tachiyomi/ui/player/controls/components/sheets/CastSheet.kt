package eu.kanade.tachiyomi.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ConnectedTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import eu.kanade.presentation.player.components.PlayerSheet
import tachiyomi.presentation.core.components.material.padding

@Composable
fun CastSheet(
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mediaRouter = remember(context) { MediaRouter.getInstance(context) }

    val castSelector = remember {
        MediaRouteSelector.Builder().addControlCategory(
            CastMediaControlIntent.categoryForRemotePlayback(
                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
            ),
        ).build()
    }

    var castRoutes by remember { mutableStateOf(filterCastRoutes(mediaRouter, castSelector)) }

    DisposableEffect(mediaRouter, castSelector) {
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                castRoutes = filterCastRoutes(router, castSelector)
            }

            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                castRoutes = filterCastRoutes(router, castSelector)
            }

            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                castRoutes = filterCastRoutes(router, castSelector)
            }
        }

        mediaRouter.addCallback(
            castSelector,
            callback,
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY,
        )

        onDispose {
            mediaRouter.removeCallback(callback)
        }
    }

    PlayerSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {

            TrackSheetTitle(
                title = "Cast",
                actions = {
                    TextButton(
                        onClick = {
                            castRoutes = filterCastRoutes(mediaRouter, castSelector)
                        },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                            Text(text = "Reload")
                        }
                    }
                },
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                castRoutes.forEach { route ->
                    CastEntry(
                        route = route,
                        isSelected = route.id == selectedId,
                        onSelect = {
                            mediaRouter.selectRoute(route)
                            onSelect(it)
                        },
                    )
                }
            }
        }
    }
}

private fun filterCastRoutes(
    mediaRouter: MediaRouter,
    selector: MediaRouteSelector,
): List<MediaRouter.RouteInfo> {
    return mediaRouter.routes.filter { route ->
        route.matchesSelector(selector) && route != mediaRouter.defaultRoute && route != mediaRouter.bluetoothRoute && route.isEnabled
    }
}

@Composable
fun CastEntry(
    route: MediaRouter.RouteInfo,
    isSelected: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = mapOf(
        MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING to "Connecting",
        MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED to "Connected",
        MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED to "Disconnected",
    )

    val connectionIcon = mapOf(
        MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING to Icons.Default.ConnectedTv,
        MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED to Icons.Filled.CastConnected,
        MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED to Icons.Default.Tv,
    )

    val defaultIcon = Icons.Default.Tv

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                onClick = { if (!isSelected) onSelect(route.id) else onSelect("") },
            )
            .padding(15.dp, 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Icon(
                imageVector = if (isSelected) connectionIcon[route.connectionState] ?: defaultIcon else defaultIcon,
                contentDescription = null,
            )
            Text(text = route.name)
        }

        if (isSelected) {
            Text(text = label[route.connectionState] ?: "Disconnected")
        }
    }
}
