# WonderTrade Plus

Wonder Trade abierto y configurable para **Cobblemon**. Se distribuye como **mod Fabric exclusivamente de servidor** (no es un plugin Bukkit/Paper). La interfaz es un menú de cofre controlado por el servidor; los jugadores no necesitan instalar WonderTradePlus en sus clientes.

## Características

- **Menú (GUI)** para elegir el Pokémon a ofrecer desde el **equipo** o desde el **PC**, con pantalla de confirmación.
- **Pool persistente** de Pokémon aportados por los jugadores, guardados como datos completos de Cobblemon (no solo la especie).
- **Donación directa** mediante `/wt donar`: usa el mismo menú de equipo/PC, retira el Pokémon elegido y lo añade a la pool sin entregar otro ni consumir cooldown o límite diario. Límite mensual por jugador (20 por defecto, se reinicia el día 1; configurable o por ventana de 7 días); no se puede donar la misma especie dos veces en el mismo mes; especies prohibidas editables en `donation.speciesBlacklist`. Si la pool está llena, avisa y no abre el menú.
- Vista del **pool completo** en un menú paginado de solo lectura (se puede restringir a operadores).
- Los intercambios entregan cualquier Pokémon válido que ya exista en la pool; nunca generan Pokémon durante un intercambio.
- Filtros de admisión para shiny, legendarios/míticos, Ultraentes y etiquetas especiales (la lista de etiquetas es configurable).
- Bonus acumulado configurable: +0,1 puntos porcentuales por un Pokémon normal, +0,5 por un Pokémon shiny o especial y +0,2 extra si el ofrecido es shiny y además legendario, mítico, Ultraente o Paradoja. El libro del menú `/wt` muestra estos porcentajes.
- Cooldown por jugador, límite diario opcional y bypass para operadores; el menú muestra un contador que se actualiza cada segundo.
- Sonidos configurables de cofre/Ender Chest, clics, paginación y obtención de especiales; el sonido del tótem solo suena con un legendario, mítico, Ultraente o Paradoja que además sea shiny; un shiny normal usa el sonido de especial.
- Panel gráfico de administración en `/wt admin`, con accesos a la pool, historial, recarga, mantenimiento, copias de seguridad y vaciado protegido por confirmación.
- Listas negra y blanca de especies.
- Protección para no intercambiar Pokémon no intercambiables, debilitados, alfa o con objeto equipado (según configuración).
- **Anuncios en el chat** cuando alguien aporta un Pokémon shiny, legendario, Ultraente o Paradoja del pasado/futuro, cada uno con su mensaje propio y su versión shiny.
- **Hora navideña:** del 25 de diciembre al 6 de enero se abren cuatro sesiones aleatorias de una hora al día; cada sesión genera una pool nueva, muestra una bossbar azul animada y se puede controlar desde el panel de administración.
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

Instala WonderTradePlus únicamente en la carpeta `mods/` del servidor. Los jugadores no necesitan instalar este mod ni ningún componente adicional de WonderTradePlus.

## Comandos

Los comandos de jugador son públicos salvo que se indique otra cosa. Los niveles OP se configuran con `admin.permissionLevel` (por defecto 2).

| Comando | Permiso | Función |
|---|---|---|
| `/wt` | Todos | Abre el menú de intercambio con equipo, PC, contador de cooldown y acceso a la pool. `/wt gui` se ha eliminado porque era un alias redundante. |
| `/wt donar` | Todos | Abre el mismo menú en modo donación: el Pokémon se añade a la pool y no recibes otro. No consume cooldown ni límite diario; máximo 5 donaciones en 7 días por defecto. |
| `/wt <slot>` | Todos | Elige un Pokémon del equipo (slot 1–6) y pide confirmación. |
| `/wt confirm` | Todos | Confirma el intercambio pendiente. |
| `/wt <slot> confirm` | Todos | Intercambia ese slot al instante, sin confirmación aparte. |
| `/wt stats [jugador]` | Todos | Muestra intercambios, donaciones directas, especiales recibidos/aportados y bonus. |
| `/wt top` | Todos | Abre un menú para elegir entre los tres rankings. |
| `/wt cancel` | Todos | Cancela el intercambio pendiente. |
| `/wt pool` | `gui.poolViewPermissionLevel` (por defecto 0) | Abre la pool en un menú paginado de solo lectura, con filtro de categoría, orden por hora añadida ascendente/descendente (la opción activa lleva una flecha ▶) y búsqueda por nombre o especie. También lo abren los admins en vista normal; para gestionar la pool usan `/wt admin`. |
| `/wt pool search <texto>` | `gui.poolViewPermissionLevel` (por defecto 0) | Abre la pool mostrando solo los Pokémon cuyo nombre (apodo) o especie coincidan con el texto, por ejemplo `/wt pool search tusk` o `great tusk`. Desde el menú, el botón **Buscar por nombre o especie** cierra el menú y deja en el chat un enlace clicable que rellena este comando. |
| `/wt help` | Todos | Muestra los comandos públicos; añade los comandos de administración solo si el jugador tiene `admin.permissionLevel`. |
| `/wt reload` | `admin.permissionLevel` | Recarga configuración y archivos. |
| `/wt eventos` | Todos | Muestra los eventos activos, qué hacen y cuándo puedes usar el intercambio de evento. |
| `/wt admin eventos` | `admin.permissionLevel` | Lista los eventos activos con su id. |
| `/wt admin eventos crear <arriesgar\|recibir> <categorías> <duración> [valor] [espera]` | `admin.permissionLevel` | Crea un evento. Duración: `30s`, `5m`, `2h`, `1d`, `1h30m`. El valor y la espera son opcionales. |
| `/wt admin eventos programar <tipo> <categoría> <duración> <cuándo>` | `admin.permissionLevel` | Programa un evento para que empiece solo. «Cuándo» admite una hora (`20:00`), un día de la semana (`sabado 20:00`), una fecha (`15/11 18:30`), y repetición `diario`, `semanal`, `mensual` o `anual`; también `valor=N` y `espera=2m`. Desde el panel Gestionar eventos → Programar evento se puede elegir duración, día, ajustar el horario con botones y seleccionar la repetición. Ejemplo: `programar recibir shiny 3h sabado 20:00 semanal`. |
| `/wt admin eventos programados` | `admin.permissionLevel` | Lista los eventos activos y los programados. |
| `/wt admin eventos desprogramar <id>` | `admin.permissionLevel` | Cancela un evento programado (el id sale como `P1`, `P2`…). |
| `/wt admin eventos parar <id>` | `admin.permissionLevel` | Finaliza un evento. |
| `/wt especiales` | Todos | Menú con los Pokémon que son Ultraentes, Paradoja, Míticos y Legendarios (los que cuentan en los eventos). También hay un botón en el menú principal. |
| `/wt rango` | Todos | Muestra solo tu rango, sus ventajas y tu probabilidad actual; a los admins les indica además qué sistema de permisos detecta. |
| `/wt rangos` (alias `/wt ranks`) | Todos | Abre un menú con todos los rangos; las ventajas de cada uno se ven pasando el ratón por encima. |
| `/wt on` / `/wt off` | `admin.permissionLevel` | Activa o desactiva Wonder Trade sin quitar el mod. |
| `/wt clearpool` | `admin.permissionLevel` | Vacía la pool. |
| `/wt admin` | `admin.permissionLevel` | Abre el panel gráfico de administración. |

