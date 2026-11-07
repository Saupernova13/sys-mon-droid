package com.raavivi.sysmon.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Colours and shape/feature switches that differ between [ThemeStyle]s. */
@Immutable
data class SysMonPalette(
    val style: ThemeStyle,
    // identity
    val idCpu: Color, val idCpuBg: Color,
    val idMem: Color, val idMemBg: Color,
    val idGpu: Color, val idGpuBg: Color,
    val idDisk: Color, val idDiskBg: Color,
    val idPwr: Color, val idPwrBg: Color,
    // severity
    val nominalInk: Color,
    val sigOk: Color, val sigWarn: Color, val sigCrit: Color, val offline: Color,
    val sigOkBg: Color, val sigWarnBg: Color, val sigCritBg: Color,
    // file kinds
    val fsDir: Color, val fsMedia: Color, val fsArchive: Color, val fsExec: Color,
    val fsCode: Color, val fsStar: Color, val fsFile: Color,
    val driveHues: List<Color>,
    val driveHueBgs: List<Color>,
    // shape / feature switches
    val cardRadius: Dp,
    val meterTicks: Boolean,
    val chartFill: Boolean,
    val chartStroke: Float,
    val bandHeaders: Boolean,
    val monoNumbers: Boolean,
    // neutral helpers
    val track: Color,
    val hairline: Color,
    val tick: Color,
    val quietInk: Color,
) {
    val isInstrument: Boolean get() = style == ThemeStyle.Instrument

    /** Tint to pair with [accent]: its identity bg when it is one, else accent at 12%. */
    fun bgFor(accent: Color): Color = when (accent) {
        idCpu -> idCpuBg
        idMem -> idMemBg
        idGpu -> idGpuBg
        idDisk -> idDiskBg
        idPwr -> idPwrBg
        else -> accent.copy(alpha = 0.12f)
    }
}

private fun Color.tint(alpha: Float) = copy(alpha = alpha)

val ClassicPalette = SysMonPalette(
    style = ThemeStyle.Classic,
    idCpu = Mint, idCpuBg = Mint.tint(0.15f),
    idMem = Sky, idMemBg = Sky.tint(0.15f),
    idGpu = Violet, idGpuBg = Violet.tint(0.15f),
    idDisk = Amber, idDiskBg = Amber.tint(0.15f),
    idPwr = PowerColor, idPwrBg = PowerColor.tint(0.15f),
    nominalInk = Mint,
    sigOk = Mint, sigWarn = Amber, sigCrit = Coral, offline = OnSurfaceMuted,
    sigOkBg = Mint.tint(0.15f), sigWarnBg = Amber.tint(0.15f), sigCritBg = Coral.tint(0.15f),
    fsDir = Sky, fsMedia = Violet, fsArchive = PowerColor, fsExec = Mint,
    fsCode = Color(0xFF7AA2F7), fsStar = Amber, fsFile = OnSurfaceMuted,
    driveHues = listOf(Sky, Mint, Violet, Amber, Coral),
    driveHueBgs = listOf(Sky, Mint, Violet, Amber, Coral).map { it.tint(0.15f) },
    cardRadius = 16.dp,
    meterTicks = false,
    chartFill = true,
    chartStroke = 3f,
    bandHeaders = false,
    monoNumbers = false,
    track = SurfaceVariantDark,
    hairline = OutlineDark,
    tick = OutlineDark,
    quietInk = OnSurfaceMuted,
)

// sys-mon web panel neutral ramp (frontend/css/base.css).
val N0 = Color(0xFF0C0C0B)
val N1 = Color(0xFF171716)
val N2 = Color(0xFF1F1F1D)
val N3 = Color(0xFF35342F)
val N4 = Color(0xFF4A4844)
val N5 = Color(0xFF6C6A66)
val N6 = Color(0xFF8A8880)
val N7 = Color(0xFFB1AEA6)
val N8 = Color(0xFFD5D1C8)
val N9 = Color(0xFFEFEBE1)

private val InstCpu = Color(0xFF50B3FB)
private val InstGpu = Color(0xFF3AC3A7)
private val InstDisk = Color(0xFF8B87FF)
private val InstMem = Color(0xFFD472FF)
private val InstPwr = Color(0xFFF07CD8)
val InstOk = Color(0xFF76A666)
val InstWarn = Color(0xFFD18B2C)
val InstCrit = Color(0xFFE65949)

private val InstCpuBg = Color(0xFF15232C)
private val InstGpuBg = Color(0xFF122520)
private val InstDiskBg = Color(0xFF1D1D2D)
private val InstMemBg = Color(0xFF281A2D)
private val InstPwrBg = Color(0xFF2B1B27)

val InstrumentPalette = SysMonPalette(
    style = ThemeStyle.Instrument,
    idCpu = InstCpu, idCpuBg = InstCpuBg,
    idMem = InstMem, idMemBg = InstMemBg,
    idGpu = InstGpu, idGpuBg = InstGpuBg,
    idDisk = InstDisk, idDiskBg = InstDiskBg,
    idPwr = InstPwr, idPwrBg = InstPwrBg,
    nominalInk = N9,
    sigOk = InstOk, sigWarn = InstWarn, sigCrit = InstCrit, offline = N5,
    sigOkBg = InstOk.tint(0.12f), sigWarnBg = InstWarn.tint(0.12f), sigCritBg = InstCrit.tint(0.14f),
    fsDir = InstCpu, fsMedia = InstMem, fsArchive = InstPwr, fsExec = InstGpu,
    fsCode = InstDisk, fsStar = Color(0xFFE9C84F), fsFile = N6,
    driveHues = listOf(InstCpu, InstGpu, InstDisk, InstMem, InstPwr),
    driveHueBgs = listOf(InstCpuBg, InstGpuBg, InstDiskBg, InstMemBg, InstPwrBg),
    cardRadius = 2.dp,
    meterTicks = true,
    chartFill = false,
    chartStroke = 2f,
    bandHeaders = true,
    monoNumbers = true,
    track = N2,
    hairline = N3,
    tick = N4,
    quietInk = N6,
)

val LocalSysMonPalette = staticCompositionLocalOf { ClassicPalette }

/** Accessor for the active palette: `SysMon.palette.idCpu`. */
object SysMon {
    val palette: SysMonPalette
        @Composable get() = LocalSysMonPalette.current
}
