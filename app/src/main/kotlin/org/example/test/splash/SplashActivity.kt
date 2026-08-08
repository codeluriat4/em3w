package org.example.test.splash

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.test.MainActivity
import org.example.test.SyncoraApplication
import org.example.test.R
import org.example.test.bitget.PipelineState
import org.example.test.onboarding.OnboardingActivity
import org.example.test.onboarding.OnboardingPreferences
import org.example.test.orb.OrbView

/**
 * Splash always shows first on every cold app open. It stays on screen — orb animating,
 * nothing else changing — until the initial chart data has actually arrived (or keeps
 * retrying quietly if there's no internet), then hands off to either Onboarding (first-ever
 * open only) or straight to the home chart screen (every subsequent open).
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var orbView: OrbView
    private lateinit var app: SyncoraApplication
    private lateinit var onboardingPreferences: OnboardingPreferences

    private var hasNavigated = false
    private var readinessJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        app = application as SyncoraApplication
        onboardingPreferences = OnboardingPreferences(this)

        orbView = findViewById(R.id.orbView)
        orbView.setBackgroundColorHex("#000000")
        orbView.hue = 0f
        orbView.hoverIntensity = 0.1f
        orbView.rotateOnHover = true
        // Same treatment as onboarding: no explicit "hover" affordance to teach here
        // either, so the orb animates on its own.
        orbView.forceHoverState = true

        // Brand palette: bright near-white teal + brand teal in the outer glow, with a
        // near-black teal anchoring the shadow side, matching the onboarding orb exactly.
        orbView.setColor1Hex("#26C7C3")
        orbView.setColor2Hex("#BFF6F2")
        orbView.setColor3Hex("#02201F")
        orbView.saturation = 0.75f

        // Kick the shared connection off immediately (no-op if it's already running) so
        // it's warming up while the splash animation plays.
        app.ensureMarketDataStarted()
    }

    override fun onResume() {
        super.onResume()
        orbView.onResume()
        awaitChartReadyThenNavigate()
    }

    override fun onPause() {
        readinessJob?.cancel()
        orbView.onPause()
        super.onPause()
    }

    /**
     * Holds on the splash screen — UI state unchanged — until the pipeline reports the
     * first full snapshot of candles (PipelineState.LIVE). Any other state (still
     * connecting, or SNAPSHOT_RETRYING because of a failed/no-internet fetch) simply means
     * we keep waiting; the pipeline already retries on its own with backoff, so there's
     * nothing extra to do here except not navigate yet.
     */
    private fun awaitChartReadyThenNavigate() {
        readinessJob?.cancel()
        readinessJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val enteredAt = System.currentTimeMillis()
                app.pipeline.pipelineState.collect { state ->
                    if (state == PipelineState.LIVE) {
                        val elapsed = System.currentTimeMillis() - enteredAt
                        if (elapsed < MIN_SPLASH_DURATION_MS) {
                            delay(MIN_SPLASH_DURATION_MS - elapsed)
                        }
                        navigateOnward()
                    }
                }
            }
        }
    }

    private fun navigateOnward() {
        if (hasNavigated || isFinishing) return
        hasNavigated = true

        val destination = if (onboardingPreferences.hasCompletedOnboarding) {
            MainActivity::class.java
        } else {
            OnboardingActivity::class.java
        }
        startActivity(Intent(this, destination))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private companion object {
        // Floor on how long the splash stays up once data is ready, so it always reads as
        // an intentional brand moment rather than a flicker on fast connections.
        const val MIN_SPLASH_DURATION_MS = 1200L
    }
}