### Comandos de administrador

Todos requieren `admin.permissionLevel` (nivel OP 2 por defecto, configurable en `config.json`). En `WonderTradePlus.kt` cada ruta administrativa incluye un comentario `// PERMISO` junto al registro del comando, indicando el permiso que hereda o exige.

| Comando | Permiso | Función |
|---|---|---|
| `/wt admin add <slot>` | `admin.permissionLevel` | Añade a la pool una copia del Pokémon del equipo en ese slot. Tú conservas el original. |
| `/wt admin addspecies <especie> [forma] [shiny]` | `admin.permissionLevel` | Crea un Pokémon con nivel aleatorio y lo añade a la pool. Desde consola requiere que haya al menos un jugador conectado como contexto de generación. Usa la misma sintaxis visible que `/pokegive`: `alolan`, `galarian`, `hisuian`, `paldean` y `shiny`. El mod resuelve la `FormData` registrada para esa especie para evitar depender de alias internos. Ejemplos: `/wt admin addspecies vulpix alolan`, `/wt admin addspecies vulpix alolan shiny`, `/wt admin addspecies meowth shiny galarian`. No se usa `form=...`. |
| `/wt admin fill <cantidad> [probabilidad%] [nivelMin nivelMax]` | `admin.permissionLevel` | Genera Pokémon para la pool sin superar su máximo configurado (500 por defecto). Funciona desde consola si hay al menos un jugador conectado para crear las entidades Cobblemon. Sin probabilidad indicada, se calcula automáticamente; con `probabilidad%`, se usa ese valor. Ejemplos: `/wt admin fill 200 5` o `/wt admin fill 50 0 10 30`. |
| `/wt admin remove` | `admin.permissionLevel` | Abre la pool en modo administrador con eliminación activada (solo en juego); el panel también permite borrar por origen. |
| `/wt admin resetstats <jugador o all>` | `admin.permissionLevel` | Reinicia contadores y rankings de un jugador o de todos. Conserva bonus, cooldowns y límites diarios. |
| `/wt admin removeplayer <jugador>` | `admin.permissionLevel` | Elimina de la pool todos los Pokémon aportados por ese jugador. |
| `/wt admin removegenerated` | `admin.permissionLevel` | Elimina solo Pokémon generados por el servidor y conserva las aportaciones de jugadores. Funciona desde consola. |
| `/wt admin removeplayers` | `admin.permissionLevel` | Elimina solo Pokémon aportados por jugadores y conserva los generados por el servidor. Funciona desde consola. |
| `/wt admin backup` | `admin.permissionLevel` | Crea una copia de seguridad manual. |
| `/wt admin maintenance` | `admin.permissionLevel` | Ejecuta el mantenimiento y elimina entradas caducadas; no genera Pokémon. |
| `/wt admin discord test` | `admin.permissionLevel` | Envía un mensaje de prueba al webhook de Discord. |
| `/wt admin history [jugador]` | `admin.permissionLevel` | Abre el historial paginado en juego; desde consola muestra las últimas 15 entradas, opcionalmente filtradas por jugador. |
| `/wt admin clear` | `admin.permissionLevel` | Vacía la pool. |

`/wondertrade` es un alias de `/wt`.

**Ejemplos**

- `/wt 3 confirm`: intercambia el Pokémon del slot 3 de golpe.
- `/wt 3` y después `/wt confirm`: realiza el intercambio en dos pasos.
- `/wt donar`: abre el menú de donación; el Pokémon se retira del equipo o PC y se incorpora a la pool sin recibir otro. Hay un máximo de donaciones por jugador y mes natural (`donation.monthlyLimit`, 20 por defecto; con `donation.limitMode: weekly` se usa la ventana de 7 días). No puedes recibir tus propias aportaciones y se pueden bloquear especies mediante `donation.speciesBlacklist`.

### Cómo funciona el menú

1. `/wt` muestra tu equipo. Pulsa un Pokémon para ofrecerlo, o **Abrir PC** para elegir uno de tus cajas (con botones para cambiar de caja). `/wt donar` abre el mismo menú en modo donación directa; la confirmación retira el Pokémon y lo añade a la pool sin entregar otro Pokémon.
2. Se abre una pantalla de confirmación (si `general.requireConfirmation` está activado).
3. Al confirmar, recibes otro Pokémon. Si ofreciste uno del equipo, el nuevo llega al equipo; si era del PC, llega al PC. El resultado y el aumento, reinicio o mantenimiento de tu probabilidad se muestran juntos en un único mensaje de chat.

Cada Pokémon muestra su nivel, **tipos**, forma (Alolan, Galarian…), categoría real (Legendario, Mítico, Ultraente, Paradoja), la etiqueta Shiny y si es alfa. El menú muestra un reloj con el tiempo restante del cooldown y se actualiza cada segundo. Los sonidos de apertura, PC, botones, paginación y obtención de especiales se pueden activar y ajustar en `sounds.*`; el tótem solo suena con un especial de categoría que además sea shiny. El libro del menú `/wt` informa de los incrementos configurados: normal, shiny, especiales y especial shiny.

Los Pokémon que no se pueden ofrecer (debilitados, fuera del rango de nivel, cooldown activo, etc.) se marcan en rojo y el motivo aparece en el chat al pulsarlos.

## Probabilidad acumulada de especiales

