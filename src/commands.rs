use tauri::{command, AppHandle, Runtime};

use crate::models::*;
use crate::MusicNotificationExt;
use crate::Result;

#[command]
pub(crate) async fn ping<R: Runtime>(
    app: AppHandle<R>,
    payload: PingRequest,
) -> Result<PingResponse> {
    app.music_notification().ping(payload)
}

#[command]
pub(crate) async fn play<R: Runtime>(
    app: AppHandle<R>,
    payload: PlayRequest,
) -> Result<PlayResponse> {
    app.music_notification().play(payload)
}

#[command]
pub(crate) async fn pause<R: Runtime>(app: AppHandle<R>) -> Result<EmptyResponse> {
    app.music_notification().pause()
}

#[command]
pub(crate) async fn pause_after<R: Runtime>(
    app: AppHandle<R>,
    payload: PauseAfterRequest,
) -> Result<EmptyResponse> {
    app.music_notification().pause_after(payload)
}

#[command]
pub(crate) async fn resume<R: Runtime>(app: AppHandle<R>) -> Result<EmptyResponse> {
    app.music_notification().resume()
}

#[command]
pub(crate) async fn stop<R: Runtime>(app: AppHandle<R>) -> Result<EmptyResponse> {
    app.music_notification().stop()
}

#[command]
pub(crate) async fn next<R: Runtime>(app: AppHandle<R>) -> Result<EmptyResponse> {
    app.music_notification().next()
}

#[command]
pub(crate) async fn previous<R: Runtime>(app: AppHandle<R>) -> Result<EmptyResponse> {
    app.music_notification().previous()
}

#[command]
pub(crate) async fn seek<R: Runtime>(app: AppHandle<R>, position: i64) -> Result<EmptyResponse> {
    app.music_notification().seek(position)
}

#[command]
pub(crate) async fn seek_and_play<R: Runtime>(
    app: AppHandle<R>,
    position: i64,
) -> Result<EmptyResponse> {
    app.music_notification().seek_and_play(position)
}

#[command]
pub(crate) async fn get_state<R: Runtime>(app: AppHandle<R>) -> Result<PlaybackState> {
    app.music_notification().get_state()
}

#[command]
pub(crate) async fn set_playing_queue<R: Runtime>(
    app: AppHandle<R>,
    payload: SetPlayingQueueRequest,
) -> Result<QueueMutationResponse> {
    app.music_notification().set_playing_queue(payload)
}

#[command]
pub(crate) async fn get_playback_session<R: Runtime>(app: AppHandle<R>) -> Result<PlaybackSession> {
    app.music_notification().get_playback_session()
}

#[command]
pub(crate) async fn clear_playing_queue<R: Runtime>(
    app: AppHandle<R>,
) -> Result<QueueMutationResponse> {
    app.music_notification().clear_playing_queue()
}

#[command]
pub(crate) async fn set_play_mode<R: Runtime>(
    app: AppHandle<R>,
    payload: SetPlayModeRequest,
) -> Result<QueueMutationResponse> {
    app.music_notification().set_play_mode(payload)
}

#[command]
pub(crate) async fn start_service<R: Runtime>(app: AppHandle<R>) -> Result<EmptyResponse> {
    app.music_notification().start_service()
}

#[command]
pub(crate) async fn stop_service<R: Runtime>(app: AppHandle<R>) -> Result<EmptyResponse> {
    app.music_notification().stop_service()
}

#[command]
pub(crate) async fn set_volume<R: Runtime>(
    app: AppHandle<R>,
    payload: SetVolumeRequest,
) -> Result<SetVolumeResponse> {
    app.music_notification().set_volume(payload)
}

#[command]
pub(crate) async fn set_normalization_config<R: Runtime>(
    app: AppHandle<R>,
    payload: SetNormalizationConfigRequest,
) -> Result<SetNormalizationConfigResponse> {
    app.music_notification().set_normalization_config(payload)
}

#[command]
pub(crate) async fn set_server<R: Runtime>(
    app: AppHandle<R>,
    library_name: String,
) -> Result<SetServerResponse> {
    app.music_notification().set_server(library_name)
}

#[command]
pub(crate) async fn set_headset_media_button_disabled<R: Runtime>(
    app: AppHandle<R>,
    payload: SetHeadsetMediaButtonDisabledRequest,
) -> Result<EmptyResponse> {
    app.music_notification()
        .set_headset_media_button_disabled(payload)
}
