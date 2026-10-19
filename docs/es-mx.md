# Guía de la API de WaterMedia

[English](en-us.md) · Español (México)

WaterMedia resuelve direcciones de contenido multimedia, decodifica sus datos y entrega cuadros de video y muestras de audio a los motores de salida. Requiere Java 17 o posterior. Los puntos de entrada de los loaders de Minecraft realizan el arranque; una aplicación que integra la biblioteca directamente debe iniciarla después de preparar su entorno y sus dependencias.

## Arranque y propiedad de los recursos

```java
import org.watermedia.WaterMedia;
import org.watermedia.WaterMedia.Status;

WaterMedia.start("My application", temporaryDirectory, workingDirectory, true);
Status snapshot = WaterMedia.status();
```

`start` carga los módulos una sola vez durante toda la vida del proceso y nada se descarga después: no existe un `stop`, y un segundo `start` lanza. El último argumento indica si el entorno es cliente; los servidores dedicados omiten los módulos de cliente. `WaterMedia.loaded()` es la única comprobación de disponibilidad. Lee `snapshot.state()` y `snapshot.failures()` en la misma instantánea:

| Estado | Significado |
| --- | --- |
| `IDLE` | Todavía no se llamó a `start`. |
| `LOADING` | Los módulos están cargando: `loading()` es la clase del módulo en curso y `done()`/`total()` cuentan los módulos, que es todo lo que necesita una pantalla de carga. |
| `LOADED` | La carga terminó; los módulos opcionales que fallaron aparecen en `failures()`. |
| `FAILED` | Falló un módulo esencial y `start` lanzó con esa falla como causa. |

Un módulo del que la biblioteca puede prescindir (el servidor de archivos, FFmpeg) queda listado en `failures()` con su causa mientras el estado llega igual a `LOADED`, así que consulta la capacidad que necesitas, como `MediaAPI.ffmpegLoaded()`; uno esencial (la configuración, un módulo de Binaries instalado) deja `FAILED`. La decodificación de imágenes en Java mediante `CodecsAPI` no requiere este arranque.

`MediaAPI.mrl`, los reproductores multimedia y `NetworkAPI.upload` lanzan `IllegalStateException` hasta que `loaded()` es verdadero, y los de medios también en servidores dedicados. `PlatformAPI` no necesita arranque: su registro es estático, `register`, `fetch` y `search` funcionan en cualquier momento, y los handlers que requieren Binaries (YouTube, yt-dlp) fallan con su propia `PlatformException` hasta que carga, y siempre en servidores dedicados, que nunca cargan Binaries. `NetworkAPI.upload` además rechaza de entrada lo que no sea un archivo existente; un servidor remoto inalcanzable o mal configurado falla el estado devuelto.

Cada reproductor es dueño de los motores que recibe. Créalos mediante proveedores (`Supplier`) para que una fuente no disponible no deje recursos nativos reservados. No compartas un motor entre reproductores.

El módulo de binarios requiere WaterMedia y carga con él. Es obligatorio en clientes de
Minecraft y opcional en servidores dedicados. Después de cargar la configuración, prepara los binarios
antes de los módulos de red y de medios; si está instalado y falla, termina la carga.
Las aplicaciones que integran directamente la API Java pueden omitirlo y conservar
imágenes y plataformas sin ejecutables. Desactivar FFmpeg omite su extracción. Las rutas nativas quedan
publicadas durante toda la vida del proceso. No existe una API separada para iniciar o detener los binarios.

Las distribuciones personalizadas compatibles de FFmpeg pueden declarar GPL, LGPL u otra licencia y
versión. El cargador comprueba integridad y capacidades nativas, sin una lista de versiones o licencias
permitidas. El pegamento JNI debe seguir siendo compatible con la API Java que usa WaterMedia.

La aplicación anfitriona es dueña de sus contextos OpenGL, Vulkan y OpenAL. Libera cada reproductor en los hilos y contextos correspondientes antes de destruir esos contextos. Después de recargar un dispositivo o contexto debes crear motores nuevos. Las bibliotecas JNI nunca se descargan ni pueden cambiar de versión dentro de la misma JVM.

## Requisitos de ejecución y configuración

La API Java requiere Java 17. Proporciona WaterConfig, Log4j, Gson, JOML y las dependencias gráficas y de audio que use el host. WaterMedia incluye WaterConfig como un JAR anidado para Forge, NeoForge y Fabric. El launcher independiente extrae ese JAR a un archivo temporal y lo agrega al classpath de la JVM relanzada; descarga las demás bibliotecas de su lista cuando hacen falta, y una biblioteca en caché o descargada sólo entra al classpath si coincide con el SHA-256 fijado al compilar. Una aplicación que integra la API debe preparar su propio classpath. El JAR distribuido de Binaries incluye su dependencia XZ con sus paquetes reubicados. Las dependencias de Gradle para desarrollo no demuestran que una instalación de Minecraft tenga las demás bibliotecas. Forge, NeoForge y Fabric cargan WaterMedia en clientes y servidores dedicados, y rechazan las versiones de Minecraft fuera de la lista publicada. WaterMedia y Binaries se aceptan entre sí desde 3.0.0 hasta antes de 3.1.0; las versiones BETA anteriores de 3.0.0 se rechazan. Fabric no puede limitar una dependencia a clientes, así que su arranque exige Binaries en clientes y su manifiesto rechaza versiones incompatibles de Binaries.

Los arranques de Minecraft se niegan a iniciar cuando TLauncher lanzó el juego. Lo detectan por su mod de skins inyectado, sus clases, sus archivos en el classpath, sus opciones de JVM, sus hilos y sus procesos padre, y fallan con un mensaje que nombra la evidencia; los restos de una instalación anterior en la carpeta del juego sólo dejan una advertencia en el log. Las aplicaciones que integran la API omiten esta comprobación.

La configuración se carga antes de preparar los binarios. Revisa el archivo generado y el classpath real del host antes de diagnosticar un motor no disponible. Estas rutas son las llaves del `watermedia.toml` generado; los tamaños usan MiB (1,048,576 bytes), aunque la configuración los identifique como MB.

