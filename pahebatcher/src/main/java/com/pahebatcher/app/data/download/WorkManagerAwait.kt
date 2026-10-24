package com.pahebatcher.app.data.download

import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Await [WorkManager.getWorkInfosForUniqueWork] without blocking a coroutine
 * thread.
 *
 * AQR-006: both the download Worker and the startup reconciler carried their
 * own byte-for-byte copy of this bridge, so a fix to one did not reach the
 * other (DL-006 in the Worker, Phase 10 F-L1 in the reconciler). The future's
 * completion listener resumes this coroutine instead of calling
 * `ListenableFuture.get()` inline, so no dispatcher thread is held while
 * WorkManager computes the work info; the listener runs on a direct executor
 * after completion, so `future.get()` there returns without blocking.
 */
internal suspend fun awaitUniqueWorkInfos(
    workManager: WorkManager,
    uniqueName: String,
): List<WorkInfo> = suspendCancellableCoroutine { cont ->
    val future = workManager.getWorkInfosForUniqueWork(uniqueName)
    future.addListener(
        {
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        },
        Executor { it.run() },
    )
}
