package us.tiba.plexamp

import android.annotation.SuppressLint
import android.content.ComponentName
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toolbar
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.ExposedDropdownMenuDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import us.tiba.plexamp.ui.theme.PlexAAOSTheme
import us.tiba.plexapi.myplex.MyPlexResource
import us.tiba.plexapi.myplex.MyPlexUser
import us.tiba.plexapi.storage.Storage
import kotlin.math.exp

class SettingsActivity : ComponentActivity() {

    lateinit var plexUtil: PlexUtil
    private lateinit var musicServiceConnection: MusicServiceConnection
    var plexToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        musicServiceConnection = MusicServiceConnection(
            applicationContext,
            ComponentName(applicationContext, MyMusicService::class.java)
        )

        plexUtil = PlexUtil(this)
        plexToken = plexUtil.getToken()

        actionBar?.setDisplayHomeAsUpEnabled(true)

        setContent {
            PlexAAOSTheme {
                //TODO: need a back button
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 50.dp),
                    verticalArrangement = Arrangement.Top,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SelectServerDropdown()
                    SelectUserSetting()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if(item.itemId == android.R.id.home){
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @OptIn(ExperimentalMaterialApi::class)
    @SuppressLint("CoroutineCreationDuringComposition")
    @Composable
    fun SelectServerDropdown() {
        val coroutineScope = rememberCoroutineScope()
        var expanded by remember { mutableStateOf(false) }
        var selectedIndex by remember { mutableStateOf(-1) }
        var selectedOptionText by remember { mutableStateOf(getServerText(null, -1)) }
        var servers by remember { mutableStateOf<List<MyPlexResource>>(emptyList()) }
        if (servers.isEmpty()) {
            coroutineScope.launch {
                servers = PlexUtil.getServers(plexToken!!)
                selectedIndex = getCurrentServer(servers)
                selectedOptionText = getServerText(servers, selectedIndex)
            }
        }

        Column(
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart),
        ) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                TextField(
                    readOnly = true,
                    value = selectedOptionText,
                    onValueChange = {},
                    label = { Text("Server:", fontSize = 30.sp) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    colors = ExposedDropdownMenuDefaults.textFieldColors(
                        unfocusedLabelColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        fontSize = 30.sp
                    )
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DropdownMenuItem(onClick = {
                        selectedIndex = -1
                        expanded = false
                        selectedOptionText = getServerText(servers, selectedIndex)
                        coroutineScope.launch {
                            selectServer(null)
                        }
                    }) {
                        Text(text = "Auto")
                    }
                    servers.forEachIndexed { index, server ->
                        DropdownMenuItem(onClick = {
                            selectedIndex = index
                            expanded = false
                            selectedOptionText = getServerText(servers, selectedIndex)
                            coroutineScope.launch {
                                selectServer(server)
                            }
                        }) {
                            Text(text = server.name)
                        }
                    }
                }
            }
        }
    }

    private fun getServerText(servers: List<MyPlexResource>?, index: Int): String {
        if (servers.isNullOrEmpty() || index < 0 || index > servers.lastIndex) {
            return "Auto"
        }
        return servers[index].name
    }

    private suspend fun getCurrentServer(servers: List<MyPlexResource>?): Int {
        val selectedServer = AndroidStorage.getServer(this)
        if (servers == null || selectedServer == null) {
            return -1
        }

        return servers.indexOfFirst { myPlexResource -> myPlexResource.clientIdentifier == selectedServer }
    }

    private suspend fun selectServer(server: MyPlexResource?) {
        AndroidStorage.setServer(server?.clientIdentifier, this)
        notifyRefresh()
    }

    private fun getUserText(users: List<MyPlexUser>?, index: Int): String {
        if (users.isNullOrEmpty() || index < 0 || index > users.lastIndex) {
            return "Select a user to switch accounts"
        }
        if(users[index].username != null) {
            return users[index].username!!
        }

        return users[index].title
    }

    private suspend fun switchUser(user: MyPlexUser, pin: String?): String {
        try {
            val newToken = PlexUtil.switchUser(plexToken!!, user.id.toString(), if(pin.isNullOrEmpty()) null else pin)
            plexUtil.setToken(newToken)
            notifyRefresh()
        } catch (exc: Exception){
            if(exc.message != null) {
                return exc.message!!
            }
            return exc.toString()
        }

        return ""
    }

    @OptIn(ExperimentalMaterialApi::class)
    @SuppressLint("CoroutineCreationDuringComposition")
    @Composable
    fun SelectUserSetting() {
        val coroutineScope = rememberCoroutineScope()
        var expanded by remember { mutableStateOf(false) }
        var selectedIndex by remember { mutableStateOf(-1) }
        var selectedOptionText by remember { mutableStateOf(getUserText(null, -1)) }
        var users by remember { mutableStateOf<List<MyPlexUser>>(emptyList()) }
        var userPin by remember { mutableStateOf("") }
        var switchingUser by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf("")}
        if (users.isEmpty()) {
            coroutineScope.launch {
                users = PlexUtil.getUsers(plexToken!!)
            }
        }

        Column(
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart),
        ) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                TextField(
                    readOnly = true,
                    value = selectedOptionText,
                    onValueChange = {},
                    label = { Text("User Switching:", fontSize = 30.sp) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    colors = ExposedDropdownMenuDefaults.textFieldColors(
                        unfocusedLabelColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        fontSize = 30.sp
                    )
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    users.forEachIndexed { index, user ->
                        DropdownMenuItem(onClick = {
                            selectedIndex = index
                            expanded = false
                            selectedOptionText = getUserText(users, selectedIndex)
                            userPin = ""
                            switchingUser = false
                            errorMessage = ""
                        }) {
                            Text(text = getUserText(users, index))
                        }
                    }
                }
            }

            if (selectedIndex >= 0 && selectedIndex <= users.lastIndex) {
                if (users[selectedIndex].protected > 0) {
                    TextField(
                        value = userPin,
                        onValueChange = { userPin = it },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        label = { Text("PIN", style = TextStyle(color = Color.Black)) })
                }

                OutlinedButton(
                    onClick = {
                        switchingUser = true
                        errorMessage = ""
                        coroutineScope.launch {
                            errorMessage = switchUser(users[selectedIndex], userPin)
                            switchingUser = false
                        }
                    },
                    enabled = (users[selectedIndex].protected == 0 || userPin.isNotEmpty()) && !switchingUser
                ) {
                    Text("Switch User")
                }
                if (errorMessage != "") {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.body2,
                    )
                }
            }
        }
    }

    private fun notifyRefresh(){
        musicServiceConnection.sendCommand(REFRESH, Bundle.EMPTY)
    }
}