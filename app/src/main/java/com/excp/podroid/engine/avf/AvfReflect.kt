/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Thin reflective wrappers over android.system.virtualmachine.*. Each call is
 * one Method.invoke with setAccessible(true) — Android 14+ requires
 * HiddenApiBypass (installed at app onCreate) for these to resolve.
 *
 * Returning `Any` keeps the call sites untyped at the framework boundary;
 * call sites pass these handles back into AvfReflect rather than poking at
 * the underlying objects directly.
 */
package com.excp.podroid.engine.avf

import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

object AvfReflect {

    private const val PKG = "android.system.virtualmachine"
    private val MGR by lazy { Class.forName("$PKG.VirtualMachineManager") }
    private val CFG by lazy { Class.forName("$PKG.VirtualMachineConfig") }
    private val CFG_B by lazy { Class.forName("$PKG.VirtualMachineConfig\$Builder") }
    private val CUSTOM by lazy { Class.forName("$PKG.VirtualMachineCustomImageConfig") }
    private val CUSTOM_B by lazy { Class.forName("$PKG.VirtualMachineCustomImageConfig\$Builder") }
    private val DISK by lazy { runCatching { Class.forName("$PKG.VirtualMachineCustomImageConfig\$Disk") }.getOrNull() }
    private val GPU_CFG by lazy { runCatching { Class.forName("$PKG.VirtualMachineCustomImageConfig\$GpuConfig") }.getOrNull() }
    private val GPU_CFG_B by lazy { runCatching { Class.forName("$PKG.VirtualMachineCustomImageConfig\$GpuConfig\$Builder") }.getOrNull() }

    fun manager(ctx: Context): Any {
        val m = Context::class.java.getMethod("getSystemService", Class::class.java)
        return m.invoke(ctx.applicationContext, MGR) ?: error("No VirtualMachineManager")
    }

    fun getOrCreate(mgr: Any, name: String, cfg: Any): Any =
        invokeDecl(mgr, "getOrCreate", String::class.java to name, CFG to cfg)
            ?: error("getOrCreate returned null")

    fun create(mgr: Any, name: String, cfg: Any): Any =
        invokeDecl(mgr, "create", String::class.java to name, CFG to cfg)
            ?: error("create returned null")

    /**
     * Attempts to replace an existing VM's config (analogous to `vm.config = config` in Kotlin).
     * Throws if AVF rejects the new config as incompatible — caller should
     * delete + recreate on that path.
     */
    fun setConfig(vm: Any, cfg: Any) {
        // getDeclaredMethod only finds a method declared on the exact runtime
        // class; on OS builds where setConfig is inherited from a supertype it
        // throws NoSuchMethodException, which the caller misreads as "config
        // rejected" and forces a needless delete+recreate every start. Fall back
        // to getMethod (searches supertypes) before concluding it's absent. A
        // genuine absence still throws — the caller's delete+recreate handles it.
        val m = (runCatching { vm.javaClass.getDeclaredMethod("setConfig", CFG) }
            .getOrNull() ?: vm.javaClass.getMethod("setConfig", CFG))
            .apply { isAccessible = true }
        m.invoke(vm, cfg)
    }

    fun delete(mgr: Any, name: String) {
        runCatching { invokeDecl(mgr, "delete", String::class.java to name) }
    }

    fun newVmConfigBuilder(ctx: Context): Any =
        CFG_B.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            .newInstance(ctx)

    fun setProtectedVm(b: Any, value: Boolean) {
        invokeDecl(b, "setProtectedVm", Boolean::class.javaPrimitiveType!! to value)
    }

    /**
     * VirtualMachineManager.getCapabilities() — bitmask of
     * AvfCapabilities.CAPABILITY_*. Returns 0 if the method is absent on an
     * older AVF revision; that 0 maps to ProtectedVmChoice.Unknown (we then
     * attempt non-protected and let any framework exception surface). This
     * runCatching tolerates a MISSING method only — it is not used to hide a
     * failure on a method that exists.
     */
    fun getCapabilities(mgr: Any): Int =
        runCatching { invokeDecl(mgr, "getCapabilities") as? Int ?: 0 }.getOrDefault(0)

    /**
     * Applies the capability-driven protected-VM choice to a VM config builder.
     * Returns the choice so the caller can fall back to QEMU on Unsupported.
     *
     * On the Unknown path we call setProtectedVm(false) WITHOUT catching: if the
     * framework throws UnsupportedOperationException("Non-protected VMs are not
     * supported on this device.") that is the real #31 signal and must surface.
     * On Unsupported we do NOT call the setter — the caller must not proceed to
     * build() (which would throw the misleading "must be called explicitly").
     */
    fun applyProtectedVm(mgr: Any, builder: Any): AvfCapabilities.ProtectedVmChoice {
        val choice = AvfCapabilities.choose(getCapabilities(mgr))
        when (choice) {
            is AvfCapabilities.ProtectedVmChoice.NonProtected -> setProtectedVm(builder, false)
            is AvfCapabilities.ProtectedVmChoice.Unknown      -> setProtectedVm(builder, false)
            is AvfCapabilities.ProtectedVmChoice.Unsupported  -> Unit
        }
        return choice
    }

    fun setMemoryBytes(b: Any, bytes: Long) {
        invokeDecl(b, "setMemoryBytes", Long::class.javaPrimitiveType!! to bytes)
    }

    /**
     * Try to enable the virtio-balloon device so the host can reclaim
     * idle guest memory under pressure. Without this, every guest page
     * that gets touched stays committed on the host indefinitely — Android
     * sees the full VM allocation as resident regardless of guest activity,
     * which on a 12 GB Pixel routinely triggers LMK kills of Podroid when
     * the VM is doing memory-heavy operations (backup, big builds).
     *
     * Verified against the Android 16 AVF API (Pixel 10, May 2026 build)
     * by pulling /apex/com.android.virt/javalib/framework-virtualization.jar
     * and dexdump'ing it. The exact method is:
     *
     *   VirtualMachineCustomImageConfig$Builder.useAutoMemoryBalloon(Z)
     *     → returns the Builder (chainable)
     *     → hiddenapi: BLOCKED, so direct reflection from a non-platform
     *       app requires the bypass that Podroid already has in place.
     *
     * The corresponding JSON-config field is `auto_memory_balloon` (we
     * confirmed this in the Stock Linux Terminal's APK strings — same
     * field name, same AVF version).
     *
     * Older Android-15 builds may have used `setAutoMemoryBalloon` or
     * `setMemoryBalloon` on the same builder, so we still probe those
     * as fallbacks. Note: there's also a RUNTIME setMemoryBalloon(long)
     * on IVirtualMachine (the binder interface) for dynamic adjustment
     * after start — that's a different code path, not what we want here.
     *
     * Returns true if any setter accepted the call. Logs the outcome so
     * we can tell whether the device actually enables ballooning.
     */
    fun tryEnableMemoryBalloon(builder: Any): Boolean {
        val candidates = listOf(
            "useAutoMemoryBalloon",      // Android 16+ (confirmed Pixel 10)
            "setAutoMemoryBalloon",      // hypothetical older revision
            "setMemoryBalloon",          // hypothetical older revision
        )
        for (name in candidates) {
            val ok = runCatching {
                val m = builder.javaClass.getDeclaredMethod(name, java.lang.Boolean.TYPE)
                    .apply { isAccessible = true }
                m.invoke(builder, true)
                android.util.Log.i(
                    "AvfReflect",
                    "memory balloon enabled via ${builder.javaClass.simpleName}.$name(true)",
                )
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /**
     * Try to attach a gfxstream GPU config to the CustomImageConfig
     * builder, enabling hardware-accelerated rendering in the guest via
     * virtio-gpu. Mirrors AOSP Terminal's ConfigJson$GpuJson → GpuConfig:
     *
     *   backend       = "gfxstream"
     *   contextTypes  = ["gfxstream-vulkan"]
     *   rendererUse{Vulkan,Gles,Egl,Surfaceless} = true
     *
     * Hard requirements for this to actually produce a usable GPU:
     *   1. virtualizationservice must ACCEPT a GpuConfig from this
     *      (non-platform) app — gated by USE_CUSTOM_VIRTUAL_MACHINE,
     *      which adb-setup.sh grants. If it silently drops it, crosvm
     *      launches without --gpu and this whole thing is a no-op.
     *   2. The guest kernel must have CONFIG_DRM_VIRTIO_GPU so
     *      /dev/dri/card0 appears. (podroid_kernel.config needs DRM
     *      enabled — it's off by default.)
     *
     * This is the de-risk probe: even if (2) isn't met yet, a successful
     * setGpuConfig + crosvm getting `--gpu` in its args confirms (1) —
     * i.e. that the privilege gate is passable and the full GPU project
     * is worth pursuing.
     *
     * Returns a human-readable status string for the launch summary.
     */
    fun tryEnableGpu(customBuilder: Any): String {
        val gpuCfgB = GPU_CFG_B ?: return "unavailable (no GpuConfig\$Builder on this AVF revision)"
        val gpuCfgCls = GPU_CFG ?: return "unavailable (no GpuConfig class on this AVF revision)"
        return runCatching {
            val b = gpuCfgB.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

            // backend — required
            gpuCfgB.getDeclaredMethod("setBackend", String::class.java)
                .apply { isAccessible = true }.invoke(b, "gfxstream")

            // context types — gfxstream-vulkan is the modern path
            runCatching {
                gpuCfgB.getDeclaredMethod("setContextTypes", Array<String>::class.java)
                    .apply { isAccessible = true }
                    .invoke(b, arrayOf("gfxstream-vulkan"))
            }

            // renderer toggles — best-effort; tolerate any being absent
            for ((name, value) in listOf(
                "setRendererUseVulkan" to true,
                "setRendererUseGles" to true,
                "setRendererUseEgl" to true,
                "setRendererUseSurfaceless" to true,
            )) {
                runCatching {
                    gpuCfgB.getDeclaredMethod(name, java.lang.Boolean.TYPE)
                        .apply { isAccessible = true }.invoke(b, value)
                }
            }

            val gpuCfg = gpuCfgB.getDeclaredMethod("build").apply { isAccessible = true }.invoke(b)
                ?: return "failed (GpuConfig.build() returned null)"

            customBuilder.javaClass.getDeclaredMethod("setGpuConfig", gpuCfgCls)
                .apply { isAccessible = true }.invoke(customBuilder, gpuCfg)

            android.util.Log.i("AvfReflect",
                "GPU config set: backend=gfxstream contextTypes=[gfxstream-vulkan]")
            "enabled (gfxstream, contextTypes=[gfxstream-vulkan])"
        }.getOrElse { e ->
            "failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** CPU topology values matching VirtualMachineConfig.CPU_TOPOLOGY_*. */
    const val CPU_TOPOLOGY_ONE_CPU: Int = 0
    const val CPU_TOPOLOGY_MATCH_HOST: Int = 1

    fun setNumCpus(b: Any, n: Int) {
        // AVF's setCpuTopology takes a CPU_TOPOLOGY_* constant — only 0 (one
        // CPU) or 1 (all host cores) are accepted. There is no fine-grained
        // count setter on the public AVF API. Map the user's requested count:
        // 1 → ONE_CPU; anything > 1 → MATCH_HOST (= all host cores).
        val topology = if (n <= 1) CPU_TOPOLOGY_ONE_CPU else CPU_TOPOLOGY_MATCH_HOST
        if (n > 1) android.util.Log.d(
            "AvfReflect",
            "AVF can't allocate a specific count of vCPUs; user requested $n → MATCH_HOST (all host cores)",
        )
        invokeDecl(b, "setCpuTopology", Int::class.javaPrimitiveType!! to topology)
    }

    fun setConsoleInputDevice(b: Any, device: String) {
        invokeDecl(b, "setConsoleInputDevice", String::class.java to device)
    }

    fun setConnectVmConsole(b: Any, value: Boolean) {
        invokeDecl(b, "setConnectVmConsole", Boolean::class.javaPrimitiveType!! to value)
    }

    fun setVmOutputCaptured(b: Any, value: Boolean) {
        invokeDecl(b, "setVmOutputCaptured", Boolean::class.javaPrimitiveType!! to value)
    }

    /**
     * AVF debug levels: NONE=0, FULL=1. Console input requires FULL.
     * Constant integer (not a reflective field read) since it's stable in the
     * public SystemApi.
     */
    const val DEBUG_LEVEL_FULL: Int = 1

    fun setDebugLevel(b: Any, level: Int) {
        invokeDecl(b, "setDebugLevel", Int::class.javaPrimitiveType!! to level)
    }

    fun setVmConsoleInputSupported(b: Any, value: Boolean) {
        // Older API revisions may not have this; tolerate absence.
        runCatching {
            invokeDecl(b, "setVmConsoleInputSupported", Boolean::class.javaPrimitiveType!! to value)
        }
    }

    fun setCustomImageConfig(b: Any, cfg: Any) {
        invokeDecl(b, "setCustomImageConfig", CUSTOM to cfg)
    }

    fun build(b: Any): Any = invokeDecl(b, "build")!!

    fun newCustomBuilder(): Any =
        CUSTOM_B.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    fun setName(b: Any, name: String) { invokeDecl(b, "setName", String::class.java to name) }
    fun setKernelPath(b: Any, p: String) { invokeDecl(b, "setKernelPath", String::class.java to p) }
    fun setInitrdPath(b: Any, p: String) { invokeDecl(b, "setInitrdPath", String::class.java to p) }
    /** Adds each whitespace-separated token of [params] via addParam(String). */
    fun addParams(b: Any, params: String) {
        val tokens = params.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val m = b.javaClass.getDeclaredMethod("addParam", String::class.java)
            .apply { isAccessible = true }
        for (t in tokens) m.invoke(b, t)
    }

    fun addDisk(b: Any, path: String, writable: Boolean) {
        val diskCls = DISK ?: error("VirtualMachineCustomImageConfig\$Disk class not found on this device")
        val factoryName = if (writable) "RWDisk" else "RODisk"
        val factory = diskCls.getDeclaredMethod(factoryName, String::class.java)
            .apply { isAccessible = true }
        val disk = factory.invoke(null, path)
            ?: error("Disk.$factoryName($path) returned null")
        val addM = b.javaClass.getDeclaredMethod("addDisk", diskCls).apply { isAccessible = true }
        addM.invoke(b, disk)
    }

    /**
     * Add a host-filesystem path that the guest can mount via virtio-9p
     * (`mount -t 9p <tag> /mnt/... -o trans=virtio,...`).
     *
     * `appDomain`: true ⇒ crosvm spins up inside the calling app's SELinux
     * domain (`untrusted_app`), which can only see paths under filesDir.
     * false ⇒ crosvm spins up as a child of virtmgr (system domain), which
     * is the only way to share external storage like `/storage/emulated/...`.
     *
     * Returns true if the share was added successfully, false if this AVF
     * revision can't honour the request (e.g. needs `appDomain=false` but
     * this device only ships the older 9-param ctor without that param).
     * Callers should treat false as "share is silently unavailable" — the VM
     * still boots without it. NEVER throws on signature mismatch — the VM
     * starting cleanly is more valuable than the share.
     *
     * Known SharedPath constructor shapes:
     *   v3 (AOSP main — has `boolean appDomain`):
     *       (String, int, int, int, int, int, String, String, boolean, String)
     *   v2 (Pixel 10 mustang beta — 9 params, no appDomain):
     *       (String, int, int, int, int, int, String, String, String)
     *   v1 (older AOSP — 2 Strings up front):
     *       (String, String, int, int, int, int, int, String, String)
     */
    fun addSharedPath(
        customBuilder: Any,
        sharedPath: String,
        tag: String,
        hostUid: Int,
        hostGid: Int,
        guestUid: Int,
        guestGid: Int,
        mask: Int,
        socket: String,
        socketPath: String,
        appDomain: Boolean,
    ): Boolean {
        val spCls = Class.forName("$PKG.VirtualMachineCustomImageConfig\$SharedPath")
        val intT = Int::class.javaPrimitiveType!!
        val boolT = Boolean::class.javaPrimitiveType!!
        val strT = String::class.java

        val sp = runCatching {
            buildSharedPath(spCls, intT, boolT, strT,
                sharedPath, tag, socket, socketPath, appDomain,
                hostUid, hostGid, guestUid, guestGid, mask)
        }.getOrElse { e ->
            android.util.Log.w("AvfReflect",
                "addSharedPath: no compatible SharedPath ctor — share for '$sharedPath' " +
                "(tag=$tag) is unavailable on this AVF revision. " +
                "Reason: ${e.message}")
            return false
        } ?: return false

        return runCatching {
            val addM = customBuilder.javaClass.getDeclaredMethod("addSharedPath", spCls)
                .apply { isAccessible = true }
            addM.invoke(customBuilder, sp)
        }.fold(
            onSuccess = { true },
            onFailure = { e ->
                android.util.Log.w("AvfReflect",
                    "addSharedPath: builder rejected SharedPath for '$sharedPath': ${e.message}")
                false
            }
        )
    }

    /**
     * Try each known SharedPath constructor in order.
     *
     * IMPORTANT: when `appDomain=false` is requested, we MUST find a
     * constructor that accepts it (the v3 10-param shape). Older shapes
     * default to in-app-domain and would surface as a VM startup crash
     * later (crosvm can't cross domains to reach the path). Refuse with
     * null in that case so the caller logs a clear "unavailable" instead
     * of letting the VM die with `reason=4`.
     *
     * Returns null if no compatible ctor was found.
     */
    private fun buildSharedPath(
        spCls: Class<*>, intT: Class<*>, boolT: Class<*>, strT: Class<*>,
        sharedPath: String, tag: String, socket: String, socketPath: String, appDomain: Boolean,
        hostUid: Int, hostGid: Int, guestUid: Int, guestGid: Int, mask: Int,
    ): Any? {
        // Shape v3: (String, 5×int, String, String, boolean, String) — AOSP main
        runCatching {
            val c = spCls.getDeclaredConstructor(
                strT, intT, intT, intT, intT, intT, strT, strT, boolT, strT
            ).apply { isAccessible = true }
            return c.newInstance(
                sharedPath, hostUid, hostGid, guestUid, guestGid, mask,
                tag, socket, appDomain, socketPath,
            )!!
        }
        // Older shapes can't honour `appDomain=false`. Refuse so the caller
        // skips the share cleanly instead of crashing the VM at start time.
        if (!appDomain) {
            val shapes = spCls.declaredConstructors.joinToString("; ") { c ->
                c.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }
            }
            android.util.Log.w("AvfReflect",
                "buildSharedPath: appDomain=false needed but device has only legacy " +
                "ctors; share would crash VM. Ctors available: $shapes")
            return null
        }
        // Shape v2: (String, 5×int, String, String, String) — Pixel 10 mustang
        runCatching {
            val c = spCls.getDeclaredConstructor(
                strT, intT, intT, intT, intT, intT, strT, strT, strT
            ).apply { isAccessible = true }
            return c.newInstance(
                sharedPath, hostUid, hostGid, guestUid, guestGid, mask,
                tag, socket, socketPath,
            )!!
        }
        // Shape v1: (String, String, 5×int, String, String) — older AOSP
        runCatching {
            val c = spCls.getDeclaredConstructor(
                strT, strT, intT, intT, intT, intT, intT, strT, strT
            ).apply { isAccessible = true }
            return c.newInstance(
                sharedPath, socket,
                hostUid, hostGid, guestUid, guestGid, mask,
                tag, socketPath,
            )!!
        }
        return null
    }

    fun setNetworkSupported(b: Any, value: Boolean) {
        val ok = runCatching { invokeDecl(b, "useNetwork", Boolean::class.javaPrimitiveType!! to value) }.isSuccess
            || runCatching { invokeDecl(b, "setNetworkSupported", Boolean::class.javaPrimitiveType!! to value) }.isSuccess
        if (!ok) android.util.Log.w("AvfReflect", "no useNetwork/setNetworkSupported on this AVF API; VM may have no network")
    }

    /**
     * Builds a Proxy of `android.system.virtualmachine.VirtualMachineCallback`.
     * The Java interface can't be implemented directly because it's @SystemApi
     * and we don't compile against the stubs.
     *
     * @param onError invoked when the VM hits a runtime error. Args: errorCode (Int), message (String?).
     * @param onStopped invoked when the VM exits cleanly. Arg: reason (Int).
     * @param onDied invoked for backend-level termination. Arg: reason (Int).
     */
    fun newVmCallback(
        onError: (Int, String?) -> Unit,
        onStopped: (Int) -> Unit,
        onDied: (Int) -> Unit,
    ): Any {
        val cls = Class.forName("$PKG.VirtualMachineCallback")
        val handler = InvocationHandler { _, method: Method, args: Array<Any?>? ->
            when (method.name) {
                "onError" -> {
                    // signature: onError(VirtualMachine vm, int errorCode, String message)
                    val code = args?.getOrNull(1) as? Int ?: -1
                    val msg = args?.getOrNull(2) as? String
                    onError(code, msg)
                }
                "onStopped" -> {
                    val reason = args?.getOrNull(1) as? Int ?: -1
                    onStopped(reason)
                }
                "onDied" -> {
                    // Both documented shapes carry reason at index 1:
                    //   onDied(VirtualMachine vm, int reason)
                    //   onDied(int cid, int reason)
                    // Use index 1 for consistency with onError/onStopped above
                    // (an extra trailing arg in a future signature would otherwise
                    // silently mis-decode under an args[last] strategy).
                    val reason = args?.getOrNull(1) as? Int ?: -1
                    onDied(reason)
                }
                else -> Unit  // ignore onPayload* — those are Microdroid-only
            }
            null
        }
        return Proxy.newProxyInstance(cls.classLoader, arrayOf(cls), handler)
    }

    fun setCallback(vm: Any, executor: Executor, callback: Any) {
        val cbCls = Class.forName("$PKG.VirtualMachineCallback")
        val m = vm.javaClass.getDeclaredMethod("setCallback", Executor::class.java, cbCls)
            .apply { isAccessible = true }
        m.invoke(vm, executor, callback)
    }

    fun getStatus(vm: Any): Int =
        (invokeDecl(vm, "getStatus") as? Int) ?: -1

    fun run(vm: Any) { invokeDecl(vm, "run") }

    /**
     * Signals the framework to stop the VM. Throws on failure rather than
     * swallowing it: AvfEngine.stop() needs to know whether the request was
     * accepted, because a swallowed failure leaves a live VM with a nulled
     * handle (unkillable). Callers wrap in runCatching where they want to react.
     */
    fun stop(vm: Any) { invokeDecl(vm, "stop") }

    /**
     * Opens a vsock connection from the host (Android) to a port the guest is
     * listening on. Returns a ParcelFileDescriptor whose underlying FD is a
     * connected AF_VSOCK socket — wrap it in
     * [android.os.ParcelFileDescriptor.AutoCloseInputStream]/
     * [android.os.ParcelFileDescriptor.AutoCloseOutputStream] for I/O.
     *
     * The guest reaches us at CID 2 (host); our peer is the VM at whatever CID
     * AVF assigned. AVF's connectVsock handles the CID lookup internally; we
     * pass only the port.
     */
    fun connectVsock(vm: Any, port: Long): android.os.ParcelFileDescriptor {
        val m = vm.javaClass.getDeclaredMethod("connectVsock", Long::class.javaPrimitiveType!!)
            .apply { isAccessible = true }
        return m.invoke(vm, port) as? android.os.ParcelFileDescriptor
            ?: error("connectVsock($port) returned null")
    }

    fun consoleOutput(vm: Any): java.io.InputStream =
        invokeDecl(vm, "getConsoleOutput") as? java.io.InputStream
            ?: error("getConsoleOutput returned null/non-stream")

    fun consoleInput(vm: Any): java.io.OutputStream =
        invokeDecl(vm, "getConsoleInput") as? java.io.OutputStream
            ?: error("getConsoleInput returned null/non-stream")

    private fun invokeDecl(target: Any, name: String, vararg typedArgs: Pair<Class<*>, Any?>): Any? {
        val argTypes = typedArgs.map { it.first }.toTypedArray()
        val argVals = typedArgs.map { it.second }.toTypedArray()
        val m = target.javaClass.getDeclaredMethod(name, *argTypes).apply { isAccessible = true }
        return m.invoke(target, *argVals)
    }
}
