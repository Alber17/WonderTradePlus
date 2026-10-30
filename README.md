# WonderTrade Plus

Wonder Trade abierto y configurable para **Cobblemon**. Se distribuye como **mod de servidor Fabric** (no es un plugin Bukkit/Paper). Los jugadores no necesitan instalar nada extra: la interfaz es un menú de cofre del propio servidor.

## Características

- **Menú (GUI)** para elegir el Pokémon a ofrecer desde el **equipo** o desde el **PC**, con pantalla de confirmación.
- **Pool persistente** de Pokémon aportados por los jugadores, guardados como datos completos de Cobblemon (no solo la especie).
- **Donación directa** mediante `/wt donar`: usa el mismo menú de equipo/PC, retira el Pokémon elegido y lo añade a la pool sin entregar otro ni consumir cooldown o límite diario. Máximo 5 donaciones por jugador en los últimos 7 días (configurable); especies prohibidas editables en `donation.speciesBlacklist`. Si la pool está llena, avisa y no abre el menú.
- Vista del **pool completo** en un menú paginado de solo lectura (se puede restringir a operadores).
- Los intercambios entregan cualquier Pokémon válido que ya exista en la pool; nunca generan Pokémon durante un intercambio.
- Filtros de admisión para shiny, legendarios/míticos, Ultraentes y etiquetas especiales (la lista de etiquetas es configurable).
- Bonus acumulado configurable: +0,1 puntos porcentuales por un Pokémon normal, +0,5 por un Pokémon shiny o especial y +0,2 extra si el ofrecido es shiny y además legendario, mítico, Ultraente o Paradoja. El libro del menú `/wt` muestra estos porcentajes.
- Cooldown por jugador, límite diario opcional y bypass para operadores; el menú muestra un contador que se actualiza cada segundo.
- Sonidos configurables de cofre/Ender Chest, clics, paginación y obtención de especiales; un especial shiny reproduce el sonido del tótem.
- Panel gráfico de administración en `/wt admin`, con accesos a la pool, historial, recarga, mantenimiento, copias de seguridad y vaciado protegido por confirmación.
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

Los comandos de jugador son públicos salvo que se indique otra cosa. Los niveles OP se configuran con `admin.permissionLevel` (por defecto 2).

| Comando | Permiso | Función |
|---|---|---|
| `/wt` | Todos | Abre el menú de intercambio con equipo, PC, contador de cooldown y acceso a la pool. `/wt gui` se ha eliminado porque era un alias redundante. |
| `/wt donar` | Todos | Abre el mismo menú en modo donación: el Pokémon se añade a la pool y no recibes otro. No consume cooldown ni límite diario; máximo 5 donaciones en 7 días por defecto. |
| `/wt <slot>` | Todos | Elige un Pokémon del equipo (slot 1–6) y pide confirmación. |
| `/wt confirm` | Todos | Confirma el intercambio pendiente. |
| `/wt <slot> confirm` | Todos | Intercambia ese slot al instante, sin confirmación aparte. |
| `/wt chance` | Todos | Muestra tu probabilidad acumulada de recibir un Pokémon especial. |
| `/wt chance <jugador>` | `admin.permissionLevel` | Muestra la probabilidad acumulada de otro jugador. |
| `/wt chance <jugador> set <valor>` o `reset` | `admin.permissionLevel` | Fija su bonus (0–100) o lo pone a 0. |
| `/wt stats [jugador]` | Todos | Muestra intercambios, donaciones directas, especiales recibidos/aportados y bonus. |
| `/wt top [trades\|received\|donated]` | Todos | Ranking de intercambios, especiales recibidos o especiales aportados. |
| `/wt event` | Todos | Muestra si hay un evento activo y cuánto le queda. |
| `/wt cancel` | Todos | Cancela el intercambio pendiente. |
| `/wt pool` | `gui.poolViewPermissionLevel` (por defecto 0) | Abre la pool en un menú paginado de solo lectura, con filtro de categoría y orden por hora añadida ascendente/descendente. |
| `/wt help` | Todos | Muestra los comandos públicos; añade los comandos de administración solo si el jugador tiene `admin.permissionLevel`. |
| `/wt reload` | `admin.permissionLevel` | Recarga configuración y archivos. |
| `/wt clearpool` | `admin.permissionLevel` | Vacía la pool. |
| `/wt admin` | `admin.permissionLevel` | Abre el panel gráfico de administración. |

