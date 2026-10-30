# Estado de compilación

- Versión actual: **0.7.5**.
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
- `/wt admin addspecies <especie> [alolan|galarian|hisuian|paldean] [shiny]` usa el parser de propiedades de Cobblemon, igual que `/pokegive`; valida que la forma solicitada se aplique y solo sugiere especies base.
- La categoría Shiny ya no lleva emoji.
- La vista de pool para administradores incluye un botón junto a «Volver» para activar/desactivar el modo de eliminación; el clic sobre Pokémon solo borra cuando ese modo está activo.
- No se ha ejecutado Gradle ni ninguna compilación en esta revisión, tal como se solicitó.

- Los intercambios de Pokémon normales suman `bonus.normalTradeGain` (0,1% por defecto); los Pokémon especiales siguen sumando `bonus.specialTradeGain` (0,5% por defecto). Ambos valores y sus mensajes se editan en `config.json`.
- La probabilidad normal se suma antes de la tirada de ese mismo intercambio; la ganancia por aportar un especial se aplica al siguiente intercambio normal.
- Añadidas opciones configurables `admin.regionalForms.*` para activar/desactivar cada forma regional.
- README.md y docs/CONFIGURATION.md actualizados para describir los comandos, propiedades regionales y el sistema de bonus.
- No se ha ejecutado Gradle en esta revisión.


## Cambios de la versión 0.7.4 (sin compilar)

- `/wt admin addspecies` acepta los alias regionales `alolan`, `galarian`, `hisuian`, `paldean` y la sintaxis `form=alola|galar|hisui|paldea` de Cobblemon 1.8.x. Las formas se validan contra las formas registradas de cada especie y se comprueban antes de añadirlas a la pool.
- La creación regional utiliza `PokemonProperties` con `form=<id>` y reaplica la propiedad tras la inicialización para conservar la forma correcta.
- El bonus de WT normal se configura con `bonus.normalTradeGain` (0,1 puntos porcentuales por defecto); el bonus de Pokémon especiales permanece en `bonus.specialTradeGain` (0,5 por defecto). Los dos valores, el máximo, los mensajes y los multiplicadores de evento son configurables.
- `admin.regionalForms.*` permite activar o desactivar cada tipo de forma regional y `fallback.minimumLevel` / `fallback.maximumLevel` controla el nivel aleatorio.
- README.md y docs/CONFIGURATION.md actualizados. No se ha ejecutado Gradle, tal como se solicitó.

- Añadido `bonus.specialShinyExtraGain` (+0,2 puntos porcentuales por defecto): se suma al +0,5% base cuando el Pokémon ofrecido es shiny y además legendario, mítico, Ultraente o Paradoja. El mensaje `bonusInfo` recibe `{specialShinyExtraGain}`. README y docs/CONFIGURATION.md actualizados. No se ha ejecutado Gradle.

## Cambios de la versión 0.7.5 (sin compilar)

- Añadido `bonus.specialShinyExtraGain` (+0,2 puntos porcentuales por defecto): se suma al incremento especial base cuando el Pokémon ofrecido es shiny y además legendario, mítico, Ultraente o Paradoja. Un shiny sin una de esas categorías no recibe el extra.
- `bonusInfo` recibe el marcador `{specialShinyExtraGain}` para mostrar el extra configurado. Los archivos `config.json` existentes reciben automáticamente la nueva clave al recargar; si conservan un `bonusInfo` personalizado antiguo, se muestra el extra en una segunda línea configurable (`messages.bonusShinyExtraInfo`).
- El multiplicador de eventos también afecta a este extra. README y docs/CONFIGURATION.md actualizados. No se ha ejecutado Gradle, según lo solicitado.
