package com.kevin.midnightai.service

/**
 * The conversation phase, owned by [LiveSessionController] and rendered by the
 * Cruise screen (avatar ring/status) and Lights Out. CONNECTING covers building
 * the system prompt and the WebSocket handshake.
 *
 * (Moved here from the UI layer in the overlay pivot: the phase is now a
 * session/service concept, since the session no longer lives inside a composable.)
 */
enum class Phase { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING }
