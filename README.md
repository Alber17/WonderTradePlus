# WonderTrade Plus

Wonder Trade abierto y configurable para **Cobblemon**. Se distribuye como **mod de servidor Fabric** (no es un plugin Bukkit/Paper). Los jugadores no necesitan instalar nada extra: la interfaz es un menú de cofre del propio servidor.

## Características

- **Menú (GUI)** para elegir el Pokémon a ofrecer desde el **equipo** o desde el **PC**, con pantalla de confirmación.
- **Pool persistente** de Pokémon aportados por los jugadores, guardados como datos completos de Cobblemon (no solo la especie).
- Vista del **pool completo** en un menú paginado de solo lectura (se puede restringir a operadores).
- Generación aleatoria opcional si el pool no tiene un Pokémon compatible.
- Filtros de shiny, legendarios/míticos, Ultraentes y etiquetas especiales (la lista de etiquetas es configurable).
- Coincidencia de rareza, shiny y nivel configurable.
- Cooldown por jugador, límite diario opcional y bypass para operadores.
- Listas negra y blanca de especies.
- Protección para no intercambiar Pokémon no intercambiables, debilitados, alfa o con objeto equipado (según configuración).
- **Anuncios en el chat** cuando alguien aporta un Pokémon shiny, legendario, Ultraente o Paradoja del pasado/futuro, cada uno con su mensaje propio y su versión shiny.
- Mensajes y anuncios totalmente editables.
- Guardado JSON con escritura temporal y reemplazo atómico cuando el sistema de archivos lo permite.

## Compatibilidad

- Minecraft Java **1.21.1**
- Java **21**
- Fabric Loader + Fabric API + Fabric Language Kotlin
- Cobblemon **1.8.4** (versiones posteriores compatibles con 1.21.1 sin probar)

No es compatible con NeoForge, Arclight, Mohist ni servidores Bukkit/Paper.

## Instalación en servidor

1. Haz una copia de seguridad del mundo.
2. Copia el JAR en la carpeta `mods/` del servidor Fabric, junto a Fabric API, Fabric Language Kotlin y Cobblemon.
3. Arranca el servidor una vez para generar `config/wondertradeplus/config.json`, `pool.json` y `player-data.json`.
4. Edita `config/wondertradeplus/config.json` y ejecuta `/wt reload` (o reinicia).

En un servidor dedicado, el mod va **solo en el servidor**. En un mundo individual, ponlo en la carpeta `mods` de tu instancia, porque Minecraft usa un servidor integrado.

## Comandos

| Comando | Función |
|---|---|
| `/wt` (o `/wt gui`) | Abre el menú con tu equipo, el acceso al PC y el pool. |
| `/wt <slot>` | Elige el Pokémon de ese slot (1–6) y pide confirmación. |
| `/wt confirm` | Confirma el intercambio pendiente. |
| `/wt <slot> confirm` | **Intercambia ese slot al instante**, sin pedir confirmación aparte. |
| `/wt cancel` | Cancela el intercambio pendiente. |
| `/wt pool` | Muestra cuántos Pokémon hay en el pool. |
| `/wt pool view` | Abre el pool completo en un menú paginado (solo lectura). Nivel de permiso configurable con `gui.poolViewPermissionLevel`. |
| `/wt help` | Muestra la ayuda. |
| `/wt reload` | Recarga configuración y archivos. Operador nivel 2. |
| `/wt clearpool` | Vacía el pool. Operador nivel 2. |

`/wondertrade` es un alias de `/wt`.

**Ejemplos**

- `/wt 3 confirm`: intercambia el Pokémon del slot 3 de golpe.
- `/wt 3` y luego `/wt confirm`: lo mismo en dos pasos.

### Cómo funciona el menú

1. `/wt` muestra tu equipo. Pulsa un Pokémon para ofrecerlo, o **Abrir PC** para elegir uno de tus cajas (con botones para cambiar de caja).
2. Se abre una pantalla de confirmación (si `general.requireConfirmation` está activado).
3. Al confirmar, recibes otro Pokémon. Si ofreciste uno del equipo, el nuevo llega al equipo; si era del PC, llega al PC.

Los Pokémon que no se pueden ofrecer (debilitados, fuera del rango de nivel, cooldown activo, etc.) se marcan en rojo y el motivo aparece en el chat al pulsarlos.

## Anuncios

Cuando alguien aporta un Pokémon al pool, se anuncia en el chat según su categoría. Solo se envía un anuncio por intercambio, con esta prioridad:

Ultraente > Paradoja (pasado / futuro) > legendario o especial > shiny > normal

Cada categoría tiene su versión shiny con un mensaje distinto. Todo se activa, desactiva y edita en las secciones `broadcast` y `messages` de `config.json`. Por defecto no se anuncian los Pokémon normales.

## Configuración

La plantilla completa está en [`src/main/resources/wondertradeplus-default.json`](src/main/resources/wondertradeplus-default.json) y la documentación campo por campo en [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md). El JSON no admite comentarios ni comas sobrantes. Si falta alguna opción nueva en tu `config.json`, se añade sola al arrancar.

Opciones principales:

- `general.requireConfirmation`: pide confirmación antes de intercambiar.
- `trade.cooldownMinutes`: tiempo mínimo entre intercambios (`0` lo desactiva).
- `trade.dailyLimit`: máximo diario (`0` = ilimitado).
- `trade.matchSpecialTier`: normales por normales y especiales por especiales.
- `trade.specialLabels`: etiquetas que definen un Pokémon como especial (`legendary`, `mythical`, `ultra_beast`, `paradox` por defecto).
- `trade.shinyMode`: `MATCH_IF_AVAILABLE`, `REQUIRE_MATCH`, `IGNORE` o `DISALLOW_SHINY`.
- `trade.matchLevel` / `trade.levelDifference`: limita la diferencia de nivel al buscar en el pool.
- `general.speciesBlacklist`, `general.useSpeciesWhitelist`, `general.speciesWhitelist`: listas de especies.
- `pool.enabled`, `pool.randomFallback`, `pool.maximumEntries`: comportamiento del pool.
- `fallback.shinyBehavior`: `MATCH_INPUT`, `SERVER_RATE`, `NEVER` o `ALWAYS`.
- `gui.enabled`: si es `false`, `/wt` muestra la ayuda en lugar del menú.
- `gui.poolViewPermissionLevel`: nivel de operador para ver el pool (`0` = todos, `2` = solo operadores).
- `gui.showDonorNames`: muestra quién aportó cada Pokémon en la vista del pool.
- `broadcast.*`: qué categorías se anuncian.

El modo shiny es una regla importante: revísalo antes de abrir el servidor. Ver el pool quita la sorpresa del Wonder Trade; si quieres mantenerla, pon `gui.poolViewPermissionLevel` en `2`.

## Datos y recuperación

- `pool.json`: Pokémon almacenados y sus índices de búsqueda.
- `player-data.json`: último intercambio y contador diario de cada jugador.
- `config.json`: reglas del servidor.

Prueba los cambios de configuración en una copia del servidor antes de producción. Si el guardado falla durante un intercambio, se revierte, pero una caída brusca del proceso entre escrituras independientes de Cobblemon y de WonderTrade Plus no puede garantizar atomicidad absoluta.

## Compilar

La forma más sencilla es dejar que GitHub lo compile: cada `push` lanza el flujo de **Actions**, y el JAR queda en *Artifacts* (`wondertradeplus-build`). Usa el archivo sin `-sources`.

Para compilar en tu PC necesitas JDK 21 y Gradle 8.14:

```bash
gradle build
```

El mod queda en `build/libs/`. El proyecto no incluye Gradle Wrapper.

## Contribuir

Las contribuciones se aceptan bajo MIT. Conserva `LICENSE` si publicas el proyecto. No incluye código de otros mods de Wonder Trade.
