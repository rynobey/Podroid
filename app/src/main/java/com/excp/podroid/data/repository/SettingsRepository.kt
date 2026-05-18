/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * App-wide settings backed by DataStore.
 */
package com.excp.podroid.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        val KEY_DARK_THEME             = booleanPreferencesKey("dark_theme")
        val KEY_VM_RAM                 = intPreferencesKey("vm_ram_mb")
        val KEY_VM_CPUS                = intPreferencesKey("vm_cpus")
        val KEY_FONT_SIZE              = intPreferencesKey("terminal_font_size")
        val KEY_STORAGE_GB             = intPreferencesKey("storage_gb")
        val KEY_STORAGE_ACCESS_ENABLED = booleanPreferencesKey("storage_access_enabled")
        val KEY_SETUP_DONE             = booleanPreferencesKey("setup_done")
        val KEY_SSH_ENABLED            = booleanPreferencesKey("ssh_enabled")
        val KEY_TERMINAL_COLOR_THEME   = stringPreferencesKey("terminal_color_theme")
        val KEY_TERMINAL_FONT          = stringPreferencesKey("terminal_font")
        val KEY_QEMU_EXTRA_ARGS        = stringPreferencesKey("qemu_extra_args")
        val KEY_KERNEL_EXTRA_CMDLINE   = stringPreferencesKey("kernel_extra_cmdline")
        val KEY_SHOW_EXTRA_KEYS        = booleanPreferencesKey("show_extra_keys")
        val KEY_HAPTICS_ENABLED        = booleanPreferencesKey("haptics_enabled")
        val KEY_DYNAMIC_COLOR_ENABLED  = booleanPreferencesKey("dynamic_color_enabled")

        /**
         * Default tunable QEMU args — CPU model, accel tuning, RNG source, overcommit.
         *
         * `-cpu max,sve=off`: keeps LSE atomics, AES/SHA crypto, BTI, PAC, CRC32 —
         * everything Node/Podman/etc. actually use — but drops SVE's variable-length
         * vector instructions (and SVE2 with it), which are expensive to TCG-translate
         * (every SVE op has many length-encoded variants) and rarely used outside HPC.
         *
         * `tb-size=512`: 512 MiB translation block cache (was 256 MiB). JIT-heavy
         * guests like V8 recompile their own bytecode often, and TCG re-translates
         * that machine code each time it falls out of cache. Larger cache = fewer
         * re-translations. 512 MiB picked as a balance — enough for Node + npm hot
         * paths without ballooning the QEMU process on phones with tight RAM.
         */
        const val DEFAULT_QEMU_EXTRA_ARGS =
            "-cpu max,sve=off " +
            "-accel tcg,thread=multi,tb-size=512 " +
            "-object rng-random,id=rng0,filename=/dev/urandom " +
            "-device virtio-rng-pci,rng=rng0 " +
            "-overcommit mem-lock=off"

        /**
         * Default extra kernel cmdline — quiet boot + TCG-safe mitigations.
         * `elevator=` is deprecated since Linux 5.0; podroid-bootstrap sets the I/O
         * scheduler per-device via sysfs instead.
         */
        const val DEFAULT_KERNEL_EXTRA_CMDLINE = "loglevel=1 quiet mitigations=off"
    }

    private fun <T> pref(key: Preferences.Key<T>, default: T): Flow<T> =
        context.dataStore.data.map { it[key] ?: default }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) =
        context.dataStore.edit { it[key] = value }

    val darkTheme            = pref(KEY_DARK_THEME, true)
    val vmRamMb              = pref(KEY_VM_RAM, 512)
    // 2 vCPUs lets multi-threaded TCG (`thread=multi`) actually schedule
    // across two host threads. With 1 vCPU the accel falls back to
    // single-thread translation regardless of the flag, so most CPU-bound
    // guest workloads (Node, npm install, Podman/Docker layer extraction)
    // see a meaningful uplift just from this default change.
    val vmCpus               = pref(KEY_VM_CPUS, 2)
    val terminalFontSize     = pref(KEY_FONT_SIZE, 20)
    val storageSizeGb        = pref(KEY_STORAGE_GB, 2)
    val storageAccessEnabled = pref(KEY_STORAGE_ACCESS_ENABLED, false)
    val isSetupDone          = pref(KEY_SETUP_DONE, false)
    val sshEnabled           = pref(KEY_SSH_ENABLED, false)
    val terminalColorTheme   = pref(KEY_TERMINAL_COLOR_THEME, "default")
    val terminalFont         = pref(KEY_TERMINAL_FONT, "default")
    val qemuExtraArgs        = pref(KEY_QEMU_EXTRA_ARGS, DEFAULT_QEMU_EXTRA_ARGS)
    val kernelExtraCmdline   = pref(KEY_KERNEL_EXTRA_CMDLINE, DEFAULT_KERNEL_EXTRA_CMDLINE)
    val showExtraKeys        = pref(KEY_SHOW_EXTRA_KEYS, true)
    val hapticsEnabled       = pref(KEY_HAPTICS_ENABLED, true)
    val dynamicColorEnabled  = pref(KEY_DYNAMIC_COLOR_ENABLED, false)

    suspend fun setDarkTheme(value: Boolean)             = set(KEY_DARK_THEME, value)
    suspend fun setVmRamMb(value: Int)                   = set(KEY_VM_RAM, value)
    suspend fun setVmCpus(value: Int)                    = set(KEY_VM_CPUS, value)
    suspend fun setTerminalFontSize(value: Int)          = set(KEY_FONT_SIZE, value)
    suspend fun setStorageSizeGb(value: Int)             = set(KEY_STORAGE_GB, value)
    suspend fun setStorageAccessEnabled(value: Boolean)  = set(KEY_STORAGE_ACCESS_ENABLED, value)
    suspend fun markSetupDone()                          = set(KEY_SETUP_DONE, true)
    suspend fun setSshEnabled(value: Boolean)            = set(KEY_SSH_ENABLED, value)
    suspend fun setTerminalColorTheme(value: String)     = set(KEY_TERMINAL_COLOR_THEME, value)
    suspend fun setTerminalFont(value: String)           = set(KEY_TERMINAL_FONT, value)
    suspend fun setQemuExtraArgs(value: String)          = set(KEY_QEMU_EXTRA_ARGS, value)
    suspend fun setKernelExtraCmdline(value: String)     = set(KEY_KERNEL_EXTRA_CMDLINE, value)
    suspend fun setShowExtraKeys(value: Boolean)         = set(KEY_SHOW_EXTRA_KEYS, value)
    suspend fun setHapticsEnabled(value: Boolean)        = set(KEY_HAPTICS_ENABLED, value)
    suspend fun setDynamicColorEnabled(value: Boolean)   = set(KEY_DYNAMIC_COLOR_ENABLED, value)

    // Snapshots used by non-Compose call sites (PodroidService, exporters).
    suspend fun getSshEnabledSnapshot()           = sshEnabled.first()
    suspend fun getVmRamMbSnapshot()              = vmRamMb.first()
    suspend fun getVmCpusSnapshot()               = vmCpus.first()
    suspend fun getStorageSizeGbSnapshot()        = storageSizeGb.first()
    suspend fun getStorageAccessEnabledSnapshot() = storageAccessEnabled.first()
    suspend fun isSetupDoneSnapshot()             = isSetupDone.first()
    suspend fun getTerminalColorThemeSnapshot()   = terminalColorTheme.first()
    suspend fun getTerminalFontSnapshot()         = terminalFont.first()
    suspend fun getQemuExtraArgsSnapshot()        = qemuExtraArgs.first()
    suspend fun getKernelExtraCmdlineSnapshot()   = kernelExtraCmdline.first()
}
