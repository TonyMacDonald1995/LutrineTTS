package com.lutrinecreations

import com.aallam.openai.api.audio.SpeechRequest
import com.aallam.openai.api.audio.SpeechResponseFormat
import com.aallam.openai.api.audio.Voice
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.ChunkingFilter
import net.dv8tion.jda.api.utils.MemberCachePolicy
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.time.Duration.Companion.seconds

data class VoiceSetting(val userId: Long, val voiceId: String)

data class TTSChannel(val guildId: Long, val channelId: Long)

lateinit var openAi: OpenAI

fun main(args: Array<String>) {
    val discordToken = System.getenv("DISCORD_TOKEN") ?: throw Exception("Cannot start bot without Discord token")
    val openAiToken = System.getenv("OPENAI_TOKEN") ?: throw Exception("Cannot start bot with OpenAI token")

    val config = OpenAIConfig(
        token = openAiToken,
        timeout = Timeout(socket = 60.seconds)
    )

    openAi = OpenAI(config)

    val lutrineTTS = LutrineTTS()

    JDABuilder
        .createDefault(discordToken)
        .enableIntents(GatewayIntent.GUILD_MEMBERS,GatewayIntent.MESSAGE_CONTENT)
        .setMemberCachePolicy(MemberCachePolicy.ALL)
        .setChunkingFilter(ChunkingFilter.ALL)
        .addEventListeners(lutrineTTS)
        .setStatus(OnlineStatus.ONLINE)
        .build()

}

class LutrineTTS : ListenerAdapter() {

    init {
        loadData()
    }

    private val ttsChannelMap = mutableMapOf<Long, Long>()
    private val ttsVoiceMap: MutableMap<Long, String> = mutableMapOf()

    override fun onGuildReady(event: GuildReadyEvent) {

        event.guild.loadMembers()
        log("Connected to ${event.guild.name}")

        event.guild.updateCommands().addCommands(
            Commands.slash("join", "Join a voice channel"),

            Commands.slash("setvoice", "Set the voice to be used")
                .addOptions(OptionData(OptionType.STRING, "voice", "Voice to use", true, false).addChoices(
                    Command.Choice("Alloy", "Alloy"),
                    Command.Choice("Echo", "Echo"),
                    Command.Choice("Fable", "Fable"),
                    Command.Choice("Onyx", "Onyx"),
                    Command.Choice("Nova", "Nova"),
                    Command.Choice("Shimmer", "Shimmer")
                )),

            Commands.slash("setchannel", "Sets the TTS channel")
        ).queue()
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {

        if (ttsChannelMap[event.guild.idLong]?.equals(event.channel.idLong) != true)
            return

        val content = event.message.contentDisplay
        val voice = ttsVoiceMap[event.member?.user?.idLong] ?: "echo"

        var audio: ByteArray?

        if (!event.guild.audioManager.isConnected)
            return

        runBlocking {
            audio = getAudioResponse(content, voice)
        }
        if (audio?.isNotEmpty() == true) {
            val ttsHandler = event.guild.audioManager.sendingHandler as TTSHandler
            ttsHandler.queue(audio!!)
        }
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.guild == null)
            return

        when (event.name) {
            "join" -> joinVoice(event)
            "setchannel" -> setChannel(event)
            "setvoice" -> setVoice(event)
            else -> {
                event.reply("Error: unknown command").setEphemeral(true).queue()
            }
        }
    }

    private fun joinVoice(event: SlashCommandInteractionEvent) {
        if (event.member?.voiceState?.inAudioChannel() != true) {
            event.reply("Error: You must be in a voice channel.").setEphemeral(true).queue()
            return
        }

        val audioManager = event.guild?.audioManager
        val handler = TTSHandler()
        audioManager?.sendingHandler = handler
        audioManager?.openAudioConnection(event.member?.voiceState?.channel?.asVoiceChannel())
        event.reply("Joined ${event.member?.voiceState?.channel?.name}").setEphemeral(true).queue()
    }

    private fun setChannel(event: SlashCommandInteractionEvent) {
        ttsChannelMap[event.guild!!.idLong] = event.channelIdLong
        event.reply("Set TTS Channel to ${event.channel.name}").setEphemeral(true).queue()
        saveData()
    }

    private fun setVoice(event: SlashCommandInteractionEvent) {
        ttsVoiceMap[event.user.idLong] = event.getOption("voice")?.asString?.lowercase() ?: "echo"
        event.reply("Set your selected voice to ${event.getOption("voice")?.asString}.").setEphemeral(true).queue()
        saveData()
    }

    private fun getAudioResponse(text: String, voice: String): ByteArray? {
        var audio: ByteArray?
        runBlocking {
            audio = openAi.speech(
                request = SpeechRequest(
                    model = ModelId("tts-1"),
                    input = text,
                    voice = Voice(voice),
                    responseFormat = SpeechResponseFormat("pcm")
                )
            )
        }

        return audio
    }

    private fun loadData() {
        val gson = Gson()

        val channelMapFile = File("/data/channelMap.json")
        if (channelMapFile.exists()) {
            val json = channelMapFile.readText()
            val channelMapList = gson.fromJson(json, Array<TTSChannel>::class.java)
            channelMapList.forEach { ttsChannelMap[it.guildId] = it.channelId }
        }

        val voiceMapFile = File("/data/voiceMap.json")
        if (voiceMapFile.exists()) {
            val json = voiceMapFile.readText()
            val voiceMapList = gson.fromJson(json, Array<VoiceSetting>::class.java)
            voiceMapList.forEach { ttsVoiceMap[it.userId] = it.voiceId }
        }
    }

    private fun saveData() {
        val gson = Gson()

        val channelMapList = ttsChannelMap.map { TTSChannel(it.key, it.value) }
        var json = gson.toJson(channelMapList)
        File("/data/channelMap.json").writeText(json)

        val voiceMapList = ttsVoiceMap.map { VoiceSetting(it.key, it.value) }
        json = gson.toJson(voiceMapList)
        File("/data/voiceMap.json").writeText(json)
    }
}

class TTSHandler : AudioSendHandler {

    private val queue = ConcurrentLinkedQueue<ByteArray>()

    fun queue(data: ByteArray) {
        val inputFormat = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 24000f, 16, 1, 2, 24000f, false)
        val outputFormat = AudioSendHandler.INPUT_FORMAT

        ByteArrayInputStream(data).use { byteArrayInputStream ->
            AudioInputStream(byteArrayInputStream, inputFormat, data.size / inputFormat.frameSize.toLong()).use { inputStream ->
                AudioSystem.getAudioInputStream(outputFormat, inputStream).use { convertedInputStream ->
                    val bytesPer20ms = (outputFormat.frameSize * (outputFormat.frameRate / 50)).toInt()
                    val buffer = ByteArray(bytesPer20ms)

                    var bytesRead: Int
                    while (convertedInputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (bytesRead < buffer.size) {
                            // Handle partial buffer by padding with zeros
                            buffer.fill(0, bytesRead, buffer.size)
                        }
                        queue.add(buffer.copyOf())
                    }
                }
            }
        }
    }

    override fun canProvide(): Boolean {
        return !queue.isEmpty()
    }

    override fun provide20MsAudio(): ByteBuffer? {
        val data = queue.poll()
        return if (data == null) null else ByteBuffer.wrap(data)
    }

    override fun isOpus(): Boolean {
        return false
    }

}

fun log(message : String) {
    println("[${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}] $message")
}

