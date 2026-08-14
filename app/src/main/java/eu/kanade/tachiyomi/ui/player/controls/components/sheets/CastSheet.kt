package eu.kanade.tachiyomi.ui.player.controls.components.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ConnectedTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManagerListener
import eu.kanade.presentation.player.components.PlayerSheet
import eu.kanade.tachiyomi.util.cast.CastHandler
import kotlinx.coroutines.delay
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
    var connectingRouteId by remember { mutableStateOf<String?>(null) }
    val latestOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val castIsConnected by castHandler.connectionState.collectAsState()

    fun refreshState() {
        discoveredCastDevices = castHandler.getCastRoutes()
        selectedRouteId = castHandler.mediaRouter.selectedRoute.id
    }

    LaunchedEffect(connectingRouteId) {
        if (connectingRouteId == null) return@LaunchedEffect
        delay(CONNECTION_TIMEOUT_MS)
        if (!castHandler.isConnected()) {
            connectingRouteId = null
            refreshState()
        }
    }

    LaunchedEffect(castIsConnected, connectingRouteId) {
        if (castIsConnected && connectingRouteId != null) {
            connectingRouteId = null
            latestOnDismissRequest()
        }
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
            override fun onSessionEnded(session: Session, error: Int) {
                connectingRouteId = null
                refreshState()
            }

            override fun onSessionSuspended(session: Session, reason: Int) {
                connectingRouteId = null
                refreshState()
            }
            override fun onSessionStarted(session: Session, sessionId: String) {
                refreshState()
            }

            override fun onSessionResumed(session: Session, wasSuspended: Boolean) {
                refreshState()
            }

            override fun onSessionStarting(session: Session) = Unit
            override fun onSessionStartFailed(session: Session, error: Int) {
                connectingRouteId = null
                refreshState()
            }

            override fun onSessionEnding(session: Session) = Unit
            override fun onSessionResuming(session: Session, sessionId: String) = Unit
            override fun onSessionResumeFailed(session: Session, error: Int) {
                connectingRouteId = null
                refreshState()
            }
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
            modifier = Modifier.padding(bottom = MaterialTheme.padding.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
        ) {
            CastSheetHeader(
                onRefresh = {
                    discoveredCastDevices = castHandler.getCastRoutes()
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Text(
                    text = "Available devices",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (discoveredCastDevices.isEmpty()) {
                    EmptyCastDevices()
                } else {
                    discoveredCastDevices.forEach { route ->
                        val isConnected = route.id == selectedRouteId && castHandler.isConnected()
                        val isConnecting = route.id == connectingRouteId ||
                            route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING

                        CastEntry(
                            route = route,
                            isSelected = isConnected || isConnecting,
                            isConnecting = isConnecting,
                            onClick = {
                                if (isConnected) {
                                    castHandler.disconnect()
                                    onDismissRequest()
                                } else {
                                    connectingRouteId = route.id
                                    castHandler.connect(route)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private const val CONNECTION_TIMEOUT_MS = 15_000L

@Composable
private fun CastSheetHeader(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = MaterialTheme.padding.medium,
                end = MaterialTheme.padding.small,
                top = MaterialTheme.padding.medium,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Cast,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Cast to a device",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Choose a nearby screen to continue watching.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Reload cast devices",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EmptyCastDevices(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.padding.large,
                vertical = 28.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "No devices found",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Make sure your device is on the same Wi-Fi network, then refresh.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun CastEntry(
    route: MediaRouter.RouteInfo,
    isSelected: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusText = when {
        isConnecting -> "Connecting"
        route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING -> "Connecting"
        route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED -> "Connected"
        else -> "Disconnected"
    }

    val statusIcon = when {
        !isSelected -> Icons.Default.Tv
        isConnecting ->
            Icons.Default.ConnectedTv

        route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED ->
            Icons.Default.CastConnected

        else -> Icons.Default.Tv
    }

    Surface(
        onClick = onClick,
        enabled = !isConnecting,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        tonalElevation = if (isSelected) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = if (isSelected) statusText else "Tap to connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}
