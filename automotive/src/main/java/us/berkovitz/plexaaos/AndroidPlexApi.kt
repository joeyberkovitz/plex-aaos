package us.berkovitz.plexaaos

import android.content.Context
import android.os.Build
import android.provider.Settings
import us.berkovitz.plexapi.config.Config
import us.berkovitz.plexapi.logging.LoggingFactory

object AndroidPlexApi {
    fun initPlexApi(ctx: Context, clientId: String? = null){
        LoggingFactory.setFactory(PlexLoggerFactory)
        Config.X_PLEX_PRODUCT = "PlexAAOS"
        Config.X_PLEX_PLATFORM = "Android Automotive"
        Config.X_PLEX_PLATFORM_VERSION = Build.VERSION.RELEASE
        Config.X_PLEX_VERSION = BuildConfig.VERSION_NAME
        Config.X_PLEX_DEVICE = Build.MODEL
        Config.X_PLEX_DEVICE_NAME = Build.MODEL // TODO: get the user set device name
        if(clientId != null){
            Config.X_PLEX_IDENTIFIER = clientId
        }
    }

}