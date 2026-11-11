# Estado de compilación

- Versión actual: **0.31.1**.
- Objetivo: Minecraft 1.21.1, Java 21, Fabric y Cobblemon 1.8.4.
- Compila con Gradle 8.14 mediante GitHub Actions en cada push.
- Probado en juego: menú del equipo y del PC, confirmación, vista del pool, intercambios y comandos.
- Los comandos `/wt admin ...`, el historial, el cooldown de 12 h y el autocompletado (v0.3.0) están pendientes de probar en juego.
- Los anuncios de legendarios, Ultraentes y Paradoja pasado/futuro (v0.2.1) están pendientes de probar en juego.
- No es una versión NeoForge/Arclight. El JAR es solo Fabric.

## Cambios de la versión 0.31.1

- Los Pokémon especiales de especie (legendarios, míticos, Ultraentes y Paradoja) que genera el servidor ya nunca son shiny, y un shiny generado nunca es de una de esas especies. Aplica a «Añadir Pokémon aleatorios» (cualquier mezcla de categorías, por ejemplo Ultraente + Shiny), «Rellenar pool», los eventos con pool asegurada y las pools de Navidad y Halloween. Antes podía salir un especial shiny por la tirada natural de Cobblemon o por combinarlo con la categoría shiny.
- No cambia: lo que aportan los jugadores, ni el comando manual `/wt admin addspecies ... shiny`, donde el admin elige explícitamente. Los especiales shiny que ya estuvieran en la pool no se tocan.

## Cambios de la versión 0.31.0

- **Top 3 de la temporada:** el aviso de cierre de Navidad y Halloween (chat y Discord) incluye ahora a las 3 personas que más intercambios hicieron durante toda la temporada. Se guarda en `seasonal-state.json`.
- **Cierre que no se pierde:** si el servidor estaba apagado a las 23:59 del último día, el aviso de cierre sale al arrancar (hasta 3 días después), siempre que el de apertura se hubiera enviado.
- **Horas de temporada por sorpresa:** el inicio y el fin de cada hora navideña (y de Halloween) ya no se avisan en el chat ni en Discord; los jugadores la encuentran al entrar o al abrir Wonder Trade (aviso de entrada y barra de evento). Se puede volver a activar con `announceSessions: true` en cada evento de `seasonal.events`. Si un admin inicia o termina la sesión a mano, sí se avisa.
- **Discord con reintentos:** si Discord limita las peticiones o falla, el aviso se reintenta hasta 3 veces. Si sigue fallando se avisa a los admins (consola y chat, no a Discord).
- **Avisos para admins (`alerts`)**, solo a la consola y al chat de admins conectados: pool con pocos Pokémon (`alerts.poolLow.threshold`), posible abuso (demasiados intercambios en 24 h, o alguien que recibe muchos Pokémon aportados por la misma persona, o dos jugadores que se los intercambian entre sí) y fallo al restaurar la pool de un evento. Solo avisan, no sancionan. Cada aviso no se repite antes de `alerts.cooldownMinutes`.
- **Menú de eliminar pool:** nuevo botón «Eliminar por Pokémon»: lista las especies de la pool con su cantidad; al elegir una y confirmar se eliminan todos los Pokémon iguales.

## Cambios de la versión 0.30.2

- Los mensajes de chat de Navidad (aviso de apertura y cierre de la temporada, inicio y fin de cada hora navideña, aviso al entrar, pausa y reanudación) pasan del azul a colores navideños: rojo, verde y blanco. Las tarjetas de Discord y la barra de evento no cambian.
- Los textos antiguos sin personalizar se actualizan solos al arrancar (en los 6 idiomas). Si habías editado alguno a mano, se respeta.
- Base: la 0.30.0 (sin el comando de prueba de la 0.30.1).

## Cambios de la versión 0.30.0

- Halloween ahora funciona como Navidad: aviso de apertura el 31 de octubre a las 00:00 (explica las sesiones, la duración, la temática y que la pool vuelve como estaba) y aviso de cierre el 1 de noviembre a las 23:59, en el chat y en Discord. El de apertura menciona el rol de Halloween.
- Textos de chat con «Noche de Brujas»; títulos de Discord «¡Evento de Halloween!» (apertura) y «Termina el Evento de Halloween» (cierre).
- Se desactivan con `windowAnnouncements: false` en el evento `halloween`. Claves: `messages.halloweenWindowOpen/Close`, `discord.halloweenWindowOpenMessage/CloseMessage`, `discord.titles.halloweenWindowOpen/Close`.

## Cambios de la versión 0.29.9

- Se quita del panel de administración el botón «Gestionar Top Donaciones» (cerrar el Top y ver pendientes). El interruptor de activar/desactivar el Top sigue en su sitio.

## Cambios de la versión 0.29.8

- Se quita del panel de administración el botón de MegaHunt (el comando `/wt megahunt` sigue igual).
- Navidad: dos avisos nuevos, en el chat y en Discord. El 25 de diciembre a las 00:00 se anuncia que hasta el 6 de enero pueden aparecer horas navideñas (explica cuántas sesiones hay, cuánto duran, la temática y que la pool vuelve como estaba). El 6 de enero a las 23:59 se anuncia que la temporada ha terminado. Cada aviso se envía una sola vez, aunque se reinicie el servidor. El de apertura menciona el rol de Navidad de Discord.
- Se desactivan con `seasonal.events[navidad].windowAnnouncements: false`. Los avisos de inicio y fin de cada hora navideña no cambian.
- Textos editables: `messages.seasonWindowOpen/Close`, `discord.seasonWindowOpenMessage/CloseMessage`, `discord.titles.seasonWindowOpen/Close` y colores `discord.style.colors.seasonWindowOpen/Close`.

## Cambios de la versión 0.29.7

- Panel de administración: nuevo botón «Gestionar Top Donaciones» (cerrar el Top del mes ya, con confirmación, y ver las recompensas pendientes; un clic entrega la recompensa si su dueño está conectado y tiene espacio).
- Panel de administración: nuevo botón «MegaHunt» (ver el bloqueo activo y quitarlo). La especie se sigue poniendo con `/wt megahunt <especie>`.
- Nuevos niveles `admin.buttonLevels.topAdmin` y `admin.buttonLevels.megaHunt`.

## Cambios de la versión 0.29.6

- La copia de seguridad automática que ya existía (`backup.intervalHours`, `backup.keep`, `/wt admin backup`) ahora incluye también `top-donations.json`, `donation-history.json`, `penalties.json`, `seasonal-state.json`, `megahunt.json`, `event.json`, `event-schedule.json`, `maintenance-state.json` y la carpeta `seasonal/`.
- Nueva revisión de la configuración al arrancar y con `/wt reload` (o el botón de recargar del menú admin): avisa de JSON roto, fechas, tipos y especies inexistentes, colores, enlaces, webhook y roles de Discord, y rangos mal definidos. Solo avisa, no cambia nada. Se desactiva con `configCheck.enabled`.

## Cambios de la versión 0.29.5

- Menciones de rol propias para Navidad y Halloween al empezar la sesión (`mentionRoleIdChristmas` / `mentionRoleIdHalloween`), sin mención al terminar.
- Título de Discord de Halloween: «¡Evento de Halloween!».

## Cambios de la versión 0.29.4

- Aviso de fin de Halloween actualizado (chat y Discord) y migración de los textos antiguos.

## Cambios de la versión 0.29.3

- Nuevo aviso de inicio de Halloween (chat y Discord) y migración automática de los textos antiguos guardados en la carpeta de idiomas.

## Cambios de la versión 0.29.2

- MegaHunt: la línea evolutiva incluye evoluciones y pre-evoluciones que dependen de formas regionales.

## Cambios de la versión 0.29.1

- Nuevo bloqueo temporal de líneas evolutivas para el MegaHunt: `/wt megahunt <especie>|quitar`, guardado en `megahunt.json`, válido hasta las 00:00 y con avisos traducidos.

## Cambios de la versión 0.29.0

- `/wt rangos`: aviso «Puedes comprar el rango dando clic.» en los rangos con enlace de compra.

## Cambios de la versión 0.28.9

- `/wt rangos`: orden de más barato a más caro y clic en un rango (que no sea el propio) para abrir su enlace de compra en el chat (`shopUrl` de cada rango en `ranks.list`; `ranks.shopUrl` como enlace general de respaldo; vacío = desactivado).

## Cambios de la versión 0.28.7

- Los fallos al enviar el aviso de Discord de una temporada se registran en la consola.

## Cambios de la versión 0.28.6

- En el juego el Evento de Halloween se muestra como «Noche de Brujas» (chat, bossbar, menú de admin y traducciones); Discord mantiene «Evento de Halloween».

## Cambios de la versión 0.28.5

- Título de Discord del Evento de Halloween: «¡Evento de Halloween!».

## Cambios de la versión 0.28.4

- «Hora de Halloween» pasa a llamarse «Evento de Halloween» (chat, bossbar, menú de admin, Discord y traducciones).

## Cambios de la versión 0.28.3

- Halloween pasa a durar del 31 de octubre al 1 de noviembre (configurable con `startDate`/`endDate`).

## Cambios de la versión 0.28.2

- Nuevo evento de temporada «Hora de Halloween» (31 oct – 1 nov, 4 sesiones/día de 45 min, tipos Fantasma y Siniestro + murciélagos y arañas), con la misma mecánica que la hora navideña y bossbar, mensajes, Discord y colores propios.
- `WonderTradeSeasons` pasa a tener controles manuales por id y añade a la config los eventos de serie que falten (Halloween en configs antiguas).
- Menú admin de eventos: cuatro botones centrados (Arriesgar, Recibir, Navidad, Halloween); los eventos en curso se muestran en la fila superior.

## Cambios de la versión 0.28.1

- Menú «Rellenar pool» de admin: botones de cantidad centrados.
- Hora navideña anual (25 dic – 6 ene) y desactivable con `seasonal.enabled` o `enabled` del evento. Documentado.
- Política de versiones: se suben subversiones hasta 0.x.9; después se pasa a la siguiente (0.29.0).

## Cambios de la versión 0.28.0

- Top Donaciones: al reclamar se comprueba el espacio del inventario (`claim.requiredFreeSlots`) y existe caducidad opcional desactivada (`claim.expire`).
- Top Donaciones: recompensa de participación configurable (`topDonations.participation`, 10 donaciones por defecto) para quien no llegue al podio.
- Top Donaciones: las recompensas ya no se entregan automáticamente. Aviso clicable al cerrar el evento y al entrar, recordatorio periódico y nuevo menú `/wt recompensas` para reclamarlas, con el contenido visible al pasar el ratón.

- Bossbar navideña: barra azul, texto en degradado rojo-blanco-verde y copo animado azul-blanco, configurable.
- «Añadir Pokémon aleatorios»: la pantalla de cantidades en abanico se sustituye por un selector en espejo con botones -1/-5/-10/-15 a la izquierda y +1/+5/+10/+15 a la derecha (mismo sistema que el tiempo personalizado, pero con Pokémon).
- El botón «Añadir Pokémon aleatorios» del panel usa ahora una Luxury Ball de Cobblemon como icono.
- El botón central muestra la cantidad y avisa si no caben en la pool; el menú de categorías no cambia.

## Cambios de la versión 0.26.38

- Nuevo botón de administración «Añadir Pokémon aleatorios».
- Selector múltiple de categorías igual al de eventos y pantalla de cantidades en abanico.
- Rechaza la operación completa si no hay espacio para toda la cantidad solicitada y avisa en el chat.
- Respeta las restricciones de generación y el nivel mínimo 50 de los especiales generados por el servidor.
- README, guía de configuración y traducciones actualizados.

- Compilación: **pendiente**. No se ha ejecutado Gradle en este entorno.

## Cambios de la versión 0.26.37

- El menú del PC tiene marco, el nombre de la caja y cuántos Pokémon hay dentro. Elige, cambia de caja y vuelve igual que antes.

## Cambios de la versión 0.26.36

- El historial de admin se abre por jugador. El botón General, en ese mismo menú, muestra todos los intercambios.

## Cambios de la versión 0.26.35

- El menú de penalizaciones ordena el tiempo en el centro y agrupa motivo, penalizados y páginas abajo. Hace lo mismo que antes.

## Cambios de la versión 0.26.34

- El historial, las donaciones, el pool, el equipo y los especiales muestran la forma regional en el icono y en el nombre.

## Cambios de la versión 0.26.33

- Discord: «Han recibido un Pokémon del pool del WonderTrade» y «Han aportado un Pokémon al pool del WonderTrade».

## Cambios de la versión 0.26.32

- La frase de Discord dice que el Pokémon sale del pool o entra al pool.

## Cambios de la versión 0.26.31

- La frase de Discord empieza por el Pokémon, no por «Ha».

## Cambios de la versión 0.26.30

- La frase de Discord vuelve a nombrar el pool del WonderTrade y ya no repite el jugador, que sigue en el campo de abajo.

## Cambios de la versión 0.26.29

- La frase de Discord ya no repite WonderTrade, así cabe en una línea junto a la imagen.

## Cambios de la versión 0.26.28

- El aviso de Discord de «ha recibido» dice «del pool». «Al pool» queda solo en «ha aportado».

## Cambios de la versión 0.26.27