| Ajuste | Valor predeterminado y límites relacionados |
| --- | --- |
| `media.ffmpeg.disable` | `false`; activarlo omite la preparación e inicialización de FFmpeg. Los ajustes de arranque se aplican en el siguiente inicio. Las imágenes siguen siendo independientes de FFmpeg. |
| `media.ffmpeg.customPath` | Vacío; un directorio explícito debe contener bibliotecas nativas compatibles, no el programa de consola `ffmpeg.exe`. Se siguen exigiendo las capacidades corregidas de TLS y origen de credenciales. |
| `media.ffmpeg.hardwareAccel` | `true`; su uso depende también del binario, controlador, códec y motor de salida. Sigue disponible la decodificación por software. |
| `media.ffmpeg.analyzeDuration` / `probeSize` | 7,000 ms / 10 MiB; se aplican a la entrada principal y al audio separado. Una duración de análisis de cero conserva el valor de FFmpeg. Aumentarlos puede retrasar el inicio. |
| `decoders.maxImageSourceSize` | 128 MiB de imagen codificada. Es independiente del límite de 512 MiB de datos decodificados del lector. |
| `media.watertx.texturesBudget` | 32 MiB; la precarga exige además un motor compatible, una cantidad válida de cuadros y que tanto los cuadros originales decodificados como las texturas de salida quepan en el presupuesto. Cero fuerza la reproducción de animaciones cuadro por cuadro. |
| `media.ffmpeg.cache` / `cacheMaxResponse` | Activada / 10 MiB por respuesta elegible; no almacena todas las transmisiones ni todas las entradas HLS/DASH. |
| `media.watertx.cache` / `media.cacheBudget` | Caché de imágenes activada / 8,192 MiB de disco compartido; después de escribir se eliminan entradas viejas si hace falta. Los límites de disco, imagen codificada, imagen decodificada y texturas son independientes. |
| `platforms.allowMatureContent` | `false`; los adaptadores compatibles pueden dejar el recurso en `BLOCKED` sin enviarlo a reproducción. |
| `platforms.searchCleanupInterval` | 15 minutos; cero limpia la caché de búsquedas en cada consulta. |

El servidor opcional de archivos arranca según `network.server`: `OFF` (el valor por defecto) nunca, `DEDICATED` en cualquier host iniciado sin cliente (servidores dedicados), `ALWAYS` también en los clientes. Escucha en `127.0.0.1:25572`; aceptar conexiones remotas exige una dirección explícita y un `network.token` personalizado no vacío; un valor inválido hace fallar el arranque del servidor de archivos, que `WaterMedia.status()` lista como falla del módulo de red mientras la carga termina igual. `network.remoteUrl` es la URL HTTP(S) completa que usa cualquier host que sube archivos, clientes incluidos, y no cambia la dirección donde escucha el servidor. El servidor integrado usa HTTP; para HTTPS remoto, el host debe proporcionar la terminación TLS. Su raíz responde sólo el nombre del producto, los identificadores de archivo usan minúsculas y dígitos para nombrar el mismo archivo en cualquier sistema de archivos, un único `Range` satisfacible se respeta tanto en `GET` como en `HEAD`, y uno malformado o múltiple se ignora.

Los valores iniciales permiten ocho solicitudes simultáneas, 30 segundos de inactividad por solicitud, 8 MiB por carga y 1,024 MiB de almacenamiento total. El plazo corta una solicitud sólo tras ese tiempo sin cabeceras ni avance de la transferencia, así que una descarga larga en un enlace lento termina y una colgada se cierra. `maxUploadSize = 0` sólo elimina el límite individual de carga: siguen aplicando la cuota total, la concurrencia y el plazo de inactividad, y un almacenamiento lleno rechaza las cargas con `507`, advirtiendo en el primer rechazo de cada arranque del servidor, hasta que se borren archivos a mano. `NetworkAPI.upload` devuelve un `UploadStatus` por archivo: `uri()` es la dirección `water://remote/<id>` cuando el servidor lo acepta, y `cancel()` aborta una transferencia en curso dejando el estado como fallido. Las solicitudes Java, las descargas de listas de reproducción y el tiempo de socket de yt-dlp comparten `network.timeout` (15 segundos), diez redirecciones y un límite de 16 MiB para texto. Las entradas nativas de reproducción tienen su propia política de espera.

## Paquetes públicos e internos

El JAR es el módulo explícito `org.watermedia`. Su API pública son los paquetes exportados: `org.watermedia`, `api.codecs`, `api.media`, `api.network`, `api.platform` y `api.util`. Todo lo demás es interno: lectores, escritores y estructuras de chunks de los códecs, colas y cachés de los reproductores, las plataformas integradas, el arranque y los bindings de FFmpeg incluidos. `org.watermedia.tools` y `org.watermedia.bootstrap` sólo se exportan al módulo Binaries: `tools` comparte sus utilerías de descarga, digestos y JSON, y `bootstrap` contiene el ciclo de vida de módulos que Binaries implementa.

Forge 1.21.1 y posteriores aplican el descriptor, así que el código fuera del módulo falla con `IllegalAccessError` o `IllegalAccessException` al tocar un paquete interno. NeoForge, Fabric, las versiones anteriores de Forge y las compilaciones en classpath lo ignoran; trata esa misma frontera como el contrato también ahí. Usa las funciones internas mediante sus puntos de entrada, como `CodecsAPI.reader`, `CodecsAPI.writer` y `PlatformAPI.fetch`. `gradle javadocInventory` revisa sólo los paquetes exportados, que lee de `src/main/module/org.watermedia/module-info.java`.

## MRL y resolución de direcciones

Un MRL representa una dirección multimedia resuelta y almacenada en caché. Una misma dirección puede representar una galería con varias fuentes; cada fuente puede tener variantes de calidad y pistas de audio separadas.

```java
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import java.net.URI;

MRL mrl = MediaAPI.mrl("https://imgur.com/gallery/abc123");
MRL[] playlist = MediaAPI.preload(
        URI.create("https://example.com/video1.mp4"),
        URI.create("https://example.com/video2.mp4"));
```

La resolución es asíncrona. En un juego, consulta `mrl.status()` desde el tick:

| Estado | Significado |
| --- | --- |
| `FETCHING` | La resolución sigue en curso. |
| `LOADED` | La resolución terminó correctamente. |
| `ERROR` | Falló la resolución; consulta `mrl.exception()`. |
| `BLOCKED` | La configuración impidió resolver el contenido; consulta `mrl.exception()`. |
| `EXPIRED` | Las fuentes resueltas caducaron. |
| `FORGOTTEN` | El recurso fue descartado de la caché. |

Para código de aplicación o tareas de fondo puedes usar `mrl.await(timeoutMillis)`. Devuelve `true` cuando la carga ya no está pendiente, incluso si terminó con un error; `false` cuando se agota la espera o se interrumpe el hilo mientras espera. No bloquees el hilo del juego para resolver una URL.

`MediaAPI.mrl(String)` trata una cadena que nombra un archivo existente como una ruta relativa al directorio de trabajo con el que arrancó WaterMedia; cualquier otra cadena se interpreta como URI. Una entrada malformada, relativa o vacía devuelve un MRL sin caché nacido en `ERROR` cuyo `reload()` se devuelve a sí mismo. Un enlace directo sólo se rechaza cuando el servidor responde con un tipo de documento (HTML, CSS, JavaScript, JSON, XHTML); cualquier otro tipo de contenido pasa sin importar mayúsculas o parámetros, y los ambiguos se clasifican leyendo los primeros bytes. Cada fuente conserva una URL por nivel de calidad: la variante más ancha, luego la más alta, luego la de mayor cuadros por segundo y, en empate, la primera que listó la plataforma; sus cabeceras quedan congeladas.

