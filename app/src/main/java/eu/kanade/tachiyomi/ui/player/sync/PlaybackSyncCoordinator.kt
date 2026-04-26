package eu.kanade.tachiyomi.ui.player.sync

import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.CopyOnWriteArraySet

class PlaybackSyncCoordinator private constructor() {
    private val eventQueue = ArrayDeque<PlaybackEvent<*>>()
    private val timeToLiveMs = 3000 // 3s

    private val listeners = CopyOnWriteArraySet<PlaybackListener>()

    private val syncLock = Any()

    // -- States
    @Volatile
    private var currentState: PlaybackSyncState? = null

    fun updateState(newState: PlaybackSyncState) {
        synchronized(syncLock) {
            val current = currentState
            if (current == null ||
                newState.revision > current.revision ||
                (newState.revision == current.revision && newState.updatedAtMs > current.updatedAtMs)
            ) {
                currentState = newState
                eventQueue.removeAll { event -> event.eventTime <= newState.updatedAtMs }

                notifyListeners(newState)
            }
        }
    }

    fun getCurrentSyncState(): PlaybackSyncState? = currentState

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

    fun getLatestEventByType(eventType: PlaybackEventType): PlaybackEvent<*>? {
        synchronized(syncLock) {
            clearOldEvents()
            return eventQueue.findLast { event -> event.eventType == eventType }
        }
    }

    private fun clearOldEvents() {
        val currentTime = System.currentTimeMillis()
        eventQueue.removeAll { event -> currentTime - event.eventTime > timeToLiveMs }
    }

    // --- Adders
    fun addEvent(event: PlaybackEvent<*>) {
        synchronized(syncLock) {
            clearOldEvents()
            val stateTimestamp = currentState?.updatedAtMs ?: 0L
            if (event.eventTime > stateTimestamp) {
                eventQueue.add(event)
                logcat { "Added new Event -> $event" }
            }
        }
    }

    fun addListener(listener: PlaybackListener) {
        listeners.add(listener)
        currentState?.let { state -> listener.callback(state) }
    }

    // --- Listeners
    fun removeListener(listenerId: String) {
        listeners.removeIf { listener -> listener.listenerId == listenerId }
    }

    private fun notifyListeners(state: PlaybackSyncState) {
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
