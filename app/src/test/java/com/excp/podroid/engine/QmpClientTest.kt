/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import com.excp.podroid.engine.QmpClient.Companion.QmpVerdict
import com.excp.podroid.engine.QmpClient.Companion.classifyQmpFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins QMP response classification. Port-forward commands run via
 * `human-monitor-command`, whose failures arrive as a {"return":"<error text>"}
 * envelope (no QMP-level error, no exception), so a naive
 * Result.success(JSONObject(line)) reports a failed forward as applied.
 *
 * Tests target the pure [classifyQmpFields] (no org.json) so they run as plain
 * JVM unit tests; the thin classifyQmpResponse(JSONObject) adapter just maps its
 * verdict onto Result/null.
 */
class QmpClientTest {

    @Test
    fun `top-level error envelope is a failure`() {
        // {"error":{"class":"GenericError","desc":"bad command"}}
        val verdict = classifyQmpFields(hasError = true, hasEvent = false, returnValue = null)
        assertTrue(verdict is QmpVerdict.Failure)
    }

    @Test
    fun `successful return object is a success`() {
        // {"return":{}}
        val verdict = classifyQmpFields(hasError = false, hasEvent = false, returnValue = emptyMap<String, Any>())
        assertEquals(QmpVerdict.Success, verdict)
    }

    @Test
    fun `async event line is skipped, not treated as a response`() {
        // {"event":"SHUTDOWN",...} — keep reading for the real reply.
        val verdict = classifyQmpFields(hasError = false, hasEvent = true, returnValue = null)
        assertEquals(QmpVerdict.SkipEvent, verdict)
    }

    @Test
    fun `human-monitor return carrying a hostfwd error is a failure`() {
        // hostfwd_add on a busy port: {"return":"could not set up host forwarding rule ..."}
        val verdict = classifyQmpFields(
            hasError = false,
            hasEvent = false,
            returnValue = "could not set up host forwarding rule 'tcp::8080-:80'\r\n",
        )
        assertTrue(verdict is QmpVerdict.Failure)
    }

    @Test
    fun `human-monitor return with capitalized Could not is a failure`() {
        val verdict = classifyQmpFields(
            hasError = false,
            hasEvent = false,
            returnValue = "Could not set up host forwarding rule",
        )
        assertTrue(verdict is QmpVerdict.Failure)
    }

    @Test
    fun `human-monitor empty return string is a success`() {
        // A successful hostfwd_add returns an empty string.
        val verdict = classifyQmpFields(hasError = false, hasEvent = false, returnValue = "")
        assertEquals(QmpVerdict.Success, verdict)
    }

    @Test
    fun `event takes precedence is not consulted when error present`() {
        // Defensive: an error envelope is terminal even if an event key co-occurs.
        val verdict = classifyQmpFields(hasError = true, hasEvent = true, returnValue = null)
        assertTrue(verdict is QmpVerdict.Failure)
    }
}
