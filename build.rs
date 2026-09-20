const COMMANDS: &[&str] = &[
    "ping",
    "play",
    "pause",
    "pause_after",
    "resume",
    "stop",
    "next",
    "previous",
    "seek",
    "seek_and_play",
    "play_track_at_index",
    "get_state",
    "set_playing_queue",
    "get_playback_session",
    "clear_playing_queue",
    "set_play_mode",
    "start_service",
    "stop_service",
    "set_volume",
    "set_normalization_config",
    "set_server",
    "set_headset_media_button_disabled",
];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .ios_path("ios")
        .build();
}
