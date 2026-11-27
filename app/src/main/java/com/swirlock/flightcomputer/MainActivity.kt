package com.swirlock.flightcomputer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.wear.compose.material3.MaterialTheme
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var navigationProvider: NavigationProvider
    private lateinit var fuelEstimator: FuelEstimator
    private var navigationSolution by mutableStateOf<NavigationSolution?>(null)
    private var fuelEstimate by mutableStateOf<FuelEstimate?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val solutionRefresh = object : Runnable {
        override fun run() {
            if (::navigationProvider.isInitialized) updateNavigation(navigationProvider.currentSolution())
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fuelEstimator = FuelEstimator.load(this)
        // This exercise build is MAG + inertial only. GPS has a separate provider and is not wired here.
        navigationProvider = MagInertialNavigationProvider(this)

        setContent {
            MaterialTheme {
                FlightInstrumentScreen(navigationSolution, fuelEstimate)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(solutionRefresh)
        mainHandler.post(solutionRefresh)
        navigationProvider.start { updateNavigation(it) }
    }

    override fun onStop() {
        mainHandler.removeCallbacks(solutionRefresh)
        navigationProvider.stop()
        super.onStop()
    }

    private fun updateNavigation(solution: NavigationSolution?) {
        navigationSolution = solution
        fuelEstimate = fuelEstimator.update(solution)
    }
}

@Composable
private fun FlightInstrumentScreen(navigationSolution: NavigationSolution?, fuelEstimate: FuelEstimate?) {
    val animatedHeading = remember { Animatable(navigationSolution?.directionDegrees ?: 0f) }
    LaunchedEffect(navigationSolution?.directionDegrees, navigationSolution?.source) {
        // Never animate the rose toward north just because a sensor heading went stale.
        // Keep its last known orientation while the readout indicates unavailable.
        val heading = navigationSolution?.directionDegrees ?: return@LaunchedEffect
        val current = animatedHeading.value
        val target = heading
        val delta = ((target - current + 540f) % 360f) - 180f
        val durationMs = if (navigationSolution.source == NavigationSource.GPS) 180 else 90
        animatedHeading.animateTo(
            targetValue = current + delta,
            animationSpec = tween(durationMillis = durationMs, easing = LinearEasing),
        )
    }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                val reference = when {
                    navigationSolution?.source == NavigationSource.GPS -> "track"
                    navigationSolution?.magneticHeadingValid == true -> "magnetic"
                    else -> "relative"
                }
                contentDescription = "Inertial flight instrument. " +
                    "Heading ${navigationSolution?.directionDegrees?.roundToInt()?.let { "$it degrees $reference" } ?: "unavailable"}. " +
                    "Estimated ground speed ${navigationSolution?.groundSpeedMps?.times(3.6f)?.let { "${it.roundToInt()} kilometers per hour" } ?: "unavailable"}. " +
                    "Relative height ${navigationSolution?.localUpM?.let { "${it.roundToInt()} meters" } ?: "unavailable"}. " +
                    "Estimated home distance ${navigationSolution?.homeDistanceM?.let { "${it.roundToInt()} meters" } ?: "unavailable"}. " +
                    "Estimated remaining fuel ${fuelEstimate?.let { "approximately ${String.format(Locale.getDefault(), "%.1f", it.remainingFuelL)} liters" } ?: "unavailable"}."
            },
    ) {
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val scale = minOf(size.width, size.height) / DESIGN_SIZE
            val offsetX = (size.width - DESIGN_SIZE * scale) / 2f
            val offsetY = (size.height - DESIGN_SIZE * scale) / 2f
            val saved = canvas.save()
            canvas.translate(offsetX, offsetY)
            canvas.scale(scale, scale)
            drawInstrument(canvas, navigationSolution, animatedHeading.value, fuelEstimate)
            canvas.restoreToCount(saved)
        }
    }
}

private const val DESIGN_SIZE = 420f
private const val CENTER = DESIGN_SIZE / 2f

private object InstrumentColors {
    val black = AndroidColor.rgb(0, 0, 0)
    val white = AndroidColor.rgb(246, 249, 252)
    val muted = AndroidColor.rgb(180, 192, 202)
    val tick = AndroidColor.rgb(105, 119, 130)
    val dim = AndroidColor.rgb(42, 52, 60)
    val cyan = AndroidColor.rgb(0, 210, 255)
    val green = AndroidColor.rgb(52, 245, 130)
    val amber = AndroidColor.rgb(255, 190, 64)
    val compassOrange = AndroidColor.rgb(255, 136, 0)
    val compassRed = AndroidColor.rgb(255, 35, 35)
}

private fun drawInstrument(
    canvas: AndroidCanvas,
    navigationSolution: NavigationSolution?,
    animatedHeading: Float,
    fuelEstimate: FuelEstimate?,
) {
    canvas.drawColor(InstrumentColors.black)
    val magneticHeadingValid = navigationSolution?.magneticHeadingValid == true
    drawCompass(canvas, animatedHeading, magneticHeadingValid)
    drawFuelArc(canvas, fuelEstimate)
    drawReadouts(canvas, navigationSolution, fuelEstimate)
}

private fun drawCompass(canvas: AndroidCanvas, trackDegrees: Float, magneticHeadingValid: Boolean) {
    val track = trackDegrees.toDouble()
    val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = InstrumentColors.tick
        strokeWidth = 1.5f
        strokeCap = Paint.Cap.ROUND
    }
    val majorPaint = Paint(tickPaint).apply {
        color = InstrumentColors.muted
        strokeWidth = 2.4f
    }

    // These labels move with the rose and are much smaller than the cardinal glyphs. A larger
    // bold face keeps their strokes legible while reducing the apparent raster shimmer in motion.
    val labelPaint = textPaint(16f, InstrumentColors.white, bold = true)
    for (bearing in 0 until 360 step 5) {
        val screenAngle = Math.toRadians(bearing - track)
        val major = bearing % 30 == 0
        val isCardinal = bearing % 90 == 0
        if (isCardinal || (!magneticHeadingValid && major)) continue
        val outerRadius = 203f
        val innerRadius = if (major) 194f else 199f
        val x1 = CENTER + kotlin.math.sin(screenAngle).toFloat() * innerRadius
        val y1 = CENTER - kotlin.math.cos(screenAngle).toFloat() * innerRadius
        val x2 = CENTER + kotlin.math.sin(screenAngle).toFloat() * outerRadius
        val y2 = CENTER - kotlin.math.cos(screenAngle).toFloat() * outerRadius
        canvas.drawLine(x1, y1, x2, y2, if (major) majorPaint else tickPaint)
        if (major) {
            val labelRadius = 179f
            val labelX = CENTER + kotlin.math.sin(screenAngle).toFloat() * labelRadius
            val labelY = CENTER - kotlin.math.cos(screenAngle).toFloat() * labelRadius + 4f
            canvas.drawText(bearing.toString().padStart(3, '0'), labelX, labelY, labelPaint)
        }
    }

    if (magneticHeadingValid) {
        drawCardinal(canvas, 0, "N", track, InstrumentColors.compassRed)
        drawCardinal(canvas, 90, "E", track, InstrumentColors.compassOrange)
        drawCardinal(canvas, 180, "S", track, InstrumentColors.compassOrange)
        drawCardinal(canvas, 270, "W", track, InstrumentColors.compassOrange)
    }
    drawTrackIndex(canvas)
}

private fun drawCardinal(canvas: AndroidCanvas, bearing: Int, label: String, track: Double, color: Int) {
    val angle = Math.toRadians(bearing - track)
    val radius = 193f
    val x = CENTER + kotlin.math.sin(angle).toFloat() * radius
    val y = CENTER - kotlin.math.cos(angle).toFloat() * radius + 5f
    canvas.drawText(label, x, y, textPaint(22f, color, bold = true))
}

private fun drawTrackIndex(canvas: AndroidCanvas) {
    val index = Path().apply {
        moveTo(CENTER, 5f)
        lineTo(CENTER - 6f, 17f)
        lineTo(CENTER + 6f, 17f)
        close()
    }
    canvas.drawPath(index, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = InstrumentColors.cyan })
}

private fun drawFuelArc(canvas: AndroidCanvas, estimate: FuelEstimate?) {
    val bounds = RectF(50f, 50f, DESIGN_SIZE - 50f, DESIGN_SIZE - 50f)
    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = InstrumentColors.dim
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
    if (estimate == null) return
    val fuelColor = when (estimate.status) {
        FuelStatus.NORMAL -> InstrumentColors.cyan
        FuelStatus.LOW -> InstrumentColors.amber
        FuelStatus.CRITICAL -> InstrumentColors.compassRed
    }
    val fuelPaint = Paint(trackPaint).apply {
        color = fuelColor
        strokeCap = Paint.Cap.ROUND
    }
    val fraction = (estimate.remainingFuelL / estimate.initialFuelL).toFloat().coerceIn(0f, 1f)
    if (fraction > 0f) canvas.drawArc(bounds, -90f, 360f * fraction, false, fuelPaint)
}

