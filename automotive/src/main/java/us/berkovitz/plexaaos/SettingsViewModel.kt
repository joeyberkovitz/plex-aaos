package us.berkovitz.plexaaos

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.berkovitz.plexapi.myplex.MyPlexResource
import us.berkovitz.plexapi.myplex.MyPlexUser

const val SERVER_ID_AUTO = "auto"

sealed class UserSwitchStatus {
    object Idle : UserSwitchStatus()
    object Switching : UserSwitchStatus()
    data class Success(val userTitle: String) : UserSwitchStatus()
    data class Error(val message: String) : UserSwitchStatus()
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val applicationScope = getApplication<PlexAAOS>().applicationScope
    private val plexUtil = PlexUtil(application)
    private val musicServiceConnection = MusicServiceConnection(
        application,
        ComponentName(application, PlexMediaService::class.java)
    )
    var plexToken = plexUtil.getToken()

    private val _users = MutableStateFlow<List<MyPlexUser>?>(null)
    val users: StateFlow<List<MyPlexUser>?> = _users.asStateFlow()

    private val _servers = MutableStateFlow<List<MyPlexResource>?>(null)
    val servers: StateFlow<List<MyPlexResource>?> = _servers.asStateFlow()

    private val _currentServerId = MutableStateFlow<String?>(SERVER_ID_AUTO)
    val currentServerId: StateFlow<String?> = _currentServerId.asStateFlow()

    private val _userSwitchStatus = MutableStateFlow<UserSwitchStatus>(UserSwitchStatus.Idle)
    val userSwitchStatus: StateFlow<UserSwitchStatus> = _userSwitchStatus.asStateFlow()

    private val _signOutEvent = MutableSharedFlow<Unit>()
    val signOutEvent: SharedFlow<Unit> = _signOutEvent.asSharedFlow()

    init {
        loadData()
    }

    private fun loadData() {
        val token = plexToken ?: ""
        viewModelScope.launch {
            launch {
                val savedServerId = withContext(Dispatchers.IO) {
                    AndroidStorage.getServer(getApplication())
                }
                _currentServerId.value = savedServerId ?: SERVER_ID_AUTO
            }
            launch {
                try {
                    val fetchedServers = withContext(Dispatchers.IO) {
                        PlexUtil.getServers(token)
                    }
                    _servers.value = fetchedServers
                } catch (e: Exception) {
                    _servers.value = emptyList()
                }
            }
            launch {
                try {
                    val fetchedUsers = withContext(Dispatchers.IO) {
                        PlexUtil.getUsers(token)
                    }
                    _users.value = fetchedUsers
                } catch (e: Exception) {
                    _users.value = emptyList()
                }
            }
        }
    }

    fun setServer(serverId: String?) {
        if (serverId == _currentServerId.value) return
        val idToSave = if (serverId == SERVER_ID_AUTO) null else serverId
        applicationScope.launch {
            withContext(Dispatchers.IO) {
                AndroidStorage.setServer(idToSave, getApplication())
            }
            _currentServerId.value = serverId ?: SERVER_ID_AUTO
            withContext(Dispatchers.Main) {
                notifyRefresh()
            }
        }
    }

    fun switchUser(userId: String, userTitle: String, pin: String?) {
        val token = plexToken ?: return
        _userSwitchStatus.value = UserSwitchStatus.Switching
        applicationScope.launch {
            try {
                // Parameter validation
                if (pin != null && (pin.length != 4 || !pin.all { it.isDigit() })) {
                    _userSwitchStatus.value = UserSwitchStatus.Error(
                        getApplication<Application>().getString(R.string.pin_error_digits))
                    return@launch
                }

                val newToken = withContext(Dispatchers.IO) {
                    PlexUtil.switchUser(token, userId, pin)
                }
                plexUtil.setToken(newToken)
                plexToken = newToken

                _userSwitchStatus.value = UserSwitchStatus.Success(userTitle)

                // Reload data (users/servers) for the new user
                loadData()
                withContext(Dispatchers.Main) {
                    notifyRefresh()
                }
            } catch (e: Exception) {
                _userSwitchStatus.value = UserSwitchStatus.Error(
                    e.message ?: getApplication<Application>().getString(R.string.unknown_error))
            }
        }
    }

    fun resetSwitchStatus() {
        _userSwitchStatus.value = UserSwitchStatus.Idle
    }

    fun signOut() {
        musicServiceConnection.sendCommand(LOGOUT, Bundle.EMPTY)
        viewModelScope.launch {
            _signOutEvent.emit(Unit)
        }
    }

    private fun notifyRefresh() {
        musicServiceConnection.sendCommand(REFRESH, Bundle.EMPTY)
    }
}
