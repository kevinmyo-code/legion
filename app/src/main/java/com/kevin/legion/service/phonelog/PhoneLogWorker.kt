package com.kevin.legion.service.phonelog

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Hourly, on any network: send what [PhoneLog] has spooled (2026-11-09). The foreground ticker and
 * the session-end trigger cover the app in use; this covers the hours it sits in the background,
 * when a crash or an ANR is most likely to go unnoticed. A failure is not retried here: the batch
 * stays on disk and the next trigger takes it, so the result is always success.
 */
class PhoneLogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (PhoneLog.isEnabled(applicationContext)) {
            // A process WorkManager started has run Application.onCreate, so PhoneLog is initialised.
            PhoneLog.uploadFromWorker()
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "legion-phone-log-upload"
        private const val INTERVAL_HOURS = 1L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PhoneLogWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
