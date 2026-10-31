package dev.openwondertrade

import com.google.gson.JsonObject
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Rango de servidor (VIP, etc.) con ventajas en el bonus de especiales.
 * [baseBonus] es el mínimo del bonus: al recibir un especial el bonus vuelve a este valor en lugar de a 0.
 * [gainMultiplier] multiplica lo que suma cada Pokémon ofrecido.
 */
data class WtRank(
    val id: String,
    val name: String,
    val permission: String,
    val tag: String,
    val baseBonus: Double,
    val gainMultiplier: Double
)

/** Detecta el rango de un jugador por permiso (LuckPerms / Fabric Permissions API) o por etiqueta (/tag). */
object WonderTradeRanks {
    fun enabled(): Boolean = ConfigManager.bool("ranks.enabled", true)

    /** Rangos de la configuración, del más alto al más bajo: gana el primero que coincida. */
    fun all(): List<WtRank> = ConfigManager.objects("ranks.list").mapNotNull { parse(it) }

    private fun parse(json: JsonObject): WtRank? {
        val id = json.get("id")?.let { runCatching { it.asString }.getOrNull() }?.takeIf { it.isNotBlank() } ?: return null
        return WtRank(
            id = id,
            name = json.get("name")?.let { runCatching { it.asString }.getOrNull() } ?: id,
            permission = json.get("permission")?.let { runCatching { it.asString }.getOrNull() } ?: "",
            tag = json.get("tag")?.let { runCatching { it.asString }.getOrNull() } ?: "",
            baseBonus = json.get("baseBonus")?.let { runCatching { it.asDouble }.getOrNull() }?.coerceIn(0.0, 100.0) ?: 0.0,
            gainMultiplier = json.get("gainMultiplier")?.let { runCatching { it.asDouble }.getOrNull() }?.coerceAtLeast(0.0) ?: 1.0
        )
    }

    fun rankOf(player: ServerPlayer): WtRank? {
        if (!enabled()) return null
        return all().firstOrNull { matches(player, it) }
    }

    fun baseBonus(player: ServerPlayer): Double {
        val maximum = ConfigManager.double("bonus.maximum", 60.0).coerceIn(0.0, 100.0)
        return (rankOf(player)?.baseBonus ?: 0.0).coerceAtMost(maximum)
    }

    fun gainMultiplier(player: ServerPlayer): Double = rankOf(player)?.gainMultiplier ?: 1.0

    private fun matches(player: ServerPlayer, rank: WtRank): Boolean {
        if (rank.permission.isNotBlank() && hasPermission(player, rank.permission)) return true
        if (rank.tag.isNotBlank() && player.tags.contains(rank.tag)) return true
        return false
    }

    // ------------------------------------------------------------ permisos (sin dependencias en compilación)

    private var fabricPermissions: java.lang.reflect.Method? = null
    private var fabricPermissionsResolved = false

    private fun fabricPermissionsMethod(): java.lang.reflect.Method? {
        if (!fabricPermissionsResolved) {
            fabricPermissionsResolved = true
            fabricPermissions = runCatching {
                Class.forName("me.lucko.fabric.api.permissions.v0.Permissions").getMethod(
                    "check",
                    net.minecraft.world.entity.Entity::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                )
            }.getOrNull()
        }
        return fabricPermissions
    }

    private fun luckPermsAvailable(): Boolean = runCatching { Class.forName("net.luckperms.api.LuckPermsProvider") }.isSuccess

    private fun luckPermsCheck(uuid: UUID, node: String): Boolean? = runCatching {
        val api = Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null)
        val userManager = Class.forName("net.luckperms.api.LuckPerms").getMethod("getUserManager").invoke(api)
        val user = Class.forName("net.luckperms.api.model.user.UserManager").getMethod("getUser", UUID::class.java).invoke(userManager, uuid)
            ?: return@runCatching null
        val cached = Class.forName("net.luckperms.api.model.user.User").getMethod("getCachedData").invoke(user)
        val permissionData = Class.forName("net.luckperms.api.cacheddata.CachedDataManager").getMethod("getPermissionData").invoke(cached)
        val result = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData").getMethod("checkPermission", String::class.java).invoke(permissionData, node)
        Class.forName("net.luckperms.api.util.Tristate").getMethod("asBoolean").invoke(result) as Boolean
    }.getOrNull()

    /** Comprueba un permiso (por ejemplo «group.vip» o «wondertradeplus.rank.5») con lo que haya instalado. */
    fun hasPermission(player: ServerPlayer, node: String): Boolean {
        val method = fabricPermissionsMethod()
        if (method != null) {
            val result = runCatching { method.invoke(null, player, node, false) as Boolean }.getOrNull()
            if (result != null) return result
        }
        return luckPermsCheck(player.uuid, node) ?: false
    }

    /** Qué sistema de permisos se ha detectado (para diagnóstico). */
    fun backendName(): String = when {
        fabricPermissionsMethod() != null -> "Fabric Permissions API (LuckPerms y compatibles)"
        luckPermsAvailable() -> "LuckPerms"
        else -> "solo etiquetas (/tag)"
    }
}
