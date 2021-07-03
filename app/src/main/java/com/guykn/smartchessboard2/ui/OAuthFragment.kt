package com.guykn.smartchessboard2.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.guykn.smartchessboard2.ChessBoardSettings
import com.guykn.smartchessboard2.GameStartRequest
import com.guykn.smartchessboard2.R
import com.guykn.smartchessboard2.network.oauth2.getLichessAuthIntent
import com.guykn.smartchessboard2.ui.OAuthViewModel.UiOAuthState
import dagger.hilt.android.AndroidEntryPoint
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService

@AndroidEntryPoint
class OAuthFragment : Fragment() {

    companion object {
        const val TAG = "MA_OAuthFragment"
    }

    private val oAuthViewModel: OAuthViewModel by activityViewModels()
    private val lichessViewModel: LichessViewModel by activityViewModels()
    private val bluetoothViewModel: BluetoothViewModel by activityViewModels()

    private val lichessAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        ::onAuthIntentFinished
    )

    private lateinit var progressBar: ProgressBar
    private lateinit var mainView: View
    private lateinit var isSignedInView: TextView
    private lateinit var signInOutButton: Button
    private lateinit var broadcastButton: Button
    private lateinit var startGameButton: Button
    private lateinit var startOfflineGameButton: Button
    private lateinit var learningModeSwitch: SwitchCompat


    private lateinit var authService: AuthorizationService


    override fun onAttach(context: Context) {
        super.onAttach(context)
        authService = AuthorizationService(context)
    }

    override fun onDetach() {
        super.onDetach()
        authService.dispose()
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.o_auth_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        progressBar = view.findViewById(R.id.progress_bar)
        mainView = view.findViewById(R.id.main_view)
        isSignedInView = view.findViewById(R.id.is_signed_in)
        signInOutButton = view.findViewById(R.id.sign_in)
        broadcastButton = view.findViewById(R.id.start_broadcast)
        startGameButton = view.findViewById(R.id.start_game)
        startOfflineGameButton = view.findViewById(R.id.start_offline_game)
        learningModeSwitch = view.findViewById(R.id.learning_mode_switch)


        oAuthViewModel.oAuthStateLiveData.observe(viewLifecycleOwner) { uiOAuthState ->
            uiOAuthState!!
            when (uiOAuthState) {
                is UiOAuthState.NotYetLoaded -> {
                    progressBar.visibility = View.VISIBLE
                    mainView.alpha = 0.5f
                    isSignedInView.text = getString(R.string.not_signed_in)
                    signInOutButton.isEnabled = false
                    signInOutButton.text = getString(R.string.sign_in)
                    broadcastButton.isEnabled = false
                }
                is UiOAuthState.NotAuthorized -> {
                    progressBar.visibility = View.GONE
                    mainView.alpha = 1f
                    isSignedInView.text = getString(R.string.not_signed_in)
                    signInOutButton.isEnabled = true
                    signInOutButton.text = getString(R.string.sign_in)
                    broadcastButton.isEnabled = false
                }
                is UiOAuthState.AuthorizationLoading -> {
                    progressBar.visibility = View.VISIBLE
                    mainView.alpha = 0.5f
                    isSignedInView.text = getString(R.string.not_signed_in)
                    signInOutButton.isEnabled = false
                    broadcastButton.isEnabled = false
                }
                is UiOAuthState.Authorized -> {
                    val userName = uiOAuthState.userInfo.username
                    progressBar.visibility = View.GONE
                    mainView.alpha = 1f
                    isSignedInView.text = "Signed in as $userName"
                    signInOutButton.isEnabled = true
                    signInOutButton.text = getString(R.string.sign_out)
                    broadcastButton.isEnabled = true
                }
            }
        }

        lichessViewModel.broadcastRound.observe(viewLifecycleOwner) { broadcastEvent ->
            if (broadcastEvent === null) return@observe
            broadcastEvent.value?.let {
                broadcastButton.text = getString(R.string.stop_broadcast)
            } ?: kotlin.run {
                broadcastButton.text = getString(R.string.start_broadcast)
            }
        }

        lichessViewModel.broadcastRound.observe(viewLifecycleOwner) { broadcastEvent ->
            if (broadcastEvent?.value != null && broadcastEvent.receive()) {
                openWebBrowser(broadcastEvent.value.url)
            }
        }

        lichessViewModel.activeGame.observe(viewLifecycleOwner) { gameEvent ->
            if (gameEvent?.value != null && gameEvent.receive()) {
                openWebBrowser(gameEvent.value.url)
            }
        }

        oAuthViewModel.errorLiveData.observe(viewLifecycleOwner) { event ->
            if (event.receive()) {
                context?.let {
                    Toast.makeText(
                        it,
                        "Error with authorization please try again.",
                        Toast.LENGTH_LONG
                    ).show()
                } ?: Log.w(
                    TAG,
                    "OAuthFragment observed change to liveData while not attached to a context"
                )
            }
        }

        signInOutButton.setOnClickListener {
            if (oAuthViewModel.oAuthStateLiveData.value is UiOAuthState.Authorized) {
                // the user is already signed in, so they want to sign out
                oAuthViewModel.signOut()
            } else {
                // the user clicked the button to sign in
                lichessAuthLauncher.launch(authService.getLichessAuthIntent())
            }
        }
        broadcastButton.setOnClickListener {
            if (lichessViewModel.broadcastRound.value?.value == null) {
                Log.d(TAG, "Creating broadcast")
                lichessViewModel.createBroadcast()
            } else {
                Log.d(TAG, "Stopping broadcast")
                lichessViewModel.stopBroadcast()
            }
        }

        startGameButton.setOnClickListener {
            lichessViewModel.startGame()
        }

        startOfflineGameButton.setOnClickListener {
            bluetoothViewModel.startGame(
                GameStartRequest(
                    enableEngine = true,
                    engineColor = "black",
                    engineLevel = 20
                )
            )
        }

        learningModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "learningModeSwitch is ${if (isChecked) "checked" else "not checked"}.")
            bluetoothViewModel.writeSettings(
                ChessBoardSettings(learningMode = isChecked)
            )
        }
    }

    private fun onAuthIntentFinished(result: ActivityResult?) {
        result?.data?.let {
            val authorizationResponse = AuthorizationResponse.fromIntent(it)
            val authorizationException = AuthorizationException.fromIntent(it)
            oAuthViewModel.onAuthorizationIntentFinished(
                authorizationResponse,
                authorizationException
            )
        } ?: run {
            Log.d(TAG, "Authorization intent was null")
            oAuthViewModel.onAuthorizationError()
        }
    }

    private fun openWebBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
        }
        startActivity(intent)
    }
}
