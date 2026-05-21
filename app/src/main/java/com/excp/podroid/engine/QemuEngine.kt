/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * QEMU engine for Podroid. Manages the VM lifecycle and exposes three Unix
 * sockets for the terminal layer:
 *
 *   serial.sock   — QEMU -serial (ttyAMA0 in the VM). Boot-log sink only:
 *                   QemuEngine's monitorBootSerial coroutine connects here
 *                   for the lifetime of the VM, streaming kernel messages
 *                   and init-podroid boot stages into console.log + the
 *                   in-memory ring buffer used by BootStageDetector.
 *
 *   terminal.sock — QEMU virtio-console (/dev/hvc0 in the VM). Primary
 *                   terminal I/O. getty runs on hvc0; the podroid-bridge
 *                   binary connects here for bidirectional shell I/O. Fully
 *                   independent of serial.sock, so no socket hand-off.
 *
 *   ctrl.sock     — QEMU virtio-console (/dev/hvc1 in the VM). Resize signal
 *                   channel only. Bridge writes "RESIZE rows cols\n" on
 *                   SIGWINCH (debounced by RESIZE_DEBOUNCE_MS); the resize
 *                   daemon in init-podroid stty's hvc0 to deliver SIGWINCH
 *                   to the foreground TUI inside the VM.
 */