private fun drawReadouts(
    canvas: AndroidCanvas,
    navigationSolution: NavigationSolution?,
    fuelEstimate: FuelEstimate?,
) {
    val headingText = navigationSolution?.directionDegrees?.roundToInt()?.let { ((it % 360) + 360) % 360 }
        ?.toString()?.padStart(3, '0') ?: "---"
    val headingLabel = when {
        navigationSolution?.source == NavigationSource.GPS -> "TRK"
        navigationSolution?.magneticHeadingValid == true -> "MAG"
        else -> "REL"
    }
    val headingLabelColor = if (headingLabel == "MAG") InstrumentColors.amber else InstrumentColors.muted
    canvas.drawText(headingLabel, CENTER, 96f, textPaint(15f, headingLabelColor, bold = true))
    canvas.drawText("$headingText\u00B0", CENTER, 124f, textPaint(32f, InstrumentColors.white, bold = true))
    canvas.drawText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), CENTER, 145f,
        textPaint(20f, InstrumentColors.white, bold = true))
    val status = when {
        navigationSolution == null -> "ALIGN"
        navigationSolution.source == NavigationSource.GPS -> "GPS"
        navigationSolution.magneticHeadingValid -> "MAG + INS"
        else -> "REL INS"
    }
    canvas.drawText(status, CENTER, 165f, textPaint(11f, InstrumentColors.amber, bold = true))

    val homeAngle = if (navigationSolution?.homeBearingDegrees != null && navigationSolution.directionDegrees != null) {
        normalizeSignedDegrees(navigationSolution.homeBearingDegrees - navigationSolution.directionDegrees)
    } else null
    if (homeAngle != null) drawHomeArrow(canvas, homeAngle)
    canvas.drawText("HOME ${navigationSolution?.homeDistanceM?.let(::formatDistance) ?: "--"}", CENTER, 280f,
        textPaint(22f, InstrumentColors.white, bold = true))

    canvas.drawText("REL H", 95f, 204f, textPaint(14f, InstrumentColors.muted, bold = true))
    canvas.drawText(navigationSolution?.localUpM?.let { "${signedInt(it)} m" } ?: "--", 95f, 229f,
        textPaint(22f, InstrumentColors.white, bold = true))
    canvas.drawText("GS EST", 325f, 204f, textPaint(14f, InstrumentColors.muted, bold = true))
    val groundSpeedKmh = navigationSolution?.groundSpeedMps?.times(3.6f)
    canvas.drawText(groundSpeedKmh?.let { "${it.roundToInt()} km/h" } ?: "--", 325f, 229f,
        textPaint(21f, InstrumentColors.white, bold = true))

    canvas.drawText("INS", 130f, 304f, textPaint(14f, InstrumentColors.muted, bold = true))
    canvas.drawText(navigationSolution?.insElapsedSeconds?.let(::formatElapsed) ?: "--:--", 130f, 329f,
        textPaint(18f, InstrumentColors.white, bold = true))
    canvas.drawText("V/S EST", 290f, 304f, textPaint(14f, InstrumentColors.muted, bold = true))
    canvas.drawText(navigationSolution?.verticalSpeedMps?.let { signedDecimal(it) + " m/s" } ?: "--", 290f, 329f,
        textPaint(18f, InstrumentColors.white, bold = true))

    val fuelText = fuelEstimate?.let {
        "FUEL ~${String.format(Locale.getDefault(), "%.1f", it.remainingFuelL)} L"
    } ?: "FUEL --"
    val fuelColor = when (fuelEstimate?.status) {
        FuelStatus.NORMAL -> InstrumentColors.cyan
        FuelStatus.LOW -> InstrumentColors.amber
        FuelStatus.CRITICAL -> InstrumentColors.compassRed
        null -> InstrumentColors.muted
    }
    canvas.drawText(fuelText, CENTER, 352f, textPaint(16f, fuelColor, bold = true))
}
private fun drawHomeArrow(canvas: AndroidCanvas, relativeBearingDegrees: Float) {
    val arrow = Path().apply {
        moveTo(CENTER, 184f)
        lineTo(236f, 225f)
        lineTo(219f, 218f)
        lineTo(219f, 252f)
        lineTo(201f, 252f)
        lineTo(201f, 218f)
        lineTo(184f, 225f)
        close()
    }
    val saved = canvas.save()
    canvas.rotate(relativeBearingDegrees, CENTER, CENTER)
    canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = InstrumentColors.green })
    canvas.restoreToCount(saved)
}

private fun formatDistance(distanceM: Float): String = if (distanceM >= 1_000f) {
    String.format(Locale.getDefault(), "%.1f km", distanceM / 1_000f)
} else {
    "${distanceM.roundToInt()} m"
}

private fun formatElapsed(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d", hours, minutes)
    else String.format(Locale.getDefault(), "%02d:%02d", minutes, remainingSeconds)
}

private fun signedInt(value: Double): String = String.format(Locale.getDefault(), "%+d", value.roundToInt())
private fun signedDecimal(value: Float): String = String.format(Locale.getDefault(), "%+.1f", value)
private fun normalizeSignedDegrees(value: Float): Float = ((value + 540f) % 360f) - 180f

private fun textPaint(size: Float, color: Int, bold: Boolean): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        textAlign = Paint.Align.CENTER
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        else Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
