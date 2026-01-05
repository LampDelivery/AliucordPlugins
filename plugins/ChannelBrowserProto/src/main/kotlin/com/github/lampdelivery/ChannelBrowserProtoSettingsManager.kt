package com.github.lampdelivery

import android.util.Base64
import com.aliucord.api.SettingsAPI
import com.aliucord.Logger
import channelbrowser.Settings
import com.aliucord.api.GatewayAPI
import java.util.concurrent.CopyOnWriteArrayList
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.Observable
import com.aliucord.Utils


class ChannelBrowserProtoSettingsManager(private val settings: SettingsAPI, private val logger: Logger) {
        // --- Guild Settings Logic ---
    fun patchGuildSettingsAsync(guildId: Long, channelOverrides: List<Map<String, Any>>, onSuccess: () -> Unit = {}, onError: (String, Exception) -> Unit = { msg, e -> logger.error(msg, e) }) {
            logger.info("patchGuildSettingsAsync: called for guild $guildId with overrides: $channelOverrides")
            Utils.threadPool.execute {
                try {
                    val overridesMap = channelOverrides.associateBy { it["channel_id"].toString() }
                    val patchBody = mapOf(
                        "guilds" to mapOf(
                            guildId.toString() to mapOf(
                                "channel_overrides" to overridesMap
                            )
                        )
                    )
                    val req = com.aliucord.Http.Request.newDiscordRNRequest(
                        "/users/@me/guilds/settings",
                        "PATCH"
                    )
                    val resp = req.executeWithJson(patchBody)
                    resp.assertOk()
                    logger.info("patchGuildSettingsAsync: PATCH success for guild $guildId")
                    onSuccess()
                } catch (e: Exception) {
                    logger.error("patchGuildSettingsAsync: Failed to patch guild settings", e)
                    onError("Failed to patch guild settings: ${e.message}", e)
                }
            }
        }

        fun listenForGuildSettingsUpdates() {
            logger.info("listenForGuildSettingsUpdates: registering USER_GUILD_SETTINGS_UPDATE handler")
            GatewayAPI.onEvent("USER_GUILD_SETTINGS_UPDATE") { payload: Any? ->
                logger.info("listenForGuildSettingsUpdates: received event payload: $payload")
                // You may want to parse and update local state here if needed
            }
        }
    private val settingsSubject = BehaviorSubject.create<Settings.UserSettings>()
    @Volatile
    private var _settings: Settings.UserSettings? = null

    var currentSettings: Settings.UserSettings
        get() = _settings ?: throw IllegalStateException("Settings not loaded yet")
        set(value) {
            _settings = value
            settingsSubject.onNext(value)
        }

    fun observeSettings(): Observable<Settings.UserSettings> {
        if (_settings == null) {
            logger.info("observeSettings: _settings is null, loading from Discord...")
            Utils.threadPool.execute {
                try {
                    val loaded = loadSettingsSync()
                    logger.info("observeSettings: loaded settings from Discord: $loaded")
                    _settings = loaded
                    settingsSubject.onNext(loaded)
                } catch (e: Exception) {
                    logger.error("Failed to load proto settings async, using default", e)
                    val defaultSettings = Settings.UserSettings.newBuilder().build()
                    _settings = defaultSettings
                    settingsSubject.onNext(defaultSettings)
                }
            }
        } else {
            logger.info("observeSettings: _settings already loaded, returning cached value")
        }
        return settingsSubject
    }

    fun updateSettings(updater: (Settings.UserSettings) -> Settings.UserSettings) {
        logger.info("updateSettings: called")
        if (_settings == null) {
            logger.info("updateSettings: _settings is null, observing settings first")
            observeSettings().subscribe({ loaded ->
                logger.info("updateSettings: loaded settings, applying updater")
                currentSettings = updater(loaded)
            }, { e -> logger.error("Failed to update settings: not loaded", e as Exception) })
        } else {
            logger.info("updateSettings: _settings already loaded, applying updater")
            currentSettings = updater(currentSettings)
        }
    }


