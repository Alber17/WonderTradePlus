# WonderTrade Plus

Wonder Trade abierto y configurable para **Cobblemon**. Se distribuye como **mod de servidor Fabric** (no es un plugin Bukkit/Paper). Los jugadores no necesitan instalar nada extra: la interfaz es un menú de cofre del propio servidor.

## Características

- **Menú (GUI)** para elegir el Pokémon a ofrecer desde el **equipo** o desde el **PC**, con pantalla de confirmación.
- **Pool persistente** de Pokémon aportados por los jugadores, guardados como datos completos de Cobblemon (no solo la especie).
- Vista del **pool completo** en un menú paginado de solo lectura (se puede restringir a operadores).
- Los intercambios entregan cualquier Pokémon válido que ya exista en la pool; nunca generan Pokémon durante un intercambio.
- Filtros de admisión para shiny, legendarios/míticos, Ultraentes y etiquetas especiales (la lista de etiquetas es configurable).
- Bonus acumulado de +0,5 puntos porcentuales por cada Pokémon especial aportado.
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
| `/wt chance` | Muestra tu probabilidad acumulada de recibir un Pokémon especial. |
| `/wt chance <jugador>` | **Solo admins.** Muestra la probabilidad acumulada de otro jugador (conectado, o que ya haya hecho algún intercambio). Se autocompleta con Tab. |
| `/wt stats [jugador]` | Muestra intercambios, especiales recibidos y aportados, y probabilidad acumulada de un jugador (el tuyo si no pones nombre). |
| `/wt top [trades\|received\|donated]` | Ranking de los 10 mejores: intercambios (por defecto), especiales recibidos o especiales aportados. Si no estás en el top te dice tu posición. |
| `/wt event` | Muestra si hay un evento activo y cuánto le queda. |
| `/wt cancel` | Cancela el intercambio pendiente. |
| `/wt pool` | Abre directamente el pool en un menú paginado (solo lectura). Los filtros se cambian desde el propio menú. Nivel de permiso configurable con `gui.poolViewPermissionLevel`. |
| `/wt help` | Muestra la ayuda. |
| `/wt reload` | Recarga configuración y archivos. Operador nivel 2. |
| `/wt clearpool` | Vacía el pool. Operador nivel 2. |

### Comandos de administrador

Requieren el nivel de operador de `admin.permissionLevel` (por defecto 2).

| Comando | Función |
|---|---|
| `/wt admin add <slot>` | Añade al pool una **copia** del Pokémon de tu equipo en ese slot. Tú conservas el tuyo. |
| `/wt admin addspecies <especie> [shiny]` | Crea un Pokémon de esa especie con nivel aleatorio (rango `fallback.minimumLevel`–`fallback.maximumLevel`) y lo añade al pool. Ej.: `/wt admin addspecies pikachu` o `/wt admin addspecies vulpix-alola shiny`. La especie y las formas se autocompletan con Tab. **Formas regionales:** `vulpix-alola`, `meowth-galar`, `growlithe-hisui`, `wooper-paldea`. |
| `/wt admin fill <cantidad> [probabilidad%] [nivelMin nivelMax]` | Genera hasta 500 Pokémon aleatorios para el pool. **Cada Pokémon tiene una probabilidad baja de ser especial** (shiny, legendario, mítico, Ultraente o Paradoja); el resto son siempre normales. Sin indicar nada, la probabilidad se calcula sola según la cantidad: `0,25% + 0,005% por Pokémon`, con máximo 2% (200 Pokémon ≈ 1,25% cada uno, unos 2-3 especiales de media; puede salir ninguno). Con `probabilidad%` la fijas tú: `/wt admin fill 200 5` usa un 5%, `/wt admin fill 200 0` no genera ninguno especial. Para fijar niveles hay que poner también la probabilidad: `/wt admin fill 50 0 10 30`. Respeta las listas de especies y los permisos de shiny/especial/alfa.
| `/wt admin remove` | Abre el pool en modo administrador con el modo de eliminación activado. También puedes activarlo desde el botón situado junto a «Volver». |
| `/wt admin resetstats <jugador\|all>` | Reinicia los contadores de `/wt stats` y los rankings de `/wt top` para un jugador o para todos. Conserva bonus, cooldowns y límites diarios. |
| `/wt admin removeplayer <jugador>` | Elimina del pool todo lo que aportó ese jugador (usa `Servidor` para lo creado con los comandos admin). |
| `/wt admin event start [minutos]` | Inicia un evento temporal (por defecto `event.defaultDurationMinutes`): menos cooldown, más bonus y probabilidad extra de especial. Lo anuncia a todos. |
| `/wt admin event stop` | Termina el evento antes de tiempo. |
| `/wt chance <jugador> set <valor>` / `reset` | **Solo admins.** Fija el porcentaje acumulado de un jugador (0–100) o lo pone a 0. Útil para compensar errores o dar premios. |
| `/wt admin backup` | Crea una copia de seguridad del pool y los datos ahora mismo. |
| `/wt admin maintenance` | Ejecuta ya el mantenimiento del pool (borrar caducados y rellenar). |
| `/wt admin discord test` | Envía un mensaje de prueba al webhook de Discord. |
| `/wt admin history [jugador]` | Abre el **historial de intercambios** en un menú paginado: quién dio qué y qué recibió, con el icono del Pokémon recibido, origen (pool o azar) y fecha. Con un nombre filtra por ese jugador. |
| `/wt admin clear` | Vacía el pool. |