Cada jugador tiene un porcentaje propio que aumenta con cada intercambio. Los incrementos actuales se muestran dentro del libro del menú `/wt` y se pueden cambiar en `bonus.*`. Por defecto: **+0,1 puntos porcentuales al ofrecer un Pokémon normal** y **+0,5 puntos porcentuales al ofrecer un Pokémon especial** (shiny, legendario, mítico, Ultraente o Paradoja), hasta el máximo configurado (60% por defecto). Si el Pokémon ofrecido es shiny y además es legendario, mítico, Ultraente o Paradoja, suma **+0,2 puntos porcentuales extra**: aporta +0,7% en total por defecto. Los tres valores se pueden configurar por separado con `bonus.normalTradeGain`, `bonus.specialTradeGain` y `bonus.specialShinyExtraGain`. Un shiny de especie normal conserva el incremento especial existente (+0,5%), pero no recibe el extra de +0,2%.

Al ofrecer un Pokémon **normal**, el incremento normal se suma primero y el porcentaje acumulado se utiliza para la tirada de ese mismo intercambio: si se activa, se intenta entregar un Pokémon especial que ya exista en el pool. Si no hay ninguno disponible, recibe cualquier Pokémon válido del pool y conserva el bonus acumulado. Al recibir un especial, su porcentaje vuelve a 0. Ofrecer un Pokémon especial suma el incremento especial y ese nuevo porcentaje se aplica en el siguiente intercambio normal. El libro del menú principal muestra los incrementos del bonus.

## Protección contra abusos

- Un jugador **no puede recibir sus propias aportaciones** (`antiAbuse.excludeOwnDonations`).
- Un Pokémon recién aportado **no se puede recibir hasta pasados unos minutos** (`antiAbuse.donationHoldMinutes`, 10 por defecto), para que dos cuentas no se lo pasen al instante.

## Mantenimiento del pool y copias de seguridad

- Las entradas del pool **caducan** a los `maintenance.expireAfterDays` días (14 por defecto) y se borran solas.
- Cada `backup.intervalHours` horas (6 por defecto) se guarda una copia de los archivos de datos en `config/wondertradeplus/backups/`, conservando las últimas `backup.keep`. Se hace otra copia antes de vaciar el pool.

## Avisos a Discord

Con un webhook de Discord, el mod puede publicar en un canal cuando alguien **aporta o recibe un Pokémon especial** (shiny, legendario, mítico, Ultraente o Paradoja). Se configura en la sección `discord`:

1. En Discord: *Ajustes del canal → Integraciones → Webhooks → Nuevo webhook → Copiar URL*.
2. Pega la URL en `discord.webhookUrl` y pon `discord.enabled` en `true`.
3. Ejecuta `/wt reload` y prueba con `/wt admin discord test`.

Los avisos se envían como **tarjetas** (embeds) con color según la rareza (shiny dorado, legendario morado, mítico rosa, Ultraente azul, Paradoja naranja), imagen oficial del Pokémon y campos de jugador, Pokémon, nivel y categoría. Al terminar un evento, la tarjeta muestra intercambios, jugadores y duración, y **lista los nicks de quienes han conseguido algo gracias al evento**. Colores, títulos, pie y miniaturas se cambian en `discord.style` y `discord.titles.*`; con `discord.style.useEmbeds: false` se envía texto simple.

La URL del webhook es secreta; no la subas a GitHub.

## Hora navideña

La temporada de Navidad está configurada del **25 de diciembre al 6 de enero**, ambos incluidos. No permanece activa todo el día: se sortean **cuatro horas distintas cada día**, y en cada una se abre una sesión de **60 minutos**. Los horarios se eligen al azar entre las 24 horas del día y se separan al menos tres horas y se guardan en `seasonal-state.json`, por lo que un reinicio no vuelve a sortear ni duplica las sesiones de ese día.

Cada activación genera una pool navideña nueva. Al terminar la hora, la pool temporal se archiva con una marca temporal y se restaura la pool normal con su antigüedad congelada. Para que Cobblemon pueda generar la pool debe haber al menos un jugador conectado al inicio de la sesión.

La bossbar de Navidad es azul y muestra un texto con degradado azul-blanco animado y el tiempo restante. En `/wt admin` → **Gestionar eventos** → **Hora navideña** puedes iniciar una sesión manualmente, pausarla/reanudarla o finalizarla para hacer pruebas. El inicio manual funciona también fuera de las fechas navideñas.

## Estadísticas y ranking

El mod guarda por jugador cuántos intercambios lleva y cuántos especiales ha recibido y aportado (desde que se instaló esta versión). `/wt stats [jugador]` lo muestra y `/wt top` enseña el ranking.

## Anuncios

Cuando alguien aporta un Pokémon al pool, se anuncia en el chat según su categoría. Además, cuando un jugador recibe un shiny, legendario, mítico, Ultraente o Paradoja, se envía un anuncio global indicando quién lo ha conseguido. Los anuncios de recepción tienen ajustes independientes en `broadcast.receivedShiny`, `receivedLegendary`, `receivedMythical`, `receivedUltraBeast` y `receivedParadox`. Para las aportaciones, solo se envía un anuncio por intercambio, con esta prioridad:

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
- `donation.limitMode` (`monthly` o `weekly`), `donation.monthlyLimit` (20; `0` = ilimitado), `donation.weeklyLimit` (5; ventana móvil de 7 días si el modo es `weekly`), `donation.sameSpecies.period` (`month`, `week` u `off`: no repetir especie al donar) y `donation.blockSameIndividual`. `donation.speciesBlacklist`: especies que no se pueden donar, acepta nombres como `ditto` o `cobblemon:ditto`.
- `fallback.minimumLevel` / `fallback.maximumLevel`: rango aleatorio para `/wt admin addspecies` y `/wt admin fill`.
- `bonus.enabled`, `bonus.maximum`, `bonus.normalTradeGain`, `bonus.specialTradeGain` y `bonus.specialShinyExtraGain`: activan el sistema, fijan el límite y configuran los incrementos: normal (+0,1 puntos porcentuales), especial (+0,5) y el extra por especial shiny (+0,2 adicionales cuando es legendario, mítico, Ultraente o Paradoja) por defecto.
- `admin.regionalForms`: permite activar o desactivar individualmente las opciones `alolan`, `galarian`, `hisuian` y `paldean` de `/wt admin addspecies`. El jugador escribe la opción regional igual que en `/pokegive`; el mod localiza y asigna la forma registrada en Cobblemon sin exigir `form=...`. `fallback.minimumLevel` y `fallback.maximumLevel` configuran el nivel aleatorio.
- `antiAbuse.*`, `maintenance.*`, `backup.*`: protección, mantenimiento del pool y copias.
- `discord.*`: avisos a Discord.
- `seasonal.events[]`: fechas, tipos, tamaño de pool y sesiones de temporada. Para Navidad se usan `sessionsPerDay: 4` y `sessionDurationMinutes: 60`; las horas aleatorias se guardan en `seasonal-state.json` para no cambiar tras reiniciar.
- `history.enabled` / `history.maxEntries`: registro de intercambios.
- `history.autoDeleteAfterDays`: elimina automáticamente del historial las entradas con más de 30 días; `0` desactiva el borrado automático.
- `cooldownNotice.normalReminderMinutes` / `cooldownNotice.eventReminderMinutes`: recordatorios de disponibilidad cada 10 minutos en condiciones normales y cada 2 minutos mientras haya un evento activo. Son configurables y se envían a todos los jugadores elegibles.
- `admin.permissionLevel`: nivel de operador para los comandos `/wt admin`.
- `gui.enabled`: si es `false`, `/wt` muestra la ayuda en lugar del menú.
- `gui.poolViewPermissionLevel`: nivel de operador para ver el pool (`0` = todos, `2` = solo operadores).
- `gui.showDonorNames`: muestra quién aportó cada Pokémon en la vista del pool.
- `sounds.enabled` y `sounds.*`: activan los sonidos de los menús, apertura del PC, clics, cambio de página y especiales; `sounds.volume` y `sounds.pitch` ajustan volumen y tono. El tótem solo suena con un especial de categoría que además sea shiny.
- La vista de pool incorpora orden por hora añadida: ascendente (más antiguos primero) y descendente (más recientes primero).
- `broadcast.*`: qué categorías se anuncian.

