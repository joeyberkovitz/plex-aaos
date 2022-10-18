package us.berkovitz.plexaaos

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AndroidStorage {
    private const val SHARED_PREFS_NAME = "plexaaos"
    private const val LAST_MEDIA_ID = "last_media_id"

    suspend fun getKey(key: String, context: Context): String? {
        return withContext(Dispatchers.IO) {
            return@withContext context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(key, null)
        }
    }

    suspend fun setKey(key: String, value: String, context: Context) {
        return withContext(Dispatchers.IO) {
            context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)?.edit()
                ?.putString(key, value)?.apply()
        }
    }

    suspend fun getLastSong(context: Context): String? {
        return getKey(LAST_MEDIA_ID, context)
    }

    suspend fun setLastSong(mediaId: String, context: Context){
        return setKey(LAST_MEDIA_ID, mediaId, context)
    }

}