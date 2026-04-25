package us.berkovitz.plexaaos

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.android.car.ui.core.CarUi
import com.android.car.ui.toolbar.NavButtonMode

class LoginActivity : AppCompatActivity() {
    private var mAccountAuthenticatorResponse: AccountAuthenticatorResponse? = null
    private var mResultBundle: Bundle? = null
    private lateinit var accountManager: AccountManager
    private lateinit var musicServiceConnection: MusicServiceConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val toolbar = CarUi.requireToolbar(this)
        toolbar.setTitle(R.string.title_activity_login)
        toolbar.navButtonMode = NavButtonMode.BACK

        musicServiceConnection = MusicServiceConnection(
            applicationContext,
            ComponentName(applicationContext, PlexMediaService::class.java)
        )

        mAccountAuthenticatorResponse =
            intent.getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)
        if (mAccountAuthenticatorResponse != null) {
            mAccountAuthenticatorResponse!!.onRequestContinued()
        }

        accountManager = AccountManager.get(this)
        AndroidPlexApi.initPlexApi(this)

        if (savedInstanceState == null) {
            switchToQrCode()
        }
    }

    fun switchToQrCode() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.login_container, QrSignInFragment())
            .commit()
    }

    fun switchToPasswordSignIn() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.login_container, PasswordSignInFragment())
            .commit()
    }

    fun setToken(token: String) {
        val account = Account(
            "PlexAAOS", //TODO: get account username from login???
            Authenticator.ACCOUNT_TYPE
        )
        accountManager.addAccountExplicitly(account, token, null)
        musicServiceConnection.sendCommand(LOGIN, Bundle.EMPTY) { _, result ->
            mResultBundle = Bundle()
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
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
