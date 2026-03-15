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
        val saveButton = findViewById<MaterialButton>(R.id.saveButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentDelay = prefs.getLong(KEY_DIM_DELAY_MS, DEFAULT_DIM_DELAY_MS)
        dimDelayInput.setText(currentDelay.toString())

        saveButton.setOnClickListener {
            val delay = dimDelayInput.text?.toString()?.trim()?.toLongOrNull()
            if (delay == null || delay < 0) {
                statusText.text = "Invalid delay"
                return@setOnClickListener
            }
            prefs.edit().putLong(KEY_DIM_DELAY_MS, delay).apply()
            statusText.text = "Saved"
        }
    }

    companion object {
        const val PREFS_NAME = "udfps_calibrator"
        const val KEY_DIM_DELAY_MS = "dim_delay_ms"
        const val DEFAULT_DIM_DELAY_MS = 0L
    }
}
