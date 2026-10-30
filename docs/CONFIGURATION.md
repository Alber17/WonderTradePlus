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
- `acceptDonations`: guarda en la pool una copia del Pokémon ofrecido tras completar un Wonder Trade.
- `playerDonationsEnabled`: permite `/wt donar`, que es una aportación directa sin recibir otro Pokémon. No consume cooldown ni límite diario; la pool se comprueba tanto al abrir el menú como al confirmar. Si alcanza `maximumEntries`, la donación se rechaza con un mensaje.
- `randomFallback`: opción heredada que se ignora; ningún intercambio genera Pokémon fuera del pool.
- `maximumEntries`: máximo de entradas guardadas.
- `trimOldestFirst`: elimina entradas antiguas al superar el máximo si está activo.
- `clampDonatedLevel`: ajusta el nivel de la copia donada al pool.
- `donatedMinimumLevel`, `donatedMaximumLevel`: rango usado por ese ajuste.

## `fallback`

- `minimumLevel`, `maximumLevel`: rango de nivel aleatorio para `/wt admin addspecies` y `/wt admin fill`. `addspecies` recibe la especie base y acepta, opcionalmente, los mismos atributos de `/pokegive`: `alolan`, `galarian`, `hisuian`, `paldean` y `shiny`. Ejemplos: `/wt admin addspecies vulpix alolan`, `/wt admin addspecies vulpix alolan shiny` y `/wt admin addspecies meowth shiny galarian`. No se usa `form=...`.
- `speciesGenerationAttempts`: intentos máximos para que `/wt admin fill` genere una especie válida.
- `shinyBehavior`: opción heredada que ya no afecta a los intercambios.

## `antiAbuse`

- `excludeOwnDonations`: un jugador no puede recibir Pokémon que él mismo aportó al pool. Por defecto `true`.
- `donationHoldMinutes`: minutos que un Pokémon recién aportado por un jugador no puede ser recibido por nadie. Por defecto `10`; `0` lo desactiva. No afecta a lo creado por comandos de admin ni a entradas antiguas.

Si ninguna entrada de la pool es válida para un jugador, el intercambio se cancela y no se genera ni entrega ningún Pokémon externo a la pool. La donación directa `/wt donar` no requiere entrada de retorno.

## `maintenance`

Mantenimiento automático del pool, cada `intervalMinutes` minutos. Solo elimina las entradas caducadas; no genera Pokémon automáticamente.

- `expireAfterDays`: borra las entradas más antiguas que estos días. `0` lo desactiva. Por defecto `14`.
- `autoRefill`, `minimumEntries`, `refillBatchSize` y `refillSpecialChance`: opciones heredadas que se ignoran. El mantenimiento no rellena el pool; para añadir Pokémon generados de forma explícita, usa `/wt admin fill`.
- `intervalMinutes`: cada cuánto se ejecuta. Por defecto `30`.

El mantenimiento manual con `/wt admin maintenance` solo elimina entradas caducadas. Los Pokémon generados con `/wt admin fill` salen con el donante `Servidor`.

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
- `normalTradeGain`: puntos porcentuales que suma ofrecer un Pokémon normal. Por defecto `0.1` por intercambio. Se suma antes de la tirada de probabilidad de ese mismo intercambio. Es un porcentaje en puntos porcentuales: `0.1` significa +0,1%, no 10%.
- `specialTradeGain`: puntos porcentuales que suma cada Pokémon clasificado como especial por el sistema (shiny, legendario, mítico, Ultraente o Paradoja). Por defecto `0.5` por intercambio. Se suma una sola vez aunque el Pokémon pertenezca a varias categorías.
- `specialShinyExtraGain`: puntos porcentuales adicionales cuando el Pokémon ofrecido es **shiny y además** es legendario, mítico, Ultraente o Paradoja. Por defecto `0.2`; por tanto, un especial shiny suma `0.5 + 0.2 = 0.7` puntos porcentuales. Un shiny de especie normal conserva el incremento base `specialTradeGain` (+0,5% por defecto), pero no recibe este extra.
- `shiny`, `legendary`, `mythical`, `ultraBeast`, `paradox`, `other` y `combineMode`: opciones heredadas que se conservan en archivos antiguos por compatibilidad, pero ya no intervienen en el cálculo.

El libro que aparece al abrir `/wt` muestra estos valores: normal, shiny sin categoría especial, Pokémon legendario/mítico/Ultraente/Paradoja y el total para especial shiny. La línea shiny sin categoría especial usa el mismo `specialTradeGain` que el resto de Pokémon considerados especiales. Todos los incrementos se expresan en puntos porcentuales (`0.1` = +0,1%, no 10%). Durante un evento activo, se aplica `event.bonusGainMultiplier` tanto al incremento base como al extra shiny.

Funcionamiento: cuando el jugador ofrece un Pokémon **normal**, primero se suma `normalTradeGain` y después se tira un dado con el porcentaje acumulado actualizado para ese mismo intercambio; si sale, se intenta entregar un especial existente en la pool. Si no hay ninguno disponible, recibe cualquier Pokémon válido de la pool y conserva el bonus acumulado. Al recibir un especial, el porcentaje vuelve a 0. Ofrecer un Pokémon especial suma `specialTradeGain` y, si además es shiny y pertenece a las categorías legendario, mítico, Ultraente o Paradoja, suma también `specialShinyExtraGain`; ese incremento se aplica al siguiente intercambio normal. El jugador puede consultarlo con `/wt chance` y los admins con `/wt chance <jugador>`. El mensaje de intercambio incluye en una sola línea el Pokémon recibido y el estado actualizado del bonus; se personaliza con `messages.success`, `successBonusIncreased`, `successBonusReset` y `successBonusUnchanged`. Los mensajes (`bonusIncreased`, `bonusNormalIncreased`, `bonusReset`, `bonusInfo`) son editables; `bonusInfo` admite `{specialShinyExtraGain}`. Si un `bonusInfo` personalizado de una configuración anterior no incluye ese marcador, se muestra adicionalmente `bonusShinyExtraInfo`, que también es editable.

## `event`

Valores del evento temporal (`/wt admin event start [minutos]`). Mientras está activo:

- `cooldownMultiplier`: multiplica el cooldown de intercambio (`0.5` = la mitad, `0` = sin cooldown). Por defecto `0.5`.
- `bonusGainMultiplier`: multiplica los incrementos `normalTradeGain`, `specialTradeGain` y `specialShinyExtraGain` mientras el evento está activo. Por defecto `2.0`.
- `extraSpecialChance`: porcentaje extra (suma plana) de recibir un especial al ofrecer un Pokémon normal. Por defecto `5.0`.
- `defaultDurationMinutes`: duración si no se indican minutos. Por defecto `60`.

Los mensajes `eventStart`, `eventEnd`, `eventStatus` y `eventNone` son editables y admiten `{time}`, `{cooldown}`, `{bonus}` y `{extra}`. El evento sobrevive a reinicios (se guarda en `event.json`).

## `history`

- `enabled`: guarda un registro de cada intercambio en `history.json`.
- `maxEntries`: máximo de intercambios guardados; al superarlo se borran los más antiguos.

Se consulta con `/wt admin history`.

## `admin`

Todos los subcomandos `/wt admin ...`, además de `/wt reload`, `/wt clearpool` y las consultas/modificaciones de bonus a otros jugadores mediante `/wt chance <jugador>`, requieren el nivel configurado aquí. Los comandos `/wt`, `/wt donar`, `/wt stats`, `/wt top`, `/wt event`, `/wt cancel` y `/wt chance` propio son públicos. `/wt pool` usa `gui.poolViewPermissionLevel`.

- `permissionLevel`: nivel de operador (`0–4`) necesario para los comandos `/wt admin ...`. Por defecto `2`.
- `regionalForms.alolan`, `regionalForms.galarian`, `regionalForms.hisuian`, `regionalForms.paldean`: activa o desactiva cada opción de forma regional para `/wt admin addspecies`. Todas están activadas por defecto. Se aceptan únicamente los atributos de comando `alolan`, `galarian`, `hisuian` y `paldean`, igual que `/pokegive`. Ejemplos: `/wt admin addspecies vulpix alolan`, `/wt admin addspecies vulpix alolan shiny` y `/wt admin addspecies meowth shiny galarian`. No se debe usar `form=alola`. El mod comprueba que la forma exista para esa especie y que Cobblemon la haya aplicado antes de guardarla en el pool.

## `gui`

- `enabled`: `/wt` sin argumentos abre el menú. Si es `false`, muestra la ayuda.
- `poolViewPermissionLevel`: nivel de operador necesario para `/wt pool` y el botón "Ver pool". `0` = todos los jugadores, `2` = solo operadores, `4` = solo owner.
- `showDonorNames`: muestra quién aportó cada Pokémon en la vista del pool.
- `showBonusRatesInBook`: muestra en el libro del menú `/wt` los incrementos configurados para Pokémon normales, shiny sin categoría especial, legendarios/míticos/Ultraentes/Paradojas y especiales shiny. Si `bonus.enabled` está desactivado, muestra el texto configurable `messages.menuBookBonusDisabled`.

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

Todas las cadenas son editables. Los colores usan `&` como prefijo, por ejemplo `&a` para verde. Las plantillas admiten `{sent}`, `{received}`, `{seconds}`, `{time}`, `{limit}`, `{min}`, `{max}`, `{size}`, `{player}` y `{pokemon}` según el mensaje. `messages.success` acepta `{bonusText}` para mostrar resultado y cambio de probabilidad en un único mensaje; `successBonusIncreased`, `successBonusReset` y `successBonusUnchanged` personalizan el fragmento del bonus. `donationPoolFull` admite `{size}` y `{maximum}`.

## Selección de Pokémon

La selección sigue el funcionamiento aleatorio de Wonder Trade: no busca una especie, rareza, nivel ni estado shiny compatible con el Pokémon ofrecido. Escoge cualquier entrada válida del pool. Si se activa el bonus especial, intenta escoger una entrada especial existente; si no hay ninguna disponible, escoge cualquier entrada válida y conserva el bonus acumulado. Las protecciones antiabuso y las opciones `general.allow*` y listas de especies siguen aplicándose.