### Comandos de administrador

Todos requieren `admin.permissionLevel` (nivel OP 2 por defecto, configurable en `config.json`). En `WonderTradePlus.kt` cada ruta administrativa incluye un comentario `// PERMISO` junto al registro del comando, indicando el permiso que hereda o exige.

| Comando | Permiso | Función |
|---|---|---|
| `/wt admin add <slot>` | `admin.permissionLevel` | Añade a la pool una copia del Pokémon del equipo en ese slot. Tú conservas el original. |
| `/wt admin addspecies <especie> [forma] [shiny]` | `admin.permissionLevel` | Crea un Pokémon con nivel aleatorio y lo añade a la pool. Usa la misma sintaxis visible que `/pokegive`: `alolan`, `galarian`, `hisuian`, `paldean` y `shiny`. El mod resuelve la `FormData` registrada para esa especie para evitar depender de alias internos. Ejemplos: `/wt admin addspecies vulpix alolan`, `/wt admin addspecies vulpix alolan shiny`, `/wt admin addspecies meowth shiny galarian`. No se usa `form=...`. |
| `/wt admin fill <cantidad> [probabilidad%] [nivelMin nivelMax]` | `admin.permissionLevel` | Genera hasta 500 Pokémon para la pool. Sin probabilidad indicada, se calcula automáticamente; con `probabilidad%`, se usa ese valor. Ejemplos: `/wt admin fill 200 5` o `/wt admin fill 50 0 10 30`. |
| `/wt admin remove` | `admin.permissionLevel` | Abre la pool en modo administrador con eliminación activada. |
| `/wt admin resetstats <jugador o all>` | `admin.permissionLevel` | Reinicia contadores y rankings de un jugador o de todos. Conserva bonus, cooldowns y límites diarios. |
| `/wt admin removeplayer <jugador>` | `admin.permissionLevel` | Elimina de la pool todos los Pokémon aportados por ese jugador. |
| `/wt admin event start [minutos]` | `admin.permissionLevel` | Inicia un evento temporal (duración predeterminada configurable). |
| `/wt admin event stop` | `admin.permissionLevel` | Termina el evento activo. |
| `/wt admin backup` | `admin.permissionLevel` | Crea una copia de seguridad manual. |
| `/wt admin maintenance` | `admin.permissionLevel` | Ejecuta el mantenimiento y elimina entradas caducadas; no genera Pokémon. |
| `/wt admin discord test` | `admin.permissionLevel` | Envía un mensaje de prueba al webhook de Discord. |
| `/wt admin history [jugador]` | `admin.permissionLevel` | Abre el historial paginado, opcionalmente filtrado por jugador. |
| `/wt admin clear` | `admin.permissionLevel` | Vacía la pool. |

`/wondertrade` es un alias de `/wt`.

**Ejemplos**

- `/wt 3 confirm`: intercambia el Pokémon del slot 3 de golpe.
- `/wt 3` y después `/wt confirm`: realiza el intercambio en dos pasos.
- `/wt donar`: abre el menú de donación; el Pokémon se retira del equipo o PC y se incorpora a la pool sin recibir otro. Hay un máximo de 5 donaciones por jugador en los últimos 7 días (configurable). No puedes recibir tus propias aportaciones y se pueden bloquear especies mediante `donation.speciesBlacklist`.

### Cómo funciona el menú

