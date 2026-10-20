package de.flkaiser.paanalyzer.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import de.flkaiser.paanalyzer.live.LiveInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class AudioException(message: String) : Exception(message)

/** Plays the sweep on the chosen output (usually Bluetooth) while recording the microphone. */
class AudioEngine(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun outputDevices(): List<AudioDeviceInfo> = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    fun inputDevices(): List<AudioDeviceInfo> = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    /** True when the device offers the unprocessed source (no AGC, noise suppression or EQ). */
    val supportsUnprocessed: Boolean
        get() = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    val micSourceLabel: String get() = if (supportsUnprocessed) "unprocessed" else "voice recognition"

    /**
     * Starts recording, then plays [signal] (mono) and keeps recording until
     * [recordSamples] samples are captured. Runs on [Dispatchers.IO]; cancellation stops both streams.
     */
    suspend fun playAndRecord(
        signal: FloatArray,
        sampleRate: Int,
        recordSamples: Int,
        channel: OutputChannel,
        output: AudioDeviceInfo?,
        input: AudioDeviceInfo?,
        onProgress: (Double) -> Unit,
    ): FloatArray = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw AudioException("Microphone permission is required.")
        }
        val record = createRecord(sampleRate, input)
        val track = try {
            createTrack(sampleRate, output)
        } catch (e: Exception) {
            record.release()
            throw e
        }
        try {
            val recording = FloatArray(recordSamples)
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw AudioException("The microphone could not be started. Is another app using it?")
            }
            // Make sure audio is actually flowing before playback starts.
            var recorded = readInto(record, recording, 0, min(recordSamples, sampleRate / 50))
            coroutineScope {
                val playback = async(Dispatchers.IO) { play(track, signal, channel) }
                while (recorded < recordSamples) {
                    ensureActive()
                    recorded += readInto(record, recording, recorded, min(recordSamples - recorded, sampleRate / 10))
                    onProgress(recorded.toDouble() / recordSamples)
                }
                playback.await()
            }
            recording
        } finally {
            runCatching { track.pause(); track.flush(); track.stop() }
            track.release()
            runCatching { record.stop() }
            record.release()
        }
    }

    /** Opens the chosen microphone for the live analyzer (same source and processing as for measurements). */
    fun openLiveMic(sampleRate: Int, input: AudioDeviceInfo?): LiveInput = LiveInput(createRecord(sampleRate, input), float = true)

    private fun readInto(record: AudioRecord, buffer: FloatArray, offset: Int, count: Int): Int {
        if (count <= 0) return 0
        val n = record.read(buffer, offset, count, AudioRecord.READ_BLOCKING)
        if (n < 0) throw AudioException("Recording failed (error $n).")
        return n
    }

    private suspend fun play(track: AudioTrack, signal: FloatArray, channel: OutputChannel) = coroutineScope {
        val chunk = 4_096
        val frames = FloatArray(2 * chunk)
        track.play()
        var position = 0
        while (position < signal.size) {
            ensureActive()
            val count = min(chunk, signal.size - position)
            channel.interleave(signal, position, count, frames)
            var written = 0
            while (written < 2 * count) {
                val n = track.write(frames, written, 2 * count - written, AudioTrack.WRITE_BLOCKING)
                if (n < 0) throw AudioException("Playback failed (error $n).")
                written += n
            }
            position += count
        }
    }

    private fun createRecord(sampleRate: Int, input: AudioDeviceInfo?): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuffer <= 0) throw AudioException("The microphone does not support $sampleRate Hz.")
        val source = if (supportsUnprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val record = try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw AudioException("Microphone permission is required.")
            }
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minBuffer * 4, sampleRate * 4))
                .build()
        } catch (e: UnsupportedOperationException) {
            throw AudioException("The microphone does not support $sampleRate Hz.")
        } catch (e: SecurityException) {
            throw AudioException("Microphone permission is required.")
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw AudioException("The microphone could not be opened at $sampleRate Hz.")
        }
        if (input != null) record.setPreferredDevice(input)
        return record
    }

    private fun createTrack(sampleRate: Int, output: AudioDeviceInfo?): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuffer <= 0) throw AudioException("Playback at $sampleRate Hz is not supported.")
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuffer * 2, sampleRate * 2 * 4 / 4))
                .build()
        } catch (e: UnsupportedOperationException) {
            throw AudioException("Playback at $sampleRate Hz is not supported.")
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw AudioException("Playback could not be started at $sampleRate Hz.")
        }
        if (output != null) track.setPreferredDevice(output)
        return track
    }
}
