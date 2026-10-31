# Estado de compilación

- Versión actual: **0.13.1**.
- Objetivo: Minecraft 1.21.1, Java 21, Fabric y Cobblemon 1.8.4.
- Compila con Gradle 8.14 mediante GitHub Actions en cada push.
- Probado en juego: menú del equipo y del PC, confirmación, vista del pool, intercambios y comandos.
- Los comandos `/wt admin ...`, el historial, el cooldown de 12 h y el autocompletado (v0.3.0) están pendientes de probar en juego.
- Los anuncios de legendarios, Ultraentes y Paradoja pasado/futuro (v0.2.1) están pendientes de probar en juego.
- No es una versión NeoForge/Arclight. El JAR es solo Fabric.

## Cambios de esta revisión

- Los intercambios solo seleccionan entradas existentes en `pool.json`; no se generan Pokémon de respaldo durante un intercambio.
- El mantenimiento ya no rellena el pool automáticamente. `/wt admin fill` sigue siendo una acción manual para añadir entradas.
- El filtro del menú muestra cada categoría una sola vez y ya no incluye la explicación entre paréntesis.
- `/wt admin addspecies <especie> [shiny]` crea la especie o forma solicitada con nivel aleatorio según `fallback.minimumLevel` y `fallback.maximumLevel`.
- La compilación Gradle de esta revisión queda pendiente de validación en GitHub Actions; este entorno no dispone de Gradle ni acceso de red para descargarlo.

- `/wt admin resetstats <jugador|all>` reinicia los contadores de `/wt stats` y las clasificaciones de `/wt top` sin modificar bonus, cooldowns o límites diarios.
- `/wt admin addspecies <especie> [alolan|galarian|hisuian|paldean] [shiny]` usa el parser de propiedades de Cobblemon, igual que `/pokegive`; valida que la forma solicitada se aplique y solo sugiere especies base.
- La categoría Shiny ya no lleva emoji.
- La vista de pool para administradores incluye un botón junto a «Volver» para activar/desactivar el modo de eliminación; el clic sobre Pokémon solo borra cuando ese modo está activo.
- No se ha ejecutado Gradle ni ninguna compilación en esta revisión, tal como se solicitó.

- Los intercambios de Pokémon normales suman `bonus.normalTradeGain` (0,1% por defecto); los Pokémon especiales siguen sumando `bonus.specialTradeGain` (0,5% por defecto). Ambos valores y sus mensajes se editan en `config.json`.
- La probabilidad normal se suma antes de la tirada de ese mismo intercambio; la ganancia por aportar un especial se aplica al siguiente intercambio normal.
- Añadidas opciones configurables `admin.regionalForms.*` para activar/desactivar cada forma regional.
- README.md y docs/CONFIGURATION.md actualizados para describir los comandos, propiedades regionales y el sistema de bonus.
- No se ha ejecutado Gradle en esta revisión.


## Cambios de la versión 0.7.4 (sin compilar)

- En las revisiones 0.7.4–0.7.6 se probaron distintos formatos internos para formas regionales; en 0.7.7 el comando queda alineado con `/pokegive` y acepta directamente `alolan`, `galarian`, `hisuian` y `paldean`.
- La creación regional utiliza `PokemonProperties` con `form=<id>` y reaplica la propiedad tras la inicialización para conservar la forma correcta.
- El bonus de WT normal se configura con `bonus.normalTradeGain` (0,1 puntos porcentuales por defecto); el bonus de Pokémon especiales permanece en `bonus.specialTradeGain` (0,5 por defecto). Los dos valores, el máximo, los mensajes y los multiplicadores de evento son configurables.
- `admin.regionalForms.*` permite activar o desactivar cada tipo de forma regional y `fallback.minimumLevel` / `fallback.maximumLevel` controla el nivel aleatorio.
- README.md y docs/CONFIGURATION.md actualizados. No se ha ejecutado Gradle, tal como se solicitó.

- Añadido `bonus.specialShinyExtraGain` (+0,2 puntos porcentuales por defecto): se suma al +0,5% base cuando el Pokémon ofrecido es shiny y además legendario, mítico, Ultraente o Paradoja. El mensaje `bonusInfo` recibe `{specialShinyExtraGain}`. README y docs/CONFIGURATION.md actualizados. No se ha ejecutado Gradle.

## Cambios de la versión 0.7.5 (sin compilar)

- Añadido `bonus.specialShinyExtraGain` (+0,2 puntos porcentuales por defecto): se suma al incremento especial base cuando el Pokémon ofrecido es shiny y además legendario, mítico, Ultraente o Paradoja. Un shiny sin una de esas categorías no recibe el extra.
- `bonusInfo` recibe el marcador `{specialShinyExtraGain}` para mostrar el extra configurado. Los archivos `config.json` existentes reciben automáticamente la nueva clave al recargar; si conservan un `bonusInfo` personalizado antiguo, se muestra el extra en una segunda línea configurable (`messages.bonusShinyExtraInfo`).
- El multiplicador de eventos también afecta a este extra. README y docs/CONFIGURATION.md actualizados. No se ha ejecutado Gradle, según lo solicitado.

## Cambios de la versión 0.7.7 (sin compilar)

- El libro de `/wt` muestra los incrementos de bonus configurados para Pokémon normales, especiales y especiales shiny; se controla con `gui.showBonusRatesInBook`.
- Los comandos de administración incluyen comentarios `PERMISO` junto a cada ruta en `WonderTradePlus.kt`, indicando si son públicos o heredan `admin.permissionLevel`; la documentación README incluye una columna de permisos.
- `/wt admin addspecies` usa únicamente los atributos de Cobblemon `/pokegive` (`alolan`, `galarian`, `hisuian`, `paldean`, `shiny`). La comprobación de formas acepta tanto el nombre de FormData como el ID de Showdown para evitar rechazos por diferencias de nomenclatura.
- El resultado del intercambio y el cambio de bonus se muestran en un único mensaje mediante `messages.success` y `{bonusText}`.
- Eliminados los símbolos de estrellas/estrellitas de mensajes de chat y Discord.
- Añadido `/wt donar`: menú compartido con `/wt`, pero dona el Pokémon seleccionado sin entregar otro ni consumir cooldown o límite diario. La opción `pool.playerDonationsEnabled` permite desactivarlo. Se comprueba la capacidad de la pool al abrir el menú y al confirmar la donación.
- Actualizados README.md, docs/CONFIGURATION.md y la configuración por defecto. No se ha ejecutado Gradle, según lo solicitado.


## Cambios de la versión 0.7.8 (sin compilar)

- El libro de `/wt` muestra los incrementos configurados para Pokémon normales, shiny sin categoría especial, legendarios/míticos/Ultraentes/Paradojas y especiales shiny. Se controla con `gui.showBonusRatesInBook` y los textos `messages.menuBook*`.
- `/wt admin addspecies <especie> [alolan|galarian|hisuian|paldean] [shiny]` conserva la sintaxis de `/pokegive`. Primero aplica el atributo regional nativo (aspecto) y, si la versión de Cobblemon utiliza `FormData`, resuelve y asigna la forma registrada después de crear el Pokémon. Si la forma no existe o no se aplica, falla explícitamente y no añade el Pokémon base por accidente.
- Revisados los comentarios `PERMISO` junto a las rutas públicas y administrativas en `WonderTradePlus.kt`; todos los subcomandos de `/wt admin` heredan `admin.permissionLevel`.
- Confirmado que el éxito del intercambio y el cambio de probabilidad se envían mediante un único mensaje configurable (`messages.success` + `{bonusText}`).
- Comprobados los textos de chat y Discord: no quedan emojis de estrellas/estrellitas en el código ni en la configuración por defecto.
- Se mantiene `/wt donar`: usa el menú de selección, no entrega un Pokémon ni consume cooldown/límite diario y rechaza nuevas donaciones cuando la pool alcanza `pool.maximumEntries`.
- README.md y docs/CONFIGURATION.md actualizados. No se ha ejecutado Gradle ni ninguna compilación.


## Cambios de la versión 0.7.9 (sin compilar)

- Añadidos sonidos configurables para apertura de menús (cofre), acceso al PC (Ender Chest), botones, paginación, Pokémon especiales y especial shiny (sonido del tótem). Volumen y tono editables en `sounds.*`.
- La pool ahora permite ordenar por hora añadida ascendente (más antiguos primero) y descendente (más recientes primero), además del filtro de categoría existente.
- `/wt donar` queda limitado a 5 donaciones por jugador en una ventana móvil de 7 días (`donation.weeklyLimit`, `0` ilimitado) y admite especies prohibidas en `donation.speciesBlacklist`. Las donaciones propias continúan excluidas de las selecciones de Wonder Trade por `antiAbuse.excludeOwnDonations`.
- `/wt admin` abre un panel gráfico con accesos a la pool, historial, recarga, mantenimiento, backup y vaciado con confirmación.
- Eliminado el subcomando redundante `/wt gui`; `/wt` sigue abriendo el menú. `/wt help` muestra los comandos administrativos solo a jugadores con `admin.permissionLevel`.
- El menú principal muestra un contador de cooldown que se actualiza cada segundo.
- Configuración por defecto y documentación actualizadas. No se ha ejecutado Gradle.


## Cambios de la versión 0.8.0 (sin compilar)

- Rediseñada la barra inferior de `/wt pool`: controles alineados y separados para volver, resumen, paginación, filtro, orden, modo de eliminación y cierre.
- Se mantiene la cuadrícula de 45 Pokémon por página; los filtros y la ordenación muestran claramente su selección actual y los recuentos.
- Todos los menús usan ahora el mismo contenedor de 6 filas y un título común. Al cambiar de pantalla desde un botón se reutiliza el menú existente, evitando enviar un nuevo paquete de apertura que hacía que Minecraft recentrara el cursor.
- La navegación entre equipo, PC, pool, historial, administración y confirmaciones se actualiza en el mismo contenedor.
- No se ha ejecutado Gradle; debe validarse con GitHub Actions.


## 0.8.1 — cambios preparados

- Restaurado el diseño del menú `/wt pool` de 0.7.9, conservando los cambios funcionales previos.
- La prueba inicial de conservación del cursor requería código cliente; esa implementación se ha retirado en 0.8.2 para mantener el mod exclusivamente en el servidor.
- `/wt help` ahora presenta cada comando en una línea y filtra las líneas de pool y administración por permisos.
- Añadidos anuncios globales al recibir shiny, legendario, mítico, Ultraente o Paradoja; los anuncios tienen interruptores independientes en `broadcast.received*`.
- Retirado el modo evento: comandos, persistencia, multiplicadores, mensajes y opciones de configuración.
- No se ha ejecutado Gradle en este entorno; requiere validación en GitHub Actions.


## 0.8.2 — distribución solo servidor

- Eliminado el mixin de cliente que intentaba conservar la posición del cursor.
- Eliminado el archivo de configuración de mixins y su registro en `fabric.mod.json`.
- Marcado el mod con `environment: "server"` para que Fabric no intente cargarlo en los clientes.
- README actualizado: WonderTradePlus se instala únicamente en el servidor.
- No se modifica el diseño ni la lógica de los menús, comandos, intercambios o anuncios.


## 0.8.3
- Añadido menú individual `/wt sonidos` y alias `/wt sounds`, con preferencias persistentes por jugador.
- Los interruptores globales de `sounds` siguen pudiendo desactivar sonidos para todo el servidor.


## 0.8.5
- Eliminado por completo el filtro que quitaba símbolos de estrellas y destellos de los mensajes. Los mensajes y prefijos personalizados vuelven a mostrarse exactamente como estén configurados.
- Se mantiene el menú individual de sonidos de la versión 0.8.3.
- No se ha ejecutado Gradle; requiere validación en GitHub Actions.


## 0.8.6
- Configuración de cantidades de relleno rápido mediante `admin.fillMenu.amounts`.
- Interruptor `admin.fillMenu.enabled`.
- Cantidades por defecto alineadas con `pool.maximumEntries=500`: 25, 50, 100, 250 y 500.
- `pool.maximumEntries` pasa a 500 por defecto; al iniciar o recargar se recorta la pool si supera ese máximo. `adminFill` limita la generación al espacio libre restante.
- Validación estática únicamente; Gradle no ejecutado.


