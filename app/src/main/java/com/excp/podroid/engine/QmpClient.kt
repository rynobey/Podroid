/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Minimal QMP (QEMU Machine Protocol) client for runtime VM management.
 * Used for adding/removing port forwards while the VM is running.
 */
package com.excp.podroid.engine

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class QmpClient(private val socketPath: String) {

    companion object {
        private const val TAG = "QmpClient"
        private const val SOCKET_TIMEOUT_MS = 5000

        /** Verdict for one QMP reply line. */
        sealed class QmpVerdict {
            /** Terminal reply: the command succeeded. */
            object Success : QmpVerdict()
            /** Terminal reply: the command failed (carries a human-readable reason). */
            data class Failure(val reason: String) : QmpVerdict()
            /** Async event line — not the reply to our command; keep reading. */
            object SkipEvent : QmpVerdict()
        }

        /**
         * Pure classification of an already-decomposed QMP reply. No org.json /
         * I/O dependency, so it is directly unit-testable.
         *
         *  - top-level `"error"`             → Failure (QMP-level rejection)
         *  - `"event"` key                   → SkipEvent (async event, keep reading)
         *  - `"return"` string carrying a    → Failure: human-monitor-command never
         *    hostfwd error                      sets a QMP-level error, so a busy
         *                                       port / bad spec arrives as return text
         *  - anything else                   → Success
         *
         * @param hasError   whether the reply object has a top-level "error" key
         * @param hasEvent   whether the reply object has an "event" key
         * @param returnValue the value of the "return" key, if present (the String
         *                    body matters for human-monitor-command failures)
         */
        fun classifyQmpFields(hasError: Boolean, hasEvent: Boolean, returnValue: Any?): QmpVerdict {
            if (hasError) return QmpVerdict.Failure("QMP error")
            // Async events (SHUTDOWN, RESET, ...) can interleave with replies.
            if (hasEvent) return QmpVerdict.SkipEvent
            if (returnValue is String && isHostfwdError(returnValue)) {
                return QmpVerdict.Failure("QMP human-monitor error: ${returnValue.trim()}")
            }
            return QmpVerdict.Success
        }

        /**
         * Classify one parsed QMP reply object. Thin org.json adapter over
         * [classifyQmpFields]; returns null for async-event lines so the read
         * loop knows to keep reading for the real reply.
         */
        fun classifyQmpResponse(json: JSONObject): Result<JSONObject>? =
            when (val v = classifyQmpFields(json.has("error"), json.has("event"), json.opt("return"))) {
                is QmpVerdict.Success -> Result.success(json)
                is QmpVerdict.Failure -> Result.failure(RuntimeException(v.reason))
                is QmpVerdict.SkipEvent -> null
            }

        /**
         * human-monitor-command (hostfwd_add/remove) reports failures as plain
         * text in the `"return"` field. Match the QEMU SLIRP/hostfwd error
         * prefixes ("could not set up host forwarding rule", "Could not ...").
         */
        private fun isHostfwdError(returnText: String): Boolean {
            val t = returnText.trim()
            if (t.isEmpty()) return false
            return t.contains("could not set up", ignoreCase = true) ||
                t.startsWith("Could not")
        }
    }

    suspend fun execute(command: String, arguments: JSONObject? = null): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                LocalSocket().use { socket ->
                    socket.connect(
                        LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM)
                    )
                    socket.soTimeout = SOCKET_TIMEOUT_MS

                    val reader = BufferedReader(InputStreamReader(socket.inputStream))
                    val writer = OutputStreamWriter(socket.outputStream)

                    // Read QMP greeting (verbose-only: noisy and uninteresting on every command)
                    val greeting = reader.readLine()
                    Log.v(TAG, "QMP greeting: $greeting")

                    // Send qmp_capabilities to enter command mode
                    writer.write("{\"execute\":\"qmp_capabilities\"}\n")
                    writer.flush()
                    val capResponse = reader.readLine()
                    Log.v(TAG, "Capabilities response: $capResponse")

                    // Send the actual command
                    val cmd = JSONObject().apply {
                        put("execute", command)
                        if (arguments != null) put("arguments", arguments)
                    }
                    writer.write(cmd.toString() + "\n")
                    writer.flush()

                    // Read until a terminal reply (return/error). QMP can emit
                    // async {"event":...} lines at any time — classifyQmpResponse
                    // returns null for those so we skip them. A null line = EOF.
                    var result: Result<JSONObject>? = null
                    while (result == null) {
                        val response = reader.readLine()
                            ?: return@withContext Result.failure(
                                RuntimeException("QMP connection closed before a reply to $command")
                            )
                        Log.d(TAG, "Command response: $response")
                        result = classifyQmpResponse(JSONObject(response))
                    }
                    result
                }
            } catch (e: Exception) {
                Log.e(TAG, "QMP command failed: $command", e)
                Result.failure(e)
            }
        }

    suspend fun addPortForward(hostPort: Int, guestPort: Int, protocol: String = "tcp"): Result<JSONObject> {
        val monitorCmd = "hostfwd_add net0 ${protocol}::${hostPort}-:${guestPort}"
        return execute(
            "human-monitor-command",
            JSONObject().put("command-line", monitorCmd)
        )
    }

    suspend fun removePortForward(hostPort: Int, protocol: String = "tcp"): Result<JSONObject> {
        val monitorCmd = "hostfwd_remove net0 ${protocol}::${hostPort}"
        return execute(
            "human-monitor-command",
            JSONObject().put("command-line", monitorCmd)
        )
    }
}