1. `/wt` muestra tu equipo. Pulsa un Pokémon para ofrecerlo, o **Abrir PC** para elegir uno de tus cajas (con botones para cambiar de caja). `/wt donar` abre el mismo menú en modo donación directa; la confirmación retira el Pokémon y lo añade a la pool sin entregar otro Pokémon.
2. Se abre una pantalla de confirmación (si `general.requireConfirmation` está activado).
3. Al confirmar, recibes otro Pokémon. Si ofreciste uno del equipo, el nuevo llega al equipo; si era del PC, llega al PC. El resultado y el aumento, reinicio o mantenimiento de tu probabilidad se muestran juntos en un único mensaje de chat.

Cada Pokémon muestra su nivel, **tipos**, forma (Alolan, Galarian…), categoría real (Legendario, Mítico, Ultraente, Paradoja), la etiqueta Shiny y si es alfa. El menú muestra un reloj con el tiempo restante del cooldown y se actualiza cada segundo. Los sonidos de apertura, PC, botones, paginación y obtención de especiales se pueden activar y ajustar en `sounds.*`; un especial shiny usa el sonido del tótem. El libro del menú `/wt` informa de los incrementos configurados: normal, shiny, especiales y especial shiny.

Los Pokémon que no se pueden ofrecer (debilitados, fuera del rango de nivel, cooldown activo, etc.) se marcan en rojo y el motivo aparece en el chat al pulsarlos.

## Probabilidad acumulada de especiales

Cada jugador tiene un porcentaje propio que aumenta con cada intercambio. Los incrementos actuales se muestran dentro del libro del menú `/wt` y se pueden cambiar en `bonus.*`. Por defecto: **+0,1 puntos porcentuales al ofrecer un Pokémon normal** y **+0,5 puntos porcentuales al ofrecer un Pokémon especial** (shiny, legendario, mítico, Ultraente o Paradoja), hasta el máximo configurado (60% por defecto). Si el Pokémon ofrecido es shiny y además es legendario, mítico, Ultraente o Paradoja, suma **+0,2 puntos porcentuales extra**: aporta +0,7% en total por defecto. Los tres valores se pueden configurar por separado con `bonus.normalTradeGain`, `bonus.specialTradeGain` y `bonus.specialShinyExtraGain`. Un shiny de especie normal conserva el incremento especial existente (+0,5%), pero no recibe el extra de +0,2%.

Al ofrecer un Pokémon **normal**, el incremento normal se suma primero y el porcentaje acumulado se utiliza para la tirada de ese mismo intercambio: si se activa, se intenta entregar un Pokémon especial que ya exista en el pool. Si no hay ninguno disponible, recibe cualquier Pokémon válido del pool y conserva el bonus acumulado. Al recibir un especial, su porcentaje vuelve a 0. Ofrecer un Pokémon especial suma el incremento especial y ese nuevo porcentaje se aplica en el siguiente intercambio normal. Se consulta con `/wt chance` (los admins pueden ver el de otro jugador con `/wt chance <jugador>`).

## Protección contra abusos

- Un jugador **no puede recibir sus propias aportaciones** (`antiAbuse.excludeOwnDonations`).
- Un Pokémon recién aportado **no se puede recibir hasta pasados unos minutos** (`antiAbuse.donationHoldMinutes`, 10 por defecto), para que dos cuentas no se lo pasen al instante.

## Mantenimiento del pool y copias de seguridad

- Las entradas del pool **caducan** a los `maintenance.expireAfterDays` días (14 por defecto) y se borran solas.
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

La configuración completa está en `config/wondertradeplus/config.json`. La plantilla de valores iniciales está en [`src/main/resources/wondertradeplus-default.json`](src/main/resources/wondertradeplus-default.json) y la documentación campo por campo en [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md). El JSON no admite comentarios ni comas sobrantes. Si falta alguna opción nueva en tu `config.json`, se añade sola al arrancar o al ejecutar `/wt reload`.