- En Discord, recibir y aportar usan la misma frase. El nivel y la categoría se quedan en los campos, y el nombre incluye la forma regional.

## Cambios de la versión 0.26.26

- El menú de idioma incluye portugués de Portugal (`pt_pt`) y alemán (`de_de`).

## Cambios de la versión 0.26.25

- Los textos de shiny pasan a amarillo. El rosa queda para el mítico.
- Discord usa el arte de la forma regional, no el del Pokémon base.

## Cambios de la versión 0.26.24

- Los textos de Lluvia y Apuesta usan el color del aviso de recepción: shiny y mítico rosa, legendario dorado, ultraente cian oscuro y paradoja rojo.

## Cambios de la versión 0.26.23

- El libro de Wonder Trade deja el amarillo solo en el nombre shiny. Los porcentajes van en blanco y el total en verde.

## Cambios de la versión 0.26.22

- Cancelar usa el botón de escape de Cobblemon. Los volver del admin dicen solo «Volver».

## Cambios de la versión 0.26.21

- Eliminar pool usa un vacío de estructura. Los botones de volver usan cristal verde lima.

## Cambios de la versión 0.26.20

- En Datos: Historial es libro y pluma, Donaciones es el libro normal y Reiniciar estadísticas es el libro verde.

## Cambios de la versión 0.26.19

- El botón Ejecutar mantenimiento del panel de admin usa un bloque de comandos de repetición.

## Cambios de la versión 0.26.18

- El panel de admin, en Datos, tiene Historial, Donaciones y Reiniciar estadísticas. Donaciones muestra qué ha donado cada jugador.

## Cambios de la versión 0.26.17

- Se elimina el alias `/wt topdonaciones`. El Top Donaciones se consulta con `/wt donaciones`.

## Cambios de la versión 0.26.16

- Los IVs usan los colores de Minecraft más cercanos a los de los juegos: HP verde, Atk amarillo, Def dorado, SpA cian, SpD azul, Spe rosa.

## Cambios de la versión 0.26.15

- Los IVs de los menús usan la nomenclatura de Pokémon: HP, Atk, Def, SpA, SpD, Spe.

## Cambios de la versión 0.26.14

- La confirmación dice el mínimo real al que vuelve el bonus si recibes un especial.

## Cambios de la versión 0.26.13

- El chat del intercambio y la confirmación muestran la mejora fija del rango y la tirada real.

## Cambios de la versión 0.26.12

- `/wt rank` hace lo mismo que `/wt rango`.

## Cambios de la versión 0.26.11

- En `/wt rangos`, la pokeball del rango del jugador brilla como un objeto encantado.
- El título «Rangos» pasa de azul a amarillo.

## Cambios de la versión 0.26.10

- Fuera de la configuración lo que ya no se leía: colores fijos de la bossbar, umbrales viejos de talla y animaciones de la action bar.

## Cambios de la versión 0.26.9

- En la confirmación, el nombre del Pokémon ofrecido usa el mismo color que en el equipo y la pool.

## Cambios de la versión 0.26.8

- Un evento con varias categorías usa la barra morada. El texto sigue con el color de cada una.

## Cambios de la versión 0.26.7

- Eliminadas las animaciones de la action bar. El mod ya no escribe en esa barra.

## Cambios de la versión 0.26.6

- El libro del menú usa el color de cada categoría en el bonus.
- El nombre del Pokémon usa el color de su categoría. Un shiny sin categoría sigue en amarillo.
- Con varias categorías, la barra del evento se queda blanca y la animación sigue solo en el texto.

## Cambios de la versión 0.26.5

- El menú de `/wt rangos` usa, de izquierda a derecha, Gloria Ball, Master Ball, Ultra Ball, Super Ball y Poké Ball.

## Cambios de la versión 0.26.4

- La bossbar de eventos se actualiza en cada tick. Arriesgar y Recibir animan el texto con el color de cada categoría; Shiny va en amarillo.
- La talla XS–XL usa la categoría real de Cobblemon.
- La animación de la action bar dura hasta que desaparece el texto.
- Los legendarios, míticos, Ultraentes y Paradoja generados por el servidor salen como mínimo al nivel 50. Lo que entrega un jugador conserva su nivel.
- Filtros de la pool reordenados y con los colores de `/wt especiales`. Legendarios incluye también a los míticos.

## Cambios de la versión 0.26.3

- Navidad: cuatro sesiones aleatorias de una hora al día entre el 25 de diciembre y el 6 de enero.
- Cada sesión genera una pool nueva y restaura la pool original al terminar; el calendario aleatorio queda guardado en `seasonal-state.json`.
- Bossbar de Navidad azul con degradado animado azul-blanco.
- Menú de administración para iniciar, pausar/reanudar y finalizar manualmente el evento.
- Aviso de Discord breve con la duración de cada hora navideña.
- README y documentación de temporada actualizados.
- Gradle no se ha ejecutado en este entorno; la compilación queda pendiente de GitHub Actions.

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

## 0.14.0
- Eventos con varias categorías a la vez (selección múltiple en el panel y mitico+legendario por comando); compatible con eventos guardados.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.14.1
- /wt help: líneas separadas para crear, parar, programar y desprogramar eventos; migración de los textos de ayuda antiguos.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.14.2
- /wt especiales incluye las formas regionales (Alola, Galar, Hisui, Paldea) de las especies de cada categoría, por ejemplo Articuno, Zapdos y Moltres de Galar.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.15.0
- Aviso con mensaje y sonido suave cuando termina el cooldown normal (cooldownNotice, sounds.cooldownReady).
- Con evento activo, el chat muestra la espera del evento en lugar de la normal.
- Reposición de la pool por objetivo: se mantiene la cantidad que había al empezar el evento (events.ensurePool.target) y se repone al instante tras cada intercambio.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.16.0
- Comandos administrativos aptos para consola donde no requieren inventario/GUI; fill y addspecies usan un jugador conectado como contexto de generación. Historial muestra texto desde consola.
- Sonido de cooldown listo cambiado a `entity.experience_orb.pickup`. Aviso a todos los jugadores elegibles al quedar disponible y recordatorios configurables cada 10 minutos (normal) o 2 minutos (evento activo).
- Panel admin permite eliminar solo entradas de origen Servidor o solo aportaciones de jugadores; comandos `/wt admin removegenerated` y `/wt admin removeplayers`.
- Programación de eventos desde GUI mediante presets relativos y repeticiones diarias/semanales.
- Limpieza automática de `history.json` configurable con `history.autoDeleteAfterDays` (30 por defecto; 0 desactiva).
- README, documentación y `/wt help` actualizados.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.16.1 — Programación de eventos rediseñada

- Sustituido el selector de horarios prefijados por un asistente visual de duración, día de la semana, hora ajustable y repetición única/diaria/semanal/mensual/anual.
- Añadida persistencia de ancla de día y mes para que las repeticiones mensuales y anuales no se desplacen en meses cortos o años bisiestos.
- Se mantiene compatibilidad con programaciones anteriores y con el comando de consola.
- No se ha ejecutado Gradle en esta revisión.


## 0.16.5

- Asistente «Programar evento»: ahora es un único menú que cambia de contenido (duración → día → hora → repetición) sin cerrarse ni reabrirse.
- Repetición mensual por día de la semana: tras elegir «Mensual» se escoge primer, segundo, tercer, cuarto o último (p. ej. «cada último viernes del mes»). Las programaciones mensuales antiguas siguen usando el mismo día del mes. En consola, «mensual» con un día de la semana usa la ocurrencia de la primera fecha.
- Menú de sonidos: el título usa un tocadiscos y el aviso de cooldown conserva el bloque musical.
- Los Pokémon de los menús (pool, equipo, PC) muestran sus IVs con un color por estadística (HP, ATK, DEF, SPATK, SPDEF, SPD); si una chapa plateada o dorada cambia un IV, el valor efectivo aparece al lado entre paréntesis. Se puede ocultar con `menu.showIvs: false`.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.16.6

- Las esperas de los eventos por jugador ya no se pierden al reiniciar: se guardan en `event.json` (`lastUse`) y solo se descartan cuando el evento termina o su espera caduca.
- La integración con Discord pasa a su propio archivo (`WonderTradeDiscord.kt`), con lo que `WonderTradeService.kt` queda más corto.
- Comandos duplicados unificados internamente (un solo código para `sonidos`/`sounds`, `especiales`/`categorias`, `eventos`/`events`, `clearpool`/`admin clear`, `admin eventos`/`lista`/`programados`). Todos los nombres siguen funcionando como alias.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.17.0

