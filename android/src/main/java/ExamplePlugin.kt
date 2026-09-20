package com.plugin.music_notification

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Channel
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import org.json.JSONArray

@InvokeArg
class PingArgs {
  var value: String? = null
}

@InvokeArg
class PlayArgs {
  var url: String = ""
  var title: String? = null
  var artist: String? = null
  var album: String? = null
  var coverUrl: String? = null
}

@InvokeArg
class SeekArgs {
  var position: Long = 0
}

@InvokeArg
class PauseAfterArgs {
  var delayMs: Long = 0
}

@InvokeArg
class SetVolumeArgs {
  var volume: Float = 0.5f
}

@InvokeArg
class SetNormalizationConfigArgs {
  var mode: String = "auto"
  var manualVolume: Float = 0.5f
  var fixedLufs: Double = -27.0
  var lufsPrecacheCount: Int = 5
}

@InvokeArg
class SetServerArgs {
  var libraryName: String? = null
}

@InvokeArg
class QueueSongArgs {
  var id: Long = -1L
  var name: String = ""
  var deviceId: String? = null
  var sourceKind: String = ""
  var localUri: String? = null
  var tempSongUrl: String? = null
  var lufs: Double? = null
  var coverUrl: String? = null
}

@InvokeArg
class PlayingQueueArgs {
  var songs: Array<QueueSongArgs> = emptyArray()
  var currentIndex: Int? = null
}

@InvokeArg
class SetPlayingQueueArgs {
  var queue: PlayingQueueArgs = PlayingQueueArgs()
  var playMode: String? = null
}

@InvokeArg
class SetPlayModeArgs {
  var playMode: String? = null
}

@InvokeArg
class SetHeadsetMediaButtonDisabledArgs {
  var disabled: Boolean = false
}

@InvokeArg
class SubscribePlaybackEventsArgs {
  lateinit var handler: Channel
}

@InvokeArg
class UnsubscribePlaybackEventsArgs {
  var channelId: Long = 0
}

@TauriPlugin
class MusicNotificationPlugin(private val activity: Activity): Plugin(activity) {

    private var playbackEventChannel: Channel? = null

    companion object {
        private const val TAG = "MusicNotificationPlugin"
        private const val PREFS_NAME = "music_notification"
        private const val PREF_SERVER_LIB_NAME = "server_lib_name"

        fun getServerLibName(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(PREF_SERVER_LIB_NAME, null)
        }
    }

    init {
        MusicPlayerService.setPlaybackEventListener { event ->
            activity.runOnUiThread {
                val channel = playbackEventChannel
                if (channel == null) {
                    Log.d(TAG, "Dropping playback event without subscriber: reason=${event.reason} sequence=${event.sequence}")
                } else {
                    Log.d(TAG, "Forwarding playback event: reason=${event.reason} sequence=${event.sequence}")
                    channel.send(playbackEventToJsObject(event))
                }
            }
        }
    }

    override fun onDestroy() {
        playbackEventChannel = null
        MusicPlayerService.setPlaybackEventListener(null)
        super.onDestroy()
    }

    private fun sessionToJsObject(snapshot: MusicPlayerService.SessionSnapshot): JSObject {
        val songs = JSONArray()
        snapshot.queue.songs.forEach { song ->
            songs.put(JSObject().apply {
                put("id", song.id)
                put("name", song.name)
                put("deviceId", song.deviceId)
                put("sourceKind", song.sourceKind)
                put("localUri", song.localUri)
                put("tempSongUrl", song.tempSongUrl)
                put("lufs", song.lufs)
                put("coverUrl", song.coverUrl)
            })
        }

        return JSObject().apply {
            put("queue", JSObject().apply {
                put("songs", songs)
                put("currentIndex", snapshot.queue.currentIndex)
            })
            put("runtime", JSObject().apply {
                put("isPlaying", snapshot.runtime.isPlaying)
                put("positionMs", snapshot.runtime.positionMs)
                put("durationMs", snapshot.runtime.durationMs)
            })
            put("playMode", snapshot.playMode)
            put("currentSongId", snapshot.currentSongId)
        }
    }

    private fun playbackEventToJsObject(event: MusicPlayerService.PlaybackEvent): JSObject {
        return JSObject().apply {
            put("protocolVersion", event.protocolVersion)
            put("serviceGeneration", event.serviceGeneration)
            put("sequence", event.sequence)
            put("revision", event.revision)
            put("reason", event.reason)
            put("session", sessionToJsObject(event.session))
        }
    }

    @Command
    fun subscribePlaybackEvents(invoke: Invoke) {
        val args = invoke.parseArgs(SubscribePlaybackEventsArgs::class.java)
        playbackEventChannel = args.handler
        Log.i(TAG, "Playback event channel subscribed: channelId=${args.handler.id}")
        val ret = JSObject()
        ret.put("success", true)
        invoke.resolve(ret)
    }

