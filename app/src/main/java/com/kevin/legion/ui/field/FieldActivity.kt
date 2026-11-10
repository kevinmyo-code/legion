package com.kevin.legion.ui.field

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * The Field screen's own window (round 5, field navigation), the Clock's shape: an app drawer
 * entry ("Field", an activity-alias in the manifest) so it is one tap from Matrix home or the app
 * drawer with no signal and no conversation. Portrait-locked, because the compass maths assumes
 * the phone's top edge is "up".
 */
class FieldActivity : ComponentActivity() {
    private val viewModel: FieldViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SoftTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val askLocation = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { viewModel.permissionChanged() }
                FieldContent(
                    state = state,
                    actions = FieldActions(
                        onBack = { finish() },
                        onGrantLocation = {
                            askLocation.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        onMark = viewModel::mark,
                        onMargin = viewModel::setMargin,
                    ),
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.start()
    }

    override fun onStop() {
        viewModel.stop()
        super.onStop()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, FieldActivity::class.java)
    }
}
