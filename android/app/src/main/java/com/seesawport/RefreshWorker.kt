package com.seesawport

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The refresh, on a schedule, the way the GitHub Action was supposed to run.
 *
 * The workflow's cron was "40 * /3 * * *" — every third hour at forty past,
 * which put two runs inside New York gallery hours. It never fired once in
 * four months. WorkManager will, because it is the phone's own scheduler and
 * the phone is awake anyway.
 *
 * What it cannot promise is the minute. Android batches work and holds it
 * through Doze, so "every three hours" is a floor and not a clock: a run may
 * land late, never early. That is fine for this — the ETag means a run that
 * finds nothing changed costs one request and no body at all.
 */
class RefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val r = runCatching { Refresh.run(applicationContext) }
        r.onSuccess { Log.i(TAG, "scheduled refresh: ${it.note}") }
        r.onFailure { Log.w(TAG, "scheduled refresh failed: ${it.message}") }
        // Retry rather than fail: a refresh that could not reach the network
        // is a refresh that has not happened yet.
        if (r.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "SeeSawPort"
        private const val NAME = "see-saw-refresh"
        private const val HOURS = 3L

        /** Idempotent: safe to call on every launch. */
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<RefreshWorker>(HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInitialDelay(untilNextFortyPast(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, work
            )
        }

        /**
         * Forty past the next third hour, New York time — the workflow's own
         * cron, kept because the two runs it lands inside gallery hours were
         * chosen on purpose and off-the-hour avoids the busiest slot.
         */
        private fun untilNextFortyPast(): Long {
            val now = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
            val next = (now.clone() as Calendar).apply {
                set(Calendar.MINUTE, 40)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                while (timeInMillis <= now.timeInMillis || get(Calendar.HOUR_OF_DAY) % 3 != 0) {
                    add(Calendar.HOUR_OF_DAY, 1)
                }
            }
            return next.timeInMillis - now.timeInMillis
        }
    }
}
