package us.berkovitz.plexaaos

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class MusicServiceConnection(context: Context, serviceComponent: ComponentName) {
    private val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(context, SessionToken(context, serviceComponent)).buildAsync()

    fun sendCommand(command: String, parameters: Bundle?) =
        sendCommand(command, parameters) { _, _ -> }

    fun sendCommand(
        command: String,
        parameters: Bundle?,
        resultCallback: ((Int, Bundle?) -> Unit)
    ): Boolean {
        if (!controllerFuture.isDone) return false
        val controller = controllerFuture.get()
        if (!controller.isConnected) return false

        val sessionCommand = SessionCommand(command, Bundle.EMPTY)
        val future = controller.sendCustomCommand(sessionCommand, parameters ?: Bundle.EMPTY)
        future.addListener({
            val result = future.get()
            resultCallback(result.resultCode, result.extras)
        }, MoreExecutors.directExecutor())
        return true
    }
}