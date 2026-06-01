/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Common lifecycle for the AVF per-rule forwarders so AvfEngine can hold TCP
 * stream forwarders and UDP datagram forwarders in one map keyed by vsock port.
 */
package com.excp.podroid.engine.avf

internal interface Forwarder {
    fun start()
    fun close()
}