El modo shiny es una regla importante: revísalo antes de abrir el servidor. Ver el pool quita la sorpresa del Wonder Trade; si quieres mantenerla, pon `gui.poolViewPermissionLevel` en `2`.

## Datos y recuperación

- `pool.json`: Pokémon almacenados y sus índices de búsqueda.
- `player-data.json`: último intercambio, contador diario, bonus y marcas de tiempo de las donaciones semanales de cada jugador.
- `history.json`: historial de intercambios.
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


## 0.26.3

- Navidad deja de ser una temporada continua: del 25 de diciembre al 6 de enero se programan cuatro sesiones aleatorias de 1 hora al día.
- Cada sesión genera una pool nueva y restaura la pool original al finalizar; los horarios diarios se persisten para sobrevivir a reinicios.
- Bossbar navideña azul con texto animado en degradado azul-blanco.
- Nuevo menú de administración para iniciar, pausar/reanudar y finalizar manualmente la hora navideña.
- Aviso de Discord breve al aparecer la hora navideña, indicando cuánto dura.
- README y documentación reorganizados y actualizados.
- Gradle no se ha ejecutado en este entorno.

## 0.26.2

- Retirado `speciesChances` de la configuración de temporadas; se mantiene `excludeSpecies` para excluir especies concretas.

## 0.26.1

- Eventos de temporada: `excludeSpecies` para quitar especies de la pool del evento (0.26.2 retira `speciesChances`).

## 0.26.0

- Eventos de temporada (Navidad): entre dos fechas la pool pasa a ser temática (tipo hielo) y solo se pueden intercambiar Pokémon de ese tipo; la pool original se guarda y vuelve al terminar. Todo se configura en `seasonal`, sin comandos.

## 0.25.1

- `general.formBlacklist` y `general.aspectBlacklist`: bloqueo de formas y aspectos (por ejemplo texturas de un resource pack) al ofrecer y al recibir.

## 0.25.0

- Historial por UUID (los menús siguen mostrando nicks): cambiar de nick ya no hace perder el historial.
- Barra de evento en el idioma de cada jugador.
- Pool: nuevos filtros Shiny, Ultraentes, Paradoja y Legendarios (legendarios y míticos juntos) y orden por especie A-Z.
- Ejemplo de lista negra de especies (`general.speciesBlacklist`) en la configuración.

## 0.24.0

- Nuevos idiomas: francés (`fr_fr`) e italiano (`it_it`). Los jugadores los eligen con `/wt idioma` o se detectan por el idioma de su Minecraft.

## 0.23.0

- Avisos de Discord en formato tarjeta (embeds) con colores por rareza, imagen del Pokémon y campos; el fin de un evento lista los nicks de quienes consiguieron algo gracias a él.
- `/wt rango` rediseñado (solo tu rango y tu probabilidad actual) y nuevo `/wt rangos` con un menú de todos los rangos.
- El nombre del mod en negrita en el chat se aplica siempre (`general.boldPrefix`).
- Talla XS/S/M/L/XL en la pool (`pool.size.*`).
- `/wt topdonaciones` solo con el evento terminado.

## 0.22.1

- Eliminado `/wt admin idioma`; el idioma por defecto del servidor se configura en `general.language`. Se mantiene `/wt idioma` para las preferencias individuales.
- `/wt donaciones` y `/wt topdonaciones` solo muestran la clasificación cuando el evento mensual ha terminado. El cierre también se procesa aunque nadie haya donado.
- La lista de la pool muestra junto al nombre del Pokémon su categoría de tamaño nativa de Cobblemon: XS, S, M, L o XL.
- README, `config-guide.txt`, idiomas y documentación actualizados.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.22.0

- Historial personal: `/wt historial` (alias `/wt history`) y botón «Tu historial» en el menú principal muestran al jugador sus últimos intercambios. No admite nombre ni filtro: nadie puede ver el historial de otro (el de todos sigue siendo solo de admins, en `/wt admin history`). Se desactiva con `history.playersCanViewOwn`. Solo hay datos dentro del límite de `history.maxEntries` y `history.autoDeleteAfterDays`.
- `/wt admin estado` (alias `status`): diagnóstico con versión, estado, pool, jugadores, historial, penalizados, eventos, Top Donaciones, idiomas, sistema de permisos, Discord, copias de seguridad y tamaño de archivos.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.21.0

