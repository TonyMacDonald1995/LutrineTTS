package com.lutrinecreations

import net.dv8tion.jda.api.audio.AudioSendHandler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Handles streaming audio playback for a single guild.
 *
 * Accepts raw 24kHz mono 16-bit little-endian PCM (OpenAI's "pcm" format),
 * converts it to 48kHz stereo 16-bit big-endian PCM (JDA's INPUT_FORMAT),
 * and queues 20ms frames for Discord playback.
 *
 * Frame math:
 *   Input  (24kHz, mono):   480 samples × 2 bytes = 960 bytes per 20ms
 *   Output (48kHz, stereo): 960 samples × 4 bytes = 3840 bytes per 20ms
 */
class AudioHandler : AudioSendHandler {

    private val queue = ConcurrentLinkedQueue<ByteArray>()

    // Accumulates bytes between streaming chunks to handle frame boundaries.
    // Synchronization note: feedPcm/flushRemaining are only called from a single
    // coroutine per guild (serialized by guildTtsLocks in Bot), so this is safe.
    private var accumulator = ByteArray(0)

    /**
     * Accepts a chunk of raw 24kHz PCM from the streaming TTS response,
     * converts complete 20ms frames, and enqueues them for playback.
     */
    fun feedPcm(data: ByteArray) {
        val combined = accumulator + data
        var offset = 0

        while (offset + INPUT_FRAME_BYTES <= combined.size) {
            val frame = combined.copyOfRange(offset, offset + INPUT_FRAME_BYTES)
            queue.add(upsampleToStereo48k(frame))
            offset += INPUT_FRAME_BYTES
        }

        // Keep leftover bytes for the next chunk
        accumulator = if (offset < combined.size) {
            combined.copyOfRange(offset, combined.size)
        } else {
            ByteArray(0)
        }
    }

    /** Flush any remaining accumulated audio, padded with silence. */
    fun flushRemaining() {
        if (accumulator.isNotEmpty()) {
            val padded = accumulator.copyOf(INPUT_FRAME_BYTES)
            queue.add(upsampleToStereo48k(padded))
            accumulator = ByteArray(0)
        }
    }

    fun clearQueue() {
        queue.clear()
        accumulator = ByteArray(0)
    }

    override fun canProvide(): Boolean = queue.isNotEmpty()

    override fun provide20MsAudio(): ByteBuffer? =
        queue.poll()?.let { ByteBuffer.wrap(it) }

    override fun isOpus(): Boolean = false

    companion object {
        // 20ms of 24kHz mono 16-bit PCM = 480 samples × 2 bytes
        private const val INPUT_FRAME_BYTES = 960

        /**
         * Converts one 20ms frame from 24kHz mono LE to 48kHz stereo BE.
         *
         * Each input sample is duplicated twice (nearest-neighbor 2× upsample)
         * and written to both left and right channels.
         */
        private fun upsampleToStereo48k(monoFrame: ByteArray): ByteArray {
            val input = ByteBuffer.wrap(monoFrame).order(ByteOrder.LITTLE_ENDIAN)
            // JDA's AudioSendHandler.INPUT_FORMAT is 48kHz, 16-bit, stereo, signed, big-endian
            val output = ByteBuffer.allocate(3840).order(ByteOrder.BIG_ENDIAN)

            repeat(INPUT_FRAME_BYTES / 2) {
                val sample = input.short
                // Write sample twice (2× upsample) to both channels (stereo)
                repeat(2) {
                    output.putShort(sample) // left
                    output.putShort(sample) // right
                }
            }

            return output.array()
        }
    }
}