package us.berkovitz.plexaaos

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.android.car.ui.preference.PreferenceFragment
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import us.berkovitz.plexapi.myplex.MyPlexResource

class SettingsFragment : PreferenceFragment() {
    private val viewModel: SettingsViewModel by activityViewModels()

    // Pending PIN dialog state
    private var pendingPinUserId: String? = null
    private var pendingPinUserTitle: String? = null
    private var showPendingPinDialog: Boolean = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.account_preferences, rootKey)

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

        // Observe view model
        val serverPref = findPreference<ListPreference>("pref_server")
        val userPref = findPreference<ListPreference>("pref_switch_user")
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.servers, viewModel.currentServerId) { servers, currentId ->
                        servers to currentId
                    }.collect { (servers, currentId) ->
                        if (servers == null) {
                            serverPref?.isEnabled = false
                            serverPref?.summary = getString(R.string.pref_loading)
                        } else if (servers.isEmpty()) {
                            serverPref?.isEnabled = false
                            serverPref?.summary = getString(R.string.pref_server_none)
                        } else {
                            serverPref?.isEnabled = true

                            val entries = mutableListOf(getString(R.string.pref_server_auto))
                            entries.addAll(servers.map { it.name })
                            serverPref?.entries = entries.toTypedArray()

                            val entryValues = mutableListOf(SERVER_ID_AUTO)
                            entryValues.addAll(servers.map { it.clientIdentifier ?: "" })
                            serverPref?.entryValues = entryValues.toTypedArray()

                            serverPref?.value = currentId ?: SERVER_ID_AUTO
                            serverPref?.summary = getServerText(servers.find { it.clientIdentifier == currentId })
                        }
                    }
                }
                launch {
                    combine(viewModel.users, viewModel.userSwitchStatus) { users, status ->
                        users to status
                    }.collect { (users, status) ->
                        if (users == null) {
                            userPref?.isEnabled = false
                            userPref?.summary = getString(R.string.pref_loading)
                        } else if (users.isEmpty()) {
                            userPref?.isEnabled = false
                            userPref?.summary = getString(R.string.pref_user_none)
                        } else {
                            userPref?.entries = users.map { it.title }.toTypedArray()
                            userPref?.entryValues = users.map { it.id.toString() }.toTypedArray()

                            // Make sure the current value of the preference is always null so that
                            // switching consistently works. The list returned by the API only
                            // contains users *other* than the current one.
                            userPref?.value = null
                            when (status) {
                                is UserSwitchStatus.Idle -> {
                                    userPref?.isEnabled = true
                                    userPref?.summary = getString(R.string.pref_user_summary)
                                }
                                is UserSwitchStatus.Switching -> {
                                    userPref?.isEnabled = false
                                    userPref?.summary = getString(R.string.user_switching)
                                }
                                is UserSwitchStatus.Success -> {
                                    userPref?.isEnabled = true
                                    val successString = getString(R.string.user_switch_success, status.userTitle)
                                    userPref?.summary = successString
                                    Toast.makeText(
                                        requireContext(),
                                        successString,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                is UserSwitchStatus.Error -> {
                                    userPref?.isEnabled = true
                                    val errorString = getString(R.string.user_switch_failed, status.message)
                                    userPref?.summary = errorString
                                    Toast.makeText(
                                        requireContext(),
                                        errorString,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                }
                launch {
                    viewModel.signOutEvent.collect {
                        activity?.finish()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Show PIN dialog if needed
        val userId = pendingPinUserId
        val userTitle = pendingPinUserTitle
        if (showPendingPinDialog && userId != null && userTitle != null) {
            showPendingPinDialog = false
            val dialog = PinEntryDialogFragment.newInstance(userId, userTitle)
            dialog.show(childFragmentManager, "PinEntryDialog")
        }
    }

    // Server Preference
    private fun getServerText(server: MyPlexResource?): String {
        return server?.name ?: getString(R.string.pref_server_auto)
    }

    private fun setupServerPreference() {
        val serverPref = findPreference<ListPreference>("pref_server")
        serverPref?.setOnPreferenceChangeListener { _, newValue ->
            val serverId = newValue as? String ?: return@setOnPreferenceChangeListener true
            viewModel.setServer(serverId)
            true
        }
    }

    // User Preference
    private fun setupUserPreference() {
        val userPref = findPreference<ListPreference>("pref_switch_user")
        userPref?.setOnPreferenceClickListener {
            viewModel.resetSwitchStatus()
            false
        }
        userPref?.setOnPreferenceChangeListener { preference, newValue ->
            if ((preference as ListPreference).value == newValue) {
                return@setOnPreferenceChangeListener true
            }

            val userId = newValue as? String ?: return@setOnPreferenceChangeListener true
            val user = viewModel.users.value?.find { it.id.toString() == userId }

            if (user?.protected == 1) {
                // Set pending PIN dialog state
                pendingPinUserId = userId
                pendingPinUserTitle = user.title
                showPendingPinDialog = true
            } else {
                viewModel.switchUser(userId, user?.title ?: getString(R.string.unknown_user), null)
            }

            // Don't allow preference to update - there's no point in doing this as the user list
            // will reload after a successful switch, and the user list only contains users
            // *other* than the currently selected one.
            false
        }
    }

    // Sign Out Preference
    private fun setupSignOutPreference() {
        val signOutPref = findPreference<Preference>("pref_sign_out")
        signOutPref?.isEnabled = (viewModel.plexToken != null)
        signOutPref?.setOnPreferenceClickListener {
            viewModel.signOut()
            true
        }
    }
}
