package com.diegouc3m.whatsappblock

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** The full policy is bundled with the app and works without a network connection. */
class PrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.privacy_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val policy = assets.open("privacy_policy.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        content.addView(TextView(this).apply {
            text = HtmlCompat.fromHtml(policy, HtmlCompat.FROM_HTML_MODE_LEGACY)
            textSize = 16f
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(Button(this).apply {
            setText(R.string.privacy_public_link)
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_url))))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(this).apply { addView(content); clipToPadding = false }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
