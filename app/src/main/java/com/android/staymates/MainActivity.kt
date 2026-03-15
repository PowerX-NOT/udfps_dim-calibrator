package dev.udfps.calibrator

import android.graphics.Color
import android.os.Bundle
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
        val hbmToggle = findViewById<SwitchMaterial>(R.id.hbmToggle)
        val nitsWithHbmInput = findViewById<TextInputEditText>(R.id.nitsWithHbmInput)
        val nitsWithoutHbmInput = findViewById<TextInputEditText>(R.id.nitsWithoutHbmInput)
        val gammaInput = findViewById<TextInputEditText>(R.id.gammaInput)
        val applyCalibration = findViewById<MaterialButton>(R.id.applyCalibration)
        val statusText = findViewById<TextView>(R.id.statusText)

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

        // Initialize HBM toggle from sysfs
        val hbmRaw = suRead(HBM_NODE)
        if (hbmRaw != null) {
            hbmToggle.isChecked = hbmRaw.trim() == "1"
        }

        hbmToggle.setOnCheckedChangeListener { _, isChecked ->
            val value = if (isChecked) "1" else "0"
            val ok = suWrite(HBM_NODE, value)
            statusText.text = if (ok) {
                "HBM set to $value"
            } else {
                "Failed to set HBM (root?)"
            }

            // When toggled, immediately (re)apply dim overlay based on calibration if present.
            applyDimOverlayIfPossible(statusText)
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

            statusText.text = "Calibration set. Toggle HBM to apply dimming."
            applyDimOverlayIfPossible(statusText)
        }
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

        val currentBrightness = suRead(CURRENT_BRIGHTNESS_NODE)?.trim()?.toIntOrNull()
        if (currentBrightness == null) {
            statusText.text = "Failed to read current brightness"
            removeDimOverlay()
            return
        }

        val brightness = currentBrightness
        val alpha = computeAlphaFromFormula(brightness, params)
        addOrUpdateDimOverlay(alpha)
        statusText.text = "Dim overlay alpha=${String.format("%.3f", alpha)}"
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
        // Mirrors 1.py: result = (1 - (A2 / backlight_ratio)^(1/gamma)) * 255
        // where backlight_ratio = (max_backlight * nits_with_hbm) / nits_without_hbm
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