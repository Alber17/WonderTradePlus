# WonderTrade Plus

Wonder Trade abierto y configurable para Cobblemon. Esta primera versión se distribuye como **mod de servidor Fabric**, no como plugin Bukkit/Paper independiente.

## Objetivos de esta primera versión

- Pool persistente de Pokémon aportados por jugadores; se guardan como datos completos de Cobblemon, no como simples nombres de especie.
- Generación aleatoria opcional si el pool no tiene un Pokémon compatible.
- Filtros de Pokémon shiny, legendarios/míticos/Ultraentes y etiquetas especiales; la lista de etiquetas se puede cambiar.
- Coincidencia de rareza, shiny y nivel configurables.
- Cooldown global por jugador, límite diario opcional y bypass para operadores.
- Listas negras y blancas de especies.
- Protección para no intercambiar un Pokémon no intercambiable, debilitado, alfa o con objeto equipado (según configuración).
- Confirmación para evitar intercambios accidentales.
- Mensajes y anuncios configurables.
- Guardado JSON mediante escritura temporal y reemplazo atómico cuando el sistema de archivos lo permite.
- Licencia MIT.

## Compatibilidad prevista

- Minecraft Java **1.21.1**
- Java **21**
- Fabric Loader + Fabric API
- Cobblemon **1.8.4 o posterior compatible con Minecraft 1.21.1**

El código compila contra las dependencias de Cobblemon 1.8.4. La compatibilidad con futuras versiones aún debe comprobarse cuando se publiquen. No se afirma compatibilidad con NeoForge, Arclight, Mohist ni servidores Bukkit híbridos en esta versión.

## Compilar

1. Instala JDK 21.
2. Abre la carpeta del proyecto en IntelliJ IDEA, importa el proyecto Gradle y deja que descargue dependencias.
3. Ejecuta la tarea `build` de Gradle. El mod debería quedar en `build/libs/`.

También se puede compilar con Gradle instalado ejecutando `gradle build` desde la carpeta raíz. Este paquete fuente no incluye el binario de Gradle Wrapper; no se ha podido generar ni probar el JAR en este entorno porque no tiene Gradle instalado ni acceso a los repositorios de dependencias. El repositorio incluye un flujo de GitHub Actions que construye el JAR cuando se sube a GitHub.

## Instalación en servidor

1. Haz una copia de seguridad de `world/` y de la carpeta `pokemon/` si tu servidor usa almacenamiento externo.
2. Instala el JAR compilado en la carpeta `mods/` del servidor Fabric junto a Cobblemon y sus dependencias.
3. Arranca el servidor una vez para generar `config/wondertradeplus/config.json`, `pool.json` y `player-data.json`.
4. Edita `config/wondertradeplus/config.json` y reinicia o ejecuta `/wt reload`.

En un servidor dedicado, instala el JAR solo en el servidor: los jugadores no tienen que instalar WonderTrade Plus ni recibir una pantalla/mod de cliente. Para usarlo en un mundo individual, sí debes poner el JAR en la carpeta `mods` de tu instancia, ya que Minecraft ejecuta el servidor integrado dentro del juego. Aunque el mod puede cargarse en el proceso cliente para ese caso, no añade HUD, pantallas ni controles cliente.

## Comandos

| Comando | Función |
|---|---|
| `/wt` o `/wt gui` | Abre el menú: equipo, PC y confirmación. |
| `/wt pool view` | Abre el pool completo en un menú paginado (solo lectura). |
| `/wt <slot>` | Pide confirmación para intercambiar el Pokémon de ese slot (1–6). |
| `/wt confirm` | Confirma el intercambio pendiente. |
| `/wt <slot> confirm` | Intercambia ese slot inmediatamente, saltando la confirmación. |
| `/wt cancel` | Cancela la confirmación pendiente. |
| `/wt pool` | Muestra el tamaño y un resumen del pool. |
| `/wt reload` | Recarga configuración y archivos; operador nivel 2. |
| `/wt clearpool` | Vacía el pool; operador nivel 2. |

Los comandos `/wondertrade` y `/wt` son alias.

## Configuración

La plantilla completa está en [`src/main/resources/wondertradeplus-default.json`](src/main/resources/wondertradeplus-default.json). El archivo generado en `config/wondertradeplus/config.json` incluye comentarios en esta documentación, no en el JSON, ya que JSON estricto no admite comentarios.

Opciones principales:

- `general.requireConfirmation`: pide confirmación antes de cambiar el equipo.
- `general.cooldownMinutes`: tiempo mínimo entre intercambios.
- `trade.dailyLimit`: máximo diario; `0` significa ilimitado.
- `trade.matchSpecialTier`: intenta que Pokémon normales se intercambien por normales y especiales por especiales.
- `trade.specialLabels`: etiquetas que definen los Pokémon especiales (`legendary`, `mythical`, `ultra_beast`, `paradox` de inicio).
- `trade.shinyMode`: `MATCH_IF_AVAILABLE`, `REQUIRE_MATCH`, `IGNORE` o `DISALLOW_SHINY`.
- `trade.matchLevel` / `trade.levelDifference`: restringe la diferencia de nivel al buscar en el pool.
- `general.speciesBlacklist`: especies que no se pueden ofrecer ni recibir (acepta `cobblemon:ditto` o `ditto`).
- `general.useSpeciesWhitelist` / `general.speciesWhitelist`: modo de lista blanca opcional.
- `pool.enabled`: activa/desactiva el pool de aportaciones.
- `pool.randomFallback`: genera un Pokémon al azar si no hay candidato adecuado.
- `pool.maximumEntries`: capacidad del pool.
- `fallback.shinyBehavior`: `MATCH_INPUT`, `SERVER_RATE`, `NEVER` o `ALWAYS`.

El modo de shiny es una regla económica importante; comprueba su configuración antes de abrir el servidor a los jugadores. `MATCH_IF_AVAILABLE` prioriza la misma condición shiny si el pool dispone de un candidato, mientras que `REQUIRE_MATCH` no acepta otra condición.

## Datos y recuperación

- `pool.json`: datos completos de Pokémon almacenados y sus índices de búsqueda.
- `player-data.json`: último intercambio y contador diario de cada UUID.
- `config.json`: reglas del servidor.

Antes de operar en producción, prueba la configuración en una copia del servidor. La transacción se revierte si falla el guardado durante el proceso normal, pero una caída brusca del proceso o del sistema de archivos entre escrituras independientes de Cobblemon y WonderTrade Plus no puede garantizar atomicidad absoluta.

## Contribuir

Las contribuciones se aceptan bajo MIT. Para publicar el proyecto, crea un repositorio Git público, sube todos los archivos fuente y conserva `LICENSE`. No incluye código de otros mods de Wonder Trade.

La documentación campo por campo está en [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md). El proyecto incluye un flujo de GitHub Actions para generar el JAR en cada push o pull request.
