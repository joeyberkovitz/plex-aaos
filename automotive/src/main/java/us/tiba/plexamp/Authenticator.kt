package us.tiba.plexamp

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

class Authenticator(val context: Context): AbstractAccountAuthenticator(context) {
    companion object {
        val ACCOUNT_TYPE = "us.tiba.plexamp"
        val AUTHTOKEN_TYPE = "us.tiba.plexamp"
    }

    init {
        AndroidPlexApi.initPlexApi(context)
    }

    override fun editProperties(p0: AccountAuthenticatorResponse?, p1: String?): Bundle {
        throw UnsupportedOperationException()
    }

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle {
        val intent = Intent(context, LoginActivity::class.java)
        intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        val bundle = Bundle()
        bundle.putParcelable(AccountManager.KEY_INTENT, intent)
        return bundle
    }

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?
    ): Bundle? {
        return null
    }

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        loginOptions: Bundle?
    ): Bundle {
        if(authTokenType != AUTHTOKEN_TYPE){
            val res = Bundle()
            res.putString(AccountManager.KEY_ERROR_MESSAGE, "invalid auth token type")
            return res
        }

        if(account == null){
            val res = Bundle()
            res.putString(AccountManager.KEY_ERROR_MESSAGE, "account must not be null")
            return res
        }

        // password is the auth token
        val accountManager = AccountManager.get(context)
        val password = accountManager.getPassword(account)
        if(password != null){
            val res = Bundle()
            res.putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
            res.putString(AccountManager.KEY_ACCOUNT_TYPE, ACCOUNT_TYPE)
            res.putString(AccountManager.KEY_AUTHTOKEN, password)
            return res
        }

        // invalid password, ask for sign-in
        val intent = Intent(context, LoginActivity::class.java)
        intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        val bundle = Bundle()
        bundle.putParcelable(AccountManager.KEY_INTENT, intent)
        return bundle
    }

    override fun getAuthTokenLabel(p0: String?): String? {
        return null
    }

    override fun updateCredentials(
        p0: AccountAuthenticatorResponse?,
        p1: Account?,
        p2: String?,
        p3: Bundle?
    ): Bundle? {
        return null
    }

    override fun hasFeatures(
        p0: AccountAuthenticatorResponse?,
        p1: Account?,
        p2: Array<out String>?
    ): Bundle {
        val result = Bundle()
        result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)
        return result
    }
}