package com.kaddie.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kaddie.app.KaddieApplication
import com.kaddie.app.domain.sync.SyncIssue
import com.kaddie.app.domain.sync.SyncOutcome
import com.kaddie.app.domain.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as KaddieApplication).container
        return try {
            when (container.syncEngine.sync()) {
                SyncOutcome.DONE -> Result.success()
                SyncOutcome.RETRY -> Result.retry()
                // Signed out: pending writes wait for the next sign-in. Signed in but token not renewable
                // right now (e.g. refresh failed temporarily): try again later.
                SyncOutcome.AUTH_REQUIRED ->
                    if (container.authRepository.currentUser.value != null) Result.retry() else Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            container.syncStatus.setIssue(SyncIssue.ServerError)
            if (runAttemptCount < MAX_UNEXPECTED_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_UNEXPECTED_ATTEMPTS = 5
    }
}

class WorkManagerSyncScheduler(context: Context) : SyncScheduler {
    private val workManager = WorkManager.getInstance(context)
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun requestSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Queued behind a running sync, so writes added meanwhile are never missed.
        workManager.enqueueUniqueWork(UNIQUE_SYNC, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val UNIQUE_SYNC = "kaddie-sync"
        const val UNIQUE_PERIODIC = "kaddie-sync-periodic"
    }
}
