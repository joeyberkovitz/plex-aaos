package us.berkovitz.plexaaos

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import us.berkovitz.plexaaos.ui.theme.PlexAAOSTheme
import us.berkovitz.plexapi.myplex.MyPlexPinLogin


class LoginActivity : ComponentActivity() {
    private val text = mutableStateOf("PIN")
    private val plexPinLogin = MyPlexPinLogin()
    private var mAccountAuthenticatorResponse: AccountAuthenticatorResponse? = null
    private var mResultBundle: Bundle? = null
    private lateinit var accountManager: AccountManager
    private lateinit var musicServiceConnection: MusicServiceConnection


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        musicServiceConnection = MusicServiceConnection(
            applicationContext,
            ComponentName(applicationContext, MyMusicService::class.java)
        )

        mAccountAuthenticatorResponse = intent.getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE);
        if(mAccountAuthenticatorResponse != null){
            mAccountAuthenticatorResponse!!.onRequestContinued()
        }

        accountManager = AccountManager.get(this)

        AndroidPlexApi.initPlexApi(this)
        plexPinLogin.pinChangeCb = {
            text.value = it
        }

        CoroutineScope(Dispatchers.IO).launch {
            val loginRes = plexPinLogin.pinLogin()
            if(loginRes.authToken != null){
                val token = "${loginRes.clientIdentifier!!}|${loginRes.authToken!!}"
                setToken(token)
            }

        }

        setContent {
            PlexAAOSTheme {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    EnterPin(text)
                }
            }
        }
    }

    fun setToken(token: String){
        val account = Account(
            "PlexAAOS", //TODO: get account username from login???
            Authenticator.ACCOUNT_TYPE
        )
        accountManager.addAccountExplicitly(account, token, null)
        musicServiceConnection.sendCommand(LOGIN, Bundle.EMPTY) { resultCode, _ ->
            finish()
        }
    }

    override fun finish() {
        if (mAccountAuthenticatorResponse != null) {
            // send the result bundle back if set, otherwise send an error.
            if (mResultBundle != null) {
                mAccountAuthenticatorResponse!!.onResult(mResultBundle);
            } else {
                mAccountAuthenticatorResponse!!.onError(AccountManager.ERROR_CODE_CANCELED,
                    "canceled");
            }
            mAccountAuthenticatorResponse = null;
        }
    }
}

@Composable
fun EnterPin(pin: MutableState<String>) {
    if(pin.value.isBlank()){
        Text(text = "Loading ...")
    } else {
        Text(text = "Enter pin at https://plex.tv/link: ${pin.value}")
    }
}

@SuppressLint("UnrememberedMutableState")
@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    PlexAAOSTheme {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            EnterPin(mutableStateOf("1234"))
        }
    }
}