- Idioma por jugador: cada jugador elige el idioma de su propio Wonder Trade con `/wt idioma [código|auto]` (alias `/wt language`) o con el botón «Idioma» del menú principal. Sin elegir, se usa el idioma de su cliente de Minecraft si existe en `lang/` (`general.useClientLanguage`) o, si no, `general.language`. Se quita el botón de idioma del panel de administración; el idioma por defecto se configura mediante `general.language`.
- Traducción completa: menús y botones (incluido el libro de especiales y las estadísticas), nombres de categorías, tipos, naturalezas (en inglés: Timid, Jolly…), días y meses, anuncios y avisos. Los anuncios globales (eventos, recordatorios, Top Donaciones, especiales conseguidos) se envían a cada jugador en su idioma.
- `/wt infinito on|off` (solo admins, oculto): quita el cooldown de `/wt` y de `/wt donar` (y los límites de donación y repeticiones) al admin que lo ejecuta.
- Mensaje de especie repetida reescrito: «Ese Pokémon ya lo has tradeado antes. Deberás intercambiar otro Pokémon distinto antes de poder ofrecerlo de nuevo.»
- El nombre del mod en el prefijo va en negrita: **[WonderTrade]**.
- Panel de administración: «Reiniciar estadísticas» abre un menú con «Borrar todas» o las cabezas de los jugadores conectados y desconectados para reiniciar solo a uno (con confirmación).
- Panel de administración: cada botón puede exigir un rango (`admin.buttonLevels.*`); sin él se ve bloqueado y avisa al pulsarlo.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.20.0

- Idiomas: nueva opción `general.language` (`es_es` español de España, principal, y `en_us`). Se configura mediante `general.language` en `config.json`. Los textos viven en `config/wondertradeplus/lang/<código>.json`; para añadir otro idioma basta con copiar `en_us.json` con otro nombre (p. ej. `de_de.json`), traducir los valores y elegirlo. Lo que falte en un idioma se muestra en español.
- Los textos ya no se guardan en `config.json`: al actualizar se quitan de ahí los que seguían sin personalizar y se conservan (con prioridad sobre el idioma) los que hayas cambiado a mano. Se traducen menús, mensajes, ayudas, avisos de Discord, nombres de eventos y categorías, y los nombres de días y meses.
- Penalizaciones: motivo opcional (`/wt admin penalizar <jugador> [duración] [motivo]`, `/wt admin motivo <texto>` y botones «Motivo» en el menú, con motivos rápidos configurables en `penalties.reasons`). El motivo se muestra al jugador y en las listas.
- Historial de penalizaciones (`/wt admin penalizaciones historial [jugador]` y botón «Historial» en el menú «Penalizados»): penalizaciones, perdones y caducidades, con límite `penalties.historySize`.
- Aviso al terminar una penalización: al instante si el jugador está conectado, y al entrar si caducó mientras estaba fuera.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.19.0

- Penalizaciones: nuevo menú «Penalizaciones» en el panel de administración (hierro). Muestra las cabezas de los jugadores conectados (clic = penalizar), un selector de tiempo (±1 d, ±1 h, ±10 min), el botón «Permanente», «Por nombre» (jugadores desconectados) y la lista de penalizados para perdonar. Un jugador penalizado no puede intercambiar ni donar. Se guarda en `penalties.json`.
- Comandos: `/wt admin penalizar <jugador> [duración|permanente]`, `/wt admin perdonar <jugador>` y `/wt admin penalizaciones`. Sin duración se usa la elegida en el menú o `penalties.defaultDuration`.
- Animaciones discretas en la barra de acción (ola dorada de unos 3 s) al recibir un Pokémon especial, al empezar un evento y al cerrar el Top Donaciones. Configurables en `animations`.
- Empates: `/wt top` ordena ahora a igualdad por nombre y los empatados comparten puesto (1, 1, 3). Top Donaciones: nuevo `topDonations.tieBreak` (`firstToReach` por defecto = gana quien llegó antes; `mostDonations`; `share` = empatados comparten puesto y premio).
- Gradle no se ha ejecutado en el entorno de edición.


## 0.18.2

- Panel de administración redistribuido: se quita el objeto «Panel de administración» del centro superior y los botones quedan en cuatro filas con espacio entre ellos (Pool, Servidor, Datos y Sistema). Historial y Reiniciar estadísticas pasan a la nueva fila «Datos».
- Gradle no se ha ejecutado en el entorno de edición.
## 0.18.1

- Panel de administración: nuevo botón «Top Donaciones» (esmeralda/redstone) en la fila de servidor que activa o desactiva el Top Donaciones (`topDonations.enabled`) y muestra si este mes hay Top. El cambio se guarda en `config.json`.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.18.0

- **Top Donaciones** mensual con recompensas (`topDonations`): solo cuentan las donaciones hechas con `/wt donar` en meses activos. Por defecto hay Top en los meses impares de 2026 (enero, marzo, mayo, julio, septiembre y noviembre) y se invierte cada año (2027: meses pares; 2028: impares…). Puntos: normal 1, shiny 2, Ultraente/Paradoja 3, legendario/mítico 4, sin extra por shiny + categoría (configurables en `topDonations.points`).
- El último día del mes, a la hora `topDonations.announceHour` (20:00 por defecto), se anuncia el Top 3 con sus puntos en el chat y en Discord (`discord.events.topDonations`), y el 1.º, 2.º y 3.º reciben las recompensas definidas en `topDonations.rewards.first/second/third` (comandos de consola con `{player}`, `{rank}`, `{points}` y `{period}`). Si el ganador está desconectado, las recibe al entrar. Los ejemplos incluidos dan caramelos raros: cámbialos por lo que quieras.
- `/wt donaciones` (alias `/wt topdonaciones`): muestra los resultados finales del Top Donaciones únicamente cuando el evento mensual ha terminado. Mientras siga activo, el comando informa de que la clasificación aún no está disponible. El estado se guarda en `top-donations.json`.
- Límite de donaciones mensual: `donation.limitMode` (`monthly` por defecto, se reinicia el día 1) con `donation.monthlyLimit` (20 por defecto). `weekly` conserva la ventana móvil de 7 días y `donation.weeklyLimit`.
- Anti-repetición al donar: no se puede donar la misma especie (`donation.sameSpecies.period`: `month`, `week` u `off`) ni el mismo ejemplar (`donation.blockSameIndividual`) dentro del periodo.
- `config-guide.txt`, README y documentación actualizados.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.17.8

- Avisos de eventos a Discord (`discord.events`): inicio del evento (con detalles y duración), fin, aviso previo de los eventos programados (solo el más anticipado de `announceBeforeMinutes`) y pausa/reanudación. Usan marcas de tiempo de Discord (`<t:…>`), así que cada persona ve la hora y la cuenta atrás en su propia zona horaria.
- Opcional: `discord.events.mentionRoleId` menciona un rol en esos avisos. Cada aviso se puede activar o desactivar por separado y los textos están en `discord.eventStartMessage`, `eventEndMessage`, `eventSoonMessage`, `eventPausedMessage` y `eventResumedMessage`. Requiere `discord.enabled: true` y un `webhookUrl` válido.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.17.7

