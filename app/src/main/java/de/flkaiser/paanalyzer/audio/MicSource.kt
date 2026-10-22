package de.flkaiser.paanalyzer.audio

import android.media.MediaRecorder
import android.os.Build

/**
 * Android's microphone sources, as offered by Spectroid. They differ in the processing the phone
 * applies before the app gets the audio (high-pass, gain control, noise suppression, EQ); which
 * processing a source gets is up to the phone maker.
 */
enum class MicSource(val label: String, val description: String) {
    AUTO("Automatic", "Unprocessed if the phone offers it, otherwise voice recognition (as for measurements)"),
    UNPROCESSED("Unprocessed", "No processing at all: what the microphone really picks up, including rumble below 50 Hz"),
    VOICE_RECOGNITION("Voice recognition", "Usually no gain control; some phones filter the deep bass"),
    VOICE_PERFORMANCE("Voice performance", "Low latency, meant for live singing; little processing"),
    MIC("Microphone", "The standard microphone source; may filter the bass and adjust the gain"),
    CAMCORDER("Camcorder", "Tuned for video recording; often with gain control"),
    DEFAULT("Default", "The phone's default source (like Spectroid's \"Default\")"),
    ;

    /** The MediaRecorder.AudioSource constant, resolving [AUTO]. */
    fun androidSource(supportsUnprocessed: Boolean): Int = when (this) {
        AUTO -> if (supportsUnprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        VOICE_PERFORMANCE -> MediaRecorder.AudioSource.VOICE_PERFORMANCE
        MIC -> MediaRecorder.AudioSource.MIC
        CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
        DEFAULT -> MediaRecorder.AudioSource.DEFAULT
    }

    companion object {
        /** Sources this phone offers. */
        fun available(supportsUnprocessed: Boolean, sdk: Int = Build.VERSION.SDK_INT): List<MicSource> = entries.filter {
            when (it) {
                UNPROCESSED -> supportsUnprocessed
                VOICE_PERFORMANCE -> sdk >= Build.VERSION_CODES.Q
                else -> true
            }
        }
    }
}