package com.excp.podroid.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.util.LogProxy
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("StaticFieldLeak") // ApplicationContext — lives as long as the process, no leak
@Singleton
class QemuEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: com.excp.podroid.data.repository.SettingsRepository,
) : VmEngine {
    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    override val state: StateFlow<VmState> = _state.asStateFlow()

    private val _consoleText = MutableStateFlow("")
    override val consoleText: StateFlow<String> = _consoleText.asStateFlow()

    private val _bootStage = MutableStateFlow("")

    private var _terminalSession: TerminalSession? = null

    override val terminalSession: TerminalSession? get() = _terminalSession
    override val bootStage: StateFlow<String> = _bootStage.asStateFlow()

    override val backendId: String = "qemu"

    @Volatile
    var process: Process? = null
        private set

    /** Unix socket paths exposed to TerminalViewModel for the bridge binary. */
    val serialSockPath: String get() = "${context.filesDir.absolutePath}/serial.sock"
    val terminalSockPath: String get() = "${context.filesDir.absolutePath}/terminal.sock"
    val ctrlSockPath: String get() = "${context.filesDir.absolutePath}/ctrl.sock"

    /** Boot-monitoring socket connection. Closed when the bridge takes over. */
    @Volatile
    private var bootSocket: LocalSocket? = null

    /**
     * Last QEMU process exit code (null until it exits) + a bounded tail of
     * QEMU's own stderr. Surfaced in the diagnostic export so a crash leaves a
     * forensic trail. Written from start()/the stderr drain, read from the
     * diagnostic thread — the deque is guarded by its own monitor.
     */
    @Volatile
    private var lastExitCode: Int? = null
    private val stderrTail = ArrayDeque<String>()

    private val qmpSocketPath: String get() = "${context.filesDir.absolutePath}/qmp.sock"

    override val qmpClient: QmpClient? by lazy { QmpClient(qmpSocketPath) }

    private var ioScope: CoroutineScope? = null

    /**
     * Single dedicated thread that BOTH fork/exec's QEMU and blocks in
     * waitFor(). libpodroid-launcher sets PR_SET_PDEATHSIG(SIGKILL), which is
     * THREAD-scoped: the kernel SIGKILLs QEMU when the thread that spawned it
     * dies — not when the app process dies. Forking from a Dispatchers.IO pool
     * thread let that thread be recycled (~60s idle keep-alive) once the start()
     * coroutine migrated off it at a delay(), which SIGKILL'd a healthy VM and
     * surfaced as "QEMU crashed (SIGKILL)". A private single-thread executor's
     * thread is never reaped on idle, so it lives exactly as long as QEMU.
     * Shut down in cleanup(), after QEMU has already exited.
     */
    private var qemuDispatcher: ExecutorCoroutineDispatcher? = null

    private var bootStartTime: Long = 0L

    private val consoleBuilder = StringBuilder()
    private val maxConsoleSize = 64 * 1024

    private val bootStageDetector = BootStageDetector(_bootStage, _state) {
        persistBootDuration()
        autoStartBridge()
    }

    /** Set once cleanup() has run for the current VM lifetime; reset by start(). */
    private val cleanedUp = AtomicBoolean(true)

    /**
     * Serializes the start() re-entrancy guard with the Starting state write so
     * two near-simultaneous ACTION_STARTs can't both pass the check and launch a
     * second QEMU (orphaning the first child + leaking its executor). Held only
     * across the guard + state flip — never across proc.waitFor() — so it can't
     * deadlock with cleanup()/stop().
     */
    private val startMutex = Mutex()

    /**
     * Proxy TerminalSessionClient — delegates to whatever real client is set.
     * Lets us create the bridge session at boot-complete time (before the
     * terminal UI exists) and plug in the real ViewModel client later.
     */
    @Volatile
    override var sessionClientDelegate: TerminalSessionClient? = null

    private val proxySessionClient = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) { sessionClientDelegate?.onTextChanged(s) }
        override fun onTitleChanged(s: TerminalSession) { sessionClientDelegate?.onTitleChanged(s) }
        override fun onSessionFinished(s: TerminalSession) { sessionClientDelegate?.onSessionFinished(s) }
        override fun onCopyTextToClipboard(s: TerminalSession, text: String?) { sessionClientDelegate?.onCopyTextToClipboard(s, text) }
        override fun onPasteTextFromClipboard(s: TerminalSession?) { sessionClientDelegate?.onPasteTextFromClipboard(s) }
        override fun onBell(s: TerminalSession) { sessionClientDelegate?.onBell(s) }
        override fun onColorsChanged(s: TerminalSession) { sessionClientDelegate?.onColorsChanged(s) }
        override fun onTerminalCursorStateChange(state: Boolean) { sessionClientDelegate?.onTerminalCursorStateChange(state) }
        override fun setTerminalShellPid(s: TerminalSession, pid: Int) { sessionClientDelegate?.setTerminalShellPid(s, pid) }
        override fun getTerminalCursorStyle(): Int = sessionClientDelegate?.terminalCursorStyle ?: 0
        override fun getTerminalVersionString(): String? = sessionClientDelegate?.terminalVersionString
        override fun logError(tag: String?, msg: String?) = LogProxy.error(tag, TAG, msg)
        override fun logWarn(tag: String?, msg: String?) = LogProxy.warn(tag, TAG, msg)
        override fun logInfo(tag: String?, msg: String?) = LogProxy.info(tag, TAG, msg)
        override fun logDebug(tag: String?, msg: String?) = LogProxy.debug(tag, TAG, msg)
        override fun logVerbose(tag: String?, msg: String?) = LogProxy.verbose(tag, TAG, msg)
        override fun logStackTraceWithMessage(tag: String?, msg: String?, e: Exception?) =
            LogProxy.stackTraceWithMessage(tag, TAG, msg, e)
        override fun logStackTrace(tag: String?, e: Exception?) = LogProxy.stackTrace(tag, TAG, e)
    }

    /**
     * Close the boot-monitoring serial socket. Called from cleanup() when the
     * VM stops; not used as a hand-off trigger any more (bridge has its own
     * socket).
     */
    fun releaseSerial() {
        val sock = bootSocket ?: return
        Log.d(TAG, "Closing boot monitor socket")
        bootSocket = null
        try {
            // Signal EOF to the monitor loop
            sock.shutdownInput()
            sock.shutdownOutput()
            sock.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing boot socket: ${e.message}")
        }
    }

    private fun persistBootDuration() {
        if (bootStartTime == 0L) return
        val duration = System.currentTimeMillis() - bootStartTime
        bootStartTime = 0L
        // ioScope is the same scope launched in start(); always non-null here
        // because we're called from inside it.
        ioScope?.launch { settingsRepository.setLastBootDurationMs(duration) }
    }

    private fun autoStartBridge() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (_terminalSession != null) return@post
            if (_state.value !is VmState.Running && _state.value !is VmState.Starting) return@post

            // Bridge connects to terminal.sock (virtio-console, separate from serial).
            // No handoff with the boot monitor needed — they use different sockets.
            val bridgeExe = File(context.applicationInfo.nativeLibraryDir, "libpodroid-bridge.so")
            if (!bridgeExe.exists()) return@post

            val sess = TerminalSession(
                bridgeExe.absolutePath,
                context.filesDir.absolutePath,
                arrayOf(bridgeExe.absolutePath, terminalSockPath, ctrlSockPath),
                null,
                2000,
                proxySessionClient,
            )
            // Cell pixel dims default to 0 — TerminalView.updateSize() pushes real values once measured.
        sess.updateSize(80, 24, 0, 0)
            _terminalSession = sess
            Log.d(TAG, "Bridge auto-started on terminal.sock")
        }
    }

    override fun createTerminalSession(client: TerminalSessionClient): TerminalSession {
        sessionClientDelegate = client

        // Session already auto-started during boot — just return it
        if (_terminalSession != null) {
            Log.d(TAG, "Returning pre-started terminal session")
            return _terminalSession!!
        }

        // Fallback: create session now
        val bridgeExe = File(context.applicationInfo.nativeLibraryDir, "libpodroid-bridge.so")
        if (!bridgeExe.exists()) {
            throw IllegalStateException("podroid-bridge not found at ${bridgeExe.absolutePath}")
        }

        val sess = TerminalSession(
            bridgeExe.absolutePath,
            context.filesDir.absolutePath,
            arrayOf(bridgeExe.absolutePath, terminalSockPath, ctrlSockPath),
            null,
            2000,
            proxySessionClient,
        )

        // Cell pixel dims default to 0 — TerminalView.updateSize() pushes real values once measured.
        sess.updateSize(80, 24, 0, 0)

        _terminalSession = sess
        Log.d(TAG, "Terminal session created in Qemu singleton")
        return sess
    }

    override suspend fun start(portForwards: List<PortForwardRule>, config: VmConfig) {
        // Atomically check the re-entrancy guard AND claim Starting before any
        // I/O, so two concurrent ACTION_STARTs can't both pass the guard and
        // launch a second QEMU. Held only across the guard + state flip.
        startMutex.withLock {
            if (_state.value is VmState.Starting || _state.value is VmState.Running) {
                Log.w(TAG, "start() called while VM is ${_state.value}, ignoring")
                return
            }
            cleanedUp.set(false)
            bootStartTime = System.currentTimeMillis()
            _state.value = VmState.Starting
        }

        val qemuExe = qemuExecutable() ?: run {
            _state.value = VmState.Error("QEMU binary not found.")
            return
        }

        ensureStorageImage(config.storageSizeGb)

        // consoleBuilder is also appended from the monitor coroutine; guard
        // every access with the same lock cleanup() uses (the @Synchronized
        // object monitor) so a concurrent append can't race this clear().
        synchronized(this) { consoleBuilder.clear() }
        _consoleText.value = ""
        _bootStage.value = "Starting QEMU..."
        // Re-arm the one-shot detector so a Stop → Start cycle's second boot
        // can fire onReady again. Without this the new boot's "Ready!" marker
        // is silently ignored and state stays Starting forever.
        bootStageDetector.reset()

        // Clean up stale sockets from a previous run. qmp.sock must be
        // included — a leftover file from a crashed QEMU prevents the new
        // process from binding its QMP server socket.
        File(serialSockPath).delete()
        File(terminalSockPath).delete()
        File(ctrlSockPath).delete()
        File(qmpSocketPath).delete()

        try {
            val cmd = buildCommand(qemuExe, portForwards, config)
            Log.d(TAG, "Launching QEMU with ${cmd.size} args: $cmd")

            val nativeDir = context.applicationInfo.nativeLibraryDir
            val pb = ProcessBuilder(cmd).directory(context.filesDir)
            pb.environment()["LD_LIBRARY_PATH"] = "$nativeDir:${context.filesDir.absolutePath}"

            // Fork QEMU on a private, long-lived thread (see qemuDispatcher).
            // PR_SET_PDEATHSIG (set by libpodroid-launcher) is thread-scoped, so
            // the spawning thread must outlive QEMU — a Dispatchers.IO pool
            // thread does not. waitFor() below runs on this same thread.
            val dispatcher = Executors.newSingleThreadExecutor { r ->
                Thread(r, "podroid-qemu").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            qemuDispatcher = dispatcher

            val proc = withContext(dispatcher) { pb.start() }
            process = proc
            _bootStage.value = "Booting kernel..."

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            ioScope = scope

            // Drain QEMU's own stderr (not serial — just QEMU startup messages)
            scope.launch {
                try {
                    val buf = ByteArray(4096)
                    while (isActive) {
                        val n = proc.errorStream.read(buf)
                        if (n < 0) break
                        val chunk = String(buf, 0, n).trimEnd()
                        Log.d("PodroidVM-err", chunk.take(300))
                        recordStderr(chunk)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Stderr drain ended: ${e.message}")
                }
            }

            // Boot monitor — connects to serial.sock once QEMU creates it
            scope.launch { monitorBootSerial(proc) }

            val startMs = System.currentTimeMillis()
            var socketsReady = false
            while (System.currentTimeMillis() - startMs < SOCKET_READY_TIMEOUT_MS) {
                if (!proc.isAlive) {
                    val exitCode = proc.exitValue()
                    lastExitCode = exitCode
                    Log.e(TAG, "QEMU died during startup, exit code: $exitCode")
                    _state.value = VmState.Error("QEMU exited with code $exitCode")
                    cleanup()
                    return
                }
                if (File(serialSockPath).exists() && File(qmpSocketPath).exists()) {
                    Log.d(TAG, "QEMU sockets ready after ${System.currentTimeMillis() - startMs}ms")
                    socketsReady = true
                    break
                }
                delay(200)
            }
            if (!socketsReady) {
                // Don't destroyForcibly()+throw here: that kills QEMU from this
                // IO thread and skips the dedicated-thread waitFor() reap. Set
                // the error, signal a graceful stop, and fall through to the
                // same waitFor() teardown the happy path uses (it reaps on the
                // podroid-qemu thread). The guard below preserves this message.
                Log.e(TAG, "Socket timeout — QEMU sockets not ready after ${SOCKET_READY_TIMEOUT_MS}ms")
                _state.value =
                    VmState.Error("QEMU failed to create sockets within ${SOCKET_READY_TIMEOUT_MS / 1000}s")
                proc.destroy()
            } else {
                // State stays Starting — boot monitor will set Running when "Ready!" is detected
                scope.launch {
                    delay(60_000)
                    // Only promote a still-Starting, still-alive VM. Without the
                    // isAlive/cleanedUp check a VM that died (or stopped) in the
                    // window after delay() resumes could be flipped to a phantom
                    // Running and have the bridge re-armed after exit.
                    if (_state.value is VmState.Starting &&
                        process?.isAlive == true && !cleanedUp.get()) {
                        Log.w(TAG, "Boot timeout fallback → forcing Running state")
                        _bootStage.value = "Ready"
                        persistBootDuration()
                        _state.value = VmState.Running
                        autoStartBridge()
                    }
                }
            }

            // Block until QEMU exits, ON THE SAME dedicated thread that fork'd
            // it, so PR_SET_PDEATHSIG never fires while QEMU is healthy.
            val exitCode = withContext(dispatcher) { proc.waitFor() }
            lastExitCode = exitCode
            Log.d(TAG, "QEMU exited: $exitCode")
            val priorError = _state.value as? VmState.Error
            cleanup()
            // If the socket-timeout branch already set a specific Error, keep it
            // rather than overwriting with the generic signal-exit message.
            _state.value = when {
                priorError != null -> priorError
                exitCode == 0 -> VmState.Stopped
                else -> VmState.Error(formatExitError(exitCode, config.storageAccessEnabled))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start QEMU", e)
            _state.value = VmState.Error(e.message ?: "Unknown error")
            cleanup()
        }
    }

    /**
     * Connects to serial.sock and streams kernel + init-podroid output for the
     * lifetime of the VM. Writes the bytes to console.log and pushes the
     * latest tail into the consoleText flow used by the diagnostic exporter
     * and the boot-stage detector. Stops when:
     *   - releaseSerial() is called during cleanup() (VM stopping), or
     *   - QEMU itself exits, or
     *   - The coroutine is cancelled.
     *
     * Writes all output to console.log for the test-deploy.sh validator and
     * publishes boot stage updates to the UI.
     */
    private suspend fun monitorBootSerial(proc: Process) {
        val sockFile = File(serialSockPath)

        // Share the same socket-readiness deadline used by start() so a slow
        // QEMU startup doesn't get inconsistent grace periods.
        var waited = 0L
        while (waited < SOCKET_READY_TIMEOUT_MS && !sockFile.exists() && proc.isAlive) {
            delay(100)
            waited += 100
        }
        if (!sockFile.exists()) {
            Log.w(TAG, "serial.sock not found after ${waited}ms — boot detection disabled")
            return
        }

        val sock = LocalSocket()
        try {
            sock.connect(LocalSocketAddress(serialSockPath, LocalSocketAddress.Namespace.FILESYSTEM))
            bootSocket = sock
            Log.d(TAG, "Boot monitor connected to serial.sock")
        } catch (e: Exception) {
            Log.w(TAG, "Boot monitor could not connect to serial.sock: ${e.message}")
            sock.close()
            return
        }

        val logFile = File(context.filesDir, "console.log")
        logFile.delete()

        // Streaming UTF-8 decoder — kept across read() calls so multi-byte
        // sequences split between chunks decode correctly instead of
        // surfacing as U+FFFD replacement chars in the in-memory console
        // and breaking BootStageDetector matches.
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val byteBuf = ByteBuffer.allocate(8192)
        val charBuf = CharBuffer.allocate(8192)

        try {
            FileOutputStream(logFile, false).use { logOut ->
                val readBuf = ByteArray(4096)
                val input = sock.inputStream
                while (true) {
                    val n = try {
                        input.read(readBuf)
                    } catch (_: Exception) {
                        break // socket closed by releaseSerial() or VM exit
                    }
                    if (n < 0) break

                    // Raw bytes go straight to disk for the diagnostic log.
                    logOut.write(readBuf, 0, n)
                    logOut.flush()

                    // Feed the decoder; carry leftover undecoded bytes via compact().
                    byteBuf.put(readBuf, 0, n)
                    byteBuf.flip()
                    decoder.decode(byteBuf, charBuf, false)
                    byteBuf.compact()
                    charBuf.flip()
                    if (charBuf.hasRemaining()) {
                        // Guard consoleBuilder with the same lock cleanup()/start()
                        // use, so a concurrent clear() can't corrupt the append/trim.
                        synchronized(this) {
                            consoleBuilder.append(charBuf)
                            if (consoleBuilder.length > maxConsoleSize) {
                                consoleBuilder.delete(0, consoleBuilder.length - maxConsoleSize)
                            }
                            _consoleText.value = consoleBuilder.toString()
                        }
                    }
                    charBuf.clear()

                    bootStageDetector.feed(readBuf, n)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Boot monitor ended: ${e.message}")
        } finally {
            try { sock.close() } catch (_: Exception) {}
            bootSocket = null
            Log.d(TAG, "Boot monitor disconnected — bridge can now connect")
        }
    }

    /**
     * Signal the VM to stop. Destroys the QEMU process; the start() coroutine's
     * proc.waitFor() will fall through and run cleanup() + set the final state.
     * Avoids the historical race where stop() and the start() exit-path both
     * called cleanup() concurrently and fought over _state.value.
     */
    override fun stop() {
        val proc = process ?: return
        proc.destroy()
        try {
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(2, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
            proc.destroyForcibly()
        }
    }

    override suspend fun addPortForward(rule: PortForwardRule) {
        if (_state.value !is VmState.Running) return
        val qmp = qmpClient ?: return
        runCatching { qmp.addPortForward(rule.hostPort, rule.guestPort, rule.protocol) }
            .onFailure { Log.w(TAG, "QMP addPortForward failed for $rule", it) }
    }

    override suspend fun removePortForward(rule: PortForwardRule) {
        if (_state.value !is VmState.Running) return
        val qmp = qmpClient ?: return
        runCatching { qmp.removePortForward(rule.hostPort, rule.protocol) }
            .onFailure { Log.w(TAG, "QMP removePortForward failed for $rule", it) }
    }

    @Synchronized
    private fun cleanup() {
        if (cleanedUp.getAndSet(true)) return
        releaseSerial()
        ioScope?.cancel()
        ioScope = null
        // QEMU has already exited by the time cleanup() runs, so retiring its
        // spawning thread here cannot trip PR_SET_PDEATHSIG against a live VM.
        qemuDispatcher?.close()
        qemuDispatcher = null
        process = null
        _terminalSession?.finishIfRunning()
        _terminalSession = null
        sessionClientDelegate = null
        consoleBuilder.clear()
        _consoleText.value = ""
        File(serialSockPath).delete()
        File(terminalSockPath).delete()
        File(ctrlSockPath).delete()
        _bootStage.value = ""
    }

    private fun buildCommand(
        qemuExe: File,
        portForwards: List<PortForwardRule>,
        config: VmConfig,
    ): List<String> {
        val args = mutableListOf<String>()
        val userQemuExtras = config.qemuExtraArgs.trim()
        val userKernelExtras = config.kernelExtraCmdline.trim()

        args += "-M"; args += "virt,gic-version=3"
        // pauth-impdef swaps QEMU's slow QARMA5 PAuth for a fast non-crypto impl (≤50% TCG win on aarch64-on-aarch64).
        args += "-cpu"; args += "max,pauth-impdef=on"
        val tbSizeMb = if (config.ramMb >= 2048) 512 else 256
        // thread=multi: one host thread per vCPU; larger tb-size reduces re-translation for JIT-heavy guests.
        args += "-accel"; args += "tcg,thread=multi,tb-size=$tbSizeMb"
        args += "-smp"; args += "${config.cpus}"
        args += "-m";   args += "${config.ramMb}"

        val kernelPath = File(context.filesDir, "vmlinuz-virt")
        val initrdPath = File(context.filesDir, "initrd.img")

        if (kernelPath.exists()) {
            args += "-kernel"; args += kernelPath.absolutePath
            val cmdline = buildString {
                // mitigations=off: speculative-exec attacks don't cross the TCG ISA boundary; 5–15% gain.
                append("console=ttyAMA0 mitigations=off")
                if (userKernelExtras.isNotEmpty()) append(" ").append(userKernelExtras)
                append(" androidip=").append(config.androidIp)
                if (config.sshEnabled) append(" ssh=1")
                append(" podroid.x11.dpi=").append(config.x11Dpi)
            }
            args += "-append"; args += cmdline
        } else {
            Log.w(TAG, "Kernel not found!")
        }

        if (initrdPath.exists()) {
            args += "-initrd"; args += initrdPath.absolutePath
        } else {
            Log.w(TAG, "Initrd not found!")
        }

        val storagePath = File(context.filesDir, "storage.img")
        if (storagePath.exists()) {
            // Single dedicated iothread for the writable disk. Multi-iothread
            // fan-out via `iothread-vq-mapping` requires `-device <full-json>`
            // form (the keyval parser cannot supply array-typed properties),
            // and on TCG-emulated guests the perf win is marginal vs the
            // refactor cost. Stick with one iothread, num-queues==vCPUs.
            args += "-object"; args += "iothread,id=iothread0"
            args += "-device"; args += "virtio-blk-pci,drive=drive1,num-queues=${config.cpus},iothread=iothread0"
            // discard=unmap + detect-zeroes=unmap: as the guest fstrim's the
            // overlay, hand the punched holes back to the host filesystem so
            // storage.img stops growing unbounded after long-term container
            // churn. detect-zeroes converts all-zero writes (e.g. mkfs.ext4's
            // erasure pass) into discards too.
            args += "-drive";  args += "file=${storagePath.absolutePath},if=none,id=drive1,format=raw,cache=writeback,aio=threads,discard=unmap,detect-zeroes=unmap"
        }

        val rootfsImg = File(context.filesDir, "alpine-rootfs.squashfs")
        if (rootfsImg.exists()) {
            // Dedicated iothread for the read-only squashfs so its decompression
            // reads don't queue behind storage.img writes on iothread0.
            args += "-object"; args += "iothread,id=iothread1"
            args += "-device"; args += "virtio-blk-pci,drive=drive2,num-queues=${config.cpus},iothread=iothread1"
            args += "-drive";  args += "file=${rootfsImg.absolutePath},if=none,id=drive2,format=raw,readonly=on,cache=writeback,aio=threads"
        }

        // Downloads folder sharing via virtio-9p
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            config.storageAccessEnabled &&
            android.os.Environment.isExternalStorageManager() &&
            downloadsDir.exists()) {
            // security_model=mapped-xattr keeps QEMU's 9p worker out of the
            // chmod/chown syscall path that has triggered SIGILL on Tensor /
            // ARMv9.2 PAC devices (Pixel 10) — uid/gid/mode are stored as
            // xattrs on the host file instead of being applied directly.
            // Falls back gracefully on filesystems without xattr support.
            args += "-fsdev"
            args += "local,id=fsdev0,path=${downloadsDir.absolutePath},security_model=none"
            args += "-device"
            args += "virtio-9p-pci,fsdev=fsdev0,mount_tag=downloads"
        }

        val netdevArg = buildString {
            append("user,id=net0,ipv6=off")
            for (rule in portForwards) {
                append(",hostfwd=${rule.protocol}::${rule.hostPort}-:${rule.guestPort}")
            }
        }
        args += "-netdev"; args += netdevArg
        args += "-device"; args += "virtio-net-pci,netdev=net0,romfile="

        // ── Serial (ttyAMA0) → boot log sink only; kernel msgs + init boot stages ─
        args += "-serial"; args += "unix:$serialSockPath,server,nowait"

        // ── virtio-console bus ────────────────────────────────────────────────
        // hvc0 = primary terminal (getty runs here; bridge connects to terminal.sock)
        // hvc1 = control channel (init daemon reads RESIZE messages from ctrl.sock)
        args += "-device";  args += "virtio-serial-pci"
        args += "-chardev"; args += "socket,id=term0,path=$terminalSockPath,server=on,wait=off"
        args += "-device";  args += "virtconsole,chardev=term0,name=org.podroid.term"
        args += "-chardev"; args += "socket,id=ctrl0,path=$ctrlSockPath,server=on,wait=off"
        args += "-device";  args += "virtconsole,chardev=ctrl0,name=org.podroid.ctrl"

        args += "-display"; args += "none"
        args += "-qmp";     args += "unix:$qmpSocketPath,server,nowait"

        // User extras appended last so later -cpu / -accel overrides earlier ones.
        if (userQemuExtras.isNotEmpty()) {
            args += userQemuExtras.split(Regex("\\s+"))
        }

        // Wrap QEMU in podroid-launcher when available — it sets PR_SET_PDEATHSIG
        // so QEMU dies with the app on uninstall/OOM/force-stop instead of leaking
        // as an orphan under PPID=1. If the launcher is missing (older deploys),
        // fall back to spawning QEMU directly.
        val launcher = File(context.applicationInfo.nativeLibraryDir, "libpodroid-launcher.so")
        return if (launcher.exists()) {
            listOf(launcher.absolutePath, qemuExe.absolutePath) + args
        } else {
            listOf(qemuExe.absolutePath) + args
        }
    }

    private fun ensureStorageImage(storageSizeGb: Int) {
        val storageFile = File(context.filesDir, "storage.img")
        val desiredBytes = storageSizeGb.toLong() * 1024L * 1024L * 1024L

        if (storageFile.exists()) {
            if (storageFile.length() == desiredBytes) return
            Log.d(TAG, "storage.img size mismatch — recreating")
            storageFile.delete()
        }

        try {
            java.io.RandomAccessFile(storageFile, "rw").use { it.setLength(desiredBytes) }
            Log.d(TAG, "Created storage.img (${storageSizeGb}GB)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create storage.img", e)
        }
    }

    private fun qemuExecutable(): File? {
        val exe = File(context.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")
        return if (exe.exists()) exe else null
    }

    override fun diagnosticsReport(): String = buildString {
        appendLine("last process exit code: ${lastExitCode?.toString() ?: "(still running / not yet exited)"}")
        val tail = synchronized(stderrTail) { stderrTail.toList() }
        if (tail.isEmpty()) {
            appendLine("qemu stderr: (none captured)")
        } else {
            appendLine("qemu stderr (last ${tail.size} line(s)):")
            tail.forEach { appendLine("  $it") }
        }
    }

    /** Keep the most recent [STDERR_TAIL_LINES] non-blank stderr lines. */
    private fun recordStderr(chunk: String) {
        if (chunk.isBlank()) return
        synchronized(stderrTail) {
            for (line in chunk.lineSequence()) {
                if (line.isBlank()) continue
                stderrTail.addLast(line)
                while (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
            }
        }
    }

    /**
     * Decode a QEMU process exit code into a user-facing error string. Process
     * exit codes ≥128 are POSIX-encoded signals (128 + signum). On some devices
     * (notably Tensor / ARMv9.2 PAC) virtio-9p crashes the QEMU worker with
     * SIGILL — surface that as a Downloads-sharing hint rather than "Exit 132".
     */
    private fun formatExitError(exitCode: Int, storageSharingEnabled: Boolean): String {
        if (exitCode < 128) return "QEMU exited with code $exitCode"
        val sig = exitCode - 128
        val name = when (sig) {
            4  -> "SIGILL"
            6  -> "SIGABRT"
            7  -> "SIGBUS"
            8  -> "SIGFPE"
            9  -> "SIGKILL"
            11 -> "SIGSEGV"
            13 -> "SIGPIPE"
            15 -> "SIGTERM"
            31 -> "SIGSYS"
            else -> "signal $sig"
        }
        // SIGILL/SIGBUS/SIGSEGV with Downloads sharing on points at virtio-9p
        // on PAC-enforcing kernels — by far the most common crash path here.
        val crashSignals = setOf(4, 7, 11)
        return if (storageSharingEnabled && sig in crashSignals) {
            "QEMU crashed ($name). Downloads sharing is unstable on this device — disable it in Settings and try again."
        } else {
            "QEMU crashed ($name)"
        }
    }

    companion object {
        private const val TAG = "QemuEngine"
        private const val STDERR_TAIL_LINES = 40

        /** Shared deadline for both start()'s socket-readiness loop and monitorBootSerial's wait. */
        private const val SOCKET_READY_TIMEOUT_MS = 10_000L
    }
}