- Pokémon generados para un evento: la especie ya no la elige el `random` de Cobblemon (repetía casi siempre las mismas). Ahora se sortea de la lista de especies de la categoría del evento, priorizando las que todavía no están en la pool; también incluye las formas regionales. Para eventos Shiny se sortea entre todas las especies.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.17.6

- `/wt stats` y `/wt stats <jugador>` tienen permisos separados: `gui.statsOwnPermissionLevel` y `gui.statsOthersPermissionLevel` (ambos 0 por defecto; pon 2 en el segundo para que solo lo vean los admins).
- Rangos: nuevo campo `improvementPercent` (% fijo que se suma a la probabilidad de especial en cada intercambio, sin consumirse). Los rangos ya guardados lo reciben automáticamente desde los valores por defecto (1, 0,75, 0,5, 0,25 y 0,1); ponlo a 0 si no lo quieres. Se muestra en `/wt rango`.
- IVs: corregido el texto `(1x31)`. Nueva línea de naturaleza en español; si hay menta, la naturaleza de la menta aparece entre paréntesis.
- Eventos programados: la hora ahora incluye «hora del servidor» y una cuenta atrás en horas y minutos («empieza en 5h 20min»), visible en `/wt eventos`, al entrar, en el panel y en los avisos. Recordatorio ocasional en el chat (`events.schedule.reminder`: cada 180 min, eventos dentro de 72 h, máximo 3). El aviso al entrar también muestra los próximos aunque no haya ninguno activo.
- Reinicio mensual de la pool (`maintenance.monthlyReset`): el día 1 renueva los Pokémon normales (los especiales se conservan). La primera vez solo anota el mes para no borrar nada al actualizar; el estado se guarda en `maintenance-state.json`.
- Especie repetida (`trade.blockRepeatSpecies`): no se puede ofrecer la misma especie dos veces seguidas (intercambio o donación). Mensaje `messages.repeatSpecies`.
- Espera para staff: nueva clave `trade.cooldownBypassPermissionLevel` (5 = nadie se salta la espera; con 2 vuelven a saltársela los admins). Sustituye a `trade.adminBypassPermissionLevel`, que ya no se usa. Si más adelante usas LuckPerms se puede afinar por grupo.
- Nombres de eventos más claros: «Apuesta de Shiny y Legendario» / «Lluvia de Mítico, Ultraente y Paradoja» (`events.names.risk` y `events.names.receive`).
- Nuevo `config-guide.txt` (se regenera en cada arranque junto a `config.json`) con la explicación de cada apartado, comandos y permisos.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.17.5

- Cabezas de donantes (menú «Eliminar pool → jugadores»): se guardan el UUID y la textura de skin de cada jugador al entrar y al donar/intercambiar (`player-data.json`: `skinValue`, `skinSignature`), de modo que la cabeza se ve aunque esté desconectado. Sin textura guardada se usa solo el nombre (en servidores no premium el UUID no sirve para consultar a Mojang, por eso no se envía).
- La entrada «Servidor» aparece la primera con una cabeza de dragón propia.
- El botón «Solo generados por jugadores» usa una cabeza con la skin del administrador que abre el menú.
- Opcional: `gui.heads.server` y `gui.heads.players` aceptan el valor base64 de «textures» de cualquier cabeza para sustituir esas dos.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.17.0

- Eventos (solo administradores): cada evento cuenta sus WT de evento y los jugadores distintos; se ve en `/wt admin eventos`, en el panel (clic en un evento) y se guarda en `event.json`.
- Historial de eventos terminados (`/wt admin eventos historial` y botón «Historial de eventos» del panel), con `events.history.keep` entradas (50 por defecto).
- Pausar y reanudar un evento activo (`/wt admin eventos pausar|reanudar <id>` o desde el panel): el tiempo se congela, el evento no tiene efecto mientras está pausado, y al reanudar se conservan el tiempo restante y las esperas de los jugadores. Sobrevive a reinicios.
- IVs: la búsqueda de la pool admite `6x31`, `5x31`… (mínimo de IVs perfectos) y `31spd`, `31hp`, `31atk`, `31def`, `31spatk`, `31spdef` (ese stat a 31), combinables con el nombre. Los Pokémon con 6x31 muestran una estrella.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.16.6

- Las esperas de los eventos por jugador ya no se pierden al reiniciar: se guardan en `event.json` (`lastUse`) y solo se descartan cuando el evento termina o su espera caduca.
- La integración con Discord pasa a su propio archivo (`WonderTradeDiscord.kt`), con lo que `WonderTradeService.kt` queda más corto.
- Comandos duplicados unificados internamente (un solo código para `sonidos`/`sounds`, `especiales`/`categorias`, `eventos`/`events`, `clearpool`/`admin clear`, `admin eventos`/`lista`/`programados`). Todos los nombres siguen funcionando como alias.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.16.5

- Asistente «Programar evento»: ahora es un único menú que cambia de contenido (duración → día → hora → repetición) sin cerrarse ni reabrirse.
- Repetición mensual por día de la semana: tras elegir «Mensual» se escoge primer, segundo, tercer, cuarto o último (p. ej. «cada último viernes del mes»). Las programaciones mensuales antiguas siguen usando el mismo día del mes. En consola, «mensual» con un día de la semana usa la ocurrencia de la primera fecha.
- Menú de sonidos: el título usa un tocadiscos y el aviso de cooldown conserva el bloque musical.
- Los Pokémon de los menús (pool, equipo, PC) muestran sus IVs con un color por estadística (HP, ATK, DEF, SPATK, SPDEF, SPD); si una chapa plateada o dorada cambia un IV, el valor efectivo aparece al lado entre paréntesis. Se puede ocultar con `menu.showIvs: false`.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.16.4

- El porcentaje predeterminado para el intervalo de Wonder Trades durante eventos pasa del 10 % al 20 % (`events.tradeIntervalPercent`). Esto permite 5 WT por jugador durante cada evento.
- Se aplica tanto a eventos iniciados desde el menú como a eventos programados. Los eventos ya creados mantienen su intervalo guardado.


### 0.16.2
- El aviso de cooldown disponible utiliza una nota musical `block.note_block.pling` con tono ligeramente agudo.

