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
    "get_state",
    "set_playing_queue",
    "get_playback_session",
    "subscribe_playback_events",
    "unsubscribe_playback_events",
    "clear_playing_queue",
    "set_play_mode",
    "start_service",
    "stop_service",
    "set_volume",
    "set_server",
];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .ios_path("ios")
        .build();
}
