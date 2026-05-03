package us.berkovitz.plexaaos

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class PlexAAOS : Application() {
    val applicationScope = CoroutineScope(SupervisorJob())
}
