package de.flkaiser.paanalyzer.screenshots

import de.flkaiser.paanalyzer.dsp.LiveSettings
import de.flkaiser.paanalyzer.dsp.LiveSpectrum
import de.flkaiser.paanalyzer.dsp.LiveSpectrumAnalyzer
import de.flkaiser.paanalyzer.live.LiveFrame
import de.flkaiser.paanalyzer.live.LiveSource
import de.flkaiser.paanalyzer.live.WaterfallHistory
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A live analyzer that has listened to 12 s of a synthetic room: background noise, 50 Hz mains hum
 * with harmonics, a kick drum every half second, a melody with overtones and a whistle gliding up
 * and down. Runs the real analyzer, so the screenshot shows what the app computes.
 */
object DemoLive {
    val settings = LiveSettings()

    val frame: LiveFrame by lazy {
        val fs = settings.sampleRate
        val analyzer = LiveSpectrumAnalyzer(settings)
        val history = WaterfallHistory.create(settings)
        val random = Random(42)
        val block = FloatArray(fs / 100)
        var low = 0.0
        var last: LiveSpectrum? = null
        val melody = doubleArrayOf(220.0, 261.63, 329.63, 392.0, 329.63, 261.63)
        var phase = 0.0
        val total = 12 * fs
        var n = 0
        while (n < total) {
            for (i in block.indices) {
                val t = (n + i).toDouble() / fs
                // Background: white noise plus low-passed noise (rumble), about −75 dBFS.
                val white = gaussian(random) * 2e-4
                low += 0.02 * (gaussian(random) * 4e-3 - low)
                var x = white + low
                // Mains hum and harmonics.
                x += 3e-3 * sin(2 * PI * 50 * t) + 1.2e-3 * sin(2 * PI * 100 * t) + 6e-4 * sin(2 * PI * 150 * t)
                // Kick drum: a decaying 55 Hz tone every 0.5 s.
                val beat = t % 0.5
                x += 0.25 * exp(-beat / 0.09) * sin(2 * PI * (55 + 60 * exp(-beat / 0.02)) * beat)
                // Melody with overtones, one note per 0.5 s.
                val f0 = melody[((t / 0.5).toInt()) % melody.size]
                val envelope = 0.03 * exp(-beat / 0.35)
                for (k in 1..5) x += envelope / k * sin(2 * PI * f0 * k * t)
                // A whistle gliding between 1.5 and 4.5 kHz.
                val glide = 3_000 + 1_500 * sin(2 * PI * t / 9)
                phase += 2 * PI * glide / fs
                x += 0.01 * sin(phase)
                block[i] = x.toFloat()
            }
            analyzer.process(block)?.let {
                history.add(it)
                last = it
            }
            n += block.size
        }
        LiveFrame(last!!, history, settings, LiveSource.MICROPHONE, calibration = null)
    }

    private fun gaussian(random: Random): Double {
        val u = random.nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2 * ln(u)) * kotlin.math.cos(2 * PI * random.nextDouble())
    }
}