### 0.16.0
- **Consola:** la mayoría de las operaciones administrativas funcionan también desde consola: activar/desactivar, recargar, rellenar pool, añadir especies (requiere al menos un jugador conectado), borrar por origen, eliminar aportaciones, resetear estadísticas, copias, mantenimiento, Discord y gestión de eventos. Las funciones que necesitan inventario o GUI siguen siendo exclusivas del juego.
- **Avisos de disponibilidad:** todos los jugadores elegibles reciben el aviso cuando termina su cooldown y recordatorios cada `cooldownNotice.normalReminderMinutes` (10 min por defecto); si hay un evento activo, el intervalo pasa a `cooldownNotice.eventReminderMinutes` (2 min). El sonido de disponibilidad ahora es el orbe de experiencia y respeta los ajustes individuales.
- **Borrado de pool por origen:** desde el panel admin se puede eliminar solo lo generado por el servidor o solo las aportaciones de jugadores. También hay comandos de consola `/wt admin removegenerated` y `/wt admin removeplayers`. Se clasifica por `donorName` para conservar correctamente aportaciones antiguas que no tengan UUID.
- **Programación desde menú:** en Gestionar eventos → elegir tipo/categorías → duración aparece «Programar evento», con opciones para iniciar en 10 min, 1 h, 3 h, mañana a la misma hora o repetir a diario/semanalmente.
- **Caducidad del historial:** `history.autoDeleteAfterDays` borra entradas antiguas (30 días por defecto); `0` lo desactiva. La limpieza se ejecuta al iniciar, recargar y una vez por minuto.
- README y `/wt help` actualizados.
- Gradle no se ha ejecutado en el entorno de edición.


### 0.15.0
- **Aviso de fin de cooldown:** cuando termina el tiempo normal de un jugador conectado recibe un mensaje y un sonido de nota musical del bloque de notas. Se desactiva con `cooldownNotice.enabled` o `sounds.cooldownReady`, y cada jugador puede silenciarlo en `/wt sonidos`.
- **Cooldown con evento:** si hay un evento activo y estás en espera, el chat muestra el tiempo del evento en lugar del normal (`messages.eventCooldown`).
- **Reposición de especiales del evento:** el objetivo de cada categoría es lo que había en la pool al empezar el evento (como mínimo `events.ensurePool.minimum`). Si había 10 y alguien se lleva uno, se retira el normal más antiguo y se genera otro de la categoría, al instante tras el intercambio. Con `events.ensurePool.target` > 0 se fija una cantidad concreta.


### 0.14.2
- `/wt especiales` muestra también las formas regionales (Alola, Galar, Hisui, Paldea) de los Pokémon de cada categoría: Articuno, Zapdos y Moltres de Galar aparecen como legendarios.

### 0.14.1
- `/wt help` (admins) muestra cada comando de eventos en su línea: ver, crear, parar, programar y desprogramar. Los textos de ayuda antiguos de tu `config.json` se actualizan solos.

### 0.14.0
- **Varias categorías en un mismo evento:** un evento ahora puede afectar a varias categorías a la vez, por ejemplo Míticos y Legendarios. En el panel (Gestionar eventos) las categorías se marcan o desmarcan con un clic y se pulsa «Continuar»; por comando se unen con `+`: `/wt admin eventos crear recibir mitico+legendario 3h`. Lo mismo vale para `programar`.
- Se aplica a los dos tipos: «Arriesgar» suma el % extra al ofrecer un Pokémon de cualquiera de las categorías y «Recibir» da la probabilidad extra de recibir uno de cualquiera de ellas.
- La reposición de la pool asegura el mínimo (`events.ensurePool.minimum`) de cada categoría por separado, y el anuncio al empezar lista los Pokémon de todas ellas.
- Los eventos y programaciones guardados con una sola categoría se leen sin cambios.

### 0.13.1
- Los rangos por defecto son los del servidor: Leyenda, Maestro, Élite, Experto y Dominguero. Permisos `wondertradeplus.rank.<id>` y etiquetas `wt_<id>`. Si tu `config.json` tenía la lista genérica sin tocar, se actualiza sola.

### 0.13.0
- **`/wt especiales`:** menú con la lista de Pokémon por categoría (Ultraente, Paradoja, Mítico y Legendario) para saber qué se puede dar en los eventos. Botón nuevo «Ver especiales» en el menú principal.
- **Rangos de servidor** (`ranks` en `config.json`): cada rango tiene `baseBonus` (mínimo del bonus: al recibir un especial vuelve a ese valor en lugar de a 0, y nunca baja de él) y `gainMultiplier` (multiplica lo que suma cada Pokémon ofrecido). La lista va del rango más alto al más bajo y gana el primero que coincida.
  - Por defecto hay 5 rangos, del más alto al más bajo: Leyenda (`baseBonus` 1.0), Maestro, Élite, Experto y Dominguero (estos cuatro con 0.0 para que pongas tú los valores).
  - Un jugador pertenece a un rango si tiene el permiso del rango (por ejemplo `wondertradeplus.rank.leyenda` o `group.leyenda` con LuckPerms) o la etiqueta del rango (`/tag <jugador> add wt_leyenda`). Cambia `id`, `name`, `permission` y `tag` por los de tu servidor.
  - Con LuckPerms basta con dar el permiso al grupo: `/lp group leyenda permission set wondertradeplus.rank.leyenda true`.
  - El libro del menú muestra los incrementos del bonus.

### 0.12.1
- Los anuncios de «LEGENDARIO/ULTRAENTE/PASADO/FUTURO SHINY» terminan con la exclamación del mismo color que el texto. Si tu `config.json` aún tenía el texto antiguo, se actualiza solo.
- **Pool de eventos:** mientras un evento está activo, cada pocos segundos (`events.ensurePool.checkIntervalSeconds`, 10 por defecto) se comprueba que haya al menos `minimum` Pokémon de su categoría y se generan los que falten. Antes solo se comprobaba al iniciar el evento. Se puede desactivar con `events.ensurePool.keepDuringEvent: false`.

