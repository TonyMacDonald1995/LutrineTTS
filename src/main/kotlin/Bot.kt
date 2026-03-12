package com.lutrinecreations

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.kyokobot.libdave.NativeDaveFactory
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.audio.AudioModuleConfig
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class Bot(
    private val discordToken: String,
    private val storage: Storage,
    private val ttsService: TtsService
) : ListenerAdapter() {

    private val logger = LoggerFactory.getLogger(Bot::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val audioHandlers = ConcurrentHashMap<Long, AudioHandler>()

    // Per-guild mutex ensures messages are spoken sequentially rather than
    // interleaving audio from concurrent TTS streams.
    private val guildTtsLocks = ConcurrentHashMap<Long, Mutex>()

    fun start() {
        val daveFactory = LDJDADaveSessionFactory(NativeDaveFactory())

        JDABuilder.createDefault(discordToken)
            .setAudioModuleConfig(
                AudioModuleConfig()
                    .withDaveSessionFactory(daveFactory)
            )
            .enableIntents(
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.GUILD_VOICE_STATES
            )
            .enableCache(CacheFlag.VOICE_STATE)
            .addEventListeners(this)
            .setStatus(OnlineStatus.ONLINE)
            .build()

        logger.info("JDA build initiated")
    }

    // ──────────────────────── Event handlers ────────────────────────

    override fun onGuildReady(event: GuildReadyEvent) {
        logger.info("Connected to guild: {}", event.guild.name)

        event.guild.updateCommands().addCommands(
            Commands.slash("join", "Join your voice channel"),
            Commands.slash("leave", "Disconnect from voice"),
            Commands.slash("setchannel", "Set this channel as the TTS channel"),

            Commands.slash("setvoice", "Set your TTS voice")
                .addOptions(
                    OptionData(OptionType.STRING, "voice", "Voice to use", true)
                        .addChoices(VOICES.map { Command.Choice(it.replaceFirstChar(Char::titlecase), it) })
                ),

            Commands.slash("setspeed", "Set your TTS speed (0.25–4.0)")
                .addOptions(
                    OptionData(OptionType.NUMBER, "speed", "Speed multiplier", true)
                        .setMinValue(0.25)
                        .setMaxValue(4.0)
                ),

            Commands.slash("setstyle", "Set speaking style instructions for your voice")
                .addOptions(
                    OptionData(OptionType.STRING, "style", "e.g. 'speak cheerfully with a British accent'", true)
                ),

            Commands.slash("clearstyle", "Remove your speaking style instructions"),
            Commands.slash("stop", "Clear the TTS queue"),
        ).queue()
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return

        when (event.name) {
            "join"       -> handleJoin(event, guild)
            "leave"      -> handleLeave(event, guild)
            "setchannel" -> handleSetChannel(event, guild)
            "setvoice"   -> handleSetVoice(event)
            "setspeed"   -> handleSetSpeed(event)
            "setstyle"   -> handleSetStyle(event)
            "clearstyle" -> handleClearStyle(event)
            "stop"       -> handleStop(event, guild)
        }
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot || !event.isFromGuild) return

        val guild = event.guild
        val guildSettings = storage.data.guildSettings[guild.idLong] ?: return
        if (guildSettings.ttsChannelId != event.channel.idLong) return
        if (!guild.audioManager.isConnected) return

        val handler = audioHandlers[guild.idLong] ?: return
        val prefs = storage.data.userPreferences[event.author.idLong] ?: UserPreferences()

        var content = event.message.contentDisplay.trim()
        if (content.isEmpty()) return
        if (content.length > MAX_MESSAGE_LENGTH) {
            content = content.take(MAX_MESSAGE_LENGTH) + "…"
        }

        val guildId = guild.idLong
        val guildName = guild.name

        scope.launch {
            val lock = guildTtsLocks.getOrPut(guildId) { Mutex() }
            lock.withLock {
                try {
                    ttsService.streamSpeech(
                        text = content,
                        voice = prefs.voice,
                        speed = prefs.speed,
                        instructions = prefs.instructions,
                        onChunk = { chunk -> handler.feedPcm(chunk) }
                    )
                    handler.flushRemaining()
                } catch (e: Exception) {
                    logger.error("TTS failed for message in {}", guildName, e)
                }
            }
        }
    }

    override fun onGuildVoiceUpdate(event: GuildVoiceUpdateEvent) {
        val connected = event.guild.audioManager.connectedChannel ?: return
        val onlyBotRemains = connected.members.size == 1
                && connected.members[0].user.isBot

        if (onlyBotRemains) {
            audioHandlers.remove(event.guild.idLong)?.clearQueue()
            guildTtsLocks.remove(event.guild.idLong)
            event.guild.audioManager.closeAudioConnection()
            logger.info("Left empty voice channel in {}", event.guild.name)
        }
    }

    // ──────────────────────── Slash command handlers ────────────────────────

    private fun handleJoin(event: SlashCommandInteractionEvent, guild: Guild) {
        val voiceChannel = event.member?.voiceState?.channel
        if (voiceChannel == null) {
            event.reply("You need to be in a voice channel first.").setEphemeral(true).queue()
            return
        }

        val handler = AudioHandler()
        audioHandlers[guild.idLong] = handler
        guild.audioManager.sendingHandler = handler
        guild.audioManager.openAudioConnection(voiceChannel)
        event.reply("Joined **${voiceChannel.name}**").setEphemeral(true).queue()
    }

    private fun handleLeave(event: SlashCommandInteractionEvent, guild: Guild) {
        audioHandlers.remove(guild.idLong)?.clearQueue()
        guildTtsLocks.remove(guild.idLong)
        guild.audioManager.closeAudioConnection()
        event.reply("Disconnected.").setEphemeral(true).queue()
    }

    private fun handleSetChannel(event: SlashCommandInteractionEvent, guild: Guild) {
        scope.launch {
            storage.save { data ->
                val current = data.guildSettings[guild.idLong] ?: GuildSettings()
                data.copy(guildSettings = data.guildSettings + (guild.idLong to current.copy(ttsChannelId = event.channelIdLong)))
            }
        }
        event.reply("TTS channel set to **${event.channel.name}**").setEphemeral(true).queue()
    }

    private fun handleSetVoice(event: SlashCommandInteractionEvent) {
        val voice = event.getOption("voice")?.asString ?: return
        scope.launch { updateUserPrefs(event.user.idLong) { it.copy(voice = voice) } }
        event.reply("Voice set to **$voice**").setEphemeral(true).queue()
    }

    private fun handleSetSpeed(event: SlashCommandInteractionEvent) {
        val speed = event.getOption("speed")?.asDouble ?: return
        scope.launch { updateUserPrefs(event.user.idLong) { it.copy(speed = speed) } }
        event.reply("Speed set to **$speed**").setEphemeral(true).queue()
    }

    private fun handleSetStyle(event: SlashCommandInteractionEvent) {
        val style = event.getOption("style")?.asString ?: return
        scope.launch { updateUserPrefs(event.user.idLong) { it.copy(instructions = style) } }
        event.reply("Style set to: *$style*").setEphemeral(true).queue()
    }

    private fun handleClearStyle(event: SlashCommandInteractionEvent) {
        scope.launch { updateUserPrefs(event.user.idLong) { it.copy(instructions = null) } }
        event.reply("Style instructions cleared.").setEphemeral(true).queue()
    }

    private fun handleStop(event: SlashCommandInteractionEvent, guild: Guild) {
        audioHandlers[guild.idLong]?.clearQueue()
        event.reply("Queue cleared.").setEphemeral(true).queue()
    }

    // ──────────────────────── Helpers ────────────────────────

    private suspend fun updateUserPrefs(userId: Long, transform: (UserPreferences) -> UserPreferences) {
        storage.save { data ->
            val current = data.userPreferences[userId] ?: UserPreferences()
            data.copy(userPreferences = data.userPreferences + (userId to transform(current)))
        }
    }

    companion object {
        private const val MAX_MESSAGE_LENGTH = 500

        val VOICES = listOf(
            "alloy", "ash", "ballad", "coral", "echo", "fable",
            "onyx", "nova", "sage", "shimmer", "verse", "marin", "cedar"
        )
    }
}