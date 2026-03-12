package com.lutrinecreations

import kotlinx.serialization.Serializable

@Serializable
data class UserPreferences(
    val voice: String = "coral",
    val speed: Double = 1.0,
    val instructions: String? = null
)

@Serializable
data class GuildSettings(
    val ttsChannelId: Long? = null
)

@Serializable
data class AppData(
    val guildSettings: Map<Long, GuildSettings> = emptyMap(),
    val userPreferences: Map<Long, UserPreferences> = emptyMap()
)