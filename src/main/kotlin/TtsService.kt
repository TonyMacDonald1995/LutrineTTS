package com.lutrinecreations

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class TtsService(private val apiKey: String) {

    private val logger = LoggerFactory.getLogger(TtsService::class.java)

    // Use Ktor directly rather than the openai-kotlin library,
    // because the library's speech() returns a full ByteArray.
    // We need the raw streaming response to feed audio chunks
    // to Discord as they arrive, minimizing time-to-first-audio.
    private val client = HttpClient()

    /**
     * Streams PCM audio from OpenAI's TTS endpoint.
     *
     * The response arrives as chunked transfer encoding; each chunk of raw
     * 24kHz mono 16-bit little-endian PCM is passed to [onChunk] as it arrives.
     */
    suspend fun streamSpeech(
        text: String,
        voice: String,
        speed: Double,
        instructions: String?,
        onChunk: (ByteArray) -> Unit
    ) {
        val body = buildJsonObject {
            put("model", "gpt-4o-mini-tts")
            put("input", text)
            put("voice", voice)
            put("response_format", "pcm")
            put("speed", speed)
            instructions?.let { put("instructions", it) }
        }

        client.preparePost("https://api.openai.com/v1/audio/speech") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val error = response.bodyAsText()
                logger.error("OpenAI TTS error ({}): {}", response.status, error)
                return@execute
            }

            val channel: ByteReadChannel = response.bodyAsChannel()
            val buffer = ByteArray(4096)

            while (!channel.isClosedForRead) {
                val bytesRead = channel.readAvailable(buffer)
                if (bytesRead > 0) {
                    onChunk(buffer.copyOf(bytesRead))
                }
            }
        }
    }

    fun close() {
        client.close()
    }
}