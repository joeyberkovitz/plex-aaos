package us.berkovitz.plexaaos

import android.util.Log
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import us.berkovitz.plexapi.logging.KotlinLoggingLogger
import us.berkovitz.plexapi.logging.Logger
import us.berkovitz.plexapi.logging.LoggingFactory
import kotlin.reflect.KClass

class PlexLogger<T: Any>(clazz: KClass<T>): Logger {
    private val TAG = clazz.simpleName ?: "N/A"

    override fun debug(message: String) {
        Log.d(TAG, message)
        Firebase.crashlytics.log("$TAG: $message")
    }

    override fun error(message: String) {
        Log.e(TAG, message)
        Firebase.crashlytics.log("$TAG: $message")
    }

    override fun info(message: String) {
        Log.i(TAG, message)
        Firebase.crashlytics.log("$TAG: $message")
    }

    override fun trace(message: String) {
        Log.d(TAG, message)
        Firebase.crashlytics.log("$TAG: $message")
    }

    override fun warn(message: String) {
        Log.w(TAG, message)
        Firebase.crashlytics.log("$TAG: $message")
    }
}

object PlexLoggerFactory: LoggingFactory {
    override fun <T : Any> loggerFor(clazz: KClass<T>): Logger {
        return PlexLogger(clazz)
    }
}
