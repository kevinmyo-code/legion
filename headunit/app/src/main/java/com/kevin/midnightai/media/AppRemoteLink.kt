package com.kevin.midnightai.media

/**
 * Spotify App Remote (playback) connection state, as [SpotifyController.link] reports it. Its own
 * file, free of the SDK, so the pure Settings status functions can take it in a plain JVM test.
 */
enum class AppRemoteLink { DISCONNECTED, CONNECTING, CONNECTED }