    @Command
    fun unsubscribePlaybackEvents(invoke: Invoke) {
        val args = invoke.parseArgs(UnsubscribePlaybackEventsArgs::class.java)
        if (playbackEventChannel?.id == args.channelId) {
            playbackEventChannel = null
            Log.i(TAG, "Playback event channel unsubscribed: channelId=${args.channelId}")
        }
        val ret = JSObject()
        ret.put("success", true)
        invoke.resolve(ret)
    }

    @Command
    fun ping(invoke: Invoke) {
        val args = invoke.parseArgs(PingArgs::class.java)
        val ret = JSObject()
        ret.put("value", args.value ?: "pong from music notification plugin")
        invoke.resolve(ret)
    }

    @Command
    fun play(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(PlayArgs::class.java)

            if (args.url.isEmpty()) {
                val ret = JSObject()
                ret.put("success", false)
                ret.put("message", "URL is required")
                invoke.resolve(ret)
                return
            }

            val serviceIntent = Intent(activity, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_PLAY
                putExtra(MusicPlayerService.EXTRA_URL, args.url)
                putExtra(MusicPlayerService.EXTRA_TITLE, args.title ?: "Unknown Title")
                putExtra(MusicPlayerService.EXTRA_ARTIST, args.artist ?: "Unknown Artist")
                putExtra(MusicPlayerService.EXTRA_ALBUM, args.album ?: "Unknown Album")
                putExtra(MusicPlayerService.EXTRA_COVER_URL, args.coverUrl)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(serviceIntent)
            } else {
                activity.startService(serviceIntent)
            }

            val ret = JSObject()
            ret.put("success", true)
            ret.put("message", "Playing music")
            invoke.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject()
            ret.put("success", false)
            ret.put("message", e.message)
            invoke.resolve(ret)
        }
    }

    @Command
    fun pause(invoke: Invoke) {
        try {
            val serviceIntent = Intent(activity, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_PAUSE
            }
            activity.startService(serviceIntent)

            val ret = JSObject()
            ret.put("success", true)
            invoke.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject()
            ret.put("success", false)
            invoke.resolve(ret)
        }
    }

    @Command
    fun pauseAfter(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(PauseAfterArgs::class.java)
            val serviceIntent = Intent(activity, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_PAUSE_AFTER
                putExtra(MusicPlayerService.EXTRA_DELAY_MS, args.delayMs)
            }
            activity.startService(serviceIntent)

            val ret = JSObject()
            ret.put("success", true)
            invoke.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule timed pause", e)
            val ret = JSObject()
            ret.put("success", false)
            ret.put("message", e.message)
            invoke.resolve(ret)
        }
    }

    @Command
    fun resume(invoke: Invoke) {
        try {
            val serviceIntent = Intent(activity, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_RESUME
            }
            activity.startService(serviceIntent)

            val ret = JSObject()
            ret.put("success", true)
            invoke.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject()
            ret.put("success", false)
            invoke.resolve(ret)
        }
    }

    @Command
    fun stop(invoke: Invoke) {
        try {
            val serviceIntent = Intent(activity, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_STOP
            }
            activity.startService(serviceIntent)

            val ret = JSObject()
            ret.put("success", true)
            invoke.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject()
            ret.put("success", false)
            invoke.resolve(ret)
        }
    }

    @Command
    fun next(invoke: Invoke) {
        try {
            Log.d(TAG, "Command next(): dispatching ACTION_NEXT to MusicPlayerService")
            val serviceIntent = Intent(activity, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_NEXT
            }
            activity.startService(serviceIntent)

            val ret = JSObject()
            ret.put("success", true)
            invoke.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject()
            ret.put("success", false)
            invoke.resolve(ret)
        }
    }

    @Command
    fun previous(invoke: Invoke) {
        try {
            Log.d(TAG, "Command previous(): dispatching ACTION_PREVIOUS to MusicPlayerService")
            val serviceIntent = Intent(activity, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_PREVIOUS
            }
            activity.startService(serviceIntent)

            val ret = JSObject()
            ret.put("success", true)
            invoke.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject()
            ret.put("success", false)
            invoke.resolve(ret)
        }
    }

    @Command
    fun seek(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(SeekArgs::class.java)
            Log.d(TAG, "Command seek(): dispatching ACTION_SEEK to position=${args.position}")
            val serviceIntent = Intent(activity, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_SEEK
                putExtra(MusicPlayerService.EXTRA_POSITION, args.position)
            }
            activity.startService(serviceIntent)

            val ret = JSObject()
            ret.put("success", true)
            invoke.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject()
            ret.put("success", false)
            invoke.resolve(ret)
        }
    }

    @Command
    fun seekAndPlay(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(SeekArgs::class.java)
            Log.d(TAG, "Command seekAndPlay(): dispatching ACTION_SEEK_AND_PLAY to position=${args.position}")
            val serviceIntent = Intent(activity, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_SEEK_AND_PLAY
                putExtra(MusicPlayerService.EXTRA_POSITION, args.position)
            }
            activity.startService(serviceIntent)

            val ret = JSObject()
            ret.put("success", true)
            invoke.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject()
            ret.put("success", false)
            invoke.resolve(ret)
        }
    }

    @Command
    fun getState(invoke: Invoke) {
        try {
            val service = MusicPlayerService.instance
            val ret = JSObject()

            if (service != null) {
                val (isPlaying, position, duration) = service.getPlaybackState()
                ret.put("isPlaying", isPlaying)
                ret.put("position", position)
                ret.put("duration", duration)
            } else {
                val snapshot = MusicPlayerService.loadPersistedSessionSnapshot(activity)
                ret.put("isPlaying", false)
                ret.put("position", snapshot.runtime.positionMs)
                ret.put("duration", snapshot.runtime.durationMs)
            }

            invoke.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject()
            ret.put("isPlaying", false)
            ret.put("position", 0)
            ret.put("duration", 0)
            invoke.resolve(ret)
        }
    }

    @Command
    fun setPlayingQueue(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(SetPlayingQueueArgs::class.java)
            val songs = args.queue.songs.map {
                MusicPlayerService.QueueSongInfo(
                    id = it.id,
                    name = it.name,
                    deviceId = it.deviceId,
                    sourceKind = it.sourceKind,
                    localUri = it.localUri,
                    tempSongUrl = it.tempSongUrl,
                    lufs = it.lufs,
                    coverUrl = it.coverUrl
                )
            }
            val invalidSong = songs.firstOrNull { !it.hasValidLocator() }
            if (invalidSong != null) {
                val ret = JSObject()
                ret.put("success", false)
                ret.put(
                    "message",
                    "Invalid ${invalidSong.sourceKind} queue locator for ${invalidSong.name}"
                )
                invoke.resolve(ret)
                return
            }
            val playMode = args.playMode ?: "sequential"
            val service = MusicPlayerService.instance
            val selectedIndex = args.queue.currentIndex ?: 0
            val selectedSong = songs.getOrNull(selectedIndex)
            Log.i(
                TAG,
                "Command setPlayingQueue(): queueSize=${songs.size} currentIndex=${args.queue.currentIndex} " +
                    "serviceAvailable=${service != null} track=${selectedSong?.name} songId=${selectedSong?.id} " +
                    "deviceId=${selectedSong?.deviceId} sourceKind=${selectedSong?.sourceKind} " +
                    "coverUrl=${selectedSong?.coverUrl}"
            )

            if (service != null) {
                service.setPlayingQueue(songs, args.queue.currentIndex, playMode)
            } else {
                MusicPlayerService.savePersistedSessionSnapshot(
                    activity,
                    MusicPlayerService.SessionSnapshot(
                        queue = MusicPlayerService.PlayingQueueSnapshot(songs, args.queue.currentIndex),
                        runtime = MusicPlayerService.PlaybackRuntimeSnapshot(false, 0L, 0L),
                        playMode = playMode,
                        currentSongId = songs.getOrNull(args.queue.currentIndex ?: 0)?.id
                    )
                )
            }

            val ret = JSObject()
            ret.put("success", true)
            invoke.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set playing queue", e)
            val ret = JSObject()
            ret.put("success", false)
            ret.put("message", e.message)
            invoke.resolve(ret)
        }
    }

    @Command
    fun getPlaybackSession(invoke: Invoke) {
        try {
            val serviceInstance = MusicPlayerService.instance
            val snapshot = serviceInstance?.getPlaybackSession()
                ?: MusicPlayerService.loadPersistedSessionSnapshot(activity)

            val ret = sessionToJsObject(snapshot)
            serviceInstance?.let {
                ret.put("serviceGeneration", it.getServiceGeneration())
                ret.put("sequence", it.getEventSequence())
                ret.put("revision", it.getSessionRevision())
            }
            invoke.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get playback session", e)
            val ret = JSObject()
            ret.put("queue", JSObject().apply {
                put("songs", JSONArray())
                put("currentIndex", null)
            })
            ret.put("runtime", JSObject().apply {
                put("isPlaying", false)
                put("positionMs", 0)
                put("durationMs", 0)
            })
            ret.put("playMode", "sequential")
            ret.put("currentSongId", null)
            invoke.resolve(ret)
        }
    }

    @Command
    fun clearPlayingQueue(invoke: Invoke) {
        try {
            val service = MusicPlayerService.instance
            if (service != null) {
                service.clearPlayingQueue()
            } else {
                MusicPlayerService.savePersistedSessionSnapshot(activity, MusicPlayerService.emptySessionSnapshot())
            }

            val ret = JSObject()
            ret.put("success", true)
            invoke.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear playing queue", e)
            val ret = JSObject()
            ret.put("success", false)
            ret.put("message", e.message)
            invoke.resolve(ret)
        }
    }

    @Command
    fun setPlayMode(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(SetPlayModeArgs::class.java)
            val playMode = args.playMode ?: "sequential"
            val service = MusicPlayerService.instance
            if (service != null) {
                service.setPlayMode(playMode)
            } else {
                val snapshot = MusicPlayerService.loadPersistedSessionSnapshot(activity)
                MusicPlayerService.savePersistedSessionSnapshot(
                    activity,
                    snapshot.copy(playMode = playMode)
                )
            }

            val ret = JSObject()
            ret.put("success", true)
            invoke.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set play mode", e)
            val ret = JSObject()
            ret.put("success", false)
            ret.put("message", e.message)
            invoke.resolve(ret)
        }
    }

    @Command
    fun startService(invoke: Invoke) {
        try {
            val serviceIntent = Intent(activity, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_START_SERVICE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(serviceIntent)
            } else {
                activity.startService(serviceIntent)
            }

            val ret = JSObject()
            ret.put("success", true)
            ret.put("message", "Service started")
            invoke.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject()
            ret.put("success", false)
            ret.put("message", e.message)
            invoke.resolve(ret)
        }
    }

    @Command
    fun stopService(invoke: Invoke) {
        try {
            val serviceIntent = Intent(activity, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_STOP_SERVICE
            }
            activity.startService(serviceIntent)

            val ret = JSObject()
            ret.put("success", true)
            ret.put("message", "Service stop requested")
            invoke.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject()
            ret.put("success", false)
            ret.put("message", e.message)
            invoke.resolve(ret)
        }
    }

    @Command
    fun setVolume(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(SetVolumeArgs::class.java)
            val serviceIntent = Intent(activity, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_SET_VOLUME
                putExtra(MusicPlayerService.EXTRA_VOLUME, args.volume)
            }
            activity.startService(serviceIntent)

            val ret = JSObject()
            ret.put("success", true)
            invoke.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject()
            ret.put("success", false)
            ret.put("message", e.message)
            invoke.resolve(ret)
        }
    }

    @Command
    fun setNormalizationConfig(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(SetNormalizationConfigArgs::class.java)
            val serviceIntent = Intent(activity, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_SET_NORMALIZATION_CONFIG
                putExtra(MusicPlayerService.EXTRA_NORMALIZATION_MODE, args.mode)
                putExtra(MusicPlayerService.EXTRA_MANUAL_VOLUME, args.manualVolume)
                putExtra(MusicPlayerService.EXTRA_FIXED_LUFS, args.fixedLufs)
                putExtra(MusicPlayerService.EXTRA_LUFS_PRECACHE_COUNT, args.lufsPrecacheCount)
            }
            activity.startService(serviceIntent)

            val ret = JSObject()
            ret.put("success", true)
            invoke.resolve(ret)
        } catch (e: Exception) {
            val ret = JSObject()
            ret.put("success", false)
            ret.put("message", e.message)
            invoke.resolve(ret)
        }
    }

    @Command
    fun setServer(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(SetServerArgs::class.java)

            if (args.libraryName == null) {
                val ret = JSObject()
                ret.put("success", false)
                ret.put("message", "libraryName is required")
                invoke.resolve(ret)
                return
            }

            val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(PREF_SERVER_LIB_NAME, args.libraryName)
                .apply()

            Log.d(TAG, "Server library registered: ${args.libraryName}")

            val ret = JSObject()
            ret.put("success", true)
            ret.put("message", "Server library registered")
            invoke.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register server library: ${e.message}")
            val ret = JSObject()
            ret.put("success", false)
            ret.put("message", e.message)
            invoke.resolve(ret)
        }
    }

    @Command
    fun setHeadsetMediaButtonDisabled(invoke: Invoke) {
        try {
            val args = invoke.parseArgs(SetHeadsetMediaButtonDisabledArgs::class.java)
            MusicPlayerService.instance?.updateHeadsetMediaButtonSetting(args.disabled)

            Log.d(TAG, "Headset media button disabled set to: ${args.disabled}")

            val ret = JSObject()
            ret.put("success", true)
            invoke.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set headset media button disabled", e)
            val ret = JSObject()
            ret.put("success", false)
            ret.put("message", e.message)
            invoke.resolve(ret)
        }
    }
}
