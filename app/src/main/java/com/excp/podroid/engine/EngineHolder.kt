/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * EngineHolder — the single @Singleton VmEngine Hilt hands out. Internally
 * swaps between QemuEngine and AvfEngine when the user changes Settings →
 * Backend; the swap only takes effect once the current VM is Stopped/Idle/Error
 * so a running VM is never killed mid-flight.
 *
 * Also owns the cross-cutting rule-diff loop: it watches
 * PortForwardRepository.rules and dispatches add/remove to whichever engine
 * is current. This removes the special-case calls SettingsViewModel used to
 * make directly into QemuEngine.qmpClient.
 */
package com.excp.podroid.engine

import android.content.Context
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.avf.AvfDiagnostics
import com.excp.podroid.engine.avf.AvfEngine
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class EngineHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val portForwards: PortForwardRepository,
    private val qemuProvider: Provider<QemuEngine>,
    private val avfProvider: Provider<AvfEngine>,
) : VmEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentFlow: MutableStateFlow<VmEngine> = MutableStateFlow(
        pick(runBlocking { settings.getEngineSelectionSnapshot() })
    )
    val currentFlow: StateFlow<VmEngine> = _currentFlow.asStateFlow()
    private val current: VmEngine get() = _currentFlow.value

    /** Last rule set we pushed into the engine, used to compute add/remove diffs. */
    @Volatile private var appliedRules: Set<PortForwardRule> = emptySet()

    init {
        // 1. Backend swap observer — drops the first emit so we don't re-pick
        //    on cold start. Waits for Stopped/Idle/Error so we never kill a
        //    running VM (the Settings UI also disables the chips, but defend
        //    in depth).
        scope.launch {
            settings.engineSelection
                .drop(1)
                .distinctUntilChanged()
                .collect { newSel -> trySwap(newSel) }
        }

        // 2. Live rule-diff observer. Re-subscribed across engine swaps via
        //    flatMapLatest. Combined with state so we only push diffs when
        //    the VM is Running (initial rules go via start()).
        scope.launch {
            currentFlow.flatMapLatest { eng ->
                portForwards.rules.combine(eng.state) { rules, state ->
                    Triple(eng, rules.toSet(), state)
                }
            }.collect { (eng, rules, state) ->
                if (state !is VmState.Running) {
                    appliedRules = emptySet()
                    return@collect
                }
                val added   = rules - appliedRules
                val removed = appliedRules - rules
                for (r in added)   eng.addPortForward(r)
                for (r in removed) eng.removePortForward(r)
                appliedRules = rules
            }
        }
    }

    private fun pick(sel: EngineSelection): VmEngine {
        val probe = AvfDiagnostics.probe(context)
        return when {
            sel == EngineSelection.QEMU -> qemuProvider.get()
            // AVF forced: no capability check; if unsupported, AvfEngine surfaces the error.
            sel == EngineSelection.AVF  -> avfProvider.get()
            probe.featureSupported &&
                probe.managePermissionGranted &&
                probe.customPermissionGranted -> avfProvider.get()
            else -> qemuProvider.get()
        }.also {
            android.util.Log.i(
                TAG,
                "pick: selection=$sel feature=${probe.featureSupported} " +
                    "perms=${probe.managePermissionGranted}/${probe.customPermissionGranted} → ${it.backendId}"
            )
        }
    }

    private suspend fun trySwap(newSel: EngineSelection) {
        // Defensive: wait for swappable state even though Settings UI gates chips.
        currentFlow.value.state.first {
            it is VmState.Stopped || it is VmState.Idle || it is VmState.Error
        }
        val next = pick(newSel)
        if (next === currentFlow.value) return
        android.util.Log.i(TAG, "swap: ${currentFlow.value.backendId} → ${next.backendId}")
        appliedRules = emptySet()
        _currentFlow.value = next
    }

    // ── VmEngine: flows that follow the currently-selected engine ──────────
    override val state: StateFlow<VmState> = currentFlow
        .flatMapLatest { it.state }
        .stateIn(scope, SharingStarted.Eagerly, VmState.Idle)

    override val bootStage: StateFlow<String> = currentFlow
        .flatMapLatest { it.bootStage }
        .stateIn(scope, SharingStarted.Eagerly, "")

    override val consoleText: StateFlow<String> = currentFlow
        .flatMapLatest { it.consoleText }
        .stateIn(scope, SharingStarted.Eagerly, "")

    // ── VmEngine: imperative members — pass through to current engine ──────
    override val terminalSession: TerminalSession? get() = current.terminalSession
    override val backendId: String get() = current.backendId
    override val qmpClient: QmpClient? get() = current.qmpClient
    override var sessionClientDelegate: TerminalSessionClient?
        get() = current.sessionClientDelegate
        set(v) { current.sessionClientDelegate = v }

    override suspend fun start(portForwards: List<PortForwardRule>, config: VmConfig) =
        current.start(portForwards, config)
    override fun stop() = current.stop()
    override fun createTerminalSession(client: TerminalSessionClient) =
        current.createTerminalSession(client)
    override suspend fun addPortForward(rule: PortForwardRule) = current.addPortForward(rule)
    override suspend fun removePortForward(rule: PortForwardRule) = current.removePortForward(rule)

    companion object {
        private const val TAG = "EngineHolder"
    }
}
