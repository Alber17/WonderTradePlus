# Estado de compilación

- Versión actual: **0.7.2**.
- Objetivo: Minecraft 1.21.1, Java 21, Fabric y Cobblemon 1.8.4.
- Compila con Gradle 8.14 mediante GitHub Actions en cada push.
- Probado en juego: menú del equipo y del PC, confirmación, vista del pool, intercambios y comandos.
- Los comandos `/wt admin ...`, el historial, el cooldown de 12 h y el autocompletado (v0.3.0) están pendientes de probar en juego.
- Los anuncios de legendarios, Ultraentes y Paradoja pasado/futuro (v0.2.1) están pendientes de probar en juego.
- No es una versión NeoForge/Arclight. El JAR es solo Fabric.

## Cambios de esta revisión

- Los intercambios solo seleccionan entradas existentes en `pool.json`; no se generan Pokémon de respaldo durante un intercambio.
- El mantenimiento ya no rellena el pool automáticamente. `/wt admin fill` sigue siendo una acción manual para añadir entradas.
- El filtro del menú muestra cada categoría una sola vez y ya no incluye la explicación entre paréntesis.
- `/wt admin addspecies <especie> [shiny]` crea la especie o forma solicitada con nivel aleatorio según `fallback.minimumLevel` y `fallback.maximumLevel`.
- La compilación Gradle de esta revisión queda pendiente de validación en GitHub Actions; este entorno no dispone de Gradle ni acceso de red para descargarlo.

- `/wt admin resetstats <jugador|all>` reinicia los contadores de `/wt stats` y las clasificaciones de `/wt top` sin modificar bonus, cooldowns o límites diarios.
- `addspecies` ahora aplica las formas regionales después de inicializar el Pokémon, para evitar que Cobblemon lo devuelva a la forma estándar.
- La categoría Shiny ya no lleva emoji.
- La vista de pool para administradores incluye un botón junto a «Volver» para activar/desactivar el modo de eliminación; el clic sobre Pokémon solo borra cuando ese modo está activo.
- No se ha ejecutado Gradle ni ninguna compilación en esta revisión, tal como se solicitó.