Los handlers de plataforma siguen un solo contrato. `Platform.resolve` devuelve `null` sólo ante un host o esquema ajeno; una URL de su propio host con una forma que no puede reproducir (un perfil, una carpeta, el host a secas) falla con `PlatformException` ("Unsupported URL") sin tocar la red, y cualquier otra falla, incluida una espera interrumpida, también es una `PlatformException`, así que `MRL` reporta el mensaje del propio handler. El contenido maduro se bloquea con `MatureContentException` donde la plataforma expone una marca o etiqueta (Imgur, Kick, Twitch, Bluesky, PornHub, yt-dlp, la verificación de edad de Twitter y la de YouTube), y `MRL` lo convierte en `BLOCKED`. Un álbum o una lista de reproducción salta un elemento malo con una advertencia y sólo falla cuando no queda ninguno. Los handlers registrados con `PlatformAPI.register` se consultan antes que los integrados, del más reciente al más antiguo, y no necesitan arranque: regístralos una vez, antes o después de `WaterMedia.start`, y el reemplazo se mantiene hasta `unregister`, que también retira un handler integrado por el resto del proceso. `PlatformAPI.search` devuelve un `PlatformSearch` vivo para consultar: `results()` crece conforme responden las plataformas y `done()` pasa a verdadero cuando todas respondieron o cuando una búsqueda más nueva reemplazó a ésta, lo que distingue `superseded()`. Registrar descarta las búsquedas en caché, y una búsqueda en blanco no hace nada ni cancela la que esté en curso.

`Metadata` significa lo mismo en todas partes: `title` nombra el contenido (un video, una transmisión, una publicación o un clip, nunca la cuenta), `description` lleva su leyenda, texto o categoría y `author` el canal, el autor o el streamer. Los enlaces firmados de CDN caducan cuando lo indica su parámetro `expire`, `expires`, `validto` o `x-expires`; el resto lleva una vigencia por plataforma o ninguna.

### Fuentes e índices

Los índices empiezan en cero. Las colecciones de fuentes son listas:

```java
import org.watermedia.api.util.MediaType;
import java.util.List;

if (mrl.status() == MRL.Status.LOADED) {
    List<MRL.Source> sources = mrl.sources();
    int count = mrl.sourceCount();
    MRL.Source first = mrl.source(0);
    MRL.Source second = mrl.source(1);
    MRL.Source firstVideo = mrl.sourceByType(MediaType.VIDEO);
    List<MRL.Source> videos = mrl.sourcesByType(MediaType.VIDEO);
}
```

`source(index)` devuelve `null` cuando la fuente no está disponible o el índice es inválido. `sourceByType(...)` devuelve la primera coincidencia o `null`; `sources()` y `sourcesByType(...)` devuelven listas vacías cuando no hay resultados. `MediaType` se importa desde `org.watermedia.api.util`.

Una galería puede resolverse así:

```text
URL: https://imgur.com/gallery/abc123
  -> Source[0]: IMAGE (cat.png)
  -> Source[1]: VIDEO (dog.mp4)
  -> Source[2]: IMAGE (bird.gif)
```

Para actualizar un MRL, conserva el valor devuelto por `mrl = mrl.reload()`. Si su entrada ya fue descartada, obtienes el recurso vigente para esa dirección y el anterior permanece descartado. Mientras una carga sigue en curso, la recarga se programa para después de que termine.

## Creación y control de reproductores

La fábrica está en `MediaAPI`. Usa el índice de la fuente y proveedores de motores:

```java
import org.watermedia.api.media.MediaPlayer;

MediaPlayer player = MediaAPI.player(mrl, 1,
        () -> MediaAPI.glEngine(renderThread, renderExecutor),
        MediaAPI::jsEngine);
if (player == null) {
    throw new IllegalStateException("Source is unavailable or its backend failed");
}
try {
    if (!player.start()) throw new IllegalStateException("Player refused to start");
} catch (RuntimeException | Error failure) {
    player.release();
    throw failure;
}
```

El ejemplo selecciona la segunda fuente de la galería. Puedes omitir el índice para seleccionar la primera. `renderThread` es el hilo dueño del contexto OpenGL y `renderExecutor` debe ejecutar tareas en ese hilo; ambos los proporciona tu integración.

La fábrica devuelve `null` si la fuente todavía se está resolviendo, el índice no existe, falta el motor de reproducción necesario o la construcción lanza una `Exception`. Los `Error` se propagan. Consulta el estado del MRL para distinguir una espera normal de una falla. Los proveedores se invocan sólo cuando hacen falta; las imágenes no consumen el proveedor de audio. Para omitir una salida, pasa un proveedor que devuelva `null`, por ejemplo `() -> null` como salida gráfica de un reproductor de sólo audio.

| Reproductor | Función |
| --- | --- |
| `TxMediaPlayer` | Imágenes y animaciones compatibles. |
| `FFMediaPlayer` | Video y audio mediante FFmpeg. |
| `ServerMediaPlayer` | Reloj de sincronización sin decodificación ni motores nativos. |

```java
player.pause(true);
player.pause(false);
player.seek(15_000);
player.volume(50);
player.mute(true);
player.speed(1.25f);
player.repeat(true);
player.scale(1280, 720);
```

`speed(value)` admite valores finitos en `(0, 4]` e informa si aceptó el cambio. Java Sound no puede cambiar la velocidad. `canSpeed()` es una consulta pura que cualquier hilo puede hacer en cada frame; `speed(value)` debe ejecutarse con el contexto de audio correspondiente activo, y el reloj del reproductor sólo sigue una velocidad que el motor de audio aceptó. Un seguidor sincronizado devuelve `false` incluso si envía una solicitud de control; ese resultado no confirma su aplicación remota. `spatialAudio(...)` también informa si el motor aceptó la actualización; consulta `spatial()` antes de mostrar controles posicionales.

Los tiempos se expresan en milisegundos y el volumen del reproductor es un porcentaje de 0 a 100. Consulta `canSeek()` antes de ofrecer desplazamiento en transmisiones que no lo permiten. Para diagnóstico usa `status()`, `time()`, `duration()`, `buffered()` y `exception()`.

