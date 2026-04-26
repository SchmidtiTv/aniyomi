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
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManagerListener
import eu.kanade.presentation.player.components.PlayerSheet
import eu.kanade.tachiyomi.util.cast.CastHandler
import tachiyomi.presentation.core.components.material.padding

@Composable
fun CastSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val castHandler = remember(context) { CastHandler.getInstance(context) }

    var discoveredCastDevices by remember { mutableStateOf(castHandler.getCastRoutes()) }
    var selectedRouteId by remember { mutableStateOf(castHandler.mediaRouter.selectedRoute.id) }

    fun refreshState() {
        discoveredCastDevices = castHandler.getCastRoutes()
        selectedRouteId = castHandler.mediaRouter.selectedRoute.id
    }

    DisposableEffect(castHandler) {
        val routerCallback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshState()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshState()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshState()
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshState()
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshState()
        }

        val sessionListener = object : SessionManagerListener<Session> {
            override fun onSessionEnded(session: Session, error: Int) = refreshState()
            override fun onSessionSuspended(session: Session, reason: Int) = refreshState()
            override fun onSessionStarted(session: Session, sessionId: String) = refreshState()
            override fun onSessionResumed(session: Session, wasSuspended: Boolean) = refreshState()
            override fun onSessionStarting(session: Session) = Unit
            override fun onSessionStartFailed(session: Session, error: Int) = Unit
            override fun onSessionEnding(session: Session) = Unit
            override fun onSessionResuming(session: Session, sessionId: String) = Unit
            override fun onSessionResumeFailed(session: Session, error: Int) = Unit
        }

        castHandler.registerCallback(routerCallback)
        castHandler.addSessionManagerListener(sessionListener)

        onDispose {
            castHandler.unregisterCallback(routerCallback)
            castHandler.removeSessionManagerListener(sessionListener)
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
                            discoveredCastDevices = castHandler.getCastRoutes()
                        },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(
                                MaterialTheme.padding.extraSmall,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reload cast devices",
                            )
                            Text(text = "Reload")
                        }
                    }
                },
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                discoveredCastDevices.forEach { route ->
                    val isSelected = route.id == selectedRouteId

                    CastEntry(
                        route = route,
                        isSelected = isSelected,
                        onClick = {
                            if (isSelected) castHandler.disconnect() else castHandler.connect(route)
                            onDismissRequest()
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun CastEntry(
    route: MediaRouter.RouteInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusText = when (route.connectionState) {
        MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING -> "Connecting"
        MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED -> "Connected"
        else -> "Disconnected"
    }

    val statusIcon = when {
        !isSelected -> Icons.Default.Tv
        route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING ->
            Icons.Default.ConnectedTv

        route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED ->
            Icons.Default.CastConnected

        else -> Icons.Default.Tv
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
            )
            Text(text = route.name)
        }

        if (isSelected) {
            Text(text = statusText)
        }
    }
}
