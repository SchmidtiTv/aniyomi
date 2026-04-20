package eu.kanade.tachiyomi.util.cast

import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: android.content.Context): CastOptions {
        val receiverAppId = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        return CastOptions.Builder()
            .setReceiverApplicationId(receiverAppId)
            .build()
    }

    override fun getAdditionalSessionProviders(context: android.content.Context): MutableList<SessionProvider>? {
        return null
    }
}
