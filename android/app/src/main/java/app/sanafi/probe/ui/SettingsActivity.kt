package app.sanafi.probe.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.sanafi.probe.data.Settings

/** Where the owner types the backend address and the device secret. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(pad, pad, pad, pad)
        }
        fun label(text: String) = column.addView(TextView(this).apply { this.text = text; textSize = 16f; setPadding(0, pad, 0, 0) })

        label("رابط الخادم (يبدأ بـ https://)")
        val url = EditText(this).apply {
            setText(settings.backendUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textDirection = View.TEXT_DIRECTION_LTR
            hint = "https://sanafi.vercel.app"
        }
        column.addView(url)

        label("كلمة سر الجهاز (نفس قيمة DEVICE_SECRET في الخادم)")
        val secret = EditText(this).apply {
            setText(settings.deviceSecret)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            textDirection = View.TEXT_DIRECTION_LTR
        }
        column.addView(secret)

        val media = CheckBox(this).apply {
            text = "وضع الصوت «وسائط» (فعّله إذا ما سمعت سنافي من سماعات السيارة)"
            isChecked = settings.mediaAudio
        }
        column.addView(media)

        label("معرّف الجهاز: ${settings.deviceId}")

        column.addView(Button(this).apply {
            text = "احفظ"
            setOnClickListener {
                val typedUrl = url.text.toString().trim()
                if (!typedUrl.startsWith("https://")) {
                    Toast.makeText(this@SettingsActivity, "الرابط لازم يبدأ بـ https://", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                settings.backendUrl = typedUrl
                settings.deviceSecret = secret.text.toString()
                settings.mediaAudio = media.isChecked
                Toast.makeText(this@SettingsActivity, "انحفظ", Toast.LENGTH_SHORT).show()
                finish()
            }
        })
        setContentView(ScrollView(this).apply { addView(column) })
    }
}
