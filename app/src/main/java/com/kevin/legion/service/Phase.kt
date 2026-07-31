package com.kevin.legion.service

/**
 * The conversation phase, owned by [LiveSessionController] and rendered by
 * whatever UI reflects the live state (avatar ring/status). CONNECTING covers
 * building the system prompt and the WebSocket handshake.
 */
enum class Phase { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING }
