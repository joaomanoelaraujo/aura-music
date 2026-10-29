package com.aura.music

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aura.music.core.designsystem.atmosphere.AtmosphereEngine
import com.aura.music.playback.controller.PlayerConnection
import com.aura.music.ui.AuraApp
import com.aura.music.ui.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playerConnection: PlayerConnection
    @Inject lateinit var atmosphereEngine: AtmosphereEngine

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.appState.value == null }
        splash.setOnExitAnimationListener { provider ->
            ObjectAnimator.ofFloat(provider.view, View.ALPHA, 1f, 0f).apply {
                duration = SPLASH_FADE_MS
                doOnEnd { provider.remove() }
                start()
            }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // On recreation the original intent comes back; it was already handled.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AuraApp(viewModel, atmosphereEngine)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        playerConnection.connect()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onStop() {
        super.onStop()
        // Releasing the controller lets the service stop itself when nothing is playing.
        if (!isChangingConfigurations) playerConnection.disconnect()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.dataString?.let { link -> if (viewModel.offerInvite(link)) intent.data = null }
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            viewModel.requestOpenPlayer()
            intent.removeExtra(EXTRA_OPEN_PLAYER)
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "com.aura.music.OPEN_PLAYER"
        private const val SPLASH_FADE_MS = 260L
    }
}
