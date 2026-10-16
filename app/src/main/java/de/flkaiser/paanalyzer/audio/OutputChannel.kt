package de.flkaiser.paanalyzer.audio

/** Which side of a stereo Bluetooth link carries the sweep. */
enum class OutputChannel(val label: String) {
    BOTH("Both"),
    LEFT("Left"),
    RIGHT("Right");

    /** Interleaves a mono signal into stereo frames according to this channel choice. */
    fun interleave(mono: FloatArray, from: Int, count: Int, out: FloatArray) {
        require(out.size >= 2 * count)
        for (i in 0 until count) {
            val v = mono[from + i]
            out[2 * i] = if (this == RIGHT) 0f else v
            out[2 * i + 1] = if (this == LEFT) 0f else v
        }
    }
}