Cada control booleano responde una sola pregunta: ¿esta llamada cambió el estado propio del reproductor? `false` cubre una llamada reenviada a la autoridad de sincronización, descartada por una sesión bloqueada, rechazada por el contenido o hecha después de `release()`; `repeat(boolean)` sigue la misma regla. Los tres reproductores comparten el contrato: una pausa durante la carga queda registrada y se aplica al abrir la tubería; `pause()` y `resume()` devuelven `false` en un reproductor terminado, detenido o fallido; `stop()` desmonta la tubería, deja `STOPPED` con `time()` en 0 y devuelve `true` sólo si hubo transición; `seek(...)` sobre un reproductor terminado o detenido lo reinicia pausado en la posición pedida, y `togglePlay()` ahí lo vuelve a iniciar; `nextFrame()` y `previousFrame()` sólo funcionan en pausa. `canPlay()` es verdadero desde la primera carga exitosa hasta que el reproductor falla o se libera, `canSeek()` y `live()` son falsos hasta que la fuente abre, `fps()` es 0 hasta conocer la tasa y `status()` es `STOPPED` tras `release()`. Una imagen fija que cae en FFmpeg respeta `displayTime(ms)` como el reproductor de imágenes y nunca repite; sin tiempo de exhibición se muestra hasta detenerla.

`onStatus(listener)` agrega un `MediaPlayer.StatusListener` que recibe el reproductor, el estado anterior y el actual en toda transición real, incluidas las que provocan un reinicio, `stop()` y `release()`. Los listeners se acumulan (agregar uno dos veces lo conserva una sola vez), y la transición a `STOPPED`, `ENDED` o `ERROR` los consume, así que un consumidor de playlist se registra una vez por reproducción y, cuando reinicia el reproductor desde ese callback terminal, vuelve a registrarse desde el propio callback. Las transiciones se entregan de una en una y en orden, en el hilo que causó el cambio (un hilo de reproducción, el ticker compartido de sincronización, o quien llama a `status()`, `time()` o `texture()` en reproductores de imagen cuyo reloj resuelve el final de forma diferida), así que mantenlo corto y nunca llames a `release()` desde él. `release()` es idempotente.

### Estados del reproductor

| Estado | Significado |
| --- | --- |
| `WAITING` | Creado, esperando condiciones para comenzar. |
| `LOADING` | Cargando o preparando el contenido. |
| `BUFFERING` | Esperando datos para continuar la reproducción. |
| `PLAYING` | Reproduciendo. |
| `PAUSED` | Pausado; puede reanudarse. |
| `STOPPED` | Detenido; puede iniciarse desde el principio. |
| `ENDED` | Alcanzó el final del contenido. |
| `ERROR` | Ocurrió una falla de reproducción. |

El escalado y el nivel de detalle afectan los formatos de píxeles compatibles. Los motores gráficos integrados todavía no aceptan texturas BC comprimidas. Llama a `player.release()` cuando elimines la superficie o cierres su sesión, incluso si la reproducción falló.

## Selección de motores

El desarrollo da prioridad a Vulkan; OpenGL sigue disponible como motor secundario. Las decisiones sobre motores pertenecen a la capa de renderizado. Esta preferencia de desarrollo no cambia el valor inicial OpenGL del launcher ni la selección que haya guardado el usuario.

| Fábrica | Responsabilidad de la aplicación anfitriona |
| --- | --- |
| `MediaAPI.glEngine(renderThread, executor)` | Mantener el contexto OpenGL y procesar el executor en su hilo mientras la reproducción o la liberación puedan necesitarlo. |
| `MediaAPI.vkEngine(context)` | Proporcionar un `VKContext` cuyos objetos y mecanismos de retiro sigan vivos hasta liberar el motor. |
| `MediaAPI.awtEngine(onFrame)` | Dibujar la imagen publicada desde la interfaz AWT/Swing y enviar el callback al hilo de interfaz cuando corresponda. |
| `MediaAPI.jfxEngine(onFrame)` | Proporcionar JavaFX 13 o posterior y enlazar la imagen del motor con la interfaz; sin él, la fábrica lanza `IllegalStateException` en lugar de un error de enlace. |
| `MediaAPI.headlessEngine()` / `headlessEngine(preload)` | Recibir cuadros en memoria sin contexto gráfico; útil para procesamiento y pruebas. Como todo motor y reproductor multimedia sólo existe en un cliente; un servidor dedicado sólo puede crear relojes `ServerMediaPlayer`. |
| `MediaAPI.alEngine()` | Mantener el contexto OpenAL activo en los hilos que operan el motor. |
| `MediaAPI.alEngine(true)` | Cumplir el mismo contrato OpenAL; negocia salida mono para sonido posicional. |
| `MediaAPI.jsEngine()` | Disponer de una línea de salida Java Sound; no admite posicionamiento espacial. |

Los motores administran sus propios recursos de reproducción, como texturas, fuentes de audio y buffers. Sus clases base son selladas: la integración proporciona contextos y ejecutores, no implementaciones arbitrarias de esos motores.

Todos los motores siguen el mismo ciclo de vida. `release()` es idempotente, nunca debe ejecutarse al mismo tiempo que el reproductor que alimenta al motor, y lo deja inerte: `released()` devuelve `true` y `texture()` devuelve 0. El reproductor consulta `supports(format, bits)` antes de subir cuadros y convierte a BGRA de 8 bits cuando el motor rechaza una disposición; las subidas nunca lanzan excepciones, así que una falla del controlador sólo detiene el motor con un error en el registro mientras la última textura sigue expuesta, y un cambio de resolución en Vulkan conserva la vista anterior hasta que llega el primer cuadro de la nueva disposición. Los motores de software y el sin pantalla exponen un valor distinto de cero en lugar de un identificador de GPU: compara el identificador sólo contra 0. OpenAL necesita que su contexto original esté activo en cada hilo que opera el reproductor, incluido el temporizador de sincronización; Java Sound es seguro entre hilos y reabre su línea cuando un bloque de audio supera el buffer de la línea.