### 0.12.0
- **Eventos programados** (`event-schedule.json`): empiezan solos a una hora, día o fecha concretos y pueden repetirse una vez, cada día, semana, mes o año. Avisos previos configurables (`events.schedule.announceBeforeMinutes`, por defecto 10 y 1 minuto antes), zona horaria (`events.schedule.timezone`, vacía = la del servidor) y tolerancia si el servidor estaba apagado (`events.schedule.maxLateMinutes`). Se pueden ver y cancelar desde el panel (Gestionar eventos → Eventos programados). `/wt eventos` muestra también los próximos (`events.showUpcoming`).
- **Aviso al entrar:** quien se conecta con un evento activo recibe un mensaje corto (`events.joinNotice.*`: activar, retraso y si muestra detalles).
- **Barra de tiempo:** una bossbar con el nombre del evento y el tiempo que queda (`events.bossbar.*`: activar, formato, estilo y color distinto para Arriesgar y Recibir). Es solo de servidor.
- **Pokémon del evento en la pool:** al empezar un evento, si hay menos Pokémon de su categoría que `events.ensurePool.minimum` (3 por defecto), se generan los que falten (máximo `maxGenerate`) y se retiran los normales más antiguos para hacer sitio (`replaceOldest`). Los especiales nunca se retiran. Se puede limitar a un tipo de evento con `events.ensurePool.types`.
- **Anuncio al empezar:** el chat indica qué Pokémon de la categoría hay en la pool (`events.announcePokemon.*`: activar, cuántos listar y si muestra el nivel).

### 0.11.2
- Menú de eventos: botón «Tiempo personalizado» para elegir una duración exacta sumando o restando minutos, horas y días.
- Las categorías aparecen en el orden Shiny, Ultraente, Paradoja, Mítico y Legendario.

### 0.11.1
- Panel `/wt admin` ampliado a 6 filas: pool, servidor (eventos, on/off, historial, estadísticas) y sistema (recarga, mantenimiento, copia, Discord) en una sola página.
- Corregido el sonido del tótem: solo suena con un legendario, mítico, Ultraente o Paradoja shiny; un shiny normal usa el sonido de especial.

### 0.11.0
- **Purga automática:** un Pokémon normal que lleve más de 1 semana en la pool sin ser recibido se elimina y se sustituye por uno nuevo (también normal). Los especiales (shiny, legendario, mítico, Ultraente y Paradoja) no se purgan. Configuración en `maintenance.purge`: `enabled`, `afterHours` (168 = 1 semana) e `includePlayerDonations`. Se ejecuta con el mantenimiento automático y hace falta al menos un jugador conectado para generar los nuevos.
- **Eventos por categoría:** ahora hay «Arriesgar» y «Recibir» para Shiny, Legendario, Mítico, Ultraente y Paradoja. Comando: `/wt admin eventos crear <tipo> <categoría> <duración> [valor] [espera]`. Los valores por defecto están en `events.risk` y `events.receive`.
- **`/wt admin fill`:** corregido. Con probabilidad 100 podían salir algunos Pokémon normales porque la búsqueda de especiales se rendía demasiado pronto. Ahora insiste mucho más y, si se pidió un especial, nunca lo sustituye por un normal.

### 0.10.0
- Eventos temporales. Se ven con `/wt eventos` y los gestionan los admins con `/wt admin eventos` o el botón «Gestionar eventos» del panel.
  - **Arriesgar Shiny:** cada shiny ofrecido suma un % extra (por defecto `+0.2%`, `events.risk.extraGain`) a tu probabilidad de especial. Se reinicia a 0 cuando te toca un especial.
  - **Recibir Shiny:** probabilidad extra de que te entreguen un shiny de la pool (por defecto `10%`, `events.receive.chance`).
  - Mientras un evento está activo puedes hacer un intercambio extra cada cierto tiempo (por defecto 1 por minuto) sin gastar tu tiempo normal ni tu límite diario.
- Los eventos se guardan en `event.json` y siguen activos tras un reinicio. El reloj del menú principal muestra cuándo tienes disponible el intercambio de evento.

### 0.9.0
- Menú de confirmación: muestra la probabilidad real de recibir un especial (especiales disponibles en la pool, tu bonus y el resultado con este intercambio). Se puede quitar con `gui.showTradePreview: false`.
- Nuevo `/wt on` y `/wt off` (solo admins): activa o desactiva Wonder Trade sin quitar el mod. El estado se guarda en `general.enabled` de `config.json`.

### 0.8.9
- `/wt top`: se quita "Volver al menú principal"; en su lugar hay un botón Cerrar.
- Pool (admin): «Eliminar Pokémon» aparece en rojo y negrita.
- Rellenar pool: al elegir una cantidad se queda en el mismo menú (ya no vuelve al panel admin).
- `/wt`: el reloj del siguiente Wonder Trade pasa a la esquina superior izquierda.

### 0.8.8
- Corregido el espacio sobrante en el mensaje `broadcastShiny`.
- La pool abierta desde el menú principal o con `/wt pool` se muestra siempre en vista normal, también para admins: el botón **Volver** lleva al menú principal. El panel de administración solo se abre con `/wt admin`.
- El botón de orden por hora marca con una flecha ▶ la opción activa, igual que el filtro de categoría.
- Nuevo filtro de búsqueda por nombre o especie en la pool (`/wt pool search <texto>` y botón en el menú). Se conserva al cambiar de página, filtro u orden.

### 0.8.7
- Corregido `pool.maximumEntries` a 500 por defecto.
- Opciones del relleno rápido: 25, 50, 100, 250 y 500.
- Las configuraciones antiguas que tenían el valor predeterminado erróneo de 5000 se actualizan automáticamente.
- Al iniciar o recargar se recorta la pool si supera el máximo configurado, respetando `pool.trimOldestFirst`.


### 0.8.6
- Cantidades del menú de relleno rápido configurables en `admin.fillMenu.amounts`.
- Valores predeterminados ajustados al máximo de 500 entradas: 25, 50, 100, 250 y 500.
- Los rellenos respetan el espacio disponible y nunca sobrepasan el máximo de la pool.

### 0.8.5
- `/wt top` abre un selector gráfico para consultar los tres rankings: intercambios, especiales recibidos y especiales aportados.
- El panel `/wt admin` incorpora un submenú de relleno de pool con cantidades rápidas (16, 32, 64, 128 y 256), además de herramientas para eliminar aportaciones, reiniciar estadísticas y probar Discord.
- `/wt help` reorganizado por utilidad y `/wt top` aparece sin argumentos.


### Preferencias de sonido (0.8.3)
- `/wt sonidos` o `/wt sounds` abre un menú individual para activar/desactivar cada sonido.
- Las preferencias se guardan por UUID en `player-data.json`; no requiere instalar el mod en el cliente.
- Se pueden silenciar todos los sonidos con un único interruptor.
