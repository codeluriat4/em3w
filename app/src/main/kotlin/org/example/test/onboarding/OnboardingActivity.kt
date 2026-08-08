package org.example.test.onboarding

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.example.test.MainActivity
import org.example.test.R
import org.example.test.orb.OrbView

/**
 * One-time, first-install-only screen shown before [MainActivity]. Introduces the
 * brand with a full-screen animated orb background, a two-line marketing headline,
 * supporting copy, and a single Continue action.
 *
 * Beyond reading/writing [OnboardingPreferences.hasCompletedOnboarding], this activity
 * makes no assumptions about what launched it or what happens after it finishes — it
 * always hands off to [MainActivity] on Continue.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var orbView: OrbView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        val headlineText = findViewById<TextView>(R.id.headlineText)
        headlineText.text = buildHeadline()

        orbView = findViewById(R.id.orbView)
        configureOrb(orbView)

        findViewById<ImageButton>(R.id.continueButton).setOnClickListener {
            OnboardingPreferences(this).hasCompletedOnboarding = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        orbView.onResume()
    }

    override fun onPause() {
        orbView.onPause()
        super.onPause()
    }

    private fun buildHeadline(): SpannableString {
        val headline = getString(R.string.onboarding_headline)
        val spannable = SpannableString(headline)
        spannable.setSpan(
            ForegroundColorSpan(Color.WHITE),
            0,
            headline.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val highlight = "Brag responsibly."
        val start = headline.indexOf(highlight)
        if (start >= 0) {
            spannable.setSpan(
                ForegroundColorSpan(Color.parseColor("#26C7C3")),
                start,
                start + highlight.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return spannable
    }

    private fun configureOrb(orbView: OrbView) {
        orbView.setBackgroundColorHex("#000000")
        orbView.hue = 0f
        orbView.hoverIntensity = 0.1f
        orbView.rotateOnHover = true
        orbView.forceHoverState = true // animates on its own; real touch still works too

        orbView.setColor1Hex("#26C7C3") // brand teal
        orbView.setColor2Hex("#BFF6F2") // near-white teal
        orbView.setColor3Hex("#02201F") // near-black teal (shadow side)
        orbView.saturation = 0.75f
    }
}
