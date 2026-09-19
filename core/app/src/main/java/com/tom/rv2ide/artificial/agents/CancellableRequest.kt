/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agents

import java.net.HttpURLConnection
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call

/**
 * Runs the request's blocking [block] so that cancelling the coroutine also reaches the socket.
 *
 * A blocking read cannot observe cancellation by itself; without this the coroutine would run to
 * the end of the stream instead of stopping when the user interrupts. The continuation's
 * cancellation hook disconnects the connection, which unblocks the read with an IOException.
 * [suspendCancellableCoroutine] links the continuation to the requesting job before [block] runs,
 * so the hook fires even though the coroutine never actually suspends here.
 */
suspend fun <T> HttpURLConnection.runCancellable(block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { disconnect() }
        continuation.resumeWith(runCatching(block))
    }

/** [Call] counterpart of [runCancellable]; cancelling the coroutine cancels the OkHttp call. */
suspend fun <T> Call.runCancellable(block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        continuation.resumeWith(runCatching(block))
    }
