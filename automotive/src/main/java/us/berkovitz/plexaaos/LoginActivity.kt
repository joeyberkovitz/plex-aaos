package us.berkovitz.plexaaos

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import us.berkovitz.plexaaos.ui.theme.PlexAAOSTheme
import us.berkovitz.plexapi.config.Config
import us.berkovitz.plexapi.config.Http
import us.berkovitz.plexapi.myplex.MyPlexAccount
import us.berkovitz.plexapi.myplex.MyPlexPinLogin


class LoginActivity : ComponentActivity() {
    private val text = mutableStateOf("PIN")
    private val plexPinLogin = MyPlexPinLogin()
    private var pinLoginJob: Job? = null
    private var mAccountAuthenticatorResponse: AccountAuthenticatorResponse? = null
    private var mResultBundle: Bundle? = null
    private lateinit var accountManager: AccountManager
    private lateinit var musicServiceConnection: MusicServiceConnection

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        musicServiceConnection = MusicServiceConnection(
            applicationContext,
            ComponentName(applicationContext, MyMusicService::class.java)
        )

        mAccountAuthenticatorResponse =
            intent.getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE);
        if (mAccountAuthenticatorResponse != null) {
            mAccountAuthenticatorResponse!!.onRequestContinued()
        }

        accountManager = AccountManager.get(this)

        AndroidPlexApi.initPlexApi(this)
        plexPinLogin.pinChangeCb = {
            text.value = it
        }

        pinLoginJob = CoroutineScope(Dispatchers.IO).launch {
            val loginRes = plexPinLogin.pinLogin()
            if (loginRes.authToken != null) {
                val token = "${loginRes.clientIdentifier!!}|${loginRes.authToken!!}"
                setToken(token)
            }
        }


        setContent {
            PlexAAOSTheme {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    var username by remember { mutableStateOf("") }
                    var password by remember { mutableStateOf("") }
                    val focusManager = LocalFocusManager.current
                    val keyboardManager = LocalSoftwareKeyboardController.current

                    EnterPin(text)
                    Text("Alternate Sign-in Option:")
                    TextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        label = { Text("username", style = TextStyle(color = Color.Black)) })
                    TextField(
                        value = password,
                        onValueChange = { password = it },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                keyboardManager?.hide()
                            }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("password", style = TextStyle(color = Color.Black)) })
                    Button(onClick = {
                        doLogin(username, password)
                    }) {
                        Text("Sign In")
                    }
                }
            }
        }
    }

    fun doLogin(username: String, password: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val loginRes = MyPlexAccount.login(username, password)
            if (!loginRes.isNullOrEmpty()) {
                val token = "${Config.X_PLEX_IDENTIFIER}|${loginRes}"
                setToken(token)
                pinLoginJob?.cancel()
            }
        }
    }

    fun setToken(token: String) {
        runOnUiThread {
            val account = Account(
                "PlexAAOS", //TODO: get account username from login???
                Authenticator.ACCOUNT_TYPE
            )
            accountManager.addAccountExplicitly(account, token, null)
            pinLoginJob?.cancel()
            musicServiceConnection.sendCommand(LOGIN, Bundle.EMPTY) { _, result ->

            }
            mResultBundle = Bundle()
            finish()
        }
    }

    override fun finish() {
        if (mAccountAuthenticatorResponse != null) {
            // send the result bundle back if set, otherwise send an error.
            if (mResultBundle != null) {
                Log.d("login", "setting result")
                mAccountAuthenticatorResponse!!.onResult(mResultBundle);
            } else {
                mAccountAuthenticatorResponse!!.onError(
                    AccountManager.ERROR_CODE_CANCELED,
                    "canceled"
                );
            }
            mAccountAuthenticatorResponse = null;
        }
        super.finish()
    }
}

@Composable
fun EnterPin(pin: MutableState<String>) {
    var text = "Loading ..."
    if (!pin.value.isBlank()) {
        text = "Enter pin at https://plex.tv/link: ${pin.value}"
    }
    Text(
        text = text,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
        fontSize = 30.sp
    )
}
