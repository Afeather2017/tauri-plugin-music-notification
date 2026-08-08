# Tauri Plugin Music Notification

A Tauri plugin for Android that provides music playback notifications with media controls.

## Features

- 🎵 Play music from URLs with media notifications
- 🎮 Full playback controls (play, pause, resume, stop)
- ⏭️ Next/Previous track navigation
- ⏩ Seek to specific positions
- 🔊 Volume control (app-only, not system volume)
- 📊 Get current playback state
- 🔔 Native Android media notification with controls
- 🖼️ Notification artwork via `coverUrl` for play requests and queue items
- 🎨 Lock screen controls support
- 🌐 HTTP Server integration via trait-based API

## Installation

Install the plugin using your preferred package manager:

```bash
npm run tauri add music-notification-api
```

Or manually add to `package.json`:

```json
{
  "dependencies": {
    "music-notification-api": "file:../../path/to/plugin"
  }
}
```

Add the plugin to your `src-tauri/Cargo.toml`:

```toml
[dependencies]
tauri-plugin-music-notification-api = { path = "../../../path/to/plugin" }
```

## Setup

### Rust

Register the plugin in your Tauri app (`src-tauri/src/lib.rs`):

```rust
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![...])
        .plugin(tauri_plugin_music_notification_api::init())
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
```

### Android - HTTP Server Integration

The plugin provides a `Server` trait that allows your app to run an HTTP server on Android. The server is automatically started when the music player service starts.

#### 1. Implement the Server Trait

In your `src-tauri/src/lib.rs`, implement the `Server` trait:

```rust
use std::sync::Arc;
use tauri_plugin_music_notification_api::Server;

struct HttpServer;

impl Server for HttpServer {
    fn library_name(&self) -> &str {
        "app_lib"
    }

    fn start(self: Arc<Self>) -> Result<(), String> {
        // Start your HTTP server here
        println!("HTTP Server starting...");
        Ok(())
    }

    fn stop(self: Arc<Self>) -> Result<(), String> {
        // Stop your HTTP server here
        println!("HTTP Server stopping...");
        Ok(())
    }

    fn is_running(self: Arc<Self>) -> bool {
        // Return whether the server is running
        true
    }
}
```

#### 2. Register Your Server

Register your server implementation before installing the plugin:

```rust
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // Register the server before installing the plugin so Android can
    // auto-register the correct native library name for the foreground service.
    tauri_plugin_music_notification_api::set_server(Arc::new(HttpServer));

    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![...])
        .plugin(tauri_plugin_music_notification_api::init())
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
```

#### 3. No Manual JNI or Frontend Library Registration Needed

The plugin now exports the fixed Android JNI symbols itself and reads the native
library name from `Server::library_name()`. That means:

- you do **not** need to write JNI wrappers in your app
- you do **not** need to call `setServer(...)` from JavaScript just to start the server

The optional `setServer(...)` JavaScript API still exists for advanced/manual flows,
but normal Rust `Server` registration is enough.

### Permissions

Add the required permissions to your `src-tauri/capabilities/default.json`:

```json
{
  "permissions": [
    "core:default",
    "music-notification:default"
  ]
}
```

**Important:** Do NOT copy the `permissions/` folder from the plugin to your app. The plugin's permissions are automatically loaded.

### Android - HTTP/Cleartext Traffic

If you're using HTTP URLs (not HTTPS), you need to enable cleartext traffic:

1. **Add network security config** to `src-tauri/gen/android/app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

2. **Update AndroidManifest.xml** (`src-tauri/gen/android/app/src/main/AndroidManifest.xml`):

```xml
<application
    ...
    android:usesCleartextTraffic="true"
    android:networkSecurityConfig="@xml/network_security_config">
```

**Note:** These Android files get regenerated when you run `npx tauri android init`. You may need to reapply these changes after regenerating.

### Android Permissions

The plugin automatically includes these Android permissions:
- `INTERNET` - For streaming audio
- `FOREGROUND_SERVICE` - For background playback
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` - For media playback service
- `POST_NOTIFICATIONS` - For displaying notifications

## Supported Audio Formats

The plugin uses Android's `MediaPlayer`, which supports:
- **MP3** (.mp3)
- **AAC** (.aac, .m4a)
- **FLAC** (.flac)
- **Vorbis** (.ogg)
- **WAV** (.wav)
- **OpUS** (.opus)
- **MIDI** (.mid, .midi)

Support varies by Android version and device hardware.

## Usage

### JavaScript/TypeScript

```typescript
import { play, pause, resume, stop, next, previous, seek, getState, setPlayingQueue, setVolume, setNormalizationConfig } from 'music-notification-api';

// Play music
await play({
  url: "https://example.com/song.mp3",
  title: "Song Title",
  artist: "Artist Name",
  album: "Album Name",
  coverUrl: "https://example.com/song-cover.jpg"
});

// Identity-based queue playback
await setPlayingQueue(
  {
    songs: [
      {
        id: 1,
        name: "Remote Song",
        deviceId: "stable-device-id",
        sourceKind: "kaulan",
        localUri: null,
        tempSongUrl: null,
        lufs: null,
        coverUrl: null
      }
    ],
    currentIndex: 0
  },
  "sequential"
);

// Pause playback
await pause();

// Resume playback
await resume();

// Stop playback
await stop();

// Skip to next track
await next();

// Go to previous track
await previous();

// Seek to position (in milliseconds)
await seek(30000); // Seek to 30 seconds

// Set volume (0.0 to 1.0)
await setVolume({ volume: 0.5 }); // Set to 50%

// Set normalization config and queue LUFS pre-cache count
await setNormalizationConfig({
  mode: "auto",
  manualVolume: 0.5,
  fixedLufs: -27,
  lufsPrecacheCount: 5
});

// Get current playback state
const state = await getState();
console.log(state.isPlaying); // true/false
console.log(state.position);  // Current position in ms
console.log(state.duration);  // Total duration in ms
```

