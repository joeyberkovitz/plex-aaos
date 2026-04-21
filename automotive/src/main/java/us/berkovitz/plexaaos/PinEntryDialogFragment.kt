package us.berkovitz.plexaaos

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import us.berkovitz.plexaaos.databinding.DialogPinEntryBinding

class PinEntryDialogFragment : DialogFragment() {
    interface PinEntryListener {
        suspend fun onPinEntered(pin: String): String?
        fun onPinCancelled()
    }

    private var listener: PinEntryListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as? PinEntryListener ?: activity as? PinEntryListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val userTitle = arguments?.getString(ARG_USER_TITLE) ?: ""
        val binding = DialogPinEntryBinding.inflate(LayoutInflater.from(context))
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pin_entry_title, userTitle))
            .setView(binding.root)
            .setPositiveButton(R.string.pin_entry_submit, null) // Set to null to override later
            .setNegativeButton(R.string.pin_entry_cancel) { _, _ ->
                listener?.onPinCancelled()
            }
            .create()

        dialog.setOnShowListener {
            val submitButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            
            val performSubmit = {
                val pin = binding.pinEditText.text.toString()
                if (pin.length != 4 || !pin.all { it.isDigit() }) {
                    binding.errorTextView.text = getString(R.string.pin_error_digits)
                    binding.errorTextView.visibility = View.VISIBLE
                } else {
                    lifecycleScope.launch {
                        binding.errorTextView.visibility = View.GONE
                        binding.progressBar.visibility = View.VISIBLE
                        binding.pinEditText.isEnabled = false
                        submitButton.isEnabled = false

                        val error = listener?.onPinEntered(pin)

                        if (error == null) {
                            dialog.dismiss()
                        } else {
                            binding.errorTextView.text = error
                            binding.errorTextView.visibility = View.VISIBLE
                            binding.progressBar.visibility = View.GONE
                            binding.pinEditText.text.clear()
                            binding.pinEditText.isEnabled = true
                            submitButton.isEnabled = true
                        }
                    }
                }
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
        }

        return dialog
    }

    companion object {
        private const val ARG_USER_TITLE = "user_title"

        fun newInstance(userTitle: String): PinEntryDialogFragment {
            val fragment = PinEntryDialogFragment()
            val args = Bundle()
            args.putString(ARG_USER_TITLE, userTitle)
            fragment.arguments = args
            return fragment
        }
    }
}
