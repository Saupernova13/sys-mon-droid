package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

/**
 * Mirrors `models/schemas.py:SystemSnapshot` from the sys-mon backend. JSON keys
 * are snake_case; the [com.raavivi.sysmon.core.net.SysMonJson] instance applies a
 * snake_case naming strategy so these idiomatic camelCase names map across.
 */
@Serializable
data class SystemSnapshot(
    val timestamp: Double = 0.0,
    val cpu: CpuSnapshot,
    val gpu: GpuSnapshot,
    val ram: RamSnapshot,
    val disk: DiskSnapshot,
)

@Serializable
data class CpuSnapshot(
    val overallPct: Double = 0.0,
    val cores: List<CoreStat> = emptyList(),
    val freqCurrentMhz: Double = 0.0,
    val freqMaxMhz: Double = 0.0,
    val tempCelsius: Double? = null,
    val topProcesses: List<ProcessStat> = emptyList(),
)

@Serializable
data class CoreStat(
    val id: Int = 0,
    val usagePct: Double = 0.0,
    val freqMhz: Double = 0.0,
)

@Serializable
data class GpuSnapshot(
    val available: Boolean = false,
    val devices: List<GpuDeviceStat> = emptyList(),
)

@Serializable
data class GpuDeviceStat(
    val index: Int = 0,
    val name: String = "",
    val usagePct: Double = 0.0,
    val memUsedMb: Double = 0.0,
    val memTotalMb: Double = 0.0,
    val memPct: Double = 0.0,
    val tempCelsius: Double? = null,
    val topProcesses: List<GpuProcessStat> = emptyList(),
    val processListAvailable: Boolean = false,
    val isIgpu: Boolean = false,
)

@Serializable
data class RamSnapshot(
    val totalBytes: Long = 0,
    val usedBytes: Long = 0,
    val availableBytes: Long = 0,
    val usagePct: Double = 0.0,
    val swapTotalBytes: Long = 0,
    val swapUsedBytes: Long = 0,
    val swapPct: Double = 0.0,
    val topProcesses: List<ProcessStat> = emptyList(),
)

@Serializable
data class DiskSnapshot(
    val drives: List<DriveStat> = emptyList(),
    val topProcesses: List<ProcessStat> = emptyList(),
)

@Serializable
data class DriveStat(
    val device: String = "",
    val mountpoint: String = "",
    val fstype: String = "",
    val totalBytes: Long = 0,
    val usedBytes: Long = 0,
    val freeBytes: Long = 0,
    val usagePct: Double = 0.0,
    val readBytesSec: Double = 0.0,
    val writeBytesSec: Double = 0.0,
)

@Serializable
data class ProcessStat(
    val pid: Int = 0,
    val name: String = "",
    val cpuPct: Double = 0.0,
    val ramBytes: Long = 0,
    val ramPct: Double = 0.0,
    val diskReadBytesSec: Double = 0.0,
    val diskWriteBytesSec: Double = 0.0,
)

@Serializable
data class GpuProcessStat(
    val pid: Int = 0,
    val name: String = "",
    val gpuMemMb: Double = 0.0,
    val cpuPct: Double = 0.0,
    val ramBytes: Long = 0,
    val ramPct: Double = 0.0,
    val diskReadBytesSec: Double = 0.0,
    val diskWriteBytesSec: Double = 0.0,
)
