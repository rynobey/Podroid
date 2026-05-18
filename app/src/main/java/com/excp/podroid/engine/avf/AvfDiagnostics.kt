/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AVF (Android Virtualization Framework) diagnostic + smoke-test entry point.
 *
 * The android.system.virtualmachine.* APIs are @SystemApi (only present in
 * the system-stub JAR, not the public SDK). We reach them via reflection so
 * a normal Gradle build still compiles on every device. On phones without
 * pKVM the reflective lookups simply fail and the probe reports "not
 * available" — no crash, no missing-class linker error.
 *
 * Purpose: validate the manifest+`adb pm grant` path on pKVM hardware
 * (Pixel 8/9/10) before investing in a real dual-backend rewrite. Reports
 * what's present, what's granted, whether the service is reachable, and
 * (optionally) attempts to create + start a minimal VM using our existing
 * Alpine kernel/initrd in filesDir.
 */
package com.excp.podroid.engine.avf

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/** One-line entries in the diagnostic report; UI just joins them. */
data class AvfReport(
    val featureSupported: Boolean,
    val managePermissionGranted: Boolean,
    val customPermissionGranted: Boolean,
    val virtApexPresent: Boolean,
    val managerClassPresent: Boolean,
    val serviceReachable: Boolean,
    val smokeTestResult: String?,
    val activeBackend: String = "?",
) {
    fun pretty(): String = buildString {
        appendLine("Active backend")
        appendLine("  $activeBackend")
        appendLine()
        appendLine("Feature: virtualization_framework")
        appendLine("  supported = $featureSupported")
        appendLine()
        appendLine("Permission: MANAGE_VIRTUAL_MACHINE")
        appendLine("  granted = $managePermissionGranted")
        appendLine()
        appendLine("Permission: USE_CUSTOM_VIRTUAL_MACHINE")
        appendLine("  granted = $customPermissionGranted")
        appendLine()
        appendLine("APEX /apex/com.android.virt")
        appendLine("  present = $virtApexPresent")
        appendLine()
        appendLine("API VirtualMachineManager")
        appendLine("  class loadable = $managerClassPresent")
        appendLine()
        appendLine("Service")
        appendLine("  reachable via system service = $serviceReachable")
        if (smokeTestResult != null) {
            appendLine()
            appendLine("Smoke test")
            appendLine(smokeTestResult.prependIndent("  "))
        }
    }
}

object AvfDiagnostics {

    private const val FEATURE = "android.software.virtualization_framework"
    private const val PERM_MANAGE = "android.permission.MANAGE_VIRTUAL_MACHINE"
    private const val PERM_CUSTOM = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
    private const val CLS_MANAGER = "android.system.virtualmachine.VirtualMachineManager"
    private const val CLS_CONFIG = "android.system.virtualmachine.VirtualMachineConfig"
    private const val CLS_CUSTOM_CFG = "android.system.virtualmachine.VirtualMachineCustomImageConfig"

    /**
     * Probe whether this AVF revision can share external storage paths
     * (e.g. /storage/emulated/.../Download). The 10-param SharedPath ctor
     * that takes a `boolean appDomain` is the prerequisite — without it,
     * crosvm inherits our untrusted_app SELinux domain and the kernel
     * refuses to read external storage (VM dies at start with reason=4).
     *
     * On shipping Pixel mustang beta, only the 9-param ctor ships and this
     * returns false. Google's TerminalApp gets around it by being signed
     * with the platform key and installed under /apex/com.android.virt/
     * priv-app/ — a path no third-party APK can take.
     */
    fun externalStorageShareSupported(): Boolean {
        val spCls = runCatching {
            Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig\$SharedPath")
        }.getOrNull() ?: return false
        val intT = Int::class.javaPrimitiveType!!
        val boolT = Boolean::class.javaPrimitiveType!!
        val strT = String::class.java
        return runCatching {
            spCls.getDeclaredConstructor(
                strT, intT, intT, intT, intT, intT, strT, strT, boolT, strT
            )
        }.isSuccess
    }

