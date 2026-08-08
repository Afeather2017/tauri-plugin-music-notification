package com.plugin.music_notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media.session.MediaButtonReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections

class MusicPlayerService : Service() {

    companion object {
        private const val TAG = "MusicPlayerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "MusicPlayerChannel"
        private const val PREFS_NAME = "music_notification"
        private const val PREF_SESSION = "playback_session"
        private const val PREF_NORMALIZATION = "normalization_config"
        private const val PREF_DISABLE_HEADSET_MEDIA_BUTTON = "disable_headset_media_button"
        private const val PREF_PAUSE_AFTER_DEADLINE = "pause_after_deadline"
        private const val LUFS_POLL_DELAY_MS = 1000L
        private const val LUFS_POLL_MAX_ATTEMPTS = 8
        private const val DEFAULT_LUFS_PRECACHE_COUNT = 5
        private const val MIN_LUFS_PRECACHE_COUNT = 0
        private const val MAX_LUFS_PRECACHE_COUNT = 20

        const val ACTION_PLAY = "com.plugin.music_notification.PLAY"
        const val ACTION_PAUSE = "com.plugin.music_notification.PAUSE"
        const val ACTION_PAUSE_AFTER = "com.plugin.music_notification.PAUSE_AFTER"
        const val ACTION_RESUME = "com.plugin.music_notification.RESUME"
        const val ACTION_STOP = "com.plugin.music_notification.STOP"
        const val ACTION_NEXT = "com.plugin.music_notification.NEXT"
        const val ACTION_PREVIOUS = "com.plugin.music_notification.PREVIOUS"
        const val ACTION_SEEK = "com.plugin.music_notification.SEEK"
        const val ACTION_SEEK_AND_PLAY = "com.plugin.music_notification.SEEK_AND_PLAY"
        const val ACTION_START_SERVICE = "com.plugin.music_notification.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.plugin.music_notification.STOP_SERVICE"
        const val ACTION_SET_VOLUME = "com.plugin.music_notification.SET_VOLUME"
        const val ACTION_SET_NORMALIZATION_CONFIG = "com.plugin.music_notification.SET_NORMALIZATION_CONFIG"

        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_COVER_URL = "coverUrl"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DELAY_MS = "delayMs"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_NORMALIZATION_MODE = "normalizationMode"
        const val EXTRA_MANUAL_VOLUME = "manualVolume"
        const val EXTRA_FIXED_LUFS = "fixedLufs"
        const val EXTRA_LUFS_PRECACHE_COUNT = "lufsPrecacheCount"

        var instance: MusicPlayerService? = null

        fun loadServerLibrary(context: Context): String? {
            val libName = MusicNotificationPlugin.getServerLibName(context)
                ?: "musicnotification_lib"

            try {
                System.loadLibrary(libName)
                return libName
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load server library $libName", e)
                try {
                    val appClassLoader = context.applicationContext.classLoader
                    val loadLibraryMethod = Class.forName("java.lang.System").getDeclaredMethod(
                        "loadLibrary",
                        String::class.java,
                        ClassLoader::class.java
                    )
                    loadLibraryMethod.invoke(null, libName, appClassLoader)
                    return libName
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to load server library $libName via classloader", ex)
                    return null
                }
            }
        }

        fun emptySessionSnapshot(): SessionSnapshot {
            return SessionSnapshot(
                queue = PlayingQueueSnapshot(emptyList(), null),
                runtime = PlaybackRuntimeSnapshot(false, 0L, 0L),
                playMode = "sequential",
                currentSongId = null
            )
        }

        fun loadPersistedSessionSnapshot(context: Context): SessionSnapshot {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(PREF_SESSION, null) ?: return emptySessionSnapshot()

            return try {
                parseSessionJson(JSONObject(raw))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse persisted playback session", e)
                emptySessionSnapshot()
            }
        }

        fun savePersistedSessionSnapshot(context: Context, snapshot: SessionSnapshot) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(PREF_SESSION, snapshotToJson(snapshot).toString())
                .apply()
        }

        data class NormalizationConfig(
            val mode: String = "auto",
            val manualVolume: Float = 0.5f,
            val fixedLufs: Double = -27.0,
            val lufsPrecacheCount: Int = DEFAULT_LUFS_PRECACHE_COUNT
        )

        fun normalizeLufsPrecacheCount(count: Int): Int {
            return count.coerceIn(MIN_LUFS_PRECACHE_COUNT, MAX_LUFS_PRECACHE_COUNT)
        }

        fun loadNormalizationConfig(context: Context): NormalizationConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(PREF_NORMALIZATION, null) ?: return NormalizationConfig()

            return try {
                val json = JSONObject(raw)
                NormalizationConfig(
                    mode = json.optString("mode", "auto"),
                    manualVolume = json.optDouble("manualVolume", 0.5).toFloat(),
                    fixedLufs = json.optDouble("fixedLufs", -27.0),
                    lufsPrecacheCount = normalizeLufsPrecacheCount(
                        json.optInt("lufsPrecacheCount", DEFAULT_LUFS_PRECACHE_COUNT)
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse normalization config", e)
                NormalizationConfig()
            }
        }

        fun saveNormalizationConfig(context: Context, config: NormalizationConfig) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = JSONObject()
            json.put("mode", config.mode)
            json.put("manualVolume", config.manualVolume.toDouble())
            json.put("fixedLufs", config.fixedLufs)
            json.put(
                "lufsPrecacheCount",
                normalizeLufsPrecacheCount(config.lufsPrecacheCount)
            )
            prefs.edit()
                .putString(PREF_NORMALIZATION, json.toString())
                .apply()
        }

        fun isHeadsetMediaButtonDisabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_DISABLE_HEADSET_MEDIA_BUTTON, false)
        }

        fun setHeadsetMediaButtonDisabled(context: Context, disabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_DISABLE_HEADSET_MEDIA_BUTTON, disabled).apply()
        }

        fun loadPersistedPauseAfterDeadline(context: Context): Long {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getLong(PREF_PAUSE_AFTER_DEADLINE, 0L)
        }

        fun savePersistedPauseAfterDeadline(context: Context, deadlineMs: Long) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(PREF_PAUSE_AFTER_DEADLINE, deadlineMs).apply()
        }

        fun computePauseAfterDeadline(delayMs: Long, nowMs: Long): Long {
            if (delayMs <= 0L) {
                return 0L
            }

            return if (Long.MAX_VALUE - nowMs < delayMs) {
                Long.MAX_VALUE
            } else {
                nowMs + delayMs
            }
        }

        fun computeRemainingPauseAfterDelay(deadlineMs: Long, nowMs: Long): Long {
            if (deadlineMs <= 0L) {
                return 0L
            }

            return maxOf(0L, deadlineMs - nowMs)
        }

        private fun parseSessionJson(json: JSONObject): SessionSnapshot {
            val queueJson = json.optJSONObject("queue") ?: JSONObject()
            val songsJson = queueJson.optJSONArray("songs") ?: JSONArray()
            val songs = mutableListOf<QueueSongInfo>()
            for (i in 0 until songsJson.length()) {
                val songJson = songsJson.optJSONObject(i) ?: continue
                val song = QueueSongInfo(
                    id = songJson.optLong("id", -1L),
                    name = songJson.optString("name", ""),
                    deviceId = songJson.optString("deviceId", "").ifBlank { null },
                    sourceKind = songJson.optString("sourceKind", ""),
                    localUri = songJson.optString("localUri", "").ifBlank { null },
                    tempSongUrl = songJson.optString("tempSongUrl", "").ifBlank { null },
                    lufs = if (songJson.has("lufs") && !songJson.isNull("lufs")) songJson.optDouble("lufs") else null,
                    coverUrl = songJson.optString("coverUrl", "").ifBlank { null }
                )
                if (song.hasValidLocator()) {
                    songs.add(song)
                }
            }

            val runtimeJson = json.optJSONObject("runtime") ?: JSONObject()
            val currentIndex = if (queueJson.has("currentIndex") && !queueJson.isNull("currentIndex")) {
                queueJson.optInt("currentIndex")
            } else {
                null
            }

            return SessionSnapshot(
                queue = PlayingQueueSnapshot(songs, currentIndex),
                runtime = PlaybackRuntimeSnapshot(
                    isPlaying = runtimeJson.optBoolean("isPlaying", false),
                    positionMs = runtimeJson.optLong("positionMs", 0L),
                    durationMs = runtimeJson.optLong("durationMs", 0L)
                ),
                playMode = json.optString("playMode", "sequential"),
                currentSongId = if (json.has("currentSongId") && !json.isNull("currentSongId")) {
                    json.optLong("currentSongId")
                } else {
                    null
                }
            )
        }

        private fun snapshotToJson(snapshot: SessionSnapshot): JSONObject {
            val durableSnapshot = durableSessionSnapshot(snapshot)
            val queueJson = JSONObject()
            val songsJson = JSONArray()

            durableSnapshot.queue.songs.forEach { song ->
                val songJson = JSONObject()
                songJson.put("id", song.id)
                songJson.put("name", song.name)
                if (song.deviceId == null) songJson.put("deviceId", JSONObject.NULL) else songJson.put("deviceId", song.deviceId)
                songJson.put("sourceKind", song.sourceKind)
                if (song.localUri == null) songJson.put("localUri", JSONObject.NULL) else songJson.put("localUri", song.localUri)
                if (song.lufs == null) {
                    songJson.put("lufs", JSONObject.NULL)
                } else {
                    songJson.put("lufs", song.lufs)
                }
                if (song.coverUrl == null) {
                    songJson.put("coverUrl", JSONObject.NULL)
                } else {
                    songJson.put("coverUrl", song.coverUrl)
                }
                songsJson.put(songJson)
            }

            queueJson.put("songs", songsJson)
            if (durableSnapshot.queue.currentIndex == null) {
                queueJson.put("currentIndex", JSONObject.NULL)
            } else {
                queueJson.put("currentIndex", durableSnapshot.queue.currentIndex)
            }

            val runtimeJson = JSONObject()
            runtimeJson.put("isPlaying", durableSnapshot.runtime.isPlaying)
            runtimeJson.put("positionMs", durableSnapshot.runtime.positionMs)
            runtimeJson.put("durationMs", durableSnapshot.runtime.durationMs)

            val json = JSONObject()
            json.put("queue", queueJson)
            json.put("runtime", runtimeJson)
            json.put("playMode", durableSnapshot.playMode)
            if (durableSnapshot.currentSongId == null) {
                json.put("currentSongId", JSONObject.NULL)
            } else {
                json.put("currentSongId", durableSnapshot.currentSongId)
            }
            return json
        }

        fun durableSessionSnapshot(snapshot: SessionSnapshot): SessionSnapshot {
            val retained = snapshot.queue.songs.withIndex()
                .filter { it.value.sourceKind != "temporary" && it.value.hasValidLocator() }
            val originalIndex = snapshot.queue.currentIndex
            val durableIndex = originalIndex?.let { selected ->
                retained.indexOfFirst { it.index == selected }.takeIf { it >= 0 }
            }
            val durableSongs = retained.map { it.value }
            return snapshot.copy(
                queue = PlayingQueueSnapshot(durableSongs, durableIndex),
                runtime = if (durableIndex == null) {
                    PlaybackRuntimeSnapshot(false, 0L, 0L)
                } else {
                    snapshot.runtime
                },
                currentSongId = durableIndex?.let { durableSongs[it].id }
            )
        }
    }

    data class QueueSongInfo(
        val id: Long,
        val name: String,
        val deviceId: String?,
        val sourceKind: String,
        val localUri: String?,
        val tempSongUrl: String?,
        val lufs: Double?,
        val coverUrl: String?
    ) {
        fun hasValidLocator(): Boolean = when (sourceKind) {
            "kaulan" -> id > 0L && !deviceId.isNullOrBlank()
            "local_raw" -> !localUri.isNullOrBlank()
            "temporary" -> !tempSongUrl.isNullOrBlank()
            else -> false
        }
    }

    data class PlaybackTarget(
        val audioUrl: String,
        val coverUrl: String?
    )

    data class PlayingQueueSnapshot(
        val songs: List<QueueSongInfo>,
        val currentIndex: Int?
    )

    data class PlaybackRuntimeSnapshot(
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long
    )

    data class SessionSnapshot(
        val queue: PlayingQueueSnapshot,
        val runtime: PlaybackRuntimeSnapshot,
        val playMode: String,
        val currentSongId: Long?
    )

    data class PrecacheLufsResult(
        val success: Boolean,
        val lufs: Double?,
        val cached: Boolean?
    )

    private external fun serverStart(): Int
    private external fun serverStop(): Int

    private var httpServerRunning = false
    private var musicPlayerActive = false
    private var tracks = mutableListOf<QueueSongInfo>()
    private var currentTrackIndex = -1
    private var playMode = "sequential"

    private var mediaPlayer: MediaPlayer? = null
    private var isPrepared = false
    private lateinit var handler: Handler
    private lateinit var progressRunnable: Runnable
    private lateinit var mediaSession: MediaSessionCompat

    fun hasMediaPlayer(): Boolean = mediaPlayer != null

    fun queueSize(): Int = tracks.size

    /** Start playing the current track from the restored queue */
    fun playCurrentTrack() {
        if (currentTrackIndex !in tracks.indices) {
            Log.w(TAG, "playCurrentTrack: no valid track at index $currentTrackIndex")
            return
        }
        val track = tracks[currentTrackIndex]
        Log.i(
            TAG,
            "Playback resolution path entered: index=$currentTrackIndex track=${track.name} " +
                "songId=${track.id} deviceId=${track.deviceId} sourceKind=${track.sourceKind}"
        )
        playQueueTrackAt(currentTrackIndex, 1, tracks.size)
    }

    private fun resolveDeviceApiBase(deviceId: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val resolverUrl = "http://localhost:2080/api/discovery/resolutions/$encodedId"
            Log.i(TAG, "Querying playback resolution: deviceId=$deviceId url=$resolverUrl")
            connection = URL(resolverUrl)
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "Playback resolution returned HTTP $responseCode for deviceId=$deviceId")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val apiBase = JSONObject(body).optString("api_url", "").ifBlank { null }
            if (apiBase == null) {
                Log.w(TAG, "Playback resolution response has no api_url for deviceId=$deviceId")
            } else {
                Log.i(TAG, "Resolved playback deviceId=$deviceId apiBase=$apiBase")
            }
            apiBase
        } catch (e: Exception) {
            Log.w(TAG, "Unable to resolve deviceId=$deviceId", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun resolvePlaybackTarget(track: QueueSongInfo): PlaybackTarget? {
        Log.i(
            TAG,
            "Resolving queue track: track=${track.name} songId=${track.id} " +
                "deviceId=${track.deviceId} sourceKind=${track.sourceKind}"
        )
        return when (track.sourceKind) {
            "kaulan" -> track.deviceId?.let(::resolveDeviceApiBase)?.let { apiBase ->
                PlaybackTarget(
                    audioUrl = "$apiBase/music/id/${track.id}",
                    coverUrl = "$apiBase/music/id/${track.id}/cover"
                )
            }
            "local_raw" -> track.localUri?.takeIf { it.isNotBlank() }?.let {
                PlaybackTarget(audioUrl = it, coverUrl = track.coverUrl)
            }
            "temporary" -> track.tempSongUrl?.takeIf { it.isNotBlank() }?.let {
                PlaybackTarget(audioUrl = it, coverUrl = track.coverUrl)
            }
            else -> null
        }
    }

    private fun playQueueTrackAt(index: Int, direction: Int, remaining: Int) {
        if (tracks.isEmpty() || remaining <= 0) {
            Log.w(TAG, "No reachable tracks remain in queue")
            stopMusic(clearQueue = false)
            return
        }
        val normalizedIndex = ((index % tracks.size) + tracks.size) % tracks.size
        val track = tracks[normalizedIndex]
        val generation = ++resolutionGeneration
        Log.i(
            TAG,
            "Scheduling playback resolution: index=$normalizedIndex generation=$generation " +
                "track=${track.name} deviceId=${track.deviceId} sourceKind=${track.sourceKind}"
        )
        Thread {
            val target = resolvePlaybackTarget(track)
            handler.post {
                if (generation != resolutionGeneration) {
                    Log.i(TAG, "Discarding stale playback resolution: generation=$generation currentGeneration=$resolutionGeneration")
                    return@post
                }
                if (target != null) {
                    currentTrackIndex = normalizedIndex
                    playTrack(track, target)
                    return@post
                }
                Log.w(TAG, "Skipping unresolved device for track=${track.name} deviceId=${track.deviceId}")
                playQueueTrackAt(normalizedIndex + direction, direction, remaining - 1)
            }
        }.start()
    }

    fun seekAndPlay(positionMs: Long) {
        val normalizedPosition = positionMs.coerceAtLeast(0L)
        pendingSeekPositionMs = normalizedPosition
        Log.d(
            TAG,
            "seekAndPlay: positionMs=$normalizedPosition mediaPlayer=${mediaPlayer != null} isPrepared=$isPrepared currentTrackIndex=$currentTrackIndex queueSize=${tracks.size}"
        )

        val player = mediaPlayer
        if (player != null && isPrepared) {
            val track = tracks.getOrNull(currentTrackIndex)
            Log.w(
                TAG,
                "seekAndPlay: resuming prepared player without resolution query " +
                    "track=${track?.name} songId=${track?.id} deviceId=${track?.deviceId} " +
                    "sourceKind=${track?.sourceKind} currentUrl=$currentUrl"
            )
            val clampedPosition = normalizedPosition.coerceAtMost(player.duration.toLong()).toInt()
            player.seekTo(clampedPosition)
            pendingSeekPositionMs = null
            resumeMusic()
            persistSession(isPlayingOverride = true)
            updatePlaybackState()
            return
        }

        if (currentTrackIndex in tracks.indices) {
            if (player == null) {
                playCurrentTrack()
            }
            return
        }

        Log.w(TAG, "seekAndPlay: no active track available for resume")
        pendingSeekPositionMs = null
    }

    fun stopFromNotification() {
        stopMusic(clearQueue = false)
    }

    fun updateHeadsetMediaButtonSetting(disabled: Boolean) {
        headsetMediaButtonDisabled = disabled
        setHeadsetMediaButtonDisabled(this, disabled)
    }

    private var currentUrl: String? = null
    private var startCommandCount = 0L
    private var playbackGeneration = 0L
    private var resolutionGeneration = 0L
    private var lufsResolutionGeneration = 0L
    private var pauseAfterRunnable: Runnable? = null
    private var normalizationConfig = NormalizationConfig()
    private var headsetMediaButtonDisabled = false
    private var playTrackStartTime = 0L
    private var prepareStartTime = 0L
    private var playTrackCallStartTime = 0L
    private var pendingSeekPositionMs: Long? = null
    private var pauseAfterDeadlineMs = 0L
    private var currentArtworkBitmap: Bitmap? = null
    private var artworkGeneration = 0L
    private val lufsRequestsInFlight = Collections.synchronizedSet(mutableSetOf<Long>())

    private fun updateServiceLifetime() {
        if (!httpServerRunning && !musicPlayerActive && pauseAfterDeadlineMs <= 0L) {
            Log.d(TAG, "Both server and player done, stopping service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        handler = Handler(Looper.getMainLooper())
        normalizationConfig = loadNormalizationConfig(this)
        headsetMediaButtonDisabled = isHeadsetMediaButtonDisabled(this)
        mediaSession = MediaSessionCompat(this, "MusicPlayerService")
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (launchIntent != null) {
            val sessionActivity = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            mediaSession.setSessionActivity(sessionActivity)
        } else {
            Log.w(TAG, "Failed to resolve launch intent for package $packageName")
        }
        Log.d(TAG, "onCreate: MusicPlayerService created, restoring persisted session")
        restorePersistedSession()
        restorePauseAfterSchedule()

        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (keyEvent != null) {
                    Log.d(
                        TAG,
                        "MediaSession callback: onMediaButtonEvent keyCode=${keyEvent.keyCode} action=${keyEvent.action} disabled=$headsetMediaButtonDisabled"
                    )
                }
                if (headsetMediaButtonDisabled && keyEvent?.action == KeyEvent.ACTION_DOWN) {
                    Log.d(TAG, "MediaSession callback: ignoring media button keyCode=${keyEvent.keyCode} because headset media buttons are disabled")
                    return true
                }

                return super.onMediaButtonEvent(mediaButtonEvent)
            }

            override fun onPlay() {
                Log.d(TAG, "MediaSession callback: onPlay, mediaPlayer=${mediaPlayer != null}, queueSize=${tracks.size}")
                if (mediaPlayer == null && currentTrackIndex in tracks.indices) {
                    playCurrentTrack()
                } else {
                    resumeMusic()
                }
            }

            override fun onPause() {
                Log.d(TAG, "MediaSession callback: onPause")
                pauseMusic()
            }

            override fun onSkipToNext() {
                Log.d(TAG, "MediaSession callback: onSkipToNext")
                playNextTrack()
            }

            override fun onSkipToPrevious() {
                Log.d(TAG, "MediaSession callback: onSkipToPrevious")
                playPreviousTrack()
            }

            override fun onStop() {
                Log.d(TAG, "MediaSession callback: onStop")
                stopMusic(clearQueue = false)
            }

            override fun onSeekTo(pos: Long) {
                Log.d(TAG, "MediaSession callback: onSeekTo $pos")
                mediaPlayer?.seekTo(pos.toInt())
                persistSession(isPlayingOverride = mediaPlayer?.isPlaying)
                updatePlaybackState()
            }
        })

        mediaSession.isActive = true

        progressRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        persistSession(isPlayingOverride = true)
                        updatePlaybackState()
                        updateNotification()
                        handler.postDelayed(this, 1000)
                    }
                }
            }
        }

        val loadedLib = loadServerLibrary(this)
        if (loadedLib == null) {
            Log.e(TAG, "No server library could be loaded, JNI call will likely fail")
        }

        val result = try {
            serverStart()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "serverStart() JNI symbol missing", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "serverStart() threw unexpected exception", e)
            throw e
        }
        if (result == 0) {
            httpServerRunning = true
        } else {
            Log.e(TAG, "Failed to start server, code: $result")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startCommandCount += 1

        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            if (headsetMediaButtonDisabled) {
                Log.d(TAG, "onStartCommand: ignoring MEDIA_BUTTON, headset buttons disabled")
                return START_STICKY
            }
            MediaButtonReceiver.handleIntent(mediaSession, intent)
        }

        intent?.let {
            when (it.action) {
                ACTION_START_SERVICE -> {
                    Log.d(TAG, "Action: START_SERVICE")
                }
                ACTION_STOP_SERVICE -> {
                    Log.d(TAG, "Action: STOP_SERVICE")
                    val result = serverStop()
                    if (result == 0) {
                        Log.d(TAG, "Server stopped successfully")
                    } else {
                        Log.e(TAG, "Failed to stop server, code: $result")
                    }
                    httpServerRunning = false
                    updateServiceLifetime()
                }
                ACTION_PLAY -> {
                    playTrackCallStartTime = System.currentTimeMillis()
                    Log.i("KaulanPerf", "ACTION_PLAY received: track intent received")
                    Log.d(TAG, "Action: PLAY")
                    val url = it.getStringExtra(EXTRA_URL) ?: run {
                        Log.e(TAG, "URL is null, returning")
                        return START_STICKY
                    }
                    val title = it.getStringExtra(EXTRA_TITLE) ?: "Unknown Title"
                    val artist = it.getStringExtra(EXTRA_ARTIST) ?: "Unknown Artist"
                    val album = it.getStringExtra(EXTRA_ALBUM) ?: "Unknown Album"
                    val coverUrl = it.getStringExtra(EXTRA_COVER_URL)
                    playTrackByUrl(url, title, artist, album, coverUrl)
                }
                ACTION_PAUSE -> pauseMusic()
                ACTION_PAUSE_AFTER -> {
                    val delayMs = it.getLongExtra(EXTRA_DELAY_MS, 0L)
                    Log.d(TAG, "Action: PAUSE_AFTER delayMs=$delayMs")
                    schedulePauseAfter(delayMs)
                }
                ACTION_RESUME -> resumeMusic()
                ACTION_STOP -> stopMusic(clearQueue = false)
                ACTION_NEXT -> {
                    Log.d(
                        TAG,
                        "onStartCommand#${startCommandCount}: handling ACTION_NEXT currentTrackIndex=$currentTrackIndex queueSize=${tracks.size}"
                    )
                    playNextTrack()
                }
                ACTION_PREVIOUS -> {
                    Log.d(
                        TAG,
                        "onStartCommand#${startCommandCount}: handling ACTION_PREVIOUS currentTrackIndex=$currentTrackIndex queueSize=${tracks.size}"
                    )
                    playPreviousTrack()
                }
                ACTION_SEEK -> {
                    val position = it.getLongExtra(EXTRA_POSITION, 0)
                    Log.d(TAG, "onStartCommand#${startCommandCount}: ACTION_SEEK to $position")
                    mediaPlayer?.seekTo(position.toInt())
                    persistSession(isPlayingOverride = mediaPlayer?.isPlaying)
                    updatePlaybackState()
                }
                ACTION_SEEK_AND_PLAY -> {
                    val position = it.getLongExtra(EXTRA_POSITION, 0)
                    Log.d(TAG, "onStartCommand#${startCommandCount}: ACTION_SEEK_AND_PLAY to $position")
                    seekAndPlay(position)
                }
                ACTION_SET_VOLUME -> {
                    val volume = it.getFloatExtra(EXTRA_VOLUME, 1.0f)
                    Log.d(TAG, "Action: SET_VOLUME to $volume")
                    mediaPlayer?.setVolume(volume, volume)
                }
                ACTION_SET_NORMALIZATION_CONFIG -> {
                    val newConfig = NormalizationConfig(
                        mode = normalizeNormalizationMode(
                            it.getStringExtra(EXTRA_NORMALIZATION_MODE)
                        ),
                        manualVolume = it.getFloatExtra(EXTRA_MANUAL_VOLUME, 0.5f),
                        fixedLufs = it.getDoubleExtra(EXTRA_FIXED_LUFS, -27.0),
                        lufsPrecacheCount = normalizeLufsPrecacheCount(
                            it.getIntExtra(
                                EXTRA_LUFS_PRECACHE_COUNT,
                                normalizationConfig.lufsPrecacheCount
                            )
                        )
                    )
                    val changed = newConfig != normalizationConfig
                    normalizationConfig = newConfig
                    saveNormalizationConfig(this, normalizationConfig)
                    if (changed) {
                        Log.i(
                            TAG,
                            "Normalization config changed: mode=${normalizationConfig.mode} manual=${normalizationConfig.manualVolume} fixed=${normalizationConfig.fixedLufs} queuePrecache=${normalizationConfig.lufsPrecacheCount}"
                        )
                    }
                    val currentTrack = tracks.getOrNull(currentTrackIndex)
                    if (currentTrack != null) {
                        applyTrackVolume(currentTrack, log = changed)
                    }
                    Unit
                }
                else -> Log.w(TAG, "Unknown action: ${it.action}")
            }
        } ?: Log.w(TAG, "Intent is null")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        return START_STICKY
    }

    fun setPlayingQueue(songs: List<QueueSongInfo>, currentIndex: Int?, newPlayMode: String) {
        tracks = songs.toMutableList()
        currentTrackIndex = when {
            tracks.isEmpty() -> -1
            currentIndex == null -> 0
            currentIndex < 0 -> 0
            currentIndex >= tracks.size -> tracks.lastIndex
            else -> currentIndex
        }
        playMode = normalizePlayMode(newPlayMode)
        val selectedTrack = tracks.getOrNull(currentTrackIndex)
        Log.i(
            TAG,
            "setPlayingQueue: queueSize=${tracks.size} requestedIndex=$currentIndex " +
                "selectedIndex=$currentTrackIndex track=${selectedTrack?.name} songId=${selectedTrack?.id} " +
                "deviceId=${selectedTrack?.deviceId} sourceKind=${selectedTrack?.sourceKind}"
        )
        persistSession(isPlayingOverride = mediaPlayer?.isPlaying)
        updateNotification()
    }

    fun setPlayMode(newPlayMode: String) {
        playMode = normalizePlayMode(newPlayMode)
        persistSession(isPlayingOverride = mediaPlayer?.isPlaying)
    }

    fun clearPlayingQueue() {
        stopMusic(clearQueue = true)
    }

    fun getPlaybackSession(): SessionSnapshot {
        return buildSessionSnapshot()
    }

    fun getPlaybackState(): Triple<Boolean, Long, Long> {
        val runtime = buildRuntimeSnapshot()
        return Triple(runtime.isPlaying, runtime.positionMs, runtime.durationMs)
    }

    private fun restorePersistedSession() {
        val snapshot = loadPersistedSessionSnapshot(this)
        Log.d(TAG, "restorePersistedSession: queueSize=${snapshot.queue.songs.size}, " +
            "currentIndex=${snapshot.queue.currentIndex}, isPlaying=${snapshot.runtime.isPlaying}, " +
            "positionMs=${snapshot.runtime.positionMs}, playMode=${snapshot.playMode}, " +
            "currentSongId=${snapshot.currentSongId}, firstSong=${snapshot.queue.songs.firstOrNull()?.name}")
        tracks = snapshot.queue.songs.toMutableList()
        currentTrackIndex = when {
            tracks.isEmpty() -> -1
            snapshot.queue.currentIndex == null -> 0
            snapshot.queue.currentIndex < 0 -> 0
            snapshot.queue.currentIndex >= tracks.size -> tracks.lastIndex
            else -> snapshot.queue.currentIndex
        }
        playMode = normalizePlayMode(snapshot.playMode)
        val restoredTrack = tracks.getOrNull(currentTrackIndex)
        Log.i(
            TAG,
            "restorePersistedSession identity: selectedIndex=$currentTrackIndex " +
                "track=${restoredTrack?.name} songId=${restoredTrack?.id} " +
                "deviceId=${restoredTrack?.deviceId} sourceKind=${restoredTrack?.sourceKind}"
        )
        musicPlayerActive = snapshot.runtime.isPlaying

        // Set media session metadata so notification shows the restored track info
        if (currentTrackIndex in tracks.indices) {
            val track = tracks[currentTrackIndex]
            currentArtworkBitmap = null
            applyTrackMetadata(track, "Unknown Artist", "Unknown Album", snapshot.runtime.durationMs, null)
            loadArtworkForTrackAsync(track, "Unknown Artist", "Unknown Album", snapshot.runtime.durationMs)
            Log.d(TAG, "restorePersistedSession: set metadata for ${track.name}, durationMs=${snapshot.runtime.durationMs}")
        }
        updatePlaybackState()
    }

    private fun restorePauseAfterSchedule() {
        val persistedDeadlineMs = loadPersistedPauseAfterDeadline(this)
        if (persistedDeadlineMs <= 0L) {
            pauseAfterDeadlineMs = 0L
            return
        }

        pauseAfterDeadlineMs = persistedDeadlineMs
        val remainingDelayMs = computeRemainingPauseAfterDelay(
            persistedDeadlineMs,
            System.currentTimeMillis()
        )
        Log.d(
            TAG,
            "restorePauseAfterSchedule: deadlineMs=$persistedDeadlineMs remainingDelayMs=$remainingDelayMs"
        )
        schedulePauseAfterAt(persistedDeadlineMs)
    }

    private fun buildSessionSnapshot(): SessionSnapshot {
        val currentIndex = if (currentTrackIndex in tracks.indices) currentTrackIndex else null
        return SessionSnapshot(
            queue = PlayingQueueSnapshot(tracks.toList(), currentIndex),
            runtime = buildRuntimeSnapshot(),
            playMode = playMode,
            currentSongId = tracks.getOrNull(currentTrackIndex)?.id
        )
    }

    private fun buildRuntimeSnapshot(): PlaybackRuntimeSnapshot {
        mediaPlayer?.let { player ->
            return PlaybackRuntimeSnapshot(
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.toLong(),
                durationMs = player.duration.toLong()
            )
        }

        val persisted = loadPersistedSessionSnapshot(this)
        return PlaybackRuntimeSnapshot(
            isPlaying = false,
            positionMs = persisted.runtime.positionMs,
            durationMs = persisted.runtime.durationMs
        )
    }

    private fun persistSession(isPlayingOverride: Boolean? = null) {
        val runtime = mediaPlayer?.let { player ->
            PlaybackRuntimeSnapshot(
                isPlaying = isPlayingOverride ?: player.isPlaying,
                positionMs = player.currentPosition.toLong(),
                durationMs = player.duration.toLong()
            )
        } ?: PlaybackRuntimeSnapshot(
            isPlaying = isPlayingOverride ?: false,
            positionMs = 0L,
            durationMs = 0L
        )

        savePersistedSessionSnapshot(
            this,
            SessionSnapshot(
                queue = PlayingQueueSnapshot(
                    songs = tracks.toList(),
                    currentIndex = if (currentTrackIndex in tracks.indices) currentTrackIndex else null
                ),
                runtime = runtime,
                playMode = playMode,
                currentSongId = tracks.getOrNull(currentTrackIndex)?.id
            )
        )
    }

    private fun normalizePlayMode(rawMode: String?): String {
        return when (rawMode) {
            "shuffle" -> "shuffle"
            "loop" -> "loop"
            else -> "sequential"
        }
    }

    private fun playTrackByUrl(url: String, title: String, artist: String, album: String, coverUrl: String?) {
        val fallbackTrack = QueueSongInfo(
            id = -1L,
            name = title,
            deviceId = null,
            sourceKind = "temporary",
            localUri = null,
            tempSongUrl = url,
            lufs = null,
            coverUrl = coverUrl
        )
        tracks = mutableListOf(fallbackTrack)
        currentTrackIndex = 0
        playMode = "sequential"
        playTrack(fallbackTrack, PlaybackTarget(url, coverUrl), artist, album)
    }

    private fun buildPrecacheUrl(track: QueueSongInfo): String? {
        if (track.id <= 0L) {
            Log.w(TAG, "buildPrecacheUrl: missing valid track id for track=${track.name} id=${track.id}")
            return null
        }

        val precacheBase = resolvePlaybackTarget(track)?.audioUrl ?: return null
        val parsed = Uri.parse(precacheBase)
        val path = parsed.path ?: return null
        val musicMarker = "/music/id/${track.id}"
        val markerIndex = path.indexOf(musicMarker)
        if (markerIndex < 0) {
            Log.w(
                TAG,
                "buildPrecacheUrl: could not derive precache URL from track=${track.name} id=${track.id} sourceKind=${track.sourceKind}"
            )
            return null
        }

        val prefixPath = path.substring(0, markerIndex)
        val precacheUrl = parsed.buildUpon()
            .path("${prefixPath}/music/${track.id}/precache-lufs")
            .clearQuery()
            .build()
            .toString()
        Log.d(TAG, "buildPrecacheUrl: track=${track.name} source=$precacheBase precacheUrl=$precacheUrl")
        return precacheUrl
    }

    private fun trackIdentityKey(track: QueueSongInfo): String = when (track.sourceKind) {
        "kaulan" -> "kaulan:${track.deviceId}:${track.id}"
        "local_raw" -> "local:${track.localUri}"
        "temporary" -> "temporary:${track.tempSongUrl}"
        else -> "invalid:${track.id}:${track.name}"
    }

    private fun requestPrecacheLufs(track: QueueSongInfo): PrecacheLufsResult? {
        val precacheUrl = buildPrecacheUrl(track) ?: return null
        var connection: HttpURLConnection? = null

        return try {
            Log.d(TAG, "requestPrecacheLufs: sending request for track=${track.name} id=${track.id} url=$precacheUrl")
            connection = URL(precacheUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.doOutput = false

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            } ?: return null

            val body = stream.bufferedReader().use { it.readText() }
            if (body.isBlank()) {
                Log.d(
                    TAG,
                    "requestPrecacheLufs: blank response for track=${track.name} id=${track.id} statusCode=$statusCode"
                )
                return PrecacheLufsResult(statusCode in 200..299, null, null)
            }

            val json = JSONObject(body)
            val result = PrecacheLufsResult(
                success = json.optBoolean("success", statusCode in 200..299),
                lufs = if (json.has("lufs") && !json.isNull("lufs")) json.optDouble("lufs") else null,
                cached = if (json.has("cached") && !json.isNull("cached")) json.optBoolean("cached") else null
            )
            Log.d(
                TAG,
                "requestPrecacheLufs: response for track=${track.name} id=${track.id} statusCode=$statusCode success=${result.success} lufs=${result.lufs} cached=${result.cached}"
            )
            result
        } catch (e: Exception) {
            Log.w(TAG, "requestPrecacheLufs failed for track=${track.name}", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun patchTrackLufs(track: QueueSongInfo, lufs: Double): QueueSongInfo {
        val updatedTrack = track.copy(lufs = lufs)
        val queueIndex = tracks.indexOfFirst { it.id == track.id }
        if (queueIndex >= 0) {
            tracks[queueIndex] = updatedTrack
            if (queueIndex == currentTrackIndex) {
                persistSession(isPlayingOverride = mediaPlayer?.isPlaying)
            }
        }
        return updatedTrack
    }

    private fun resolveTrackLufsAsync(
        track: QueueSongInfo,
        reason: String,
        retryUntilResolved: Boolean,
        applyVolumeIfCurrent: Boolean
    ) {
        if (track.id <= 0L || track.lufs != null) {
            return
        }

        if (!lufsRequestsInFlight.add(track.id)) {
            Log.d(TAG, "resolveTrackLufsAsync: skipping duplicate request track=${track.name} reason=$reason")
            return
        }

        Thread {
            try {
                for (attempt in 0..LUFS_POLL_MAX_ATTEMPTS) {
                    Log.d(
                        TAG,
                        "resolveTrackLufsAsync: attempt=$attempt track=${track.name} id=${track.id} reason=$reason retryUntilResolved=$retryUntilResolved"
                    )
                    val result = requestPrecacheLufs(track)
                    if (result?.success == true && result.lufs != null) {
                        handler.post {
                            val updatedTrack = patchTrackLufs(track, result.lufs)
                            Log.d(
                                TAG,
                                "resolveTrackLufsAsync: resolved track=${track.name} reason=$reason attempt=$attempt lufs=${result.lufs}"
                            )
                            if (applyVolumeIfCurrent) {
                                val currentTrack = tracks.getOrNull(currentTrackIndex)
                                if (currentTrack?.id == updatedTrack.id && isPrepared) {
                                    applyTrackVolume(updatedTrack)
                                }
                            }
                        }
                        return@Thread
                    }

                    if (!retryUntilResolved || result?.cached != false || attempt == LUFS_POLL_MAX_ATTEMPTS) {
                        Log.d(
                            TAG,
                            "resolveTrackLufsAsync: stopping track=${track.name} id=${track.id} reason=$reason attempt=$attempt cached=${result?.cached} lufs=${result?.lufs}"
                        )
                        return@Thread
                    }

                    try {
                        Thread.sleep(LUFS_POLL_DELAY_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            } finally {
                lufsRequestsInFlight.remove(track.id)
            }
        }.start()
    }

    private fun precacheQueueTracks(fallbackCurrentTrack: QueueSongInfo? = null) {
        val precacheCount = normalizeLufsPrecacheCount(normalizationConfig.lufsPrecacheCount)
        if (precacheCount == 0) {
            Log.d(TAG, "precacheQueueTracks: disabled by config")
            return
        }

        if (tracks.isEmpty() || currentTrackIndex !in tracks.indices) {
            if (fallbackCurrentTrack == null || fallbackCurrentTrack.lufs != null) {
                Log.d(
                    TAG,
                    "precacheQueueTracks: skipping queueSize=${tracks.size} currentTrackIndex=$currentTrackIndex"
                )
                return
            }

            Log.d(
                TAG,
                "precacheQueueTracks: scheduling fallback current track=${fallbackCurrentTrack.name}"
            )
            resolveTrackLufsAsync(
                track = fallbackCurrentTrack,
                reason = "current",
                retryUntilResolved = true,
                applyVolumeIfCurrent = true
            )
            return
        }

        val startIndex = currentTrackIndex
        val currentTrack = tracks[startIndex]
        val maxPositions = if (playMode == "loop") 1 else tracks.size
        val requestedTrackKeys = mutableSetOf<String>()
        var queuedCount = 0

        for (offset in 0 until maxPositions) {
            val track = tracks[(startIndex + offset) % tracks.size]
            if (track.lufs != null) {
                continue
            }

            val requestKey = trackIdentityKey(track)
            if (!requestedTrackKeys.add(requestKey)) {
                continue
            }

            val isCurrentTrack = trackIdentityKey(track) == trackIdentityKey(currentTrack)
            queuedCount += 1
            Log.d(
                TAG,
                "precacheQueueTracks: scheduling track=${track.name} reason=${if (isCurrentTrack) "current" else "queue"} queuedCount=$queuedCount/$precacheCount"
            )
            resolveTrackLufsAsync(
                track = track,
                reason = if (isCurrentTrack) "current" else "queue",
                retryUntilResolved = true,
                applyVolumeIfCurrent = isCurrentTrack
            )

            if (queuedCount >= precacheCount) {
                return
            }
        }
    }

    private fun normalizeNormalizationMode(mode: String?): String {
        return when (mode) {
            "manual", "fixed" -> mode
            else -> "auto"
        }
    }

    private fun calculateTrackVolume(track: QueueSongInfo): Float {
        val manualVolume = normalizationConfig.manualVolume.coerceIn(0.0f, 1.0f)
        val trackLufs = track.lufs ?: return manualVolume

        val rawVolume = when (normalizationConfig.mode) {
            "fixed" -> {
                Math.pow(10.0, (normalizationConfig.fixedLufs - trackLufs) / 20.0)
            }
            "manual" -> {
                manualVolume.toDouble()
            }
            else -> {
                val defaultMinLufs = -29.0
                var minLufs = defaultMinLufs
                for (t in tracks) {
                    if (t.lufs != null) {
                        minLufs = Math.min(t.lufs, minLufs)
                    }
                }
                // If no tracks have LUFS (all null), use manual volume
                if (minLufs == defaultMinLufs && tracks.all { it.lufs == null }) {
                    return manualVolume
                }
                Math.pow(10.0, (minLufs - trackLufs) / 20.0)
            }
        }

        return rawVolume.coerceIn(0.0, 1.0).toFloat()
    }

    private fun applyTrackVolume(track: QueueSongInfo, log: Boolean = true) {
        val volume = calculateTrackVolume(track)
        if (log) {
            Log.d(
                TAG,
                "applyTrackVolume: track=${track.name} mode=${normalizationConfig.mode} lufs=${track.lufs} volume=$volume"
            )
        }
        mediaPlayer?.setVolume(volume, volume)
    }

    private fun applyTrackMetadata(
        track: QueueSongInfo,
        artist: String,
        album: String,
        durationMs: Long,
        artwork: Bitmap?
    ) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.name)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)

        artwork?.let {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }

        mediaSession.setMetadata(metadataBuilder.build())
        updateNotification()
    }

    private fun clearArtworkState() {
        currentArtworkBitmap = null
        artworkGeneration += 1
    }

    private fun loadArtworkForTrackAsync(
        track: QueueSongInfo,
        artist: String,
        album: String,
        durationMs: Long,
        coverUrlOverride: String? = track.coverUrl,
        expectedPlaybackUrl: String? = null
    ) {
        val coverUrl = coverUrlOverride?.trim()
        if (coverUrl.isNullOrEmpty()) {
            currentArtworkBitmap = null
            applyTrackMetadata(track, artist, album, durationMs, null)
            return
        }

        val generation = artworkGeneration
        Thread {
            val artwork = try {
                loadArtworkBitmap(coverUrl)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load artwork for track=${track.name} coverUrl=$coverUrl", e)
                null
            }

            handler.post {
                val isCurrentPlayback = expectedPlaybackUrl == null || currentUrl == expectedPlaybackUrl
                if (generation != artworkGeneration || !isCurrentPlayback) {
                    Log.d(TAG, "Ignoring stale artwork result for track=${track.name}")
                    return@post
                }

                currentArtworkBitmap = artwork
                applyTrackMetadata(track, artist, album, durationMs, artwork)
            }
        }.start()
    }

    private fun loadArtworkBitmap(coverUrl: String): Bitmap? {
        val parsed = Uri.parse(coverUrl)
        val scheme = parsed.scheme?.lowercase()

        val bitmap = when (scheme) {
            "http", "https" -> loadArtworkBitmapFromNetwork(coverUrl)
            "content" -> contentResolver.openInputStream(parsed)?.use(::decodeArtworkBitmap)
            "file" -> {
                val filePath = parsed.path ?: return null
                FileInputStream(filePath).use(::decodeArtworkBitmap)
            }
            else -> {
                Log.w(TAG, "Unsupported artwork URI scheme: ${scheme ?: "none"} url=$coverUrl")
                null
            }
        }

        return bitmap?.let(::resizeArtworkBitmap)
    }

    private fun loadArtworkBitmapFromNetwork(coverUrl: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(coverUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true
            connection.doInput = true
            connection.connect()
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "Artwork request failed for url=$coverUrl status=${connection.responseCode}")
                null
            } else {
                connection.inputStream.use(::decodeArtworkBitmap)
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun decodeArtworkBitmap(stream: InputStream): Bitmap? {
        return BitmapFactory.decodeStream(stream)
    }

    private fun resizeArtworkBitmap(bitmap: Bitmap): Bitmap {
        val maxDimension = 512
        val largestDimension = maxOf(bitmap.width, bitmap.height)
        if (largestDimension <= maxDimension) {
            return bitmap
        }

        val scale = maxDimension.toFloat() / largestDimension.toFloat()
        val resizedWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val resizedHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)
    }

    fun playTrack(
        track: QueueSongInfo,
        target: PlaybackTarget,
        artist: String = "Unknown Artist",
        album: String = "Unknown Album"
    ) {
        lufsResolutionGeneration += 1
        playTrackInternal(track, target, artist, album)
    }

    private fun playTrackInternal(
        track: QueueSongInfo,
        target: PlaybackTarget,
        artist: String = "Unknown Artist",
        album: String = "Unknown Album"
    ) {
        val resolvedUrl = target.audioUrl
        playTrackStartTime = System.currentTimeMillis()
        Log.d(TAG, "========== playTrack called ==========")
        Log.i("KaulanPerf", "playTrack start: track=${track.name}")
        Log.d(TAG, "Track: ${track.name} ($resolvedUrl) currentTrackIndex=$currentTrackIndex queueSize=${tracks.size}")

        musicPlayerActive = true

        if (currentUrl == resolvedUrl && mediaPlayer != null && isPrepared) {
            applyTrackVolume(track)
            currentArtworkBitmap = null
            val currentDuration = mediaPlayer?.duration?.toLong() ?: 0L
            applyTrackMetadata(track, artist, album, currentDuration, null)
            loadArtworkForTrackAsync(track, artist, album, currentDuration, target.coverUrl, resolvedUrl)
            pendingSeekPositionMs?.let { pendingPosition ->
                val clampedPosition = pendingPosition.coerceAtMost(mediaPlayer?.duration?.toLong() ?: pendingPosition).toInt()
                mediaPlayer?.seekTo(clampedPosition)
                pendingSeekPositionMs = null
            }
            Log.d(TAG, "Same URL already prepared, resuming")
            resumeMusic()
            return
        }

        mediaPlayer?.let { player ->
            try {
                player.setOnPreparedListener(null)
                player.setOnErrorListener(null)
                player.setOnCompletionListener(null)
                if (isPrepared || player.isPlaying) {
                    player.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MediaPlayer", e)
            }
            try {
                player.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaPlayer", e)
            }
        }

        mediaPlayer = null
        isPrepared = false
        currentUrl = resolvedUrl
        clearArtworkState()
        playbackGeneration += 1
        val generation = playbackGeneration
        persistSession(isPlayingOverride = false)

        mediaPlayer = MediaPlayer().apply {
            try {
                val sourceUri = Uri.parse(resolvedUrl)
                when (sourceUri.scheme?.lowercase()) {
                    "content" -> {
                        Log.i(TAG, "Using content URI data source for track=${track.name}")
                        setDataSource(this@MusicPlayerService, sourceUri)
                    }
                    "file" -> {
                        Log.i(TAG, "Using file URI data source for track=${track.name}")
                        setDataSource(this@MusicPlayerService, sourceUri)
                    }
                    else -> {
                        Log.i(
                            TAG,
                            "Using string data source for track=${track.name} scheme=${sourceUri.scheme ?: "none"}"
                        )
                        setDataSource(resolvedUrl)
                    }
                }
                prepareStartTime = System.currentTimeMillis()

                setOnPreparedListener { mp ->
                    if (mediaPlayer !== this || playbackGeneration != generation || currentUrl != resolvedUrl) {
                        Log.d(
                            TAG,
                            "Ignoring stale onPrepared for generation=$generation currentGeneration=$playbackGeneration track=${track.name}"
                        )
                        return@setOnPreparedListener
                    }
                    Log.d(TAG, "========== onPrepared called ==========")
                    val prepareElapsed = if (prepareStartTime > 0) System.currentTimeMillis() - prepareStartTime else -1L
                    val playTrackElapsed = if (playTrackStartTime > 0) System.currentTimeMillis() - playTrackStartTime else -1L
                    val intentElapsed = if (playTrackCallStartTime > 0) System.currentTimeMillis() - playTrackCallStartTime else -1L
                    Log.i("KaulanPerf", "onPrepared: prepareAsync=${prepareElapsed}ms playTrackInternal=${playTrackElapsed}ms fromIntent=${intentElapsed}ms track=${track.name}")
                    isPrepared = true
                    pendingSeekPositionMs?.let { pendingPosition ->
                        val clampedPosition = pendingPosition.coerceAtMost(mp.duration.toLong()).toInt()
                        Log.d(TAG, "Applying pending seek in onPrepared: $clampedPosition for track=${track.name}")
                        mp.seekTo(clampedPosition)
                        pendingSeekPositionMs = null
                    }
                    applyTrackVolume(track)
                    applyTrackMetadata(track, artist, album, mp.duration.toLong(), null)
                    loadArtworkForTrackAsync(track, artist, album, mp.duration.toLong(), target.coverUrl, resolvedUrl)
                    persistSession(isPlayingOverride = false)
                    resumeMusic()
                    precacheQueueTracks(track)
                }

                setOnErrorListener { _, what, extra ->
                    if (mediaPlayer !== this || playbackGeneration != generation || currentUrl != resolvedUrl) {
                        Log.d(
                            TAG,
                            "Ignoring stale onError for generation=$generation currentGeneration=$playbackGeneration what=$what extra=$extra"
                        )
                        return@setOnErrorListener true
                    }
                    Log.e(TAG, "MediaPlayer error - what: $what, extra: $extra")
                    isPrepared = false
                    persistSession(isPlayingOverride = false)
                    true
                }

                setOnCompletionListener {
                    if (mediaPlayer !== this || playbackGeneration != generation || currentUrl != resolvedUrl) {
                        Log.d(
                            TAG,
                            "Ignoring stale onCompletion for generation=$generation currentGeneration=$playbackGeneration track=${track.name}"
                        )
                        return@setOnCompletionListener
                    }
                    Log.d(TAG, "========== onCompletion called ==========")
                    playNextTrack()
                }

                prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "Exception in playTrack", e)
                isPrepared = false
                persistSession(isPlayingOverride = false)
            }
        }
    }

    fun resumeMusic() {
        mediaPlayer?.let {
            if (isPrepared && !it.isPlaying) {
                it.start()
                handler.post(progressRunnable)
                persistSession(isPlayingOverride = true)
                updatePlaybackState()
                updateNotification()
            }
        } ?: Log.w(TAG, "MediaPlayer is null")
    }

    fun pauseMusic() {
        resolutionGeneration += 1
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                handler.removeCallbacks(progressRunnable)
                persistSession(isPlayingOverride = false)
                updatePlaybackState()
                updateNotification()
            }
        } ?: Log.w(TAG, "MediaPlayer is null")
    }

    private fun clearPauseAfterRunnable() {
        pauseAfterRunnable?.let(handler::removeCallbacks)
        pauseAfterRunnable = null
    }

    private fun clearPauseAfterState() {
        clearPauseAfterRunnable()
        pauseAfterDeadlineMs = 0L
        savePersistedPauseAfterDeadline(this, 0L)
    }

    private fun triggerTimedPause(deadlineMs: Long) {
        Log.d(TAG, "Timed pause fired at deadlineMs=$deadlineMs")
        clearPauseAfterState()
        pauseMusic()
    }

    private fun schedulePauseAfterAt(deadlineMs: Long) {
        clearPauseAfterRunnable()

        if (deadlineMs <= 0L) {
            clearPauseAfterState()
            Log.d(TAG, "schedulePauseAfterAt: cleared pending timed pause")
            updateServiceLifetime()
            return
        }

        pauseAfterDeadlineMs = deadlineMs
        savePersistedPauseAfterDeadline(this, deadlineMs)

        val remainingDelayMs = computeRemainingPauseAfterDelay(
            deadlineMs,
            System.currentTimeMillis()
        )
        if (remainingDelayMs <= 0L) {
            Log.d(TAG, "schedulePauseAfterAt: deadline already expired, triggering pause immediately")
            handler.post {
                if (pauseAfterDeadlineMs == deadlineMs) {
                    triggerTimedPause(deadlineMs)
                }
            }
            return
        }

        val runnable = Runnable {
            if (pauseAfterDeadlineMs != deadlineMs) {
                Log.d(TAG, "Ignoring stale timed pause callback for deadlineMs=$deadlineMs")
                return@Runnable
            }

            Log.d(TAG, "Timed pause fired after ${remainingDelayMs}ms")
            triggerTimedPause(deadlineMs)
        }
        pauseAfterRunnable = runnable
        handler.postDelayed(runnable, remainingDelayMs)
        Log.d(
            TAG,
            "schedulePauseAfterAt: scheduled timed pause in ${remainingDelayMs}ms deadlineMs=$deadlineMs"
        )
    }

    private fun schedulePauseAfter(delayMs: Long) {
        val deadlineMs = computePauseAfterDeadline(delayMs, System.currentTimeMillis())
        schedulePauseAfterAt(deadlineMs)
    }

    private fun stopMusic(clearQueue: Boolean) {
        resolutionGeneration += 1
        handler.removeCallbacks(progressRunnable)
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping media player", e)
            }
            try {
                player.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing media player", e)
            }
        }
        mediaPlayer = null
        isPrepared = false
        currentUrl = null
        musicPlayerActive = false
        clearArtworkState()

        if (clearQueue) {
            tracks = mutableListOf()
            currentTrackIndex = -1
        }

        persistSession(isPlayingOverride = false)
        updatePlaybackState()
        updateNotification()
        updateServiceLifetime()
    }

    fun playNextTrack() {
        if (tracks.isEmpty()) {
            Log.w(TAG, "No tracks in queue")
            return
        }

        val previousIndex = currentTrackIndex
        if (playMode == "loop" && currentTrackIndex in tracks.indices) {
            Log.d(
                TAG,
                "playNextTrack: loop mode replaying currentTrackIndex=$currentTrackIndex track=${tracks[currentTrackIndex].name}"
            )
            playQueueTrackAt(currentTrackIndex, 1, tracks.size)
            return
        }

        currentTrackIndex = if (currentTrackIndex in tracks.indices) {
            (currentTrackIndex + 1) % tracks.size
        } else {
            0
        }
        Log.d(
            TAG,
            "playNextTrack: previousIndex=$previousIndex newIndex=$currentTrackIndex playMode=$playMode nextTrack=${tracks[currentTrackIndex].name}"
        )
        playQueueTrackAt(currentTrackIndex, 1, tracks.size)
    }

    fun playPreviousTrack() {
        if (tracks.isEmpty()) {
            Log.w(TAG, "No tracks in queue")
            return
        }

        val previousIndex = currentTrackIndex
        if (playMode == "loop" && currentTrackIndex in tracks.indices) {
            Log.d(
                TAG,
                "playPreviousTrack: loop mode replaying currentTrackIndex=$currentTrackIndex track=${tracks[currentTrackIndex].name}"
            )
            playQueueTrackAt(currentTrackIndex, -1, tracks.size)
            return
        }

        currentTrackIndex = if (currentTrackIndex in tracks.indices) {
            if (currentTrackIndex == 0) tracks.lastIndex else currentTrackIndex - 1
        } else {
            0
        }
        Log.d(
            TAG,
            "playPreviousTrack: previousIndex=$previousIndex newIndex=$currentTrackIndex playMode=$playMode previousTrack=${tracks[currentTrackIndex].name}"
        )
        playQueueTrackAt(currentTrackIndex, -1, tracks.size)
    }

    private fun createNotification(): Notification {
        val controller = mediaSession.controller
        val mediaMetadata = controller.metadata
        val description = mediaMetadata?.description
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)

        val isPlaying = mediaPlayer?.isPlaying == true
        val title = if (musicPlayerActive && mediaPlayer != null) {
            description?.title ?: "Music Player"
        } else if (currentTrackIndex in tracks.indices) {
            tracks[currentTrackIndex].name
        } else {
            "No song is playing"
        }
        val playPauseIntent = if (isPlaying) {
            createServiceActionPendingIntent(ACTION_PAUSE, 2)
        } else {
            createServiceActionPendingIntent(ACTION_RESUME, 3)
        }

        val playPauseAction = NotificationCompat.Action(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "Pause" else "Play",
            playPauseIntent
        )

        val previousAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous,
            "Previous",
            createServiceActionPendingIntent(ACTION_PREVIOUS, 1)
        )

        val nextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next,
            "Next",
            createServiceActionPendingIntent(ACTION_NEXT, 4)
        )

        builder.setContentTitle(title)
            .setContentText(description?.subtitle ?: "")
            .setSubText(description?.description ?: "")
            .setContentIntent(controller.sessionActivity)
            .setDeleteIntent(createServiceActionPendingIntent(ACTION_STOP, 5))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(currentArtworkBitmap)
            .setOngoing(isPlaying)
            .addAction(previousAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        return builder.build()
    }

    private fun createServiceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicPlayerService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun updatePlaybackState() {
        val availableActions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SEEK_TO

        mediaPlayer?.let {
            val isPlaying = it.isPlaying
            val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            val position = it.currentPosition.toLong()

            val playbackState = PlaybackStateCompat.Builder()
                .setActions(availableActions)
                .setState(state, position, 1.0f)
                .build()
            mediaSession.setPlaybackState(playbackState)
        } ?: run {
            val playbackState = PlaybackStateCompat.Builder()
                .setActions(availableActions)
                .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f)
                .build()
            mediaSession.setPlaybackState(playbackState)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "========== onDestroy ==========")
        super.onDestroy()
        instance = null
        handler.removeCallbacks(progressRunnable)
        clearPauseAfterRunnable()
        mediaSession.release()
        mediaPlayer?.apply {
            try {
                if (isPlaying) stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping in onDestroy", e)
            }
            try {
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing in onDestroy", e)
            }
        }
        mediaPlayer = null
        persistSession(isPlayingOverride = false)
        Log.d(TAG, "onDestroy complete")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Player Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