## 0.8.7
- Corregido el máximo predeterminado de la pool a 500 Pokémon.
- Cantidades del relleno rápido: 25, 50, 100, 250 y 500.
- Migración automática de configuraciones 0.8.6 que conservaban el valor predeterminado erróneo de 5000 y las cantidades antiguas.
- Al iniciar o recargar, la pool se ajusta al máximo configurado, eliminando primero las entradas más antiguas si `pool.trimOldestFirst` está activado.
- Se mantiene la protección de `adminFill` para generar solo los Pokémon que caben.
- No se ha ejecutado Gradle; requiere validación en GitHub Actions.


## 0.8.8
- Corregido el espacio sobrante en `broadcastShiny`.
- La pool abierta desde `/wt` o `/wt pool` es siempre la vista normal; el panel de admin solo se abre con `/wt admin`.
- Flecha ▶ en el botón de orden por hora y nuevo filtro de búsqueda por nombre o especie (`/wt pool search`).
- No se ha ejecutado Gradle; requiere validación en GitHub Actions.

## 0.8.9
- /wt top sin botón Volver (ahora Cerrar), «Eliminar Pokémon» en rojo y negrita, rellenar pool sin volver al panel admin, reloj de /wt arriba a la izquierda.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.9.0
- Vista previa de probabilidades en el menú de confirmación (gui.showTradePreview).
- /wt on | off (general.enabled) para desactivar Wonder Trade sin quitar el mod.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.10.0
- Eventos: «Arriesgar Shiny» y «Recibir Shiny» (WonderTradeEvents.kt, event.json, sección events en config).
- /wt eventos, /wt admin eventos [crear|parar|lista] y botón «Gestionar eventos» en el panel admin.
- Los intercambios de evento usan su propia espera y no tocan el tiempo normal ni el límite diario.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.11.0
- Purga automática de normales con más de maintenance.purge.afterHours en la pool (se sustituyen por otros nuevos).
- Eventos por categoría (shiny, legendario, mítico, Ultraente, Paradoja) para Arriesgar y Recibir.
- Corregido /wt admin fill con probabilidad alta (más intentos para especiales y sin sustituir por normales).
- Gradle no se ha ejecutado en el entorno de edición.

## 0.11.1
- Panel de administración ampliado a 6 filas con todas las funciones en una sola página (incluye interruptor on/off y eventos).
- Sonido del tótem solo para especial de categoría + shiny; un shiny normal usa el sonido de especial.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.11.2
- Eventos: nuevo botón «Tiempo personalizado» con selector de duración (±1 min, 10 min, 1 h, 1 día).
- Categorías de evento en el menú en este orden: Shiny, Ultraente, Paradoja, Mítico, Legendario.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.12.0
- Eventos programados (WonderTradeSchedule.kt), aviso al entrar y bossbar (WonderTradeEventDisplay.kt).
- Generación de Pokémon de la categoría al iniciar un evento y anuncio en el chat de los que hay.
- Gradle no se ha ejecutado en el entorno de edición. Puntos a validar al compilar: ServerBossEvent/BossEvent y ServerPlayConnectionEvents.JOIN.

## 0.12.1
- Corregido el color de la exclamación final en los anuncios de shiny + categoría (con migración de config.json).
- La pool se repone durante todo el evento (events.ensurePool.keepDuringEvent / checkIntervalSeconds).
- Gradle no se ha ejecutado en el entorno de edición.

## 0.13.0
- /wt especiales (menú de Pokémon por categoría) y /wt rango.
- Rangos de servidor con bonus mínimo y multiplicador de ganancias (WonderTradeRanks.kt, detección por permiso vía reflexión o por tag).
- Gradle no se ha ejecutado en el entorno de edición. A validar: Species/PokemonProperties.create en speciesByCategory y la reflexión de permisos.

## 0.13.1
- Rangos por defecto del servidor: Leyenda, Maestro, Élite, Experto y Dominguero (con migración desde la lista genérica de 0.13.0).
- Gradle no se ha ejecutado en el entorno de edición.