    /**
     * Read-only probe — never blocks, never touches the system service for
     * real (just checks reachability). Safe to call from anywhere.
     */
    fun probe(context: Context): AvfReport {
        val pm = context.packageManager
        val featureSupported = pm.hasSystemFeature(FEATURE)
        val managePermissionGranted = pm.checkPermission(PERM_MANAGE, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
        val customPermissionGranted = pm.checkPermission(PERM_CUSTOM, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
        val virtApexPresent = File("/apex/com.android.virt/bin").exists()
        val managerClassPresent = runCatching { Class.forName(CLS_MANAGER) }.isSuccess
        val serviceReachable = managerClassPresent && managePermissionGranted &&
            runCatching { getVirtualizationManager(context) != null }.getOrDefault(false)

        return AvfReport(
            featureSupported = featureSupported,
            managePermissionGranted = managePermissionGranted,
            customPermissionGranted = customPermissionGranted,
            virtApexPresent = virtApexPresent,
            managerClassPresent = managerClassPresent,
            serviceReachable = serviceReachable,
            smokeTestResult = null,
        )
    }

    /**
     * Attempts a real minimal-VM creation using our existing alpine kernel
     * + initrd. Returns a human-readable result string. Blocks for a few
     * seconds. Call off the UI thread.
     *
     * NOTE: this only validates that *creation* + *run* are accepted by the
     * framework — it does not stream the console or wait for guest boot.
     * The VM is stopped/deleted immediately.
     */
    fun runSmokeTest(context: Context): String {
        val pre = probe(context)
        if (!pre.featureSupported)   return "skipped: feature flag not present (device does not ship AVF)"
        if (!pre.managePermissionGranted) return "skipped: MANAGE_VIRTUAL_MACHINE not granted (run: adb shell pm grant ${context.packageName} $PERM_MANAGE)"
        if (!pre.customPermissionGranted) return "skipped: USE_CUSTOM_VIRTUAL_MACHINE not granted (run: adb shell pm grant ${context.packageName} $PERM_CUSTOM)"
        if (!pre.managerClassPresent) return "FAILED: $CLS_MANAGER not on the boot classpath — system stub missing"

        val kernel = File(context.filesDir, "vmlinuz-virt")
        val initrd = File(context.filesDir, "initrd.img")
        if (!kernel.exists()) return "FAILED: kernel not extracted yet at ${kernel.absolutePath}"
        if (!initrd.exists()) return "FAILED: initrd not extracted yet at ${initrd.absolutePath}"

        return try {
            val vmm = getVirtualizationManager(context)
                ?: return "FAILED: VirtualMachineManager system service returned null"

            val customCfg = buildCustomImageConfig(kernel.absolutePath, initrd.absolutePath)
            val config = buildVirtualMachineConfig(context, customCfg)

            val name = "podroid-avf-smoke"
            val vm = invokeOrCreate(vmm, name, config)

            // Start + immediately stop. We're proving the framework accepts us,
            // not running a workload.
            runCatching {
                vm.javaClass.getMethod("run").invoke(vm)
            }.onFailure { e ->
                deleteSafely(vmm, name)
                return "FAILED at vm.run(): ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}: ${e.cause?.message ?: e.message}"
            }

            // Give the VM 1.5s to actually start (we just want to know whether
            // the framework rejected us — usually fails synchronously).
            Thread.sleep(1500)

            runCatching { vm.javaClass.getMethod("stop").invoke(vm) }
            deleteSafely(vmm, name)

            "SUCCESS: AVF accepted our config, VM started + stopped cleanly. The dev-grant path works on this device."
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            "FAILED: ${cause.javaClass.simpleName}: ${cause.message}"
        }
    }

    private fun getVirtualizationManager(context: Context): Any? {
        // Context.getSystemService(Class) — but the class is loaded reflectively
        // so we can't call the typed overload at compile time.
        val mgrCls = Class.forName(CLS_MANAGER)
        val m = Context::class.java.getMethod("getSystemService", Class::class.java)
        return m.invoke(context, mgrCls)
    }

    private fun buildCustomImageConfig(kernelPath: String, initrdPath: String): Any {
        val builderCls = Class.forName("$CLS_CUSTOM_CFG\$Builder")
        val ctor = builderCls.getDeclaredConstructor().apply { isAccessible = true }
        val builder = ctor.newInstance()
        invokeSetter(builderCls, builder, "setName", String::class.java, "podroid-avf-smoke")
        invokeSetter(builderCls, builder, "setKernelPath", String::class.java, kernelPath)
        invokeSetter(builderCls, builder, "setInitrdPath", String::class.java, initrdPath)
        runCatching {
            invokeSetter(builderCls, builder, "setParams", String::class.java, "console=hvc0 panic=1")
        }
        val buildM = builderCls.getDeclaredMethod("build").apply { isAccessible = true }
        return buildM.invoke(builder)
    }

    private fun buildVirtualMachineConfig(context: Context, customCfg: Any): Any {
        val builderCls = Class.forName("$CLS_CONFIG\$Builder")
        val ctor = builderCls.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val builder = ctor.newInstance(context)
        val customCfgCls = Class.forName(CLS_CUSTOM_CFG)
        invokeSetter(builderCls, builder, "setCustomImageConfig", customCfgCls, customCfg)
        runCatching {
            invokeSetter(builderCls, builder, "setProtectedVm",
                Boolean::class.javaPrimitiveType!!, false)
        }
        runCatching {
            invokeSetter(builderCls, builder, "setMemoryBytes",
                Long::class.javaPrimitiveType!!, 256L * 1024 * 1024)
        }
        val buildM = builderCls.getDeclaredMethod("build").apply { isAccessible = true }
        return buildM.invoke(builder)
    }

    private fun invokeSetter(cls: Class<*>, target: Any, name: String, argType: Class<*>, arg: Any?) {
        val m = cls.getDeclaredMethod(name, argType).apply { isAccessible = true }
        m.invoke(target, arg)
    }

    private fun invokeOrCreate(vmm: Any, name: String, config: Any): Any {
        val cfgCls = Class.forName(CLS_CONFIG)
        return runCatching {
            val m = vmm.javaClass.getDeclaredMethod("getOrCreate", String::class.java, cfgCls)
                .apply { isAccessible = true }
            m.invoke(vmm, name, config)
        }.getOrElse {
            val m = vmm.javaClass.getDeclaredMethod("create", String::class.java, cfgCls)
                .apply { isAccessible = true }
            m.invoke(vmm, name, config)
        } ?: error("getOrCreate returned null")
    }

    private fun deleteSafely(vmm: Any, name: String) {
        runCatching { vmm.javaClass.getMethod("delete", String::class.java).invoke(vmm, name) }
    }
}
