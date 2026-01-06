package us.tiba.plexamp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AndroidStorage {
    private const val SHARED_PREFS_NAME = "plexaaos"
    private const val SERVER = "server"
    private const val LAST_MEDIA_ID = "last_media_id"
    private const val LAST_POSITION = "last_position"
    private const val SHUFFLE_ENABLED = "shuffle_enabled"
    private const val REPEAT_MODE = "repeat_mode"

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

    suspend fun removeKey(key: String, context: Context) {
        return withContext(Dispatchers.IO) {
            context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)?.edit()
                ?.remove(key)?.apply()
        }
    }

    suspend fun getLastSong(context: Context): String? {
        return getKey(LAST_MEDIA_ID, context)
    }

    suspend fun setLastSong(mediaId: String, context: Context){
        return setKey(LAST_MEDIA_ID, mediaId, context)
    }

    suspend fun getLastPosition(context: Context): Long? {
        return getKey(LAST_POSITION, context)?.toLongOrNull()
    }

    suspend fun setLastPosition(position: Long, context: Context){
        return setKey(LAST_POSITION, position.toString(), context)
    }

    suspend fun getShuffleEnabled(context: Context): Boolean {
        return getKey(SHUFFLE_ENABLED, context)?.toBoolean() ?: false
    }

    suspend fun setShuffleEnabled(value: Boolean, context: Context){
        return setKey(SHUFFLE_ENABLED, value.toString(), context)
    }

    suspend fun getRepeatMode(context: Context): Int {
        return getKey(REPEAT_MODE, context)?.toIntOrNull() ?: 0
    }

    suspend fun setRepeatMode(value: Int, context: Context){
        return setKey(REPEAT_MODE, value.toString(), context)
    }

    suspend fun getServer(context: Context): String? {
        return getKey(SERVER, context)
    }

    suspend fun setServer(server: String?, context: Context){
        if(server == null){
            removeKey(SERVER, context)
            return
        }
        return setKey(SERVER, server, context)
    }

}