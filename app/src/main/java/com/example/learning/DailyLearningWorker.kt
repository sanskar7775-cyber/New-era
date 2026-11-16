package com.example.learning

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.ai.NewEraAiService
import com.example.device.DeviceController
import com.example.web.WebSearchTool
import java.util.concurrent.TimeUnit

class DailyLearningWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val store = DailyLearningStore(applicationContext)
            val web = WebSearchTool()
            val device = DeviceController(applicationContext)
            val ai = NewEraAiService(web, device)
            val engine = DailyLearningEngine(store, web, ai)
            engine.ensureLearnedTodayIfEnabled()
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Daily internet learning worker failed; will retry", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DailyLearningWorker"
        private const val UNIQUE_WORK_NAME = "new_era_daily_internet_learning"

        fun scheduleDaily(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<DailyLearningWorker>(
                24,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }
    }
}