    private fun decodeSettings(base64: String): Settings.UserSettings {
        return Settings.UserSettings.parseFrom(Base64.decode(base64, Base64.DEFAULT))
    }


    private fun encodeSettings(settings: Settings.UserSettings): String =
        Base64.encodeToString(settings.toByteArray(), Base64.DEFAULT)


    fun patchSettingsAsync(settings: Settings.UserSettings, onSuccess: () -> Unit = {}, onError: (String, Exception) -> Unit = { msg, e -> logger.error(msg, e) }) {
        logger.info("patchSettingsAsync: called with settings: $settings")
        Utils.threadPool.execute {
            try {
                patchSettingsSync(settings)
                logger.info("patchSettingsAsync: Patched proto settings to Discord.")
                onSuccess()
            } catch (e: Exception) {
                logger.error("patchSettingsAsync: Failed to patch proto settings", e)
                try {
                    val reloaded = loadSettingsSync()
                    _settings = reloaded
                    settingsSubject.onNext(reloaded)
                } catch (reloadErr: Exception) {
                    logger.error("patchSettingsAsync: Failed to reload settings after error", reloadErr)
                }
                onError("Failed to patch proto settings: ${e.message}", e)
            }
        }
    }

    private fun patchSettingsSync(settings: Settings.UserSettings) {
        val req = com.aliucord.Http.Request.newDiscordRNRequest(
            "/users/@me/guilds/settings",
            "PATCH"
        )
        val body = mapOf("settings" to encodeSettings(settings))
        val resp = req.executeWithJson(body)
        resp.assertOk()
    }

    fun listenForGatewayUpdates() {
        logger.info("listenForGatewayUpdates: registering USER_SETTINGS_PROTO_UPDATE handler")
        GatewayAPI.onEvent("USER_SETTINGS_PROTO_UPDATE") { payload: Any? ->
            logger.info("listenForGatewayUpdates: received event payload: $payload")
            val data = payload as? Map<*, *> ?: run {
                logger.error("listenForGatewayUpdates: payload is not a Map: $payload", null)
                return@onEvent
            }
            val type = (data["type"] as? Number)?.toInt() ?: run {
                logger.error("listenForGatewayUpdates: type missing or not a Number: $data", null)
                return@onEvent
            }
            if (type != 2) {
                logger.info("listenForGatewayUpdates: type != 2, ignoring event")
                return@onEvent
            }
            val base64 = data["settings"] as? String ?: run {
                logger.error("listenForGatewayUpdates: settings missing or not a String: $data", null)
                return@onEvent
            }
            val decoded = decodeSettings(base64)
            logger.info("listenForGatewayUpdates: decoded settings: $decoded")
            currentSettings = decoded
            logger.info("Received proto settings update from gateway.")
        }
    }
    private fun loadSettingsSync(): Settings.UserSettings {
        logger.info("loadSettingsSync: called")
        return try {
            val req = com.aliucord.Http.Request.newDiscordRNRequest(
                "/users/@me/guilds/settings",
                "GET"
            )
            val resp = req.execute()
            logger.info("loadSettingsSync: HTTP status: ${resp.statusCode}")
            resp.assertOk()
            // Removed resp.text() to avoid double reading the response body
            val json = try { resp.json(Response::class.java) } catch (e: Exception) { logger.error("loadSettingsSync: error parsing json", e); throw e }
            logger.info("loadSettingsSync: parsed json: $json")
            val decoded = try { decodeSettings(json.settings) } catch (e: Exception) { logger.error("loadSettingsSync: error decoding settings", e); throw e }
            logger.info("loadSettingsSync: decoded settings: $decoded")
            decoded
        } catch (e: Exception) {
            logger.error("loadSettingsSync: Failed to load proto settings", e)
            throw RuntimeException("Failed to load proto settings", e)
        }
    }

    private data class Response(val settings: String)
}
