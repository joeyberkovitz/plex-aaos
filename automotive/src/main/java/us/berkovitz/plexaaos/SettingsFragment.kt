package us.berkovitz.plexaaos

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.android.car.ui.preference.PreferenceFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.berkovitz.plexapi.myplex.MyPlexResource
import us.berkovitz.plexapi.myplex.MyPlexUser

class SettingsFragment : PreferenceFragment(), PinEntryDialogFragment.PinEntryListener {
    // Pending PIN dialog state
    private var pendingPinUserId: String? = null
    private var pendingPinUserTitle: String? = null
    private var showPendingPinDialog: Boolean = false

    private var plexToken: String? = null
    private lateinit var plexUtil: PlexUtil
    private var users: List<MyPlexUser> = emptyList()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.account_preferences, rootKey)

        plexUtil = PlexUtil(requireContext())
        plexToken = plexUtil.getToken()

        if (savedInstanceState != null) {
            pendingPinUserId = savedInstanceState.getString("pendingPinUserId")
            pendingPinUserTitle = savedInstanceState.getString("pendingPinUserTitle")
            showPendingPinDialog = savedInstanceState.getBoolean("showPendingPinDialog")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pendingPinUserId", pendingPinUserId)
        outState.putString("pendingPinUserTitle", pendingPinUserTitle)
        outState.putBoolean("showPendingPinDialog", showPendingPinDialog)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupServerPreference()
        setupUserPreference()
        setupSignOutPreference()
    }

    override fun onResume() {
        super.onResume()
        // Show PIN dialog if needed
        val userId = pendingPinUserId
        val userTitle = pendingPinUserTitle
        if (showPendingPinDialog && userId != null && userTitle != null) {
            showPendingPinDialog = false
            val dialog = PinEntryDialogFragment.newInstance(userTitle)
            dialog.show(childFragmentManager, "PinEntryDialog")
        }
    }

    // Server Preference
    private fun getServerText(server: MyPlexResource?): String {
        return server?.name ?: getString(R.string.pref_server_auto)
    }

    private fun setupServerPreference() {
        val serverPref = findPreference<ListPreference>("pref_server")
        serverPref?.isSelectable = false
        serverPref?.setOnPreferenceChangeListener { preference, newValue ->
            onServerPreferenceChange(preference, newValue)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val settingsActivity = activity as? SettingsActivity
            val servers = settingsActivity?.cachedServers ?: withContext(Dispatchers.IO) {
                val fetched = PlexUtil.getServers(plexToken ?: "")
                settingsActivity?.cachedServers = fetched
                fetched
            }

            if (servers.isNotEmpty()) {
                val entries = mutableListOf(getString(R.string.pref_server_auto))
                val entryValues = mutableListOf("auto")

                entries.addAll(servers.map { it.name })
                entryValues.addAll(servers.map { it.clientIdentifier ?: "" })

                serverPref?.entries = entries.toTypedArray()
                serverPref?.entryValues = entryValues.toTypedArray()

                val currentServer = withContext(Dispatchers.IO) {
                    AndroidStorage.getServer(requireContext())
                }
                serverPref?.isEnabled = true
                serverPref?.value = currentServer ?: "auto"
                serverPref?.summary = getServerText(servers.find { it.clientIdentifier == currentServer })
            } else {
                serverPref?.isEnabled = false
                serverPref?.summary = getString(R.string.pref_server_none)
            }

            serverPref?.isSelectable = true
        }
    }

    private fun onServerPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        if ((preference as ListPreference).value == newValue) {
            return true
        }

        val serverId = newValue as? String ?: return true
        val context = context?.applicationContext ?: return true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AndroidStorage.setServer(if (serverId == "auto") null else serverId, context)
            }
            (activity as? SettingsActivity)?.notifyRefresh()
        }
        return true
    }

    // User Preference
    private fun getUserText(user: MyPlexUser?): String {
        return user?.title ?: getString(R.string.pref_user_summary)
    }

    private fun setupUserPreference() {
        val userPref = findPreference<ListPreference>("pref_switch_user")
        userPref?.isSelectable = false
        userPref?.setOnPreferenceChangeListener { preference, newValue ->
            onUserPreferenceChange(preference, newValue)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val settingsActivity = activity as? SettingsActivity
            users = settingsActivity?.cachedUsers ?: withContext(Dispatchers.IO) {
                val fetched = PlexUtil.getUsers(plexToken ?: "")
                settingsActivity?.cachedUsers = fetched
                fetched
            }

            if (users.isNotEmpty()) {
                val entries = users.map { it.title }.toTypedArray()
                val entryValues = users.map { it.id.toString() }.toTypedArray()
                userPref?.entries = entries
                userPref?.entryValues = entryValues
                userPref?.summary = getUserText(users.find { it.id.toString() == userPref.value })
            } else {
                userPref?.isEnabled = false
                userPref?.summary = getString(R.string.pref_user_none)
            }

            userPref?.isSelectable = true
        }
    }

    private suspend fun performUserSwitch(userId: String, userTitle: String, pin: String? = null): String? {
        val context = requireContext().applicationContext
        val activity = activity as? SettingsActivity
        return try {
            val newToken = withContext(Dispatchers.IO) {
                PlexUtil.switchUser(plexToken ?: "", userId, pin)
            }
            plexUtil.setToken(newToken)

            val userPref = findPreference<ListPreference>("pref_switch_user")
            userPref?.value = userId
            userPref?.summary = userTitle

            activity?.notifyRefresh()
            Toast.makeText(context, getString(R.string.user_switch_success, userTitle), Toast.LENGTH_SHORT).show()
            null
        } catch (e: Exception) {
            val errorMessage = e.message ?: getString(R.string.unknown_error)
            if (pin == null) {
                Toast.makeText(context, getString(R.string.user_switch_failed, errorMessage), Toast.LENGTH_SHORT).show()
            }
            errorMessage
        }
    }

    private fun onUserPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        if ((preference as ListPreference).value == newValue) {
            return true
        }

        val userId = newValue as? String ?: return true
        val user = users.find { it.id.toString() == userId }

        if (user?.protected == 1) {
            // Set pending PIN dialog state
            pendingPinUserId = userId
            pendingPinUserTitle = user.title
            showPendingPinDialog = true

            // Don't allow preference to update, we will update it if user switch succeeds
            return false 
        } else {
            lifecycleScope.launch {
                performUserSwitch(userId, user?.title ?: getString(R.string.unknown_user))
            }
            return false
        }
    }

    // PinEntryListener implementation
    override suspend fun onPinEntered(pin: String): String? {
        val userId = pendingPinUserId ?: ""
        val userTitle = pendingPinUserTitle ?: getString(R.string.unknown_user)

        val error = performUserSwitch(userId, userTitle, pin)

        if (error == null) {
            // Clear pending state
            pendingPinUserId = null
            pendingPinUserTitle = null
        }
        return error
    }

    override fun onPinCancelled() {
        pendingPinUserId = null
        pendingPinUserTitle = null
    }

    // Sign Out Preference
    private fun setupSignOutPreference() {
        val signOutPref = findPreference<Preference>("pref_sign_out")
        signOutPref?.isEnabled = (plexToken != null)
        signOutPref?.setOnPreferenceClickListener {
            (activity as? SettingsActivity)?.signOut()
            true
        }
    }
}
