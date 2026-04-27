package eu.kanade.tachiyomi.ui.player.sync

import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.CopyOnWriteArraySet

class PlaybackSyncCoordinator private constructor() {
    private val eventQueue = ArrayDeque<PlaybackCommand<*>>()
    private val timeToLiveMs = 3000 // 3s

    private val listeners = CopyOnWriteArraySet<PlaybackSessionListener>()

    private val syncLock = Any()

    // -- States
    @Volatile
    private var currentState: PlaybackSessionState? = null

    fun updateState(newState: PlaybackSessionState) {
        synchronized(syncLock) {
            val current = currentState
            if (current == null ||
                newState.revision > current.revision ||
                (newState.revision == current.revision && newState.updatedAtMs > current.updatedAtMs)
            ) {
                currentState = newState

                notifyListeners(newState)
            }
        }
    }

    fun getCurrentSyncState(): PlaybackSessionState? = currentState

    fun clearState() {
        synchronized(syncLock) {
            currentState = null
            eventQueue.clear()
        }
    }

    // --- Event

    fun isDuplicateEvent(commandId: String?): Boolean {
        if (commandId == null) return false

        synchronized(syncLock) {
            val isInQueue = eventQueue.any { event -> event.commandId == commandId }
            val isCurrentState = currentState?.lastCommandId == commandId
            return isInQueue || isCurrentState
        }
    }

    fun getLatestEventByType(eventType: PlaybackCommandType): PlaybackCommand<*>? {
        synchronized(syncLock) {
            clearOldEvents()
            return eventQueue.findLast { event -> event.commandType == eventType }
        }
    }

    private fun clearOldEvents() {
        val currentTime = System.currentTimeMillis()
        eventQueue.removeAll { event -> currentTime - event.eventTime > timeToLiveMs }
    }

    // --- Adders
    fun addEvent(event: PlaybackCommand<*>) {
        synchronized(syncLock) {
            clearOldEvents()
            if (eventQueue.none { queuedEvent -> queuedEvent.commandId == event.commandId }) {
                eventQueue.add(event)
                logcat { "Added command -> $event" }
            }
        }
    }

    fun addListener(listener: PlaybackSessionListener) {
        listeners.add(listener)
        currentState?.let { state -> listener.callback(state) }
    }

    // --- Listeners
    fun removeListener(listenerId: String) {
        listeners.removeIf { listener -> listener.listenerId == listenerId }
    }

    private fun notifyListeners(state: PlaybackSessionState) {
        logcat { "Notifying Listeners" }
        listeners.forEach { listener -> listener.callback(state) }
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
