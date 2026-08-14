package eu.kanade.tachiyomi.ui.player.sync

import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.CopyOnWriteArraySet

class PlaybackSyncCoordinator private constructor() {
    private val listeners = CopyOnWriteArraySet<PlaybackSessionListener>()

    private val syncLock = Any()

    // Quick cache to prevent infinite loops (Mobile sends to Cast -> Cast sends back to Mobile)
    // We only need to store recent command IDs to detect echoes.
    private val recentCommandIds = LinkedHashMap<String, Boolean>()
    private val maxRecentCommands = 20

    fun broadcastCommand(command: PlaybackCommand<*>) {
        synchronized(syncLock) {
            if (recentCommandIds.containsKey(command.commandId)) return

            recentCommandIds[command.commandId] = true
            if (recentCommandIds.size > maxRecentCommands) {
                recentCommandIds.remove(recentCommandIds.keys.first())
            }
        }

        logcat { "Broadcasting command -> $command" }
        listeners.forEach { listener ->
            listener.callback(command)
        }
    }

    // --- Adders
    fun addEvent(event: PlaybackCommand<*>) {
        broadcastCommand(event)
    }

    fun addListener(listener: PlaybackSessionListener) {
        listeners.add(listener)
    }

    // --- Listeners
    fun removeListener(listenerId: String) {
        listeners.removeIf { listener -> listener.listenerId == listenerId }
    }

    companion object {
        @Volatile
        private var coordinatorInstance: PlaybackSyncCoordinator? = null

        fun getInstance(): PlaybackSyncCoordinator {
            return coordinatorInstance ?: synchronized(this) {
                coordinatorInstance ?: PlaybackSyncCoordinator().also { coordinatorInstance = it }
            }
        }
    }
}
