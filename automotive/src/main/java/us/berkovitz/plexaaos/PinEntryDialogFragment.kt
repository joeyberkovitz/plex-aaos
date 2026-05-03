package us.berkovitz.plexaaos

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import us.berkovitz.plexaaos.databinding.DialogPinEntryBinding

class PinEntryDialogFragment : DialogFragment() {
    private val viewModel: SettingsViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val userTitle = arguments?.getString(ARG_USER_TITLE) ?: ""
        val userId = arguments?.getString(ARG_USER_ID) ?: ""
        val binding = DialogPinEntryBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pin_entry_title, userTitle))
            .setView(binding.root)
            .setPositiveButton(R.string.pin_entry_submit, null) // Set to null to override later
            .setNegativeButton(R.string.pin_entry_cancel, null)
            .create()

        dialog.setOnShowListener {
            val submitButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            
            val performSubmit = {
                val pin = binding.pinEditText.text.toString()
                viewModel.switchUser(userId, userTitle, pin) 
            }

            submitButton.setOnClickListener {
                performSubmit()
            }

            binding.pinEditText.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    performSubmit()
                    true
                } else {
                    false
                }
            }

            // Observe view model state
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        viewModel.userSwitchStatus.collect { status ->
                            // Block dialog interaction and show progress while user is switching
                            val isSwitching = (status == UserSwitchStatus.Switching)
                            isCancelable = !isSwitching
                            dialog.setCanceledOnTouchOutside(!isSwitching)
                            binding.pinEditText.isEnabled = !isSwitching
                            submitButton.isEnabled = !isSwitching
                            cancelButton.isEnabled = !isSwitching
                            binding.progressBar.visibility = if (isSwitching) View.VISIBLE else View.GONE

                            // Show error information if applicable
                            if (status is UserSwitchStatus.Error) {
                                binding.errorTextView.text = status.message
                                binding.errorTextView.visibility = View.VISIBLE
                                binding.pinEditText.text.clear()
                            } else {
                                binding.errorTextView.visibility = View.GONE
                            }

                            // Auto-dismiss dialog on success
                            if (status is UserSwitchStatus.Success) {
                                dialog.dismiss()
                            }
                        }
                    }
                }
            }
        }

        return dialog
    }

    companion object {
        private const val ARG_USER_TITLE = "user_title"
        private const val ARG_USER_ID = "user_id"

        fun newInstance(userId: String, userTitle: String): PinEntryDialogFragment {
            val fragment = PinEntryDialogFragment()
            val args = Bundle()
            args.putString(ARG_USER_ID, userId)
            args.putString(ARG_USER_TITLE, userTitle)
            fragment.arguments = args
            return fragment
        }
    }
}
