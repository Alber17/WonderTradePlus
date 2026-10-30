# Configuración de WonderTrade Plus

La configuración inicial se copia en `config/wondertradeplus/config.json`. Edita el JSON con el servidor apagado o usa `/wt reload` después de guardar. El JSON debe seguir siendo válido: no admite comentarios ni comas sobrantes.

## `general`

- `requireConfirmation`: pide `/wt confirm` después de `/wt <slot>`.
- `confirmationTimeoutSeconds`: cuánto dura la confirmación pendiente.
- `allowInBattle`: permite intentar intercambiar durante un combate; se recomienda `false`.
- `allowFaintedPokemon`: permite ofrecer Pokémon debilitados.
- `allowShinyPokemon`, `allowSpecialPokemon`, `allowAlphaPokemon`: permiten o bloquean esas categorías tanto para lo ofrecido como para lo recibido.
- `allowHeldItems`: permite Pokémon que llevan un objeto equipado.
- `minimumOfferedLevel`, `maximumOfferedLevel`: rango de nivel aceptado para el Pokémon ofrecido.
- `speciesBlacklist`: especies prohibidas; admite `ditto` y `cobblemon:ditto`.
- `useSpeciesWhitelist`: activa el modo lista blanca.
- `speciesWhitelist`: especies admitidas cuando la lista blanca está activa.

## `trade`

- `cooldownMinutes`: espera entre intercambios, en **minutos**. Por defecto `720` (12 horas). `0` desactiva el cooldown. Ejemplos: `60` = 1 h, `360` = 6 h, `1440` = 24 h. Los operadores (`adminBypassPermissionLevel`) lo ignoran.
- `dailyLimit`: número máximo de intercambios por día. `0` significa ilimitado.
- `adminBypassPermissionLevel`: nivel de operador que ignora cooldown y límite diario; rango `0–4`.
- `matchSpecialTier`: intenta emparejar Pokémon especiales con especiales y normales con normales.
- `specialLabels`: etiquetas adicionales que cuentan como categoría especial, además de legendarios, míticos y Ultraentes detectados por Cobblemon.
- `avoidSameSpecies`: evita devolver la misma especie que se ofreció.
- `matchLevel`: activa el rango de nivel respecto al Pokémon ofrecido.
- `levelDifference`: diferencia de nivel máxima cuando `matchLevel` está activo.
- `shinyMode`: `MATCH_IF_AVAILABLE` prioriza el mismo estado shiny; `REQUIRE_MATCH` exige el mismo estado; `IGNORE` no lo tiene en cuenta; `DISALLOW_SHINY` evita entregar Pokémon shiny.

## `pool`

- `enabled`: usa el pool compartido de Pokémon aportados por jugadores.
- `acceptDonations`: guarda en el pool una copia del Pokémon que se ha ofrecido tras completar el intercambio.
- `randomFallback`: genera un Pokémon nuevo cuando no hay ninguna entrada compatible.
- `maximumEntries`: máximo de entradas guardadas.
- `trimOldestFirst`: elimina entradas antiguas al superar el máximo si está activo.
- `clampDonatedLevel`: ajusta el nivel de la copia donada al pool.
- `donatedMinimumLevel`, `donatedMaximumLevel`: rango usado por ese ajuste.

## `fallback`

- `minimumLevel`, `maximumLevel`: rango de nivel de Pokémon generados por el sistema de respaldo.
- `speciesGenerationAttempts`: intentos máximos para encontrar una especie que respete los filtros.
- `shinyBehavior`: `MATCH_INPUT`, `SERVER_RATE`, `NEVER` o `ALWAYS`.

## `antiAbuse`

- `excludeOwnDonations`: un jugador no puede recibir Pokémon que él mismo aportó al pool. Por defecto `true`.
- `donationHoldMinutes`: minutos que un Pokémon recién aportado por un jugador no puede ser recibido por nadie. Por defecto `10`; `0` lo desactiva. No afecta a lo creado por comandos de admin ni a entradas antiguas.

