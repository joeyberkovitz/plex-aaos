package us.berkovitz.plexaaos

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.os.Bundle
import us.berkovitz.plexaaos.library.PlexSource
import us.berkovitz.plexapi.config.Config
import us.berkovitz.plexapi.media.PlexServer
import us.berkovitz.plexapi.myplex.MyPlexAccount
import us.berkovitz.plexapi.myplex.MyPlexResource
import us.berkovitz.plexapi.myplex.MyPlexUser

class PlexUtil(private val ctx: Context) {
    private var accountManager = AccountManager.get(ctx)

    fun getToken(): String? {
        val accounts = accountManager.getAccountsByType(Authenticator.ACCOUNT_TYPE)
        if (accounts.isEmpty()) {
            return null
        }

        val token = accountManager.getPassword(accounts[0]).split('|')
        if (token.size != 2) {
            accountManager.removeAccountExplicitly(accounts[0])
            return null
        }
        AndroidPlexApi.initPlexApi(ctx, token[0])
        return token[1]
    }

    fun setToken(token: String) {
        val account = Account(
            "PlexAAOS", //TODO: get account username from login???
            Authenticator.ACCOUNT_TYPE
        )
        val finalToken = Config.X_PLEX_IDENTIFIER + "|" + token
        accountManager.setPassword(account, finalToken)
    }

    fun clearToken() {
        val account = Account(
            "PlexAAOS", //TODO: get account username from login???
            Authenticator.ACCOUNT_TYPE
        )
        accountManager.removeAccountExplicitly(account)
    }

    companion object {
        suspend fun getServers(token: String): List<MyPlexResource> {
            val plexAccount = MyPlexAccount(token)
            return plexAccount.resources().filter { server ->
                if (server.connections != null) {
                    for (conn in server.connections!!) {
                        if (conn.local == 0) {
                            return@filter true
                        }
                    }
                }
                return@filter false
            }
        }

        suspend fun getUsers(token: String): List<MyPlexUser> {
            val plexAccount = MyPlexAccount(token)
            return plexAccount.users().toList()
        }

        suspend fun switchUser(token: String, userId: String, pin: String?): String {
            val plexAccount = MyPlexAccount(token)
            return plexAccount.switchUser(userId, pin)
        }
    }
}