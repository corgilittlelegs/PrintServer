package dev.jaspreet.printserver.access

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ClientAccessSettings(
    val mode: ClientAccessMode = ClientAccessMode.OPEN,
    val rules: List<String> = emptyList(),
) {
    val policy: ClientAccessPolicy
        get() = ClientAccessPolicy(
            mode,
            rules.mapNotNull(Ipv4AccessRule::parse),
        )
}

/** App-private persistent settings shared by Compose and the live network gate. */
object ClientAccessSettingsState {
    private const val PREFS_NAME = "client_access"
    private const val KEY_RESTRICTED = "restricted"
    private const val KEY_RULES = "rules"

    private val lock = Any()
    private val _settings = MutableStateFlow(ClientAccessSettings())
    val settings: StateFlow<ClientAccessSettings> = _settings.asStateFlow()

    @Volatile private var initialized = false
    private var appContext: Context? = null

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            val prefs = appContext!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mode = if (prefs.getBoolean(KEY_RESTRICTED, false)) {
                ClientAccessMode.RESTRICTED
            } else {
                ClientAccessMode.OPEN
            }
            val rules = prefs.getStringSet(KEY_RULES, emptySet()).orEmpty()
                .mapNotNull(Ipv4AccessRule::parse)
                .map { it.canonical }
                .distinct()
                .sorted()
            _settings.value = ClientAccessSettings(mode, rules)
            initialized = true
        }
    }

    /** Returns null on success or a user-facing validation/persistence error. */
    fun save(restricted: Boolean, rawRules: String): String? = synchronized(lock) {
        val context = appContext ?: return@synchronized "Access settings are not initialized"
        val lines = rawRules.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val invalid = lines.firstOrNull { Ipv4AccessRule.parse(it) == null }
        if (invalid != null) {
            return@synchronized "Invalid IPv4 address or CIDR: $invalid"
        }
        val rules = lines.map { Ipv4AccessRule.parse(it)!!.canonical }.distinct().sorted()
        val next = ClientAccessSettings(
            if (restricted) ClientAccessMode.RESTRICTED else ClientAccessMode.OPEN,
            rules,
        )
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RESTRICTED, restricted)
            .putStringSet(KEY_RULES, rules.toSet())
            .commit()
        if (!saved) return@synchronized "Could not save restricted-access settings"
        _settings.value = next
        null
    }
}
