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
        val m = vm.javaClass.getDeclaredMethod("setConfig", CFG).apply { isAccessible = true }
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

    fun setMemoryBytes(b: Any, bytes: Long) {
        invokeDecl(b, "setMemoryBytes", Long::class.javaPrimitiveType!! to bytes)
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
                    // some API revisions: onDied(VirtualMachine vm, int reason); others: onDied(int cid, int reason)
                    val reason = args?.getOrNull((args.size - 1).coerceAtLeast(0)) as? Int ?: -1
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
        invokeDecl(vm, "getStatus") as Int

    fun run(vm: Any) { invokeDecl(vm, "run") }
    fun stop(vm: Any) { runCatching { invokeDecl(vm, "stop") } }

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
        return m.invoke(vm, port) as android.os.ParcelFileDescriptor
    }

    fun consoleOutput(vm: Any): java.io.InputStream =
        invokeDecl(vm, "getConsoleOutput") as java.io.InputStream

    fun consoleInput(vm: Any): java.io.OutputStream =
        invokeDecl(vm, "getConsoleInput") as java.io.OutputStream

    private fun invokeDecl(target: Any, name: String, vararg typedArgs: Pair<Class<*>, Any?>): Any? {
        val argTypes = typedArgs.map { it.first }.toTypedArray()
        val argVals = typedArgs.map { it.second }.toTypedArray()
        val m = target.javaClass.getDeclaredMethod(name, *argTypes).apply { isAccessible = true }
        return m.invoke(target, *argVals)
    }
}