## API Reference

### `play(options: PlayOptions)`

Starts playing music from a URL.

**Parameters:**
- `url` (string, required): The URL of the audio file
- `title` (string, optional): Song title
- `artist` (string, optional): Artist name
- `album` (string, optional): Album name
- `coverUrl` (string, optional): Artwork URL for Android notifications. Supports `http://`, `https://`, `content://`, and `file://`

**Returns:** `Promise<{ success: boolean; message?: string }>`

**Behavior notes:**
- `coverUrl` updates Android media-session metadata and the notification large icon.
- `play({url})` is ephemeral direct playback and does not survive a process restart.
- If artwork loading fails, playback continues and the notification falls back to text-only metadata.

### Queue source fields

Queue entries use one source-specific locator:

- `kaulan`: `deviceId + id`; the service queries the current device API URL before creating the media data source.
- `local_raw`: `localUri`, containing a filesystem, `file://`, or `content://` URI.
- `temporary`: `tempSongUrl`; these entries remain in the live session but are excluded from restart persistence.

Queue synchronization never changes the resolved URL owned by an active player.

### `pause()`

Pauses the current playback.

**Returns:** `Promise<{ success: boolean }>`

### `pauseAfter(delayMs: number)`

Schedules a pause after the provided delay in milliseconds. Passing `0` clears any pending timed pause.

**Parameters:**
- `delayMs` (number): Delay in milliseconds before pausing playback

**Returns:** `Promise<{ success: boolean }>`

### `resume()`

Resumes paused playback.

**Returns:** `Promise<{ success: boolean }>`

### `stop()`

Stops playback and clears the notification.

**Returns:** `Promise<{ success: boolean }>`

### `next()`

Skips to the next track (if available in playlist).

**Returns:** `Promise<{ success: boolean }>`

### `previous()`

Goes back to the previous track (if available in playlist).

**Returns:** `Promise<{ success: boolean }>`

### `seek(position: number)`

Seeks to a specific position in the current track.

**Parameters:**
- `position` (number): Position in milliseconds

**Returns:** `Promise<{ success: boolean }>`

### `getState()`

Gets the current playback state.

**Returns:** `Promise<PlaybackState>`

```typescript
interface PlaybackState {
  isPlaying: boolean;
  position: number;  // in milliseconds
  duration: number;  // in milliseconds
}
```

### `setVolume(options: SetVolumeOptions)`

Sets the playback volume for the current music player.

**Parameters:**
- `volume` (number): Volume level from 0.0 (silent) to 1.0 (full volume)

**Returns:** `Promise<{ success: boolean; message?: string }>`

**Note:** This controls only the app's audio volume, not the system volume. The actual output also depends on the system's media volume level.

### `setNormalizationConfig(options: SetNormalizationConfigOptions)`

Updates the mobile normalization mode and LUFS queue pre-cache behavior.

**Parameters:**
- `mode` (`"auto" | "manual" | "fixed"`): Normalization mode
- `manualVolume` (number): Manual fallback volume from `0.0` to `1.0`
- `fixedLufs` (number): Target LUFS used when `mode` is `"fixed"`
- `lufsPrecacheCount` (number, optional): Number of missing-LUFS queue items to pre-cache from the current track onward. Clamped to `0..20`, default `5`.

**Returns:** `Promise<{ success: boolean; message?: string }>`

## Platform Support

| Platform | Supported |
|----------|-----------|
| Android  | ✅        |
| iOS      | ❌        |
| Windows  | ❌        |
| macOS    | ❌        |
| Linux    | ❌        |

Currently, this plugin only supports Android. Desktop implementations return placeholder responses.

## Troubleshooting

### "music-notification|play not allowed by ACL" error

This means the plugin permissions aren't configured correctly:

1. Make sure you're using `music-notification:default` in capabilities (NOT individual permissions)
2. Do NOT copy the `permissions/` folder to your app
3. Make sure `tauri-plugin-music-notification-api` is in your `Cargo.toml`

### HTTP URLs not working (MediaPlayer errors)

Android blocks cleartext (HTTP) traffic by default. Follow the **HTTP/Cleartext Traffic** section above to enable it.

### "stop called in state 0" MediaPlayer error

This error is handled internally by the plugin and can be ignored. It occurs when the MediaPlayer is stopped while in an idle state.

### Build errors after regenerating Android project

After running `npx tauri android init`, you may need to reapply:
- Network security config (`network_security_config.xml`)
- Cleartext traffic settings in `AndroidManifest.xml`

## Example

Check out the [example app](./examples/music.notification) for a complete vanilla TypeScript implementation.

## License

MIT

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
