package us.berkovitz.plexaaos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.berkovitz.plexaaos.databinding.FragmentPasswordSignInBinding
import us.berkovitz.plexapi.config.Config
import us.berkovitz.plexapi.myplex.MyPlexAccount

class PasswordSignInFragment : Fragment() {

    private var _binding: FragmentPasswordSignInBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPasswordSignInBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSignIn.setOnClickListener {
            val username = binding.username.text.toString()
            val password = binding.password.text.toString()
            
            binding.errorMessage.visibility = View.GONE
            binding.btnSignIn.isEnabled = false
            
            doLogin(username, password) { error ->
                binding.errorMessage.text = error
                binding.errorMessage.visibility = View.VISIBLE
                binding.btnSignIn.isEnabled = true
            }
        }

        binding.password.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                WindowInsetsControllerCompat(requireActivity().window, v)
                    .hide(WindowInsetsCompat.Type.ime())

                binding.btnSignIn.performClick()
                true
            } else {
                false
            }
        }

        binding.switchToQr.setOnClickListener {
            (activity as? LoginActivity)?.switchToQrCode()
        }
    }

    fun doLogin(username: String, password: String, errCb: (String) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val loginRes = MyPlexAccount.login(username, password)
                if (loginRes.isNotEmpty()) {
                    val token = "${Config.X_PLEX_IDENTIFIER}|${loginRes}"
                    withContext(Dispatchers.Main) {
                        (activity as? LoginActivity)?.setToken(token)
                    }
                }
            } catch (exc: Exception) {
                withContext(Dispatchers.Main) {
                    errCb(exc.message ?: getString(R.string.unknown_error))
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
