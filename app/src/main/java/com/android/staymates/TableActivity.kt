package dev.udfps.calibrator

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlin.math.pow

class TableActivity : AppCompatActivity() {

    private var generatedTable: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_table)

        val tableInfo = findViewById<TextView>(R.id.tableInfo)
        val tableContent = findViewById<TextView>(R.id.tableContent)
        val generateButton = findViewById<MaterialButton>(R.id.generateButton)
        val copyButton = findViewById<MaterialButton>(R.id.copyButton)

        // Load calibration params from SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val maxBacklight = prefs.getInt(KEY_MAX_BACKLIGHT, 2047)
        val nitsWithHbm = prefs.getFloat(KEY_NITS_WITH_HBM, 0f)
        val nitsWithoutHbm = prefs.getFloat(KEY_NITS_WITHOUT_HBM, 0f)
        val gamma = prefs.getFloat(KEY_GAMMA, 2.2f)

        tableInfo.text = "Max: $maxBacklight | Nits w/HBM: $nitsWithHbm | Nits w/o: $nitsWithoutHbm | Gamma: $gamma"

        generateButton.setOnClickListener {
            if (nitsWithHbm <= 0 || nitsWithoutHbm <= 0 || gamma <= 0) {
                Toast.makeText(this, "Set calibration values in main screen first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            generatedTable = generateLutTable(maxBacklight, nitsWithHbm, nitsWithoutHbm, gamma)
            tableContent.text = generatedTable
        }

        copyButton.setOnClickListener {
            if (generatedTable.isEmpty()) {
                Toast.makeText(this, "Generate table first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = ClipData.newPlainText("UDFPS LUT", generatedTable)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateLutTable(
        maxBacklight: Int,
        nitsWithHbm: Float,
        nitsWithoutHbm: Float,
        gamma: Float
    ): String {
        val backlightRatio = (maxBacklight.toFloat() * nitsWithHbm) / nitsWithoutHbm

        // Generate A2 values based on max_backlight (similar to 1.py)
        val a2Values = when (maxBacklight) {
            2047 -> listOf(0, 3, 13, 31, 58, 96, 143, 200, 269, 348, 439, 551, 667, 794, 934, 1086, 1250, 1427, 1618, 1821, 2047)
            4095 -> listOf(0, 6, 26, 62, 116, 192, 286, 400, 538, 696, 878, 1102, 1334, 1588, 1868, 2172, 2500, 2854, 3236, 3642, 4095)
            1023 -> listOf(0, 1, 16, 33, 48, 97, 146, 194, 243, 292, 341, 389, 438, 487, 535, 584, 633, 682, 730, 779, 828, 876, 925, 974, 1023)
            else -> (0..maxBacklight step maxBacklight / 20).toList()
        }

        val sb = StringBuilder()
        sb.appendLine("<!-- Framework dimming array -->")
        sb.appendLine("<integer-array name=\"config_udfpsDimmingBrightnessAlphaArray\">")
        sb.appendLine()
        for (a2 in a2Values) {
            val base = (a2.toFloat() / backlightRatio).coerceAtLeast(0.000001f)
            val pow = base.toDouble().pow(1.0 / gamma.toDouble()).toFloat()
            val alpha255 = (1f - pow) * 255f
            val alpha = alpha255.toInt().coerceIn(0, 255)
            sb.appendLine("    <item>$a2,$alpha</item>")
        }
        sb.appendLine()
        sb.appendLine("</integer-array>")

        return sb.toString()
    }

    companion object {
        const val PREFS_NAME = "udfps_calibrator"
        const val KEY_MAX_BACKLIGHT = "max_backlight"
        const val KEY_NITS_WITH_HBM = "nits_with_hbm"
        const val KEY_NITS_WITHOUT_HBM = "nits_without_hbm"
        const val KEY_GAMMA = "gamma"
    }
}
