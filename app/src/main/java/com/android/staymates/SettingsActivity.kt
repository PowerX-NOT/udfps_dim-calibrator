package dev.udfps.calibrator

import android.content.Context
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val dimDelayInput = findViewById<TextInputEditText>(R.id.dimDelayInput)
        val currentBrightnessInput = findViewById<TextInputEditText>(R.id.currentBrightnessInput)
        val maxBrightnessInput = findViewById<TextInputEditText>(R.id.maxBrightnessInput)
        val hbmNodeInput = findViewById<TextInputEditText>(R.id.hbmNodeInput)
        val saveButton = findViewById<MaterialButton>(R.id.saveButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentDelay = prefs.getLong(KEY_DIM_DELAY_MS, DEFAULT_DIM_DELAY_MS)
        dimDelayInput.setText(currentDelay.toString())

        // Load saved paths or defaults
        currentBrightnessInput.setText(prefs.getString(KEY_CURRENT_BRIGHTNESS_NODE, DEFAULT_CURRENT_BRIGHTNESS_NODE))
        maxBrightnessInput.setText(prefs.getString(KEY_MAX_BRIGHTNESS_NODE, DEFAULT_MAX_BRIGHTNESS_NODE))
        hbmNodeInput.setText(prefs.getString(KEY_HBM_NODE, DEFAULT_HBM_NODE))

        saveButton.setOnClickListener {
            val delay = dimDelayInput.text?.toString()?.trim()?.toLongOrNull()
            if (delay == null || delay < 0) {
                statusText.text = "Invalid delay"
                return@setOnClickListener
            }

            val currentBrightnessPath = currentBrightnessInput.text?.toString()?.trim() ?: DEFAULT_CURRENT_BRIGHTNESS_NODE
            val maxBrightnessPath = maxBrightnessInput.text?.toString()?.trim() ?: DEFAULT_MAX_BRIGHTNESS_NODE
            val hbmPath = hbmNodeInput.text?.toString()?.trim() ?: DEFAULT_HBM_NODE

            prefs.edit().apply {
                putLong(KEY_DIM_DELAY_MS, delay)
                putString(KEY_CURRENT_BRIGHTNESS_NODE, currentBrightnessPath)
                putString(KEY_MAX_BRIGHTNESS_NODE, maxBrightnessPath)
                putString(KEY_HBM_NODE, hbmPath)
                apply()
            }
            statusText.text = "Saved"
        }
    }

    companion object {
        const val PREFS_NAME = "udfps_calibrator"
        const val KEY_DIM_DELAY_MS = "dim_delay_ms"
        const val DEFAULT_DIM_DELAY_MS = 0L

        const val KEY_CURRENT_BRIGHTNESS_NODE = "current_brightness_node"
        const val KEY_MAX_BRIGHTNESS_NODE = "max_brightness_node"
        const val KEY_HBM_NODE = "hbm_node"

        const val DEFAULT_CURRENT_BRIGHTNESS_NODE = "/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/backlight/panel0-backlight/brightness"
        const val DEFAULT_MAX_BRIGHTNESS_NODE = "/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/backlight/panel0-backlight/max_brightness"
        const val DEFAULT_HBM_NODE = "/sys/kernel/oplus_display/hbm"
    }
}