Opciones principales:

- `general.requireConfirmation`: pide confirmación antes de intercambiar.
- `trade.cooldownMinutes`: tiempo mínimo entre intercambios, en minutos. Por defecto `720` (**12 horas**); `0` lo desactiva. Los operadores lo ignoran.
- `trade.dailyLimit`: máximo diario (`0` = ilimitado).
- `trade.specialLabels`: etiquetas que definen un Pokémon como especial (`legendary`, `mythical`, `ultra_beast`, `paradox` por defecto).
- La selección no empareja especie, rareza, nivel ni estado shiny: se elige cualquier entrada válida de la pool. Los ajustes antiguos `matchSpecialTier`, `avoidSameSpecies`, `matchLevel`, `levelDifference` y `shinyMode` se conservan por compatibilidad, pero ya no se utilizan.
- `general.speciesBlacklist`, `general.useSpeciesWhitelist`, `general.speciesWhitelist`: listas de especies.
- `pool.enabled`, `pool.maximumEntries`: comportamiento del pool. Los intercambios solo entregan Pokémon ya guardados en el pool.
- `donation.weeklyLimit`: máximo de donaciones directas por jugador en una ventana móvil de 7 días; `0` = ilimitado (5 por defecto). `donation.speciesBlacklist`: especies que no se pueden donar, acepta nombres como `ditto` o `cobblemon:ditto`.
- `fallback.minimumLevel` / `fallback.maximumLevel`: rango aleatorio para `/wt admin addspecies` y `/wt admin fill`.
- `bonus.enabled`, `bonus.maximum`, `bonus.normalTradeGain`, `bonus.specialTradeGain` y `bonus.specialShinyExtraGain`: activan el sistema, fijan el límite y configuran los incrementos: normal (+0,1 puntos porcentuales), especial (+0,5) y el extra por especial shiny (+0,2 adicionales cuando es legendario, mítico, Ultraente o Paradoja) por defecto.
- `admin.regionalForms`: permite activar o desactivar individualmente las opciones `alolan`, `galarian`, `hisuian` y `paldean` de `/wt admin addspecies`. El jugador escribe la opción regional igual que en `/pokegive`; el mod localiza y asigna la forma registrada en Cobblemon sin exigir `form=...`. `fallback.minimumLevel` y `fallback.maximumLevel` configuran el nivel aleatorio.
- `event.*`: efectos del evento temporal.
- `antiAbuse.*`, `maintenance.*`, `backup.*`: protección, mantenimiento del pool y copias.
- `discord.*`: avisos a Discord.
- `history.enabled` / `history.maxEntries`: registro de intercambios.
- `admin.permissionLevel`: nivel de operador para los comandos `/wt admin`.
- `gui.enabled`: si es `false`, `/wt` muestra la ayuda en lugar del menú.
- `gui.poolViewPermissionLevel`: nivel de operador para ver el pool (`0` = todos, `2` = solo operadores).
- `gui.showDonorNames`: muestra quién aportó cada Pokémon en la vista del pool.
- `sounds.enabled` y `sounds.*`: activan los sonidos de los menús, apertura del PC, clics, cambio de página y especiales; `sounds.volume` y `sounds.pitch` ajustan volumen y tono. El shiny especial usa el sonido del tótem.
- La vista de pool incorpora orden por hora añadida: ascendente (más antiguos primero) y descendente (más recientes primero).
- `broadcast.*`: qué categorías se anuncian.

El modo shiny es una regla importante: revísalo antes de abrir el servidor. Ver el pool quita la sorpresa del Wonder Trade; si quieres mantenerla, pon `gui.poolViewPermissionLevel` en `2`.

## Datos y recuperación

- `pool.json`: Pokémon almacenados y sus índices de búsqueda.
- `player-data.json`: último intercambio, contador diario, bonus y marcas de tiempo de las donaciones semanales de cada jugador.
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