`/wondertrade` es un alias de `/wt`.

**Ejemplos**

- `/wt 3 confirm`: intercambia el Pokémon del slot 3 de golpe.
- `/wt 3` y luego `/wt confirm`: lo mismo en dos pasos.

### Cómo funciona el menú

1. `/wt` muestra tu equipo. Pulsa un Pokémon para ofrecerlo, o **Abrir PC** para elegir uno de tus cajas (con botones para cambiar de caja).
2. Se abre una pantalla de confirmación (si `general.requireConfirmation` está activado).
3. Al confirmar, recibes otro Pokémon. Si ofreciste uno del equipo, el nuevo llega al equipo; si era del PC, llega al PC.

Cada Pokémon muestra su nivel, **tipos**, forma (Alolan, Galarian…), categoría real (Legendario, Mítico, Ultraente, Paradoja), la etiqueta Shiny y si es alfa.

Los Pokémon que no se pueden ofrecer (debilitados, fuera del rango de nivel, cooldown activo, etc.) se marcan en rojo y el motivo aparece en el chat al pulsarlos.

## Probabilidad acumulada de especiales

Cada jugador tiene un porcentaje propio que **sube +0,5 puntos porcentuales cada vez que ofrece un Pokémon especial** (shiny, legendario, mítico, Ultraente o Paradoja), hasta el máximo configurado (60% por defecto). La subida se aplica una sola vez por intercambio, aunque el Pokémon tenga varias características especiales. El valor se puede ajustar con `bonus.specialTradeGain`.

Cuando ese jugador ofrece después un Pokémon **normal**, se tira un dado con el porcentaje acumulado: si se activa, se intenta entregar un Pokémon especial que ya exista en el pool. Si no hay ninguno disponible, recibe cualquier Pokémon válido del pool y conserva el bonus acumulado para futuros intercambios. Al recibir un especial, su porcentaje vuelve a 0. Ofrecer un especial solo aumenta el porcentaje, nunca lo resetea. Se consulta con `/wt chance` (los admins pueden ver el de otro jugador con `/wt chance <jugador>`).

## Protección contra abusos

- Un jugador **no puede recibir sus propias aportaciones** (`antiAbuse.excludeOwnDonations`).
- Un Pokémon recién aportado **no se puede recibir hasta pasados unos minutos** (`antiAbuse.donationHoldMinutes`, 10 por defecto), para que dos cuentas no se lo pasen al instante.

## Mantenimiento del pool y copias de seguridad

- Las entradas del pool **caducan** a los `maintenance.expireAfterDays` días (14 por defecto) y se borran solas.
- Si el pool baja de `maintenance.minimumEntries` (100 por defecto), se **rellena automáticamente** con Pokémon aleatorios, con una probabilidad baja de especiales. Necesita algún jugador conectado.
- Cada `backup.intervalHours` horas (6 por defecto) se guarda una copia de los archivos de datos en `config/wondertradeplus/backups/`, conservando las últimas `backup.keep`. Se hace otra copia antes de vaciar el pool.

## Avisos a Discord

Con un webhook de Discord, el mod puede publicar en un canal cuando alguien **aporta o recibe un Pokémon especial** (shiny, legendario, mítico, Ultraente o Paradoja) y cuando empieza o termina un evento. Se configura en la sección `discord`:

1. En Discord: *Ajustes del canal → Integraciones → Webhooks → Nuevo webhook → Copiar URL*.
2. Pega la URL en `discord.webhookUrl` y pon `discord.enabled` en `true`.
3. Ejecuta `/wt reload` y prueba con `/wt admin discord test`.

La URL del webhook es secreta; no la subas a GitHub.

## Eventos temporales

Un admin puede lanzar un evento con `/wt admin event start [minutos]`. Mientras dura, el cooldown se reduce, cada especial ofrecido suma más al bonus y todos tienen un porcentaje extra de recibir un especial. Se anuncia a todos al empezar y al terminar, y los jugadores lo consultan con `/wt event`. Todo se ajusta en la sección `event`.

## Estadísticas y ranking

El mod guarda por jugador cuántos intercambios lleva y cuántos especiales ha recibido y aportado (desde que se instaló esta versión). `/wt stats [jugador]` lo muestra y `/wt top` enseña el ranking.

## Anuncios

Cuando alguien aporta un Pokémon al pool, se anuncia en el chat según su categoría. Solo se envía un anuncio por intercambio, con esta prioridad:

Ultraente > Paradoja (pasado / futuro) > legendario o especial > shiny > normal

Cada categoría tiene su versión shiny con un mensaje distinto. Todo se activa, desactiva y edita en las secciones `broadcast` y `messages` de `config.json`. Por defecto no se anuncian los Pokémon normales.

## Configuración

La plantilla completa está en [`src/main/resources/wondertradeplus-default.json`](src/main/resources/wondertradeplus-default.json) y la documentación campo por campo en [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md). El JSON no admite comentarios ni comas sobrantes. Si falta alguna opción nueva en tu `config.json`, se añade sola al arrancar.

Opciones principales:

- `general.requireConfirmation`: pide confirmación antes de intercambiar.
- `trade.cooldownMinutes`: tiempo mínimo entre intercambios, en minutos. Por defecto `720` (**12 horas**); `0` lo desactiva. Los operadores lo ignoran.
- `trade.dailyLimit`: máximo diario (`0` = ilimitado).
- `trade.specialLabels`: etiquetas que definen un Pokémon como especial (`legendary`, `mythical`, `ultra_beast`, `paradox` por defecto).
- La selección no empareja especie, rareza, nivel ni estado shiny: se elige cualquier entrada válida de la pool. Los ajustes antiguos `matchSpecialTier`, `avoidSameSpecies`, `matchLevel`, `levelDifference` y `shinyMode` se conservan por compatibilidad, pero ya no se utilizan.
- `general.speciesBlacklist`, `general.useSpeciesWhitelist`, `general.speciesWhitelist`: listas de especies.
- `pool.enabled`, `pool.maximumEntries`: comportamiento del pool. Los intercambios solo entregan Pokémon ya guardados en el pool.
- `fallback.minimumLevel` / `fallback.maximumLevel`: rango aleatorio para `/wt admin addspecies` y `/wt admin fill`.
- `bonus.specialTradeGain`: aumento por ofrecer un Pokémon especial (0,5 puntos porcentuales por defecto); `bonus.maximum` limita el acumulado.
- `event.*`: efectos del evento temporal.
- `antiAbuse.*`, `maintenance.*`, `backup.*`: protección, mantenimiento del pool y copias.
- `discord.*`: avisos a Discord.
- `history.enabled` / `history.maxEntries`: registro de intercambios.
- `admin.permissionLevel`: nivel de operador para los comandos `/wt admin`.
- `gui.enabled`: si es `false`, `/wt` muestra la ayuda en lugar del menú.
- `gui.poolViewPermissionLevel`: nivel de operador para ver el pool (`0` = todos, `2` = solo operadores).
- `gui.showDonorNames`: muestra quién aportó cada Pokémon en la vista del pool.
- `broadcast.*`: qué categorías se anuncian.

El modo shiny es una regla importante: revísalo antes de abrir el servidor. Ver el pool quita la sorpresa del Wonder Trade; si quieres mantenerla, pon `gui.poolViewPermissionLevel` en `2`.

## Datos y recuperación

- `pool.json`: Pokémon almacenados y sus índices de búsqueda.
- `player-data.json`: último intercambio y contador diario de cada jugador.
- `history.json`: historial de intercambios.
- `event.json`: evento temporal activo.
- `backups/`: copias automáticas de los archivos anteriores.
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
