package us.berkovitz.plexaaos

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import us.berkovitz.plexaaos.ui.theme.PlexAAOSTheme
import us.berkovitz.plexapi.myplex.MyPlexResource
import us.berkovitz.plexapi.storage.Storage
import kotlin.math.exp

class SettingsActivity : ComponentActivity() {

    lateinit var plexUtil: PlexUtil
    var plexToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        plexUtil = PlexUtil(this)
        plexToken = plexUtil.getToken()

        setContent {
            PlexAAOSTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 50.dp),
                    verticalArrangement = Arrangement.Top,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {

                    SettingsUi()
                }
            }
        }
    }

    @Composable
    fun SettingsUi() {
        SelectServerDropdown()
    }

    @OptIn(ExperimentalMaterialApi::class)
    @SuppressLint("CoroutineCreationDuringComposition")
    @Composable
    fun SelectServerDropdown() {
        val coroutineScope = rememberCoroutineScope()
        var expanded by remember { mutableStateOf(false) }
        var selectedIndex by remember { mutableStateOf(0) }
        var selectedOptionText by remember { mutableStateOf(getServerText(null, 0)) }
        var servers by remember { mutableStateOf<List<MyPlexResource>>(emptyList()) }
        coroutineScope.launch {
            servers = PlexUtil.getServers(plexToken!!)
            selectedOptionText = getServerText(servers, selectedIndex)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
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
                    label = { Text("Server", fontSize = 30.sp) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    colors = ExposedDropdownMenuDefaults.textFieldColors(),
                    modifier=Modifier.fillMaxWidth(),
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
        if(servers.isNullOrEmpty() || index > servers.lastIndex){
            return "Automatic server selection"
        }
        return servers[index].name
    }

    private suspend fun selectServer(server: MyPlexResource?){
        AndroidStorage.setServer(server?.clientIdentifier, this)
    }
}