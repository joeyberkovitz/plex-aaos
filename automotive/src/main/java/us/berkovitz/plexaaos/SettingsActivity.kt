package us.berkovitz.plexaaos

import android.content.ComponentName
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.android.car.ui.core.CarUi
import com.android.car.ui.toolbar.NavButtonMode
import us.berkovitz.plexapi.myplex.MyPlexResource
import us.berkovitz.plexapi.myplex.MyPlexUser

class SettingsActivity : AppCompatActivity() {

    lateinit var plexUtil: PlexUtil
    private lateinit var musicServiceConnection: MusicServiceConnection
    var plexToken: String? = null

    var cachedServers: List<MyPlexResource>? = null
    var cachedUsers: List<MyPlexUser>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = CarUi.requireToolbar(this)
        toolbar.setTitle(R.string.title_activity_settings)
        toolbar.navButtonMode = NavButtonMode.BACK

        musicServiceConnection = MusicServiceConnection(
            applicationContext,
            ComponentName(applicationContext, PlexMediaService::class.java)
        )

        plexUtil = PlexUtil(this)
        plexToken = plexUtil.getToken()

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    fun signOut() {
        musicServiceConnection.sendCommand(LOGOUT, Bundle.EMPTY)
        finish()
    }

    fun notifyRefresh() {
        musicServiceConnection.sendCommand(REFRESH, Bundle.EMPTY)
    }
}