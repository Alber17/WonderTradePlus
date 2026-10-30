# WonderTrade Plus

Wonder Trade abierto y configurable para **Cobblemon**. Se distribuye como **mod Fabric exclusivamente de servidor** (no es un plugin Bukkit/Paper). La interfaz es un menú de cofre controlado por el servidor; los jugadores no necesitan instalar WonderTradePlus en sus clientes.

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
| `/wt chance` | Todos | Muestra tu probabilidad acumulada de recibir un Pokémon especial. |
| `/wt chance <jugador>` | `admin.permissionLevel` | Muestra la probabilidad acumulada de otro jugador. |
| `/wt chance <jugador> set <valor>` o `reset` | `admin.permissionLevel` | Fija su bonus (0–100) o lo pone a 0. |
| `/wt stats [jugador]` | Todos | Muestra intercambios, donaciones directas, especiales recibidos/aportados y bonus. |
| `/wt top` | Todos | Abre un menú para elegir entre los tres rankings. |
| `/wt cancel` | Todos | Cancela el intercambio pendiente. |
| `/wt pool` | `gui.poolViewPermissionLevel` (por defecto 0) | Abre la pool en un menú paginado de solo lectura, con filtro de categoría, orden por hora añadida ascendente/descendente (la opción activa lleva una flecha ▶) y búsqueda por nombre o especie. También lo abren los admins en vista normal; para gestionar la pool usan `/wt admin`. |
| `/wt pool search <texto>` | `gui.poolViewPermissionLevel` (por defecto 0) | Abre la pool mostrando solo los Pokémon cuyo nombre (apodo) o especie coincidan con el texto, por ejemplo `/wt pool search tusk` o `great tusk`. Desde el menú, el botón **Buscar por nombre o especie** cierra el menú y deja en el chat un enlace clicable que rellena este comando. |
| `/wt help` | Todos | Muestra los comandos públicos; añade los comandos de administración solo si el jugador tiene `admin.permissionLevel`. |
| `/wt reload` | `admin.permissionLevel` | Recarga configuración y archivos. |
| `/wt on` / `/wt off` | `admin.permissionLevel` | Activa o desactiva Wonder Trade sin quitar el mod. |
| `/wt clearpool` | `admin.permissionLevel` | Vacía la pool. |
| `/wt admin` | `admin.permissionLevel` | Abre el panel gráfico de administración. |

### Comandos de administrador

Todos requieren `admin.permissionLevel` (nivel OP 2 por defecto, configurable en `config.json`). En `WonderTradePlus.kt` cada ruta administrativa incluye un comentario `// PERMISO` junto al registro del comando, indicando el permiso que hereda o exige.

| Comando | Permiso | Función |
|---|---|---|
| `/wt admin add <slot>` | `admin.permissionLevel` | Añade a la pool una copia del Pokémon del equipo en ese slot. Tú conservas el original. |
| `/wt admin addspecies <especie> [forma] [shiny]` | `admin.permissionLevel` | Crea un Pokémon con nivel aleatorio y lo añade a la pool. Usa la misma sintaxis visible que `/pokegive`: `alolan`, `galarian`, `hisuian`, `paldean` y `shiny`. El mod resuelve la `FormData` registrada para esa especie para evitar depender de alias internos. Ejemplos: `/wt admin addspecies vulpix alolan`, `/wt admin addspecies vulpix alolan shiny`, `/wt admin addspecies meowth shiny galarian`. No se usa `form=...`. |
| `/wt admin fill <cantidad> [probabilidad%] [nivelMin nivelMax]` | `admin.permissionLevel` | Genera Pokémon para la pool sin superar su máximo configurado (500 por defecto). Sin probabilidad indicada, se calcula automáticamente; con `probabilidad%`, se usa ese valor. Ejemplos: `/wt admin fill 200 5` o `/wt admin fill 50 0 10 30`. |
| `/wt admin remove` | `admin.permissionLevel` | Abre la pool en modo administrador con eliminación activada. |
| `/wt admin resetstats <jugador o all>` | `admin.permissionLevel` | Reinicia contadores y rankings de un jugador o de todos. Conserva bonus, cooldowns y límites diarios. |
| `/wt admin removeplayer <jugador>` | `admin.permissionLevel` | Elimina de la pool todos los Pokémon aportados por ese jugador. |
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

Con un webhook de Discord, el mod puede publicar en un canal cuando alguien **aporta o recibe un Pokémon especial** (shiny, legendario, mítico, Ultraente o Paradoja). Se configura en la sección `discord`:

1. En Discord: *Ajustes del canal → Integraciones → Webhooks → Nuevo webhook → Copiar URL*.
2. Pega la URL en `discord.webhookUrl` y pon `discord.enabled` en `true`.
3. Ejecuta `/wt reload` y prueba con `/wt admin discord test`.

La URL del webhook es secreta; no la subas a GitHub.

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
- `donation.weeklyLimit`: máximo de donaciones directas por jugador en una ventana móvil de 7 días; `0` = ilimitado (5 por defecto). `donation.speciesBlacklist`: especies que no se pueden donar, acepta nombres como `ditto` o `cobblemon:ditto`.
- `fallback.minimumLevel` / `fallback.maximumLevel`: rango aleatorio para `/wt admin addspecies` y `/wt admin fill`.
- `bonus.enabled`, `bonus.maximum`, `bonus.normalTradeGain`, `bonus.specialTradeGain` y `bonus.specialShinyExtraGain`: activan el sistema, fijan el límite y configuran los incrementos: normal (+0,1 puntos porcentuales), especial (+0,5) y el extra por especial shiny (+0,2 adicionales cuando es legendario, mítico, Ultraente o Paradoja) por defecto.
- `admin.regionalForms`: permite activar o desactivar individualmente las opciones `alolan`, `galarian`, `hisuian` y `paldean` de `/wt admin addspecies`. El jugador escribe la opción regional igual que en `/pokegive`; el mod localiza y asigna la forma registrada en Cobblemon sin exigir `form=...`. `fallback.minimumLevel` y `fallback.maximumLevel` configuran el nivel aleatorio.
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


### Preferencias de sonido (0.8.3)
- `/wt sonidos` o `/wt sounds` abre un menú individual para activar/desactivar cada sonido.
- Las preferencias se guardan por UUID en `player-data.json`; no requiere instalar el mod en el cliente.
- Se pueden silenciar todos los sonidos con un único interruptor.


### 0.8.6
- Cantidades del menú de relleno rápido configurables en `admin.fillMenu.amounts`.
- Valores predeterminados ajustados al máximo de 500 entradas: 25, 50, 100, 250 y 500.
- Los rellenos respetan el espacio disponible y nunca sobrepasan el máximo de la pool.

### 0.8.5
- `/wt top` abre un selector gráfico para consultar los tres rankings: intercambios, especiales recibidos y especiales aportados.
- El panel `/wt admin` incorpora un submenú de relleno de pool con cantidades rápidas (16, 32, 64, 128 y 256), además de herramientas para eliminar aportaciones, reiniciar estadísticas y probar Discord.
- `/wt help` reorganizado por utilidad y `/wt top` aparece sin argumentos.


### 0.8.7
- Corregido `pool.maximumEntries` a 500 por defecto.
- Opciones del relleno rápido: 25, 50, 100, 250 y 500.
- Las configuraciones antiguas que tenían el valor predeterminado erróneo de 5000 se actualizan automáticamente.
- Al iniciar o recargar se recorta la pool si supera el máximo configurado, respetando `pool.trimOldestFirst`.


### 0.8.8
- Corregido el espacio sobrante en el mensaje `broadcastShiny`.
- La pool abierta desde el menú principal o con `/wt pool` se muestra siempre en vista normal, también para admins: el botón **Volver** lleva al menú principal. El panel de administración solo se abre con `/wt admin`.
- El botón de orden por hora marca con una flecha ▶ la opción activa, igual que el filtro de categoría.
- Nuevo filtro de búsqueda por nombre o especie en la pool (`/wt pool search <texto>` y botón en el menú). Se conserva al cambiar de página, filtro u orden.

### 0.8.9
- `/wt top`: se quita "Volver al menú principal"; en su lugar hay un botón Cerrar.
- Pool (admin): «Eliminar Pokémon» aparece en rojo y negrita.
- Rellenar pool: al elegir una cantidad se queda en el mismo menú (ya no vuelve al panel admin).
- `/wt`: el reloj del siguiente Wonder Trade pasa a la esquina superior izquierda.

### 0.9.0
- Menú de confirmación: muestra la probabilidad real de recibir un especial (especiales disponibles en la pool, tu bonus y el resultado con este intercambio). Se puede quitar con `gui.showTradePreview: false`.
- Nuevo `/wt on` y `/wt off` (solo admins): activa o desactiva Wonder Trade sin quitar el mod. El estado se guarda en `general.enabled` de `config.json`.
