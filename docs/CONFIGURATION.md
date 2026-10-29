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

## `broadcast`

- `enabled`: activa anuncios.
- `normalDonations`: anuncia aportaciones normales.
- `shinyDonations`: anuncia aportaciones shiny.

## `messages`

Todas las cadenas son editables. Los colores usan `&` como prefijo, por ejemplo `&a` para verde. Las plantillas admiten `{sent}`, `{received}`, `{seconds}`, `{time}`, `{limit}`, `{min}`, `{max}`, `{size}`, `{player}` y `{pokemon}` según el mensaje.

## Importante sobre shiny

Las reglas del pool y las reglas de generación aleatoria son diferentes. Si quieres que entregar un shiny garantice otro shiny incluso cuando el pool esté vacío, usa `trade.shinyMode: "REQUIRE_MATCH"` y `fallback.shinyBehavior: "MATCH_INPUT"`. Eso puede hacer que el intercambio no se complete si no se encuentra un resultado compatible tras el número de intentos configurado; no se retirará el Pokémon ofrecido en ese caso.
