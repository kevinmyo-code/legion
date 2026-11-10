package com.kevin.legion.ui.typeset

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.kevin.legion.ui.theme.soft.ProvideTypesetEdition
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * Box breathing, full screen (Kevin, 2026-11-10). Its own activity so it covers everything, a
 * blocked feed app's bounce screen included, and ends back where it was opened from. Opened only by
 * a tap ([start]); nothing schedules it, counts it or reminds about it (CLAUDE.md section 7).
 */
class BreathActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProvideTypesetEdition { SoftTheme { BoxBreathingScreen(onEnd = ::finish) } } }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, BreathActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
