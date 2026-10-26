package com.vibeplayer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** The app's front door. Android starts this when you tap the app icon. */
class MainActivity : ComponentActivity() {

    private lateinit var player: PlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // lets the app draw behind the status bar and navigation bar
        PlayHistory.load(applicationContext)
        AppSettings.load(applicationContext)
        ExtraSettings.load(applicationContext)
        BlacklistStore.load(applicationContext)
        PlaylistStore.load(applicationContext)
        player = PlayerController(applicationContext)
        player.connect()
        setContent {
            VibeTheme {
                VibeApp(player)
            }
        }
    }

    override fun onDestroy() {
        player.release() // disconnects the remote control; music keeps playing in the service
        super.onDestroy()
    }
}
