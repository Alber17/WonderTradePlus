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
- `matchSpecialTier`: opción heredada; ya no se usa. Wonder Trade puede entregar cualquier especie de la pool, sin emparejar la rareza del Pokémon ofrecido.
- `specialLabels`: etiquetas adicionales que cuentan como categoría especial, además de legendarios, míticos y Ultraentes detectados por Cobblemon.
- `avoidSameSpecies`: opción heredada; ya no se usa. La especie ofrecida no limita la selección.
- `matchLevel` y `levelDifference`: opciones heredadas; ya no se usan. El nivel ofrecido no limita la selección.
- `shinyMode`: opción heredada; ya no se usa para emparejar. El Pokémon recibido se selecciona al azar entre las entradas válidas de la pool; `general.allowShinyPokemon` sigue controlando si se permiten shiny.

## `pool`

- `enabled`: usa el pool compartido de Pokémon aportados por jugadores.
- `acceptDonations`: guarda en el pool una copia del Pokémon que se ha ofrecido tras completar el intercambio.
- `randomFallback`: opción heredada que se ignora; ningún intercambio genera Pokémon fuera del pool.
- `maximumEntries`: máximo de entradas guardadas.
- `trimOldestFirst`: elimina entradas antiguas al superar el máximo si está activo.
- `clampDonatedLevel`: ajusta el nivel de la copia donada al pool.
- `donatedMinimumLevel`, `donatedMaximumLevel`: rango usado por ese ajuste.

## `fallback`

- `minimumLevel`, `maximumLevel`: rango de nivel aleatorio para `/wt admin addspecies` y `/wt admin fill`.
- `speciesGenerationAttempts`: intentos máximos para que `/wt admin fill` genere una especie válida.
- `shinyBehavior`: opción heredada que ya no afecta a los intercambios.

## `antiAbuse`

- `excludeOwnDonations`: un jugador no puede recibir Pokémon que él mismo aportó al pool. Por defecto `true`.
- `donationHoldMinutes`: minutos que un Pokémon recién aportado por un jugador no puede ser recibido por nadie. Por defecto `10`; `0` lo desactiva. No afecta a lo creado por comandos de admin ni a entradas antiguas.

Si ninguna entrada del pool es válida para un jugador, el intercambio se cancela y no se genera ni entrega ningún Pokémon externo al pool.

## `maintenance`

Mantenimiento automático del pool, cada `intervalMinutes` minutos. Solo elimina las entradas caducadas; no genera Pokémon automáticamente.

- `expireAfterDays`: borra las entradas más antiguas que estos días. `0` lo desactiva. Por defecto `14`.
- `autoRefill`: opción heredada que se ignora. El mantenimiento ya no rellena el pool automáticamente; para añadir Pokémon generados de forma explícita, usa `/wt admin fill`.
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
- `specialTradeGain`: porcentaje que suma cada Pokémon especial ofrecido. Por defecto `0.5` puntos porcentuales por intercambio, independientemente de si es shiny, legendario, mítico, Ultraente o Paradoja. Se suma una sola vez aunque el Pokémon pertenezca a varias categorías.
- `shiny`, `legendary`, `mythical`, `ultraBeast`, `paradox`, `other` y `combineMode`: opciones heredadas que se conservan en archivos antiguos por compatibilidad, pero ya no intervienen en el cálculo.

Funcionamiento: cuando el jugador ofrece un Pokémon **normal**, se tira un dado con su porcentaje acumulado; si sale, se intenta entregar un especial existente en la pool. Si no hay ninguno disponible, recibe cualquier Pokémon válido de la pool y conserva el bonus. Si recibe un especial, su porcentaje vuelve a 0. Ofrecer un especial nunca resetea el porcentaje, solo lo aumenta. El jugador puede consultarlo con `/wt chance` y los admins con `/wt chance <jugador>`. Los mensajes (`bonusIncreased`, `bonusReset`, `bonusInfo`) son editables.

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
- `poolViewPermissionLevel`: nivel de operador necesario para `/wt pool` y el botón "Ver pool". `0` = todos los jugadores, `2` = solo operadores, `4` = solo owner.
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

## Selección de Pokémon

La selección sigue el funcionamiento aleatorio de Wonder Trade: no busca una especie, rareza, nivel ni estado shiny compatible con el Pokémon ofrecido. Escoge cualquier entrada válida del pool. Si se activa el bonus especial, intenta escoger una entrada especial existente; si no hay ninguna disponible, escoge cualquier entrada válida y conserva el bonus acumulado. Las protecciones antiabuso y las opciones `general.allow*` y listas de especies siguen aplicándose.
