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

- `cooldownMinutes`: espera entre intercambios. `0` desactiva el cooldown.
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
