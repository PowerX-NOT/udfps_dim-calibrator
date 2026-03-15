package dev.udfps.calibrator

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlin.math.pow

class MainActivity : AppCompatActivity() {
    private var dimView: View? = null
    private var maxBacklight: Int? = null
    private var calibration: CalibrationParams? = null
    private var brightnessAlphaMap: Map<Int, Int>? = null
    private var dimTableSource: String? = null
    private var suppressHbmListener = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val maxBrightnessValue = findViewById<TextView>(R.id.maxBrightnessValue)
        val currentBrightnessValue = findViewById<TextView>(R.id.currentBrightnessValue)
        val hbmToggle = findViewById<SwitchMaterial>(R.id.hbmToggle)
        val nitsWithHbmInput = findViewById<TextInputEditText>(R.id.nitsWithHbmInput)
        val nitsWithoutHbmInput = findViewById<TextInputEditText>(R.id.nitsWithoutHbmInput)
        val gammaInput = findViewById<TextInputEditText>(R.id.gammaInput)
        val applyCalibration = findViewById<MaterialButton>(R.id.applyCalibration)
        val loadTable = findViewById<MaterialButton>(R.id.loadTable)
        val tableInput = findViewById<TextInputEditText>(R.id.tableInput)
        val openSettings = findViewById<MaterialButton>(R.id.openSettings)
        val openTable = findViewById<MaterialButton>(R.id.openTable)
        val statusText = findViewById<TextView>(R.id.statusText)

        loadTable.setOnClickListener {
            val tableText = tableInput.text?.toString() ?: ""
            if (tableText.isBlank()) {
                statusText.text = "Paste table content first"
                return@setOnClickListener
            }

            val loadedMap = parseFrameworkTable(tableText)
            if (loadedMap.isEmpty()) {
                statusText.text = "Failed to parse table. Format: <item>brightness,alpha</item>"
                return@setOnClickListener
            }

            brightnessAlphaMap = loadedMap
            dimTableSource = "loaded (${loadedMap.size} entries)"
            calibration = CalibrationParams(
                maxBacklight = loadedMap.keys.max(),
                nitsWithHbm = 0f, // unknown when loading from table
                nitsWithoutHbm = 0f,
                gamma = 0f
            )
            statusText.text = "Loaded table with ${loadedMap.size} entries. Toggle HBM to apply."
        }

        openSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        openTable.setOnClickListener {
            startActivity(Intent(this, TableActivity::class.java))
        }

        ensureRoot(statusText)

        // Load max_backlight from sysfs and show it.
        val maxBrightnessRaw = suRead(MAX_BRIGHTNESS_NODE)
        if (maxBrightnessRaw != null) {
            val parsed = maxBrightnessRaw.trim().toIntOrNull()
            maxBacklight = parsed
            maxBrightnessValue.text = "Max brightness: ${maxBrightnessRaw.trim()}"
        } else {
            maxBrightnessValue.text = "Max brightness: (failed to read)"
        }

        updateCurrentBrightnessText(currentBrightnessValue)

        // Initialize HBM toggle from sysfs
        val hbmRaw = suRead(HBM_NODE)
        if (hbmRaw != null) {
            suppressHbmListener = true
            hbmToggle.isChecked = hbmRaw.trim() == "1"
            suppressHbmListener = false
        }

        hbmToggle.setOnCheckedChangeListener { _, isChecked ->
            if (suppressHbmListener) return@setOnCheckedChangeListener
            onHbmToggleRequested(isChecked, statusText, currentBrightnessValue)
        }

