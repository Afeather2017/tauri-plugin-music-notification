use serde::{Deserialize, Serialize};

fn default_lufs_precache_count() -> u32 {
    5
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PingRequest {
    pub value: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PingResponse {
    pub value: Option<String>,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayRequest {
    pub url: String,
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub cover_url: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayResponse {
    pub success: bool,
    pub message: Option<String>,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EmptyRequest {}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EmptyResponse {
    pub success: bool,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PauseAfterRequest {
    pub delay_ms: i64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackState {
    pub is_playing: bool,
    pub position: i64,
    pub duration: i64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum PlayMode {
    Sequential,
    Shuffle,
    Loop,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum NormalizationMode {
    Auto,
    Manual,
    Fixed,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QueueSong {
    pub id: i64,
    pub name: String,
    pub path: String,
    pub device_id: Option<String>,
    pub source_kind: Option<String>,
    #[serde(default)]
    pub url: String,
    pub lufs: Option<f64>,
    pub cover_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayingQueue {
    pub songs: Vec<QueueSong>,
    pub current_index: Option<usize>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackRuntime {
    pub is_playing: bool,
    pub position_ms: i64,
    pub duration_ms: i64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackSession {
    pub queue: PlayingQueue,
    pub runtime: PlaybackRuntime,
    pub play_mode: PlayMode,
    pub current_song_id: Option<i64>,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetPlayingQueueRequest {
    pub queue: PlayingQueue,
    pub play_mode: PlayMode,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QueueMutationResponse {
    pub success: bool,
    pub message: Option<String>,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetPlayModeRequest {
    pub play_mode: PlayMode,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetVolumeRequest {
    pub volume: f32,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetVolumeResponse {
    pub success: bool,
    pub message: Option<String>,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetNormalizationConfigRequest {
    pub mode: NormalizationMode,
    pub manual_volume: f32,
    pub fixed_lufs: f64,
    #[serde(default = "default_lufs_precache_count")]
    pub lufs_precache_count: u32,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetNormalizationConfigResponse {
    pub success: bool,
    pub message: Option<String>,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetServerRequest {
    pub library_name: String,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetServerResponse {
    pub success: bool,
    pub message: Option<String>,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetHeadsetMediaButtonDisabledRequest {
    pub disabled: bool,
}

// Server trait for example apps to implement
use std::sync::{Arc, Mutex};

/// Trait that example apps must implement to provide a custom server
pub trait Server: Send + Sync {
    fn library_name(&self) -> &str;
    fn start(self: Arc<Self>) -> Result<(), String>;
    fn stop(self: Arc<Self>) -> Result<(), String>;
    fn is_running(self: Arc<Self>) -> bool;
}

#[derive(Clone)]
pub struct RegisteredServer {
    pub server: Arc<dyn Server>,
    pub library_name: String,
}

/// Global storage for the registered server implementation
static REGISTERED_SERVER: Mutex<Option<RegisteredServer>> = Mutex::new(None);

/// Register a server implementation
pub fn set_server(server: Arc<dyn Server>) {
    let library_name = server.library_name().to_string();
    *REGISTERED_SERVER.lock().unwrap() = Some(RegisteredServer {
        server,
        library_name,
    });
}

/// Get the registered server
pub fn get_server() -> Option<Arc<dyn Server>> {
    REGISTERED_SERVER
        .lock()
        .unwrap()
        .as_ref()
        .map(|registered| registered.server.clone())
}

/// Get the registered server library name
pub fn get_server_library_name() -> Option<String> {
    REGISTERED_SERVER
        .lock()
        .unwrap()
        .as_ref()
        .map(|registered| registered.library_name.clone())
}

/// Android log binding for server functions
#[cfg(target_os = "android")]
/// Call start on the registered server (called from JNI wrapper)
#[no_mangle]
pub extern "C" fn server_start() -> i32 {
    #[cfg(target_os = "android")]
    {
        if let Some(server) = get_server() {
            match server.start() {
                Ok(()) => 0,
                Err(e) => {
                    eprintln!("server_start failed: {}", e);
                    -1
                }
            }
        } else {
            eprintln!("server_start: No server registered");
            -1
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = get_server();
        0
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn Java_com_plugin_music_1notification_MusicPlayerService_serverStart(
    _env: jni::JNIEnv,
    _this: jni::objects::JObject,
) -> i32 {
    server_start()
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn Java_com_plugin_music_1notification_MusicPlayerService_serverStop(
    _env: jni::JNIEnv,
    _this: jni::objects::JObject,
) -> i32 {
    server_stop()
}

#[cfg(target_os = "android")]
pub fn ensure_android_jni_symbols_linked() {
    let _ = Java_com_plugin_music_1notification_MusicPlayerService_serverStart as *const ();
    let _ = Java_com_plugin_music_1notification_MusicPlayerService_serverStop as *const ();
}

/// Call stop on the registered server (called from JNI wrapper)
#[no_mangle]
pub extern "C" fn server_stop() -> i32 {
    #[cfg(target_os = "android")]
    {
        if let Some(server) = get_server() {
            match server.stop() {
                Ok(()) => 0,
                Err(e) => {
                    eprintln!("server_stop failed: {}", e);
                    -1
                }
            }
        } else {
            eprintln!("server_stop: No server registered");
            -1
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = get_server();
        0
    }
}