- Eventos (solo administradores): cada evento cuenta sus WT de evento y los jugadores distintos; se ve en `/wt admin eventos`, en el panel (clic en un evento) y se guarda en `event.json`.
- Historial de eventos terminados (`/wt admin eventos historial` y botón «Historial de eventos» del panel), con `events.history.keep` entradas (50 por defecto).
- Pausar y reanudar un evento activo (`/wt admin eventos pausar|reanudar <id>` o desde el panel): el tiempo se congela, el evento no tiene efecto mientras está pausado, y al reanudar se conservan el tiempo restante y las esperas de los jugadores. Sobrevive a reinicios.
- IVs: la búsqueda de la pool admite `6x31`, `5x31`… (mínimo de IVs perfectos) y `31spd`, `31hp`, `31atk`, `31def`, `31spatk`, `31spdef` (ese stat a 31), combinables con el nombre. Los Pokémon con 6x31 muestran una estrella.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.17.5

- Cabezas de donantes (menú «Eliminar pool → jugadores»): se guardan el UUID y la textura de skin de cada jugador al entrar y al donar/intercambiar (`player-data.json`: `skinValue`, `skinSignature`), de modo que la cabeza se ve aunque esté desconectado. Sin textura guardada se usa solo el nombre (en servidores no premium el UUID no sirve para consultar a Mojang, por eso no se envía).
- La entrada «Servidor» aparece la primera con una cabeza de dragón propia.
- El botón «Solo generados por jugadores» usa una cabeza con la skin del administrador que abre el menú.
- Opcional: `gui.heads.server` y `gui.heads.players` aceptan el valor base64 de «textures» de cualquier cabeza para sustituir esas dos.
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


## 0.17.7

- Pokémon generados para un evento: la especie ya no la elige el `random` de Cobblemon (repetía casi siempre las mismas). Ahora se sortea de la lista de especies de la categoría del evento, priorizando las que todavía no están en la pool; también incluye las formas regionales. Para eventos Shiny se sortea entre todas las especies.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.17.8

- Avisos de eventos a Discord (`discord.events`): inicio del evento (con detalles y duración), fin, aviso previo de los eventos programados (solo el más anticipado de `announceBeforeMinutes`) y pausa/reanudación. Usan marcas de tiempo de Discord (`<t:…>`), así que cada persona ve la hora y la cuenta atrás en su propia zona horaria.
- Opcional: `discord.events.mentionRoleId` menciona un rol en esos avisos. Cada aviso se puede activar o desactivar por separado y los textos están en `discord.eventStartMessage`, `eventEndMessage`, `eventSoonMessage`, `eventPausedMessage` y `eventResumedMessage`. Requiere `discord.enabled: true` y un `webhookUrl` válido.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.18.0

- **Top Donaciones** mensual con recompensas (`topDonations`): solo cuentan las donaciones hechas con `/wt donar` en meses activos. Por defecto hay Top en los meses impares de 2026 (enero, marzo, mayo, julio, septiembre y noviembre) y se invierte cada año (2027: meses pares; 2028: impares…). Puntos: normal 1, shiny 2, Ultraente/Paradoja 3, legendario/mítico 4, sin extra por shiny + categoría (configurables en `topDonations.points`).
- El último día del mes, a la hora `topDonations.announceHour` (20:00 por defecto), se anuncia el Top 3 con sus puntos en el chat y en Discord (`discord.events.topDonations`), y el 1.º, 2.º y 3.º reciben las recompensas definidas en `topDonations.rewards.first/second/third` (comandos de consola con `{player}`, `{rank}`, `{points}` y `{period}`). Si el ganador está desconectado, las recibe al entrar. Los ejemplos incluidos dan caramelos raros: cámbialos por lo que quieras.
- `/wt donaciones` (alias `/wt topdonaciones`): muestra los resultados finales del Top Donaciones únicamente cuando el evento mensual ha terminado. Mientras siga activo, el comando informa de que la clasificación aún no está disponible. El estado se guarda en `top-donations.json`.
- Límite de donaciones mensual: `donation.limitMode` (`monthly` por defecto, se reinicia el día 1) con `donation.monthlyLimit` (20 por defecto). `weekly` conserva la ventana móvil de 7 días y `donation.weeklyLimit`.
- Anti-repetición al donar: no se puede donar la misma especie (`donation.sameSpecies.period`: `month`, `week` u `off`) ni el mismo ejemplar (`donation.blockSameIndividual`) dentro del periodo.
- `config-guide.txt`, README y documentación actualizados.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.18.1

- Panel de administración: nuevo botón «Top Donaciones» (esmeralda/redstone) en la fila de servidor que activa o desactiva el Top Donaciones (`topDonations.enabled`) y muestra si este mes hay Top. El cambio se guarda en `config.json`.
- Gradle no se ha ejecutado en el entorno de edición.


## 0.18.2

- Panel de administración redistribuido: se quita el objeto «Panel de administración» del centro superior y los botones quedan en cuatro filas con espacio entre ellos (Pool, Servidor, Datos y Sistema). Historial y Reiniciar estadísticas pasan a la nueva fila «Datos».
- Gradle no se ha ejecutado en el entorno de edición.

## 0.19.0

- Penalizaciones: nuevo menú «Penalizaciones» en el panel de administración (hierro). Muestra las cabezas de los jugadores conectados (clic = penalizar), un selector de tiempo (±1 d, ±1 h, ±10 min), el botón «Permanente», «Por nombre» (jugadores desconectados) y la lista de penalizados para perdonar. Un jugador penalizado no puede intercambiar ni donar. Se guarda en `penalties.json`.
- Comandos: `/wt admin penalizar <jugador> [duración|permanente]`, `/wt admin perdonar <jugador>` y `/wt admin penalizaciones`. Sin duración se usa la elegida en el menú o `penalties.defaultDuration`.
- Animaciones discretas en la barra de acción (ola dorada de unos 3 s) al recibir un Pokémon especial, al empezar un evento y al cerrar el Top Donaciones. Configurables en `animations`.
- Empates: `/wt top` ordena ahora a igualdad por nombre y los empatados comparten puesto (1, 1, 3). Top Donaciones: nuevo `topDonations.tieBreak` (`firstToReach` por defecto = gana quien llegó antes; `mostDonations`; `share` = empatados comparten puesto y premio).
- Gradle no se ha ejecutado en el entorno de edición.

## 0.20.0

- Idiomas: nueva opción `general.language` (`es_es` español de España, principal, y `en_us`). Se configura mediante `general.language` en `config.json`. Los textos viven en `config/wondertradeplus/lang/<código>.json`; para añadir otro idioma basta con copiar `en_us.json` con otro nombre (p. ej. `de_de.json`), traducir los valores y elegirlo. Lo que falte en un idioma se muestra en español.
- Los textos ya no se guardan en `config.json`: al actualizar se quitan de ahí los que seguían sin personalizar y se conservan (con prioridad sobre el idioma) los que hayas cambiado a mano. Se traducen menús, mensajes, ayudas, avisos de Discord, nombres de eventos y categorías, y los nombres de días y meses.
- Penalizaciones: motivo opcional (`/wt admin penalizar <jugador> [duración] [motivo]`, `/wt admin motivo <texto>` y botones «Motivo» en el menú, con motivos rápidos configurables en `penalties.reasons`). El motivo se muestra al jugador y en las listas.
- Historial de penalizaciones (`/wt admin penalizaciones historial [jugador]` y botón «Historial» en el menú «Penalizados»): penalizaciones, perdones y caducidades, con límite `penalties.historySize`.
- Aviso al terminar una penalización: al instante si el jugador está conectado, y al entrar si caducó mientras estaba fuera.
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

## 0.26.2

- Eventos de temporada: se quita `speciesChances`; se mantiene `excludeSpecies`.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.26.1

- Eventos de temporada: `excludeSpecies` (especies que no salen en la pool del evento) y `speciesChances` (% de la pool que ocupa cada especie). Vacías por defecto.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.26.0

- Eventos de temporada (`seasonal`): la Navidad viene configurada (15-12 a 06-01, tipo hielo, pool de 100, solo se pueden intercambiar Pokémon de tipo hielo). La pool original se guarda aparte y se restaura al terminar; la del evento se archiva. Sin comandos nuevos, con avisos en el chat, al entrar y en Discord, traducido a es, en, fr e it.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.25.1

- Nuevas listas `general.formBlacklist` y `general.aspectBlacklist` (vacías por defecto, con ejemplo en la guía y en la documentación) para bloquear formas y aspectos, por ejemplo las texturas de un resource pack del servidor. Bloquean al ofrecer y al recibir; admiten `nombre` o `especie:nombre`.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.25.0

- Historial por UUID: cada intercambio guarda el UUID del jugador, así que un cambio de nick no hace perder historial. Los menús y comandos siguen mostrando nicks (se actualizan al nick actual cuando el jugador entra). Las entradas antiguas, que solo tenían el nick, reciben su UUID al arrancar si ese jugador es conocido; las de jugadores desconocidos siguen buscándose por nick.
- Lista negra de especies: ya existía (`general.speciesBlacklist`); ahora está documentada con ejemplo en `config-guide.txt` y `docs/CONFIGURATION.md`. Está vacía por defecto.
- Barra de evento en el idioma de cada jugador (una barra por evento e idioma).
- Pool: nuevos filtros Shiny, Ultraentes, Paradoja y Legendarios (legendarios y míticos juntos) y nuevo orden por especie A-Z. Traducido a es, en, fr e it.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.24.0

- Nuevos idiomas: francés (`fr_fr`) e italiano (`it_it`), con las mismas 1004 entradas que el inglés y la terminología oficial de Pokémon. Aparecen solos en `/wt idioma`, se eligen con `general.language` y se detectan por el idioma del cliente de Minecraft (fr_fr, fr_ca…, it_it).
- Los archivos nuevos se copian a `config/wondertradeplus/lang/` al arrancar; los que ya existan no se sobrescriben.
- Las traducciones son automáticas: conviene que las revise alguien nativo.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.23.7

- La talla (XS, S, M, L, XL) aparece junto al nombre del Pokémon en todos los menús: equipo, PC, pool y confirmación. Se controla con `pool.size.show` y los límites `pool.size.*`.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.23.6

- Eliminado el comando duplicado `/wt categorias`; queda `/wt especiales`.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.23.5

- Menú `/wt rangos`: cada rango tiene un objeto distinto (estrella del Nether, diamante, esmeralda, oro, hierro, cobre…) y los rangos aparecen centrados en el menú.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.23.4

- Los avisos de penalización (al penalizar y al intentar intercambiar penalizado) se redactan ahora en el idioma del jugador penalizado: el tiempo («permanente», «2h 3m») y el motivo se calculaban en el idioma del admin. Los motivos rápidos predefinidos también se traducen; un motivo escrito a mano se muestra tal cual.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.23.3

- `/wt rango` muestra solo lo que tiene el jugador (rango, mínimo, mejora fija, ganancia y probabilidad actual).
- Nuevo `/wt rangos` (alias `/wt ranks`): menú con todos los rangos; las ventajas de cada uno aparecen al pasar el ratón por encima y tu rango lleva una estrella. Se quita la opción `ranks.showList`.
- La línea «Ganancia por Pokémon ofrecido» se mantiene porque `gainMultiplier` sí se usa en la configuración de los rangos (multiplica lo que suma cada Pokémon ofrecido).
- Gradle no se ha ejecutado en el entorno de edición.

## 0.23.2

- Discord: el título ya no sale duplicado. Los archivos `lang/*.json` del servidor conservaban los textos antiguos de los avisos (con «Evento iniciado…» y «Dura… termina en…»); al arrancar, los textos antiguos sin personalizar se actualizan a los nuevos. Los que hayas cambiado a mano se respetan.
- Discord: «Termina <t:{endEpoch}:R>» ya no sale sin sustituir; todas las plantillas reciben `{name}`, `{details}`, `{duration}`, `{endEpoch}` y `{startEpoch}`.
- Gradle no se ha ejecutado en el entorno de edición.

## 0.23.0

- Discord más visual: los avisos ahora son tarjetas (embeds) con título, color según la rareza, campos (jugador, Pokémon, nivel, categoría), imagen del Pokémon y pie. Se pueden volver a mensajes de texto con `discord.style.useEmbeds: false`.
- Al terminar un evento, el aviso de Discord muestra intercambios, jugadores y duración, y lista los nicks de quienes han conseguido algo gracias al evento («Recibir …»: recibieron un Pokémon de la categoría; «Arriesgar …»: aportaron uno). Se guarda en `event.json` y se controla con `discord.events.listPlayers` y `discord.events.maxPlayersListed`.
- Top Donaciones en Discord con medallas y campos por ganador.
- `/wt rango` rediseñado: rango, mínimo, mejora fija, ganancia, probabilidad actual y lista de rangos con ✔ en el tuyo (`ranks.showList`).
- Negrita del nombre del mod en el chat: el prefijo se corrige siempre (`general.boldPrefix`), aunque `config.json` tuviera un prefijo antiguo.
- Talla en la pool (XS, S, M, L, XL) calculada con el tamaño del Pokémon y límites configurables en `pool.size.*` (sustituye a la llamada anterior, que no existe en Cobblemon).
- `/wt topdonaciones` y `/wt donaciones` solo muestran la clasificación cuando el evento ha terminado (ya incluido en 0.22.1).
- Corrección: el nuevo prefijo se llamaba a sí mismo y provocaba un StackOverflowError al abrir `/wt`.
- Gradle no se ha ejecutado en el entorno de edición.

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