Si ninguna entrada del pool es válida para un jugador, se genera un Pokémon aleatorio (si `pool.randomFallback` está activado).

## `maintenance`

Mantenimiento automático del pool, cada `intervalMinutes` minutos (y a la primera vez, un minuto después de arrancar).

- `expireAfterDays`: borra las entradas más antiguas que estos días. `0` lo desactiva. Por defecto `14`.
- `autoRefill`: si el pool tiene menos de `minimumEntries`, genera Pokémon aleatorios hasta llegar. Necesita al menos un jugador conectado. Por defecto `true`.
- `minimumEntries`: tamaño mínimo del pool. Por defecto `100`.
- `refillBatchSize`: máximo de Pokémon generados por pasada, para no cargar el servidor. Por defecto `50`.
- `refillSpecialChance`: probabilidad (%) de que cada Pokémon generado sea especial. Por defecto `1.0`.
- `intervalMinutes`: cada cuánto se ejecuta. Por defecto `30`.

Los Pokémon generados salen con el donante `Servidor`. Se puede lanzar a mano con `/wt admin maintenance`.

## `backup`

Copias automáticas de `pool.json`, `player-data.json`, `history.json` y `event.json` en `config/wondertradeplus/backups/<fecha>/`.

- `intervalHours`: cada cuántas horas. `0` desactiva las automáticas. Por defecto `6`.
- `keep`: cuántas copias se conservan; las más antiguas se borran. Por defecto `10`.

También se crea una copia justo antes de vaciar el pool, y se puede hacer a mano con `/wt admin backup`.

## `discord`

Avisos a un canal de Discord mediante un **webhook** (no hace falta ningún bot).

- `enabled`: activa los avisos. Por defecto `false`.
- `webhookUrl`: URL del webhook. **Es secreta**: quien la tenga puede escribir en ese canal, así que no la compartas ni la subas a GitHub.
- `username`: nombre con el que aparecen los mensajes.
- `notifyDonations`: avisa cuando alguien aporta un Pokémon especial al pool.
- `notifyReceived`: avisa cuando alguien recibe un Pokémon especial.
- `notifyEvents`: avisa al empezar y terminar un evento.
- `donationMessage`, `receivedMessage`, `eventStartMessage`, `eventEndMessage`: textos. Admiten Markdown de Discord (`**negrita**`) y `{player}`, `{pokemon}`, `{level}`, `{category}`; los de evento admiten `{time}`, `{cooldown}`, `{bonus}` y `{extra}`.

"Especial" aquí es shiny, legendario, mítico, Ultraente o Paradoja. Los mensajes se envían sin bloquear el servidor y no pueden mencionar a nadie (`@everyone` queda desactivado). Prueba la conexión con `/wt admin discord test`.

## `bonus`

Sistema de probabilidad acumulada **por jugador** para recibir un Pokémon especial.

- `enabled`: activa o desactiva el sistema.
- `maximum`: límite del porcentaje acumulado (`0–100`). Por defecto `60`.
- `shiny`, `legendary`, `mythical`, `ultraBeast`, `paradox`, `other`: porcentaje que suma ofrecer un Pokémon de cada tipo. `other` cubre las etiquetas personalizadas de `trade.specialLabels`. Por defecto todos valen `10`. Ejemplo: `"shiny": 15, "paradox": 5` hace que un shiny valga el triple que una Paradoja.
- `combineMode`: qué pasa cuando un Pokémon cumple varios tipos a la vez (por ejemplo, un legendario shiny). `HIGHEST` suma solo el valor más alto; `SUM` los suma todos (con valores de 10, un legendario shiny daría 20, el doble).

Funcionamiento: cuando el jugador ofrece un Pokémon **normal**, se tira un dado con su porcentaje acumulado; si sale, recibe un especial (del pool si hay, y si no se genera uno). Si recibe un especial ofreciendo uno normal, su porcentaje vuelve a 0. Ofrecer un especial nunca resetea el porcentaje, solo lo sube. El jugador puede consultarlo con `/wt chance` y los admins con `/wt chance <jugador>`. Los mensajes (`bonusIncreased`, `bonusReset`, `bonusInfo`) son editables.

## `event`

Valores del evento temporal (`/wt admin event start [minutos]`). Mientras está activo:

- `cooldownMultiplier`: multiplica el cooldown de intercambio (`0.5` = la mitad, `0` = sin cooldown). Por defecto `0.5`.
- `bonusGainMultiplier`: multiplica lo que suma cada especial ofrecido al bonus. Por defecto `2.0`.
- `extraSpecialChance`: porcentaje extra (suma plana) de recibir un especial al ofrecer un Pokémon normal. Por defecto `5.0`.
- `defaultDurationMinutes`: duración si no se indican minutos. Por defecto `60`.

Los mensajes `eventStart`, `eventEnd`, `eventStatus` y `eventNone` son editables y admiten `{time}`, `{cooldown}`, `{bonus}` y `{extra}`. El evento sobrevive a reinicios (se guarda en `event.json`).

## `history`

- `enabled`: guarda un registro de cada intercambio en `history.json`.
- `maxEntries`: máximo de intercambios guardados; al superarlo se borran los más antiguos.

Se consulta con `/wt admin history`.

## `admin`

- `permissionLevel`: nivel de operador (`0–4`) necesario para los comandos `/wt admin ...`. Por defecto `2`.

## `gui`

- `enabled`: `/wt` sin argumentos abre el menú. Si es `false`, muestra la ayuda.
- `poolViewPermissionLevel`: nivel de operador necesario para `/wt pool view` y el botón "Ver pool". `0` = todos los jugadores, `2` = solo operadores, `4` = solo owner.
- `showDonorNames`: muestra quién aportó cada Pokémon en la vista del pool.

## `broadcast`

- `enabled`: activa anuncios.
- `normalDonations`: anuncia aportaciones normales.
- `shinyDonations`: anuncia aportaciones shiny.
- `specialDonations` / `shinySpecialDonations`: legendarios, míticos y etiquetas de `trade.specialLabels` (normal / shiny).
- `ultraBeastDonations` / `shinyUltraBeastDonations`: Ultraentes.
- `pastParadoxDonations` / `shinyPastParadoxDonations`: Paradoja del pasado (Great Tusk, Scream Tail, Brute Bonnet, Flutter Mane, Slither Wing, Sandy Shocks, Roaring Moon, Walking Wake, Gouging Fire, Raging Bolt).
- `futureParadoxDonations` / `shinyFutureParadoxDonations`: Paradoja del futuro (Iron Treads, Iron Bundle, Iron Hands, Iron Jugulis, Iron Moth, Iron Thorns, Iron Valiant, Iron Leaves, Iron Boulder, Iron Crown).

Cada intercambio genera un solo anuncio. Prioridad: Ultraente > Paradoja pasado/futuro > legendario/especial > shiny sin categoría > normal. Cada categoría tiene su versión shiny con mensaje propio en `messages` (`broadcastUltraBeast`, `broadcastShinyUltraBeast`, `broadcastPastParadox`, etc.).

## `messages`

Todas las cadenas son editables. Los colores usan `&` como prefijo, por ejemplo `&a` para verde. Las plantillas admiten `{sent}`, `{received}`, `{seconds}`, `{time}`, `{limit}`, `{min}`, `{max}`, `{size}`, `{player}` y `{pokemon}` según el mensaje.

## Importante sobre shiny

Las reglas del pool y las reglas de generación aleatoria son diferentes. Si quieres que entregar un shiny garantice otro shiny incluso cuando el pool esté vacío, usa `trade.shinyMode: "REQUIRE_MATCH"` y `fallback.shinyBehavior: "MATCH_INPUT"`. Eso puede hacer que el intercambio no se complete si no se encuentra un resultado compatible tras el número de intentos configurado; no se retirará el Pokémon ofrecido en ese caso.
