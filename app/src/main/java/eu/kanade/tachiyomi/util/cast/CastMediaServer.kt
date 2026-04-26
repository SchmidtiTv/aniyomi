package eu.kanade.tachiyomi.util.cast

import eu.kanade.tachiyomi.animesource.model.Video
import com.google.android.gms.cast.framework.CastSession

class CastMediaServer {
    private var currentCastSession: CastSession? = null

    fun setSession(session: CastSession?) {
        this.currentCastSession = session
    }

    fun getSession(): CastSession? {
        return this.currentCastSession
    }

    fun isReady(): Boolean {
        return currentCastSession?.isConnected == true
    }
}