        applyCalibration.setOnClickListener {
            val nitsWith = nitsWithHbmInput.text?.toString()?.trim()?.toFloatOrNull()
            val nitsWithout = nitsWithoutHbmInput.text?.toString()?.trim()?.toFloatOrNull()
            val gamma = gammaInput.text?.toString()?.trim()?.toFloatOrNull()
            val maxBl = maxBacklight

            if (nitsWith == null || nitsWithout == null || gamma == null) {
                statusText.text = "Enter nits_with_hbm, nits_without_hbm and gamma"
                return@setOnClickListener
            }
            if (maxBl == null) {
                statusText.text = "max_brightness unavailable"
                return@setOnClickListener
            }
            if (nitsWithout == 0f || gamma == 0f) {
                statusText.text = "Invalid values"
                return@setOnClickListener
            }

            calibration = CalibrationParams(
                maxBacklight = maxBl,
                nitsWithHbm = nitsWith,
                nitsWithoutHbm = nitsWithout,
                gamma = gamma,
            )

            // Generate LUT table for framework dimming
            brightnessAlphaMap = generateBrightnessAlphaMap(maxBl, nitsWith, nitsWithout, gamma)
            dimTableSource = "generated (nits:${nitsWith}/${nitsWithout}, g:${gamma})"

            // Save to SharedPreferences for TableActivity
            getSharedPreferences(TableActivity.PREFS_NAME, MODE_PRIVATE).edit().apply {
                putInt(TableActivity.KEY_MAX_BACKLIGHT, maxBl)
                putFloat(TableActivity.KEY_NITS_WITH_HBM, nitsWith)
                putFloat(TableActivity.KEY_NITS_WITHOUT_HBM, nitsWithout)
                putFloat(TableActivity.KEY_GAMMA, gamma)
                apply()
            }

            statusText.text = "Calibration set (${brightnessAlphaMap?.size} LUT entries). Toggle HBM to apply dimming."
            applyDimOverlayIfPossible(statusText)
            updateCurrentBrightnessText(currentBrightnessValue)
        }
    }

    private fun onHbmToggleRequested(enable: Boolean, statusText: TextView, currentBrightnessValue: TextView) {
        val hasFrameworkDimming = calibration != null
        val value = if (enable) "1" else "0"

        if (!hasFrameworkDimming) {
            val ok = suWrite(HBM_NODE, value)
            val source = dimTableSource ?: "none"
            statusText.text = "HBM set to $value (dim: $source)"
            applyDimOverlayIfPossible(statusText)
            updateCurrentBrightnessText(currentBrightnessValue)
            return
        }

        if (enable) {
            // Delay dim layer slightly if configured, then enable HBM on the next frame.
            val alpha = computeCurrentAlphaOrNull() ?: run {
                statusText.text = "Failed to read current brightness"
                return
            }
            val delayMs = getDimDelayMs()
            mainHandler.postDelayed({
                addOrUpdateDimOverlay(alpha)
                dimView?.post {
                    val ok = suWrite(HBM_NODE, "1")
                    val source = dimTableSource ?: "formula"
                    statusText.text = "HBM set to 1 (dim: $source)"
                    // Keep current dim layer; further updates happen on next user action.
                    updateCurrentBrightnessText(currentBrightnessValue)
                }
            }, delayMs)
        } else {
            // Disable HBM first (next frame), then remove dim layer after configured delay.
            val delayMs = getDimDelayMs()
            dimView?.post {
                val ok = suWrite(HBM_NODE, "0")
                statusText.text = "HBM set to 0"
                mainHandler.postDelayed({ removeDimOverlay() }, delayMs)
                updateCurrentBrightnessText(currentBrightnessValue)
            } ?: run {
                val ok = suWrite(HBM_NODE, "0")
                statusText.text = "HBM set to 0"
                mainHandler.postDelayed({ removeDimOverlay() }, delayMs)
                updateCurrentBrightnessText(currentBrightnessValue)
            }
        }
    }

    private fun updateCurrentBrightnessText(view: TextView) {
        val brightness = suRead(CURRENT_BRIGHTNESS_NODE)?.trim()
        view.text = if (brightness != null) {
            "Current brightness: $brightness"
        } else {
            "Current brightness: (failed to read)"
        }
    }

    private fun getDimDelayMs(): Long {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        return prefs.getLong(SettingsActivity.KEY_DIM_DELAY_MS, SettingsActivity.DEFAULT_DIM_DELAY_MS)
            .coerceAtLeast(0L)
    }

    private fun ensureRoot(statusText: TextView) {
        val ok = suExec("id")?.first == true
        statusText.text = if (ok) "Root granted" else "Root not granted (su failed)"
    }

    private fun applyDimOverlayIfPossible(statusText: TextView) {
        val params = calibration
        if (params == null) {
            removeDimOverlay()
            return
        }

        val hbmEnabled = suRead(HBM_NODE)?.trim() == "1"
        if (!hbmEnabled) {
            removeDimOverlay()
            return
        }

        val alpha = computeCurrentAlphaOrNull() ?: run {
            statusText.text = "Failed to read current brightness"
            removeDimOverlay()
            return
        }
        addOrUpdateDimOverlay(alpha)
        statusText.text = "Dim overlay alpha=${String.format("%.3f", alpha)}"
    }

    private fun parseFrameworkTable(tableText: String): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        // Pattern to match <item>brightness,alpha</item>
        val regex = "<item>(\\d+),(\\d+)</item>".toRegex()
        val matches = regex.findAll(tableText)
        
        for (match in matches) {
            val brightness = match.groupValues[1].toInt()
            val alpha = match.groupValues[2].toInt()
            map[brightness] = alpha.coerceIn(0, 255)
        }
        
        return map
    }

    private fun computeCurrentAlphaOrNull(): Float? {
        val map = brightnessAlphaMap ?: return null
        val currentBrightness = suRead(CURRENT_BRIGHTNESS_NODE)?.trim()?.toIntOrNull() ?: return null
        return lookupOrInterpolateAlpha(currentBrightness, map)
    }

    private fun lookupOrInterpolateAlpha(brightness: Int, map: Map<Int, Int>): Float {
        // Exact match
        map[brightness]?.let { return it / 255f }

        // Find surrounding entries for interpolation
        val lowerEntry = map.entries.lastOrNull { it.key <= brightness }
        val upperEntry = map.entries.firstOrNull { it.key >= brightness }

        return when {
            lowerEntry == null && upperEntry == null -> 0f
            lowerEntry == null -> upperEntry!!.value / 255f
            upperEntry == null -> lowerEntry.value / 255f
            lowerEntry.key == upperEntry.key -> lowerEntry.value / 255f
            else -> {
                // Linear interpolation
                val (b1, a1) = lowerEntry.toPair()
                val (b2, a2) = upperEntry.toPair()
                val alpha = a1 + (brightness - b1) * (a2 - a1) / (b2 - b1)
                alpha / 255f
            }
        }
    }

    private fun generateBrightnessAlphaMap(
        maxBacklight: Int,
        nitsWithHbm: Float,
        nitsWithoutHbm: Float,
        gamma: Float
    ): Map<Int, Int> {
        val backlightRatio = (maxBacklight.toFloat() * nitsWithHbm) / nitsWithoutHbm

        val a2Values = when (maxBacklight) {
            2047 -> listOf(0, 3, 13, 31, 58, 96, 143, 200, 269, 348, 439, 551, 667, 794, 934, 1086, 1250, 1427, 1618, 1821, 2047)
            4095 -> listOf(0, 6, 26, 62, 116, 192, 286, 400, 538, 696, 878, 1102, 1334, 1588, 1868, 2172, 2500, 2854, 3236, 3642, 4095)
            1023 -> listOf(0, 1, 16, 33, 48, 97, 146, 194, 243, 292, 341, 389, 438, 487, 535, 584, 633, 682, 730, 779, 828, 876, 925, 974, 1023)
            else -> (0..maxBacklight step maxBacklight / 20).toList()
        }

        return a2Values.associate { a2 ->
            val base = (a2.toFloat() / backlightRatio).coerceAtLeast(0.000001f)
            val pow = base.toDouble().pow(1.0 / gamma.toDouble()).toFloat()
            val alpha255 = (1f - pow) * 255f
            val alpha = alpha255.toInt().coerceIn(0, 255)
            a2 to alpha
        }
    }

    private fun addOrUpdateDimOverlay(alpha: Float) {
        val root = findViewById<View>(android.R.id.content)
        val overlay = dimView ?: View(this).also { v ->
            v.setBackgroundColor(Color.BLACK)
            v.alpha = 0f
            v.isClickable = false
            (root as? android.view.ViewGroup)?.addView(
                v,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )
            dimView = v
        }
        overlay.alpha = alpha.coerceIn(0f, 1f)
        overlay.visibility = View.VISIBLE
    }

    private fun removeDimOverlay() {
        val v = dimView ?: return
        v.visibility = View.GONE
    }

    private data class CalibrationParams(
        val maxBacklight: Int,
        val nitsWithHbm: Float,
        val nitsWithoutHbm: Float,
        val gamma: Float,
    )

    private fun computeAlphaFromFormula(brightness: Int, params: CalibrationParams): Float {
        // Kept for compatibility - but now we primarily use LUT lookup
        val backlightRatio = (params.maxBacklight.toFloat() * params.nitsWithHbm) / params.nitsWithoutHbm
        val a2 = brightness.toFloat().coerceAtLeast(0.0001f)
        val base = (a2 / backlightRatio).coerceAtLeast(0.000001f)
        val pow = base.toDouble().pow(1.0 / params.gamma.toDouble()).toFloat()
        val alpha255 = (1f - pow) * 255f
        return (alpha255 / 255f).coerceIn(0f, 1f)
    }

    private fun suRead(path: String): String? {
        val result = suExec("cat $path")
        return if (result?.first == true) result.second?.trim() else null
    }

    private fun suWrite(path: String, value: String): Boolean {
        val cmd = "sh -c 'echo $value > $path'"
        val result = suExec(cmd)
        return result?.first == true
    }

    private fun suExec(command: String): Pair<Boolean, String?>? {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            Pair(code == 0, out)
        } catch (t: Throwable) {
            Log.e("UDFPSCalibrator", "suExec failed", t)
            null
        }
    }

    private companion object {
        private const val CURRENT_BRIGHTNESS_NODE = "/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/backlight/panel0-backlight/brightness"
        private const val MAX_BRIGHTNESS_NODE = "/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/backlight/panel0-backlight/max_brightness"
        private const val HBM_NODE = "/sys/kernel/oplus_display/hbm"
    }
}