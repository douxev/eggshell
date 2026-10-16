package com.douxev.eggshell.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * How the pedometer's background pieces reach [StepSync].
 *
 * An entry point rather than a Hilt worker factory: the app has no custom
 * WorkManager configuration, and adding one only for this would change how
 * every future worker is built.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface StepSyncEntryPoint {
    fun stepSync(): StepSync
}

private fun Context.stepSync(): StepSync =
    EntryPointAccessors.fromApplication(applicationContext, StepSyncEntryPoint::class.java).stepSync()

/**
 * The quarter-hourly reading. See [StepSync] for why it exists at all.
 *
 * Always reports success: a sensor that did not answer this time is not a
 * reason for WorkManager to retry with backoff — the next period comes in
 * fifteen minutes and reads the same cumulative counter.
 */
class StepWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching { applicationContext.stepSync().sampleAndFlush() }
        return Result.success()
    }

    companion object {
        const val NAME = "steps-periodic"
    }
}

/**
 * The moments the counter must be read at no matter what: just before
 * midnight, just before the phone shuts down — and the ones that invalidate
 * the midnight alarm (boot, app update, clock or timezone change).
 *
 * Exported because the shutdown, boot and time broadcasts come from the
 * system. Nothing here can be abused by another app sending these actions
 * explicitly: the worst it can do is make eggshell read its own step counter.
 */
class StepReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val sync = context.stepSync()
                when (action) {
                    ACTION_DAY_END -> {
                        sync.sampleAndFlush()
                        sync.armDayEnd()
                    }
                    // Short timeout and no flush: the system gives a shutdown
                    // broadcast a few seconds, and the vault can wait for the
                    // next unlock — the reading is what cannot.
                    Intent.ACTION_SHUTDOWN -> sync.sample(StepCounter.SHUTDOWN_TIMEOUT_MS)
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        sync.ensureScheduled()
                        sync.sample()
                    }
                }
            } catch (_: Throwable) {
                // A broadcast receiver that throws takes the process with it;
                // a missed reading is recovered by the next one.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DAY_END = "com.douxev.eggshell.action.STEPS_DAY_END"
    }
}
