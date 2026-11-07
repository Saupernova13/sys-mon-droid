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
    /** Smart-plug reading; null when the power widget is off or not configured. */
    val power: PowerReading? = null,
)

@Serializable
data class CpuSnapshot(
    val overallPct: Double = 0.0,
    val cores: List<CoreStat> = emptyList(),
    val freqCurrentMhz: Double? = null,
    val freqMaxMhz: Double? = null,
    val tempCelsius: Double? = null,
    val topProcesses: List<ProcessStat> = emptyList(),
)

@Serializable
data class CoreStat(
    val id: Int = 0,
    val usagePct: Double = 0.0,
    val freqMhz: Double? = null,
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
    val usagePct: Double? = null,
    val memUsedMb: Double? = null,
    val memTotalMb: Double? = null,
    val memPct: Double? = null,
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
    val readBytesSec: Double? = null,
    val writeBytesSec: Double? = null,
    val label: String = "",
    val mediaType: String = "",
    val busType: String = "",
    val model: String = "",
    val smartTempC: Double? = null,
    /** "good" / "caution" / "bad" / "unknown"; null means no reading at all. */
    val smartHealth: String? = null,
    val smartDiskId: String? = null,
    val smartRotational: Boolean? = null,
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