El audio espacial se configura con `SpatialAudio` y un procesador opcional del entorno. La [guía de audio espacial y Sound Physics Remastered](#audio-espacial-y-sound-physics-remastered) incluye el adaptador, los requisitos de OpenAL, la frecuencia de actualización y las opciones del mod que pueden impedir que se apliquen efectos. La posición y los efectos pertenecen al oyente local y no viajan por el protocolo de sincronización.

## Audio espacial y Sound Physics Remastered

Crea un motor OpenAL con `MediaAPI.alEngine(true)` antes de construir el reproductor. El modo espacial permanece fijo durante toda la vida del motor: su tabla de canales sólo anuncia audio mono, así que el remuestreador existente de FFmpeg mezcla las fuentes estéreo y envolventes a mono antes de enviarlas a OpenAL. La fábrica `alEngine()` normal negocia canales sin forzar mono; los formatos que el dispositivo no admite aún pueden convertirse al número de canales disponible más cercano. Java Sound devuelve `false` al actualizar el audio espacial y no expone una fuente nativa.

El motor comienza con audio mono seco y relativo al oyente. Proporciona un estado `SpatialAudio` para ubicar la fuente en el mundo. Pasar `null` restaura la reproducción seca y relativa al oyente, pero conserva la decodificación mono. El volumen, el silencio, la velocidad, la pausa y la sincronización son independientes de la configuración espacial.

```java
import org.watermedia.api.media.SFXEngine.SpatialAudio;

final ALEngine audio = MediaAPI.alEngine(true);
final SpatialAudio position = new SpatialAudio(
        blockX + 0.5, blockY + 0.5, blockZ + 0.5,
        8.0f, 64.0f, 1.0f, false, null);
audio.spatialAudio(position);
```

Crea el motor, actualiza su posición y libéralo en el ejecutor de sonido del host, con el contexto OpenAL original activo. El host es dueño del dispositivo, el contexto y el oyente; éstos deben sobrevivir a todos los reproductores que los usan. `FFMediaPlayer` usa hilos privados de reproducción y actualmente requiere que el contexto de la fuente sea el contexto activo del proceso en esos hilos, igual que la reproducción ordinaria con `ALEngine`. La canalización del reproductor no admite hosts que expongan el contexto de audio únicamente mediante una vinculación local al hilo; vincularlo sólo en el hilo de sonido que llama a la API es insuficiente. Las operaciones nativas del motor rechazan un contexto distinto o ausente; nunca cambian ni recrean el contexto del host. Libera los reproductores antes de recargar el dispositivo y crea motores nuevos en el contexto reemplazado. `FFMediaPlayer.release()` interrumpe y espera su canalización de reproducción antes de eliminar la fuente de audio; debe ejecutarse antes de destruir el contexto y no desde un callback de esa canalización.

`SpatialAudio` exige posiciones finitas que puedan representarse como `float` de OpenAL, `0 < referenceDistance < maxDistance` y un `rolloff` finito y no negativo. El modelo de distancia del host determina cómo atenúan el audio estos parámetros. La API no cambia el modelo global de distancia, el oyente ni la velocidad de la fuente. En particular, `maxDistance` no es un radio de silencio universal para todos los modelos de OpenAL. Pasar `rolloff = 0` desactiva la atenuación por distancia. El estado espacial pertenece al oyente local y no se incluye en los paquetes de sincronización.

### Adaptador para Sound Physics Remastered

El artefacto de referencia revisado durante esta integración fue `sound-physics-remastered-1.21.1.zip`: contiene código fuente, no un JAR compilado. Su punto de entrada moderno es:

```java
SoundPhysics.processSound(source, x, y, z, category, soundId, auxOnly);
```

Implementa un adaptador pequeño en el mod de Minecraft que consume WATERMeDIA. La API de WATERMeDIA no contiene clases de Minecraft ni de Sound Physics. El host debe cargar este adaptador sólo cuando Sound Physics Remastered esté presente e inicializado.

```java
final ResourceLocation soundId = ResourceLocation.fromNamespaceAndPath("waterframes", "media");
final SoundSource category = SoundSource.MASTER;
final SpatialAudio.Environment environment = new SpatialAudio.Environment() {
    @Override
    public void process(final int source, final SpatialAudio audio) {
        SoundPhysics.processSound(source, audio.x(), audio.y(), audio.z(),
                category, soundId, audio.auxOnly());
    }

    @Override
    public void reset(final int source) {
        SoundPhysics.setDefaultEnvironment(source, false);
    }
};

// INSIDE THE HOST SOUND EXECUTOR, AFTER ITS CONTEXT, SOUND PHYSICS AND MRL ARE READY.
final MRL.Source media = mrl.source(0);
if (media == null || (media.type() != MediaType.AUDIO && media.type() != MediaType.VIDEO)) {
    throw new IllegalStateException("A resolved audio or video source is required");
}
final MediaPlayer player = MediaAPI.player(mrl, () -> null, () -> MediaAPI.alEngine(true));
if (player == null) throw new IllegalStateException("The media backend could not create a player");
try {
    player.spatialAudio(new SpatialAudio(sourceX, sourceY, sourceZ, 8.0f, 64.0f, 1.0f, false, environment));
    if (!player.start()) throw new IllegalStateException("The player refused to start");
} catch (final RuntimeException | Error failure) {
    player.release();
    throw failure;
}

// SUBMIT FROM THE HOST'S TICK INTEGRATION; THE EXECUTOR MUST SERIALIZE SOUND PHYSICS CALLS.
soundExecutor.execute(() -> player.spatialAudio(new SpatialAudio(
        sourceX, sourceY, sourceZ, 8.0f, 64.0f, 1.0f, false, environment)));

// BEFORE THE CONTEXT IS DESTROYED, ON THE SAME SOUND EXECUTOR.
soundExecutor.execute(player::release);
```

En este ejemplo, `soundExecutor`, el `mrl` resuelto y las coordenadas del mundo provienen del mod consumidor. Se reproduce la pista de audio sin salida de video; el proveedor de video que devuelve `null` es intencional. El proveedor de audio crea un motor sólo si la fábrica lo necesita, y la fábrica lo libera si la construcción del reproductor lanza una `Exception`. Para mostrar también el video, proporciona un proveedor que respete la propiedad del contexto de renderizado. Conserva la propiedad de cualquier motor creado por adelantado hasta que la fábrica lo consuma. La llamada moderna a `processSound` pasa la categoría y el identificador directamente; la pareja heredada `setLastSoundCategoryAndName` y `onPlaySound` utiliza metadatos mutables compartidos y no se necesita aquí.

Conserva la misma instancia del adaptador entre actualizaciones. Cada actualización restaura primero la posición original en el mundo, pues Sound Physics puede reemplazar `AL_POSITION` con el origen de un sonido reflejado. También limpia los filtros de la fuente y las salidas auxiliares anteriores, para que un procesador desactivado o reemplazado no deje aplicada una oclusión vieja. El procesador debe aplicar todo su entorno en cada invocación. Limita la frecuencia de llamadas a `spatialAudio(...)` desde el host, en lugar de regresar temprano dentro del procesador. `reset` se ejecuta al reemplazar o quitar un adaptador y durante la liberación; el motor elimina únicamente su propia fuente y sus búferes, nunca los filtros compartidos ni las ranuras de reverberación de Sound Physics. Si `process` o `reset` lanza una excepción, se limpian los efectos de la fuente y la excepción se propaga; un fallo de `reset` no impide eliminar la fuente nativa durante la liberación.

Actualiza los medios de larga duración cuando cambien el oyente, la fuente o el mundo, incluso si la fuente permanece inmóvil pero el oyente se mueve. Un punto de partida razonable es el `soundUpdateInterval` configurado por el mod, de cinco ticks, con actualizaciones inmediatas para fuentes recién creadas y cambios de posición. Distribuye las actualizaciones de varios reproductores entre distintos ticks y respeta los límites de frecuencia de sonido; no hace falta lanzar rayos por cada paquete de audio decodificado. La API sólo invoca el adaptador durante las actualizaciones explícitas del host, nunca desde el bucle de decodificación o reproducción de FFmpeg.

Los requisitos y ajustes relacionados de Sound Physics, verificados en el código fuente proporcionado, son:

| Requisito o condición | Efecto en la integración | Fuente dentro del ZIP |
| --- | --- | --- |
| El host inicializa Sound Physics en el contexto de la fuente | Los ID de sus filtros y reverberación compartidos deben pertenecer al mismo contexto; no llames `SoundPhysics.init()` por cada reproductor | `SoundPhysics.java:66`, `:85`; `mixin/SoundSystemMixin.java:27` |
| EFX y cuatro envíos auxiliares | Solicita `ALC_MAX_AUXILIARY_SENDS = 4` al crear el contexto del host; con menos envíos hay menos rutas de reverberación | `mixin/LibraryMixin.java:18`; `SoundPhysics.java:97`, `:631` |
| `enabled` | Tanto el procesamiento como `setDefaultEnvironment` no hacen nada cuando está desactivado; por eso WATERMeDIA limpia por sí mismo las conexiones EFX de su fuente | `SoundPhysics.java:189`, `:625` |
| `updateMovingSounds = false` de forma predeterminada | Se excluye la categoría `RECORDS`; elige la categoría deseada y habilita el procesamiento de sonidos en movimiento si los discos necesitan efectos | `SoundPhysics.java:223`; `config/SoundPhysicsConfig.java:150` |
| Mundo inicializado, jugador y nivel disponibles | Hasta que estén listos, el mod restaura el entorno predeterminado | `SoundPhysics.java:208`, `:230` |
| Posición exacta `(0, 0, 0)` | El mod la trata como entorno predeterminado; WATERMeDIA la acepta, pero no puede obligar al mod a procesarla | `SoundPhysics.java:211` |
| `maxSoundProcessingDistance = 512` de forma predeterminada | Las fuentes lejanas reciben el entorno predeterminado | `SoundPhysics.java:216`; `config/SoundPhysicsConfig.java:158` |
| Límites de frecuencia por identificador y ajustes de sonido ambiental | Una llamada puede recibir el entorno predeterminado aunque la fuente y el contexto sean válidos | `SoundPhysics.java:236`, `:242` |
| Factor de atenuación | Para igualar el mixin de atenuación lineal de Minecraft, divide el alcance deseado entre `attenuationFactor` mientras esté habilitado y establece la distancia de referencia a la mitad de ese alcance; el host debe elegir el modelo lineal correspondiente para la fuente | `mixin/SourceMixin.java:42` |

`auxOnly = true` solicita al adaptador una salida compuesta sólo por reflexiones y exige un procesador de entorno. Por sí solo no implementa reverberación ni garantiza el silencio de la ruta directa. Un procesador desactivado u omitido que regresa sin aplicar efectos deja audio seco; el consumidor debe silenciar el reproductor si en ese caso se requiere silencio estricto. El posicionamiento mono básico funciona sin EFX; un adaptador basado en EFX requiere que el dispositivo lo exponga. Mantén el código opcional del adaptador en el módulo de integración del consumidor, para que WATERMeDIA siga cargando sin Minecraft ni Sound Physics.

### Verificación

`SpatialAudioTest` cubre el rechazo de estados inválidos, la negativa de Java Sound, las coordenadas y la atenuación de una fuente OpenAL real, la restauración del origen antes del callback, la limpieza de efectos, el manejo de excepciones, los contextos diferentes y las llamadas tardías después de liberar la fuente. También genera un WAV estéreo y lo reproduce con `FFMediaPlayer`, verificando PCM mono en los búferes reales de OpenAL. Las pruebas de dispositivo requieren una salida OpenAL disponible; el caso de decodificación necesita además los binarios nativos de FFmpeg.

Todavía hace falta una prueba dentro del juego para comprobar que la oclusión y las reflexiones se escuchen como se espera, que el ejecutor de sonido del host serialice las llamadas, el movimiento del oyente, los ajustes de categoría y la recarga del dispositivo. El código fuente de referencia se revisó durante la integración, pero este checkout no contiene un entorno ejecutable con Minecraft y Sound Physics integrados.

## HTTPS y certificados

La reproducción HTTPS requiere las capacidades de verificación del paquete incluido o de una compilación personalizada compatible. FFmpeg verifica la cadena de certificados y la identidad DNS o IP con una copia de las autoridades de confianza de Java tomada durante el arranque. Para servidores privados, configura las propiedades estándar `javax.net.ssl.trustStore` antes de iniciar WaterMedia. Un almacén vacío o inválido impide inicializar FFmpeg; la verificación no se desactiva automáticamente.

`FFMediaPlayer` crea el archivo PEM temporal al arrancar FFmpeg, comprueba que el binario tenga las opciones necesarias y las aplica tanto a la entrada principal como a la pista de audio separada de cada reproductor que abre; nada de la API pública toca esa configuración. Las autoridades también se transmiten a las solicitudes internas de HLS y DASH. El módulo de medios elimina el archivo al cerrar, después de liberar todos los reproductores. Un `customPath` vacío no añade el directorio de trabajo a la búsqueda de bibliotecas nativas.

Se comparten autoridades de confianza; el transporte sigue siendo OpenSSL. No se transfieren fábricas de sockets JSSE personalizadas, callbacks de validación de nombres ni políticas de revocación de Java. Tampoco se modifica la lista de protocolos permitidos. El paquete temporal de CA vive durante todo el proceso y se borra al salir la JVM.

Las credenciales pertenecen al origen de la solicitud inicial: esquema, nombre de host y puerto efectivo. En redirecciones y solicitudes internas de HLS/DASH, `Authorization`, `Proxy-Authorization`, `Cookie`, `Cookie2` y `X-WaterMedia-Token` sólo se envían a ese origen. Al cambiar de origen se genera un encabezado `Host` nuevo; otros encabezados, como User-Agent, Accept, Referer y Origin, pueden acompañar las solicitudes a una CDN.

Las cookies generadas siguen el mismo límite: las respuestas `Set-Cookie` de otro origen se ignoran, incluso si una redirección regresa después al servidor inicial. Una entrada de reproducción no establece sesiones de cookies independientes con los servidores de destino. Los registros HTTP nativos omiten solicitudes completas, encabezados y valores de cookies.

`NetRequest` aplica el mismo límite a los cuerpos de solicitud. Una redirección a otro origen falla en lugar de reenviar el cuerpo, salvo que el cuerpo se configure con `body(cuerpo, true)`. Una respuesta 303 cambia sólo ese envío a GET sin cuerpo; el builder conserva su método y cuerpo para envíos posteriores.

Las redirecciones sólo se mueven entre http y https, en cualquier dirección: un `Location` que apunte a un recurso `file:`, `jar:` o `ftp:` hace fallar la solicitud, y sólo la primera solicitud puede nombrar un archivo local. Las URLs tomadas del JSON de las plataformas también deben ser enlaces web. Las entradas de playlists pueden usar cualquier esquema de streaming que FFmpeg entienda (`rtsp`, `rtmp`, `udp`, `srt`), pero nunca un archivo local ni un archivo comprimido, salvo que la playlist misma sea un archivo local. Los encabezados que fija el llamador ganan sobre los predeterminados, User-Agent incluido, y el Referer derivado conserva un puerto explícito. `inputStream()` y `text()` devuelven el cuerpo de un estado de error en lugar de lanzar, el cuerpo sólo se lee una vez, `json(type)` acepta todos los tipos de contenido JSON (`application/json`, `text/json`, subtipos `+json`) y un cuerpo de texto que llega a cuentagotas se detiene después de cuatro tiempos de lectura en total. Ni el builder ni la respuesta son seguros para varios hilos.

## Imágenes y contenedores DDS

`CodecsAPI.reader` elige un lector interno según los primeros bytes y lo devuelve como `ImageReader`. Las estructuras de chunks PNG y GIF de `api.codecs.common` son internas y conservan sus serializadores para futuros escritores de animación. Ejecuta `gradle javadocInventory` desde el raíz del proyecto para listar la documentación faltante de métodos públicos con archivo y número de línea; la tarea sólo reporta los hallazgos.

```java
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.util.PixelFormat;
import java.nio.ByteBuffer;

try (ImageReader reader = CodecsAPI.reader(encodedBuffer)) {
    PixelFormat format = reader.pixelFormat();
    while (reader.hasNext()) {
        ByteBuffer pixels = reader.next();
        // CONSUME OR COPY BEFORE THE NEXT FRAME REUSES THE READER'S BUFFER.
    }
}
```

Todos los decodificadores entregan un solo búfer BGRA compacto por cuadro, salvo los JPEG en escala de grises, que entregan un byte de luma por píxel (`GRAY`); `CodecsAPI.reader(buffer, format)` pide `BGRA`, `RGBA` o `GRAY` y el lector convierte cuando no es su propio formato, así que `pixelFormat()` e `ImageData.format()` siempre dicen qué contienen los bytes, y cualquier otro formato se rechaza con `IllegalArgumentException`. `hasNext()` y `next()` siguen las reglas de un iterador: `next()` después del último cuadro lanza `NoSuchElementException`, `hasNext()`, `next()`, `reset()` y `readAll()` sobre un lector cerrado lanzan `IllegalStateException` mientras los descriptores siguen legibles, y `close()` es idempotente y el único método seguro desde otro hilo. `delay()` es el retardo del cuadro que `next()` acaba de devolver, `repeat()` cuenta reproducciones totales (`ImageData.REPEAT_FOREVER`, 0, repite para siempre), `variableFrameRate()` es falso con un solo cuadro, y `metadata()` devuelve una instantánea congelada que nunca cambia bajo el consumidor. Los cuadros JPEG y WebP salen ya rotados según su orientación EXIF, con `width()` y `height()` intercambiados cuando corresponde. La metadata usa valores Java simples bajo claves compartidas (`CodecsAPI.METAKEY_ICC_PROFILE`, `METAKEY_EXIF`, `METAKEY_XMP`, `METAKEY_ORIENTATION`, `METAKEY_LOOP_COUNT`) más claves por formato como `PNG_METAKEY_*`; un chunk auxiliar malformado o demasiado grande se omite y se registra una vez, nunca es fatal. Un rasgo válido que este decodificador no implementa (un chunk crítico PNG desconocido, un JPEG sin pérdida o CMYK, un tipo de tupla PAM desconocido) lanza `UnsupportedFormatException`, subtipo de `CodecException`, para que el host distinga "caer a otro motor" de "archivo roto". Netpbm cubre las variantes de texto `P1`..`P3` y las binarias `P4`..`P7`. `readAll()` conserva copias de los cuadros y limita el total de bytes decodificados por imagen; `CodecsAPI.decode` también acepta un `ByteBuffer` o un `InputStream`, y `sniff` un `ByteBuffer`. El búfer de origen se comparte con el lector, no se copia, así que no debe cambiar hasta que el lector se cierre, y cuando un lector rechaza su contenido la posición del búfer queda donde estaba. Varios lectores simultáneos siguen consumiendo memoria por separado: los reproductores coordinan su decodificación y reutilizan las imágenes decodificadas mediante sus cachés, mientras que el código que usa los decodificadores directamente gestiona su propia concurrencia.

`CodecsAPI.writer(...)` devuelve un `ImageWriter` experimental que codifica VP9 dentro de WebM; requiere FFmpeg y aplana el canal alfa.

`BcReader` lee bloques BC1, BC3 y BC7 ya comprimidos dentro de un arreglo de texturas DDS con extensión DX10. Crea un `org.watermedia.api.codecs.BcReader` directamente para acceder a los bloques; `CodecsAPI.reader` no abre archivos DDS. No necesita un codificador nativo, pero los motores integrados OpenGL, Vulkan y de software no aceptan texturas BC, así que leer bloques DDS no implica soporte completo de reproducción. El pie de animación de WaterMedia aporta los tiempos por cuadro cuando existe; las capas DDS ordinarias tienen duración cero. Se rechazan cadenas de mipmaps, volúmenes y mapas de cubo. No hay codificador BC ni opción de caché de texturas recodificadas. `CodecsAPI.available(...)` informa soporte de decodificación de píxeles, no soporte de formatos de textura en la GPU.

## Reproducción sincronizada con Bridge

WaterMedia implementa el protocolo de sincronización y la corrección de tiempo; tu integración proporciona el transporte. Implementa `Bridge.send(ByteBuffer)` para enviar los bytes y entrega cada mensaje entrante a `player.sync(payload)` en la sesión correspondiente.

El bridge de la autoridad envía hacia todos sus seguidores; el bridge del seguidor envía hacia la autoridad. Tu transporte identifica y autentica a los participantes y decide qué controles permite. Si encolas el contenido para usarlo después de la llamada, copia los bytes. Las implementaciones de `Bridge` deben ser seguras entre hilos y no bloquear.

```java
import org.watermedia.api.media.ServerMediaPlayer;
import org.watermedia.api.media.Config;

ServerMediaPlayer authority = MediaAPI.serverPlayer(downstreamBridge,
        Config.Capability.LOCKSTEP, Config.Capability.CONTROLS);
authority.start();
MediaPlayer follower = MediaAPI.player(mrl, gfxSupplier, sfxSupplier, upstreamBridge);
if (follower == null) throw new IllegalStateException("Follower media is unavailable");
```

Los bridges y proveedores del ejemplo los construye la aplicación anfitriona. Usa `authority.sync(payload)` para lo recibido de un seguidor y `follower.sync(payload)` para lo recibido de la autoridad. Libera ambos reproductores cuando termine la sesión que representan. `MediaAPI.follower(upstreamBridge)` construye un seguidor sin medios (un reloj) para HUDs, réplicas de lógica y pruebas del protocolo; `MediaAPI.serverPlayer()` construye un reloj simple sin bridge. Los motores y reproductores no tienen constructores públicos: toda instancia sale de `MediaAPI`.

### Secuencia de sincronización

1. El seguidor se anuncia y entra como un miembro que todavía está cargando. Su llegada no pausa inmediatamente a los demás.
2. La autoridad responde con los permisos de la sesión y una instantánea del estado actual.
3. El seguidor reporta sus transiciones y mantiene su registro mediante mensajes periódicos. La autoridad adopta la duración o condición de transmisión en vivo informada por un seguidor que ya dispone de esos datos. Mientras no haya duración ni una transmisión en vivo identificada, su reloj permanece en cero.
4. La autoridad difunde cambios y repite una instantánea aproximadamente cada cinco segundos. Los seguidores descartan revisiones anteriores (comparadas por distancia con signo, así que una autoridad de reemplazo, cuya revisión arranca de su reloj, toma el mando); una revisión igual puede actualizar la referencia temporal.
5. Al liberarse, el seguidor envía su despedida. La autoridad descarta a quienes dejan de reportarse según `followerTimeout(ms)`, con 15 segundos por defecto.

El tiempo de desconexión debe estar entre 1 y `Long.MAX_VALUE / 1_000_000` milisegundos. Los valores fuera de ese rango se rechazan sin cambiar el tiempo configurado.

La autoridad conserva la primera duración positiva recibida durante su sesión. Cuando los reportes identifican una transmisión en vivo, un reporte posterior con `live=false` no elimina esa clasificación.

`sync(...)` valida y consume el mensaje en el hilo que lo invoca; un reloj simple sin bridge ignora el tráfico de sesión, y un seguidor que acaba de despedirse (`Unwatch`) no vuelve a registrarse por un reporte que seguía en vuelo. El seguidor aplica el estado recibido en su ciclo de sincronización de 50 ms. La autoridad puede procesar controles y enviar respuestas durante la llamada: no supongas que todo el trabajo se difiere a ese ciclo. Los callbacks del bridge también pueden ejecutarse desde el hilo de una llamada de control.

### Permisos

| Capacidad | Comportamiento |
| --- | --- |
| `LOCKSTEP` | La autoridad muestra `BUFFERING` y congela el reloj mientras un seguidor que ya estaba listo necesita cargar o almacenar más datos. Reanuda desde la misma posición, ignora seguidores fallidos y no espera inmediatamente por quienes acaban de entrar. |
| `CONTROLS` | Permite solicitar cambios compartidos de reproducción. Las llamadas del seguidor, como start, pause, seek, speed y repeat, viajan a la autoridad y no se aplican localmente. Una vez recibidos los permisos, las solicitudes sin esta capacidad se descartan. |
| `VOLUME` | Sincroniza volumen y silencio; sin esta capacidad, ambos permanecen locales. |

Consulta `player.granted(capability)` para adaptar la interfaz a los permisos recibidos. El transporte debe validar quién puede enviar cada mensaje. El escalado, el nivel de detalle y el audio espacial siempre permanecen locales. El avance manual por cuadros no se permite en seguidores.

### Ajustes y diagnóstico

`tolerance(ms)` define el desfase permitido; el valor predeterminado es un segundo. Cuando corresponde corregirlo, el seguidor usa `seekQuick` y limita la frecuencia de las correcciones mientras la reproducción se acomoda. No corrige durante carga o buffering. En contenido finito con repetición, el cálculo considera la vuelta del ciclo para no interpretar su frontera como un salto enorme.

`authority()` devuelve la última instantánea recibida. `authorityTime()` estima la posición actual a partir de ella y del tiempo transcurrido. `drift()` muestra el desfase y `role()` identifica si el reproductor es independiente, autoridad o seguidor. La corrección no altera la velocidad para converger gradualmente.

### Formato de los mensajes

Los mensajes son records de tamaño fijo y orden big-endian en el paquete `org.watermedia.api.media`. `Packet.read(ByteBuffer)` consume sólo los bytes del mensaje, dejando intactos los bytes posteriores para que tu transporte pueda incluir campos adicionales; el orden de bytes del buffer no importa y un mensaje rechazado deja la posición intacta.

| Mensaje | Tamaño | Dirección |
| --- | ---: | --- |
| `Sync` | 29 bytes | Autoridad → seguidores |
| `Config` | 11 bytes | Autoridad → seguidores |
| `Watch` / `Unwatch` | 10 bytes | Seguidor → autoridad |
| `Report` | 20 bytes | Seguidor → autoridad |
| `Control` | 19 bytes | Seguidor → autoridad |

## Ejemplos compilados y verificación

Los loggers del proyecto se llaman `watermedia` y `watermedia_binaries`; configura esos nombres para obtener diagnósticos DEBUG. Los mensajes reducen las URLs multimedia a su origen, ocultan los valores de encabezados y enmascaran tokens de acceso sueltos, valores `Authorization`, argumentos de sesión de Minecraft y pares `clave=valor` con pinta de secreto (`DataTool.redact` aplica el mismo filtro a las subidas de logs de la app). Las trazas con URLs privadas conservan el tipo, la pila, las causas y las excepciones suprimidas como texto censurado. Nada se acumula por ventanas de tiempo: una anomalía que puede repetirse, como un búfer de cuadro corto, un formato rechazado, un callback que falla o una subida rechazada, se registra la primera vez que ocurre en cada motor, player, servidor o lista de reproducción y después calla, y las iteraciones lentas de reproducción se cuentan y reportan una sola vez al terminar la ejecución. FFmpeg todavía escribe sus propios mensajes nativos en la salida de error del proceso, fuera de los loggers del proyecto y de su censura; el enrutamiento por el logger del proyecto está preparado pero desactivado hasta validarlo en macOS y Linux.

[ApiGuideExample.java](../src/test/java/org/watermedia/ApiGuideExample.java) contiene ejemplos compilados de las fábricas de motores y de ambos roles de sincronización. [ApiGuideExampleTest.java](../src/test/java/org/watermedia/ApiGuideExampleTest.java) ejecuta el ejemplo de decodificación de imágenes, que filtra cada cuadro con un `DownscaleFilter`. Los ejemplos de esta guía usan variables del anfitrión; no son una aplicación completa para copiar y ejecutar sin esa integración.

Las pruebas de reproducción y audio espacial usan FFmpeg y OpenAL reales cuando están disponibles; las ejecuciones con `require_natives=true` exigen los binarios de FFmpeg. La integración visual y acústica en Minecraft, los contextos gráficos y los dispositivos de audio todavía requieren comprobación en el entorno anfitrión.
