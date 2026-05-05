package com.plugin.music_notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent

/**
 * Handles notification media button presses by calling [MusicPlayerService] directly.
 *
 * Routes button presses to the service methods instead of going through
 * [MediaButtonReceiver.handleIntent], which requires manifest intent-filters
 * that MusicPlayerService does not declare.
 *
 * Related: MusicPlayerService.kt
 */
class SafeMediaButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SafeMediaBtnReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_MEDIA_BUTTON != intent.action) return

        val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (keyEvent.action != KeyEvent.ACTION_DOWN) return

        if (MusicPlayerService.isHeadsetMediaButtonDisabled(context)) {
            Log.d(TAG, "onReceive: headset media buttons disabled, ignoring keyCode=${keyEvent.keyCode}")
            return
        }

        val service = MusicPlayerService.instance
        if (service == null) {
            Log.w(TAG, "onReceive: MusicPlayerService not running, ignoring keyCode=${keyEvent.keyCode}")
            return
        }

        Log.d(TAG, "onReceive: keyCode=${keyEvent.keyCode}, hasPlayer=${service.hasMediaPlayer()}, queueSize=${service.queueSize()}")

        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (service.hasMediaPlayer()) {
                    service.resumeMusic()
                } else {
                    service.playCurrentTrack()
                }
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> service.pauseMusic()
            KeyEvent.KEYCODE_MEDIA_NEXT -> service.playNextTrack()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> service.playPreviousTrack()
            KeyEvent.KEYCODE_MEDIA_STOP -> service.stopFromNotification()
        }
    }
}
