# Guía de la API de WaterMedia

[English](en-us.md) · Español (México)

WaterMedia resuelve direcciones de contenido multimedia, decodifica sus datos y entrega cuadros de video y muestras de audio a los motores de salida. Requiere Java 17 o posterior. Los puntos de entrada de los loaders de Minecraft realizan el arranque; una aplicación que integra la biblioteca directamente debe iniciarla después de preparar su entorno y sus dependencias.

## Arranque y propiedad de los recursos

```java
import org.watermedia.WaterMedia;
import org.watermedia.WaterMedia.BootStatus;

WaterMedia.start("My application", temporaryDirectory, workingDirectory, true);
BootStatus snapshot = WaterMedia.status();
```

El último argumento indica si el entorno es cliente. Lee `snapshot.state()` y `snapshot.failures()` en la misma instantánea:

| Estado | Significado |
| --- | --- |
| `STOPPED` | No hay una sesión activa. |
| `STARTING` | Los servicios todavía están iniciando. |
| `READY` | El arranque terminó sin fallas registradas. |
| `DEGRADED` | El arranque terminó, pero algún servicio u operación opcional falló. |
| `FAILED` | Falló el arranque esencial o quedó una limpieza incompleta. |
| `STOPPING` | La sesión está cerrando sus servicios. |

`DEGRADED` no garantiza que todos los formatos puedan reproducirse: consulta la capacidad que necesitas, como `MediaAPI.ffmpegLoaded()`. Los servidores dedicados omiten los módulos de cliente y la extracción de sus binarios. La decodificación de imágenes en Java mediante `CodecsAPI` no requiere este arranque.

Cada reproductor es dueño de los motores que recibe. Créalos mediante proveedores (`Supplier`) para que una fuente no disponible no deje recursos nativos reservados. No compartas un motor entre reproductores.

El módulo de binarios requiere WaterMedia y comparte este ciclo de vida. Es obligatorio en clientes de
Minecraft y opcional en servidores dedicados. Después de cargar la configuración, su arranque bloquea
los demás servicios hasta terminar; si está instalado y falla, se
detiene el arranque. Las aplicaciones que integran directamente la API Java pueden omitirlo y conservar
imágenes y plataformas sin ejecutables. Desactivar FFmpeg omite su extracción. `WaterMedia.stop()` limpia
las rutas después de detener a sus consumidores. No existe una API separada para iniciar o detener los binarios.

Las distribuciones personalizadas compatibles de FFmpeg pueden declarar GPL, LGPL u otra licencia y
versión. El cargador comprueba integridad y capacidades nativas, sin una lista de versiones o licencias
permitidas. El pegamento JNI debe seguir siendo compatible con la API Java que usa WaterMedia.

La aplicación anfitriona es dueña de sus contextos OpenGL, Vulkan y OpenAL. Libera cada reproductor en los hilos y contextos correspondientes antes de llamar a `WaterMedia.stop()`: el cierre se rechaza mientras haya reproductores abiertos. Si una limpieza falla, conserva el contexto necesario y vuelve a intentar el cierre. Después de recargar un dispositivo o contexto debes crear motores nuevos. Reiniciar WaterMedia no descarga las bibliotecas JNI ni permite cambiar su versión dentro de la misma JVM.

## Requisitos de ejecución y configuración

La API Java requiere Java 17. Proporciona WaterConfig, Log4j, Gson, JOML y las dependencias gráficas y de audio que use el host. WaterMedia incluye WaterConfig como un JAR anidado para Forge, NeoForge y Fabric. El launcher independiente extrae ese JAR a un archivo temporal y lo agrega al classpath de la JVM relanzada; descarga las demás bibliotecas de su lista cuando hacen falta, y una biblioteca en caché o descargada sólo entra al classpath si coincide con el SHA-256 fijado al compilar. Una aplicación que integra la API debe preparar su propio classpath. El JAR distribuido de Binaries incluye su dependencia XZ con sus paquetes reubicados. Las dependencias de Gradle para desarrollo no demuestran que una instalación de Minecraft tenga las demás bibliotecas. Forge, NeoForge y Fabric cargan WaterMedia en clientes y servidores dedicados, y rechazan las versiones de Minecraft fuera de la lista publicada. WaterMedia y Binaries se aceptan entre sí desde 3.0.0 hasta antes de 3.1.0; las versiones BETA anteriores de 3.0.0 se rechazan. Fabric no puede limitar una dependencia a clientes, así que su arranque exige Binaries en clientes y su manifiesto rechaza versiones incompatibles de Binaries.

La configuración se carga antes de preparar los binarios. Revisa el archivo generado y el classpath real del host antes de diagnosticar un motor no disponible. Estas rutas corresponden a campos de `WaterMediaConfig`; los tamaños usan MiB (1,048,576 bytes), aunque la configuración los identifique como MB.

| Ajuste | Valor predeterminado y límites relacionados |
| --- | --- |
| `media.ffmpeg.disable` | `false`; activarlo omite la preparación e inicialización de FFmpeg. Reinicia la sesión después de cambiar ajustes de arranque. Las imágenes siguen siendo independientes de FFmpeg. |
| `media.ffmpeg.customPath` | Vacío; un directorio explícito debe contener bibliotecas nativas compatibles, no el programa de consola `ffmpeg.exe`. Se siguen exigiendo las capacidades corregidas de TLS y origen de credenciales. |
| `media.ffmpeg.hardwareAccel` | `true`; su uso depende también del binario, controlador, códec y motor de salida. Sigue disponible la decodificación por software. |
| `media.ffmpeg.analyzeDuration` / `probeSize` | 7,000 ms / 10 MiB; se aplican a la entrada principal y al audio separado. Una duración de análisis de cero conserva el valor de FFmpeg. Aumentarlos puede retrasar el inicio. |
| `decoders.maxImageSourceSize` | 128 MiB de imagen codificada. Es independiente del límite de 512 MiB de datos decodificados del lector. |
| `media.tx.texturesBudget` | 32 MiB; la precarga exige además un motor compatible, una cantidad válida de cuadros y que tanto los cuadros originales decodificados como las texturas de salida quepan en el presupuesto. Cero fuerza la reproducción de animaciones cuadro por cuadro. |
| `media.ffmpeg.cache` / `cacheMaxSize` | Activada / 10 MiB por respuesta elegible; no almacena todas las transmisiones ni todas las entradas HLS/DASH. |
| `media.tx.cache` / `media.cacheMaxSize` | Caché de imágenes activada / 8,192 MiB de disco compartido; después de escribir se eliminan entradas viejas si hace falta. Los límites de disco, imagen codificada, imagen decodificada y texturas son independientes. |
| `platforms.allowMatureContent` | `false`; los adaptadores compatibles pueden dejar el recurso en `BLOCKED` sin enviarlo a reproducción. |
| `platforms.searchCacheCleanup` | 15 minutos; cero limpia la caché de búsquedas en cada consulta. |

El servidor opcional de archivos arranca si `network.forceEnableServer` está activo, o en un entorno de servidor con `network.enableServer` activo. Ambos están desactivados por defecto. Escucha en `127.0.0.1:25572`; aceptar conexiones remotas exige una dirección explícita y un `network.token` personalizado no vacío. `network.remoteHost` es la URL HTTP(S) completa que usa el cliente y no cambia la dirección donde escucha el servidor. El servidor integrado usa HTTP; para HTTPS remoto, el host debe proporcionar la terminación TLS.

Los valores iniciales permiten ocho solicitudes simultáneas, 30 segundos por solicitud del servidor, 8 MiB por carga y 1,024 MiB de almacenamiento total. `maxUploadSize = 0` sólo elimina el límite individual de carga: siguen aplicando la cuota total, la concurrencia y el plazo. Las solicitudes Java tienen aparte 15 segundos de espera, diez redirecciones y un límite de 16 MiB para texto. Las entradas nativas de reproducción tienen su propia política de espera.

## Utilerías compartidas

`IOTool` ofrece utilerías compartidas: `httpsText` y `downloadVerified` con límite de bytes,
`verifySha256` sin borrar archivos, `makeExecutable`, `publishGeneration`/`currentGeneration` y `deleteTree`.
Las descargas exigen HTTPS también en las redirecciones. Las generaciones admiten cualquier nombre
seguro de un hijo directo. `JSONTool.parse` procesa el JSON descargado con el analizador compartido.

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
| `FORGOTTEN` | El recurso fue descartado o su sesión terminó. |

Para código de aplicación o tareas de fondo puedes usar `mrl.await(timeoutMillis)`. Devuelve `true` cuando la carga ya no está pendiente, incluso si terminó con un error; `false` cuando se agota la espera o se interrumpe el hilo mientras espera. No bloquees el hilo del juego para resolver una URL.

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

Para actualizar un MRL, conserva el valor devuelto por `mrl = mrl.reload()`. Si su entrada ya fue descartada, obtienes el recurso vigente para esa dirección y el anterior permanece descartado. Si su sesión terminó, no puede reactivar trabajo dentro de una sesión nueva. Mientras una carga sigue en curso, la recarga se programa para después de que termine.

## Creación y control de reproductores

La fábrica está en `MediaAPI`. Usa el índice de la fuente y proveedores de motores:

```java
import org.watermedia.api.media.players.MediaPlayer;

MediaPlayer player = MediaAPI.createPlayer(mrl, 1,
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
player.maxSize(1280, 720);
```

`speed(value)` admite valores finitos en `(0, 4]` e informa si aceptó el cambio. Java Sound no puede cambiar la velocidad. `canSpeed()` es una consulta pura que cualquier hilo puede hacer en cada frame; `speed(value)` debe ejecutarse con el contexto de audio correspondiente activo, y el reloj del reproductor sólo sigue una velocidad que el motor de audio aceptó. Un seguidor sincronizado devuelve `false` incluso si envía una solicitud de control; ese resultado no confirma su aplicación remota. `spatialAudio(...)` también informa si el motor aceptó la actualización; consulta `spatialAudioSupported()` antes de mostrar controles posicionales.

Los tiempos se expresan en milisegundos y el volumen del reproductor es un porcentaje de 0 a 100. Consulta `canSeek()` antes de ofrecer desplazamiento en transmisiones que no lo permiten. Para diagnóstico usa `status()`, `time()`, `duration()`, `buffered()` y `exception()`.

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
| `MediaAPI.jfxEngine(onFrame)` | Proporcionar JavaFX y enlazar la imagen del motor con la interfaz. |
| `MediaAPI.headlessEngine(preload)` | Recibir cuadros en memoria sin contexto gráfico; útil para procesamiento y pruebas. |
| `MediaAPI.alEngine()` | Mantener el contexto OpenAL activo en los hilos que operan el motor. |
| `MediaAPI.alEngine(true)` | Cumplir el mismo contrato OpenAL; negocia salida mono para sonido posicional. |
| `MediaAPI.jsEngine()` | Disponer de una línea de salida Java Sound; no admite posicionamiento espacial. |

Los motores administran sus propios recursos de reproducción, como texturas, fuentes de audio y buffers. Sus clases base son selladas: la integración proporciona contextos y ejecutores, no implementaciones arbitrarias de esos motores.

El audio espacial se configura con `SpatialAudio` y un procesador opcional del entorno. La [guía de audio espacial y Sound Physics Remastered](#audio-espacial-y-sound-physics-remastered) incluye el adaptador, los requisitos de OpenAL, la frecuencia de actualización y las opciones del mod que pueden impedir que se apliquen efectos. La posición y los efectos pertenecen al oyente local y no viajan por el protocolo de sincronización.

## Audio espacial y Sound Physics Remastered

Crea un motor OpenAL con `MediaAPI.alEngine(true)` antes de construir el reproductor. El modo espacial permanece fijo durante toda la vida del motor: su tabla de canales sólo anuncia audio mono, así que el remuestreador existente de FFmpeg mezcla las fuentes estéreo y envolventes a mono antes de enviarlas a OpenAL. La fábrica `alEngine()` normal negocia canales sin forzar mono; los formatos que el dispositivo no admite aún pueden convertirse al número de canales disponible más cercano. Java Sound devuelve `false` al actualizar el audio espacial y no expone una fuente nativa.

El motor comienza con audio mono seco y relativo al oyente. Proporciona un estado `SpatialAudio` para ubicar la fuente en el mundo. Pasar `null` restaura la reproducción seca y relativa al oyente, pero conserva la decodificación mono. El volumen, el silencio, la velocidad, la pausa y la sincronización son independientes de la configuración espacial.

```java
import org.watermedia.api.media.engines.SFXEngine.SpatialAudio;

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
final MediaPlayer player = MediaAPI.createPlayer(mrl, () -> null, () -> MediaAPI.alEngine(true));
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

`MediaAPI` crea el archivo PEM temporal, comprueba que el binario tenga las opciones necesarias y las aplica tanto a la entrada principal como a la pista de audio separada. Las autoridades también se transmiten a las solicitudes internas de HLS y DASH. El módulo de medios elimina el archivo al cerrar, después de liberar todos los reproductores. Un `customPath` vacío no añade el directorio de trabajo a la búsqueda de bibliotecas nativas.

Se comparten autoridades de confianza; el transporte sigue siendo OpenSSL. No se transfieren fábricas de sockets JSSE personalizadas, callbacks de validación de nombres ni políticas de revocación de Java. Tampoco se modifica la lista de protocolos permitidos.

Las credenciales pertenecen al origen de la solicitud inicial: esquema, nombre de host y puerto efectivo. En redirecciones y solicitudes internas de HLS/DASH, `Authorization`, `Proxy-Authorization`, `Cookie`, `Cookie2` y `X-WaterMedia-Token` sólo se envían a ese origen. Al cambiar de origen se genera un encabezado `Host` nuevo; otros encabezados, como User-Agent, Accept, Referer y Origin, pueden acompañar las solicitudes a una CDN.

Las cookies generadas siguen el mismo límite: las respuestas `Set-Cookie` de otro origen se ignoran, incluso si una redirección regresa después al servidor inicial. Una entrada de reproducción no establece sesiones de cookies independientes con los servidores de destino. Los registros HTTP nativos omiten solicitudes completas, encabezados y valores de cookies.

`NetRequest` aplica el mismo límite a los cuerpos de solicitud. Una redirección a otro origen falla en lugar de reenviar el cuerpo, salvo que el cuerpo se configure con `body(cuerpo, true)`. Una respuesta 303 cambia sólo ese envío a GET sin cuerpo; el builder conserva su método y cuerpo para envíos posteriores.

## Imágenes y contenedores DDS

Las representaciones de chunks PNG implementan `IChunk`: `toBytes()` serializa los datos sin longitud, tipo ni CRC; `toChunk()` devuelve un contenedor `CHUNK` con su tipo y CRC calculado. La interfaz común documenta ambas operaciones heredadas. Las cuatro estructuras GIF implementan su propio `common.gif.IChunk` con `toBytes()`: las paletas emiten ternas RGB, los descriptores emiten su cuerpo y las extensiones gráficas incluyen tamaño y terminador. GIF no tiene un contenedor con CRC como PNG. Sus lectores de descriptores requieren búferes little-endian; la escritura de contenedores PNG requiere big-endian. Se conservan ambos grupos de serializadores para futuros escritores de animación. Los paquetes bajo `org.watermedia.bootstrap` y `org.watermedia.tools` son internos y quedan fuera del inventario de documentación de API pública. Los métodos restantes de la lista revisada ya documentan sus contratos en Javadoc. Ejecuta `gradle javadocInventory` desde el raíz del proyecto para listar la documentación faltante de métodos públicos con archivo y número de línea; la tarea sólo reporta los hallazgos.

```java
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.util.PixelFormat;
import java.nio.ByteBuffer;

try (ImageReader reader = CodecsAPI.decodeImage(encodedBuffer)) {
    while (reader.hasNext()) {
        reader.next();
        PixelFormat format = reader.pixelFormat();
        for (int plane = 0; plane < reader.planeCount(); plane++) {
            ByteBuffer pixels = reader.plane(plane);
            // CONSUME OR COPY BEFORE THE NEXT FRAME REUSES THE READER'S BUFFER.
        }
    }
}
```

Consulta el formato y la cantidad de planos; no supongas que todos los lectores entregan BGRA. `readAll()` conserva copias de los cuadros y limita el total de bytes decodificados por imagen. Varios lectores simultáneos siguen consumiendo memoria por separado.

`BCReader` lee bloques BC1, BC3 y BC7 ya comprimidos dentro de un arreglo de texturas DDS con extensión DX10. Crea un `BCReader` directamente para acceder a los bloques; `CodecsAPI.decodeImage` no abre archivos DDS. No necesita un codificador nativo, pero los motores integrados OpenGL, Vulkan y de software no aceptan texturas BC, así que leer bloques DDS no implica soporte completo de reproducción. El pie de animación de WaterMedia aporta los tiempos por cuadro cuando existe; las capas DDS ordinarias tienen duración cero. Se rechazan cadenas de mipmaps, volúmenes y mapas de cubo. No hay codificador BC ni opción de caché de texturas recodificadas. `CodecsAPI.available(...)` informa soporte de decodificación de píxeles, no soporte de formatos de textura en la GPU.

## Reproducción sincronizada con Bridge

WaterMedia implementa el protocolo de sincronización y la corrección de tiempo; tu integración proporciona el transporte. Implementa `Bridge.send(ByteBuffer)` para enviar los bytes y entrega cada mensaje entrante a `player.sync(payload)` en la sesión correspondiente.

El bridge de la autoridad envía hacia todos sus seguidores; el bridge del seguidor envía hacia la autoridad. Tu transporte identifica y autentica a los participantes y decide qué controles permite. Si encolas el contenido para usarlo después de la llamada, copia los bytes. Las implementaciones de `Bridge` deben ser seguras entre hilos y no bloquear.

```java
import org.watermedia.api.media.players.ServerMediaPlayer;
import org.watermedia.api.media.players.sync.Config;

ServerMediaPlayer authority = MediaAPI.createPlayer(downstreamBridge,
        Config.Capability.LOCKSTEP, Config.Capability.CONTROLS);
authority.start();
MediaPlayer follower = MediaAPI.createPlayer(mrl, gfxSupplier, sfxSupplier, upstreamBridge);
if (follower == null) throw new IllegalStateException("Follower media is unavailable");
```

Los bridges y proveedores del ejemplo los construye la aplicación anfitriona. Usa `authority.sync(payload)` para lo recibido de un seguidor y `follower.sync(payload)` para lo recibido de la autoridad. Libera ambos reproductores cuando termine la sesión que representan.

### Secuencia de sincronización

1. El seguidor se anuncia y entra como espectador que todavía está cargando. Su llegada no pausa inmediatamente a los demás.
2. La autoridad responde con los permisos de la sesión y una instantánea del estado actual.
3. El seguidor reporta sus transiciones y mantiene su registro mediante mensajes periódicos. La autoridad adopta la duración o condición de transmisión en vivo informada por un seguidor que ya dispone de esos datos. Mientras no haya duración ni una transmisión en vivo identificada, su reloj permanece en cero.
4. La autoridad difunde cambios y repite una instantánea aproximadamente cada cinco segundos. Los seguidores descartan revisiones anteriores; una revisión igual puede actualizar la referencia temporal.
5. Al liberarse, el seguidor envía su despedida. La autoridad descarta a quienes dejan de reportarse según `watcherTimeout(ms)`, con 15 segundos por defecto.

El tiempo de desconexión debe estar entre 1 y `Long.MAX_VALUE / 1_000_000` milisegundos. Los valores fuera de ese rango se rechazan sin cambiar el tiempo configurado.

La autoridad conserva la primera duración positiva recibida durante su sesión. Cuando los reportes identifican una transmisión en vivo, un reporte posterior con `live=false` no elimina esa clasificación.

`sync(...)` valida y consume el mensaje en el hilo que lo invoca. El seguidor aplica el estado recibido en su ciclo de sincronización de 50 ms. La autoridad puede procesar controles y enviar respuestas durante la llamada: no supongas que todo el trabajo se difiere a ese ciclo. Los callbacks del bridge también pueden ejecutarse desde el hilo de una llamada de control.

### Permisos

| Capacidad | Comportamiento |
| --- | --- |
| `LOCKSTEP` | La autoridad muestra `BUFFERING` y congela el reloj mientras un espectador que ya estaba listo necesita cargar o almacenar más datos. Reanuda desde la misma posición, ignora seguidores fallidos y no espera inmediatamente por quienes acaban de entrar. |
| `CONTROLS` | Permite solicitar cambios compartidos de reproducción. Las llamadas del seguidor, como start, pause, seek, speed y repeat, viajan a la autoridad y no se aplican localmente. Una vez recibidos los permisos, las solicitudes sin esta capacidad se descartan. |
| `VOLUME` | Sincroniza volumen y silencio; sin esta capacidad, ambos permanecen locales. |

Consulta `player.granted(capability)` para adaptar la interfaz a los permisos recibidos. El transporte debe validar quién puede enviar cada mensaje. El escalado, el nivel de detalle y el audio espacial siempre permanecen locales. El avance manual por cuadros no se permite en seguidores.

### Ajustes y diagnóstico

`tolerance(ms)` define el desfase permitido; el valor predeterminado es un segundo. Cuando corresponde corregirlo, el seguidor usa `seekQuick` y limita la frecuencia de las correcciones mientras la reproducción se acomoda. No corrige durante carga o buffering. En contenido finito con repetición, el cálculo considera la vuelta del ciclo para no interpretar su frontera como un salto enorme.

`authority()` devuelve la última instantánea recibida. `authorityTime()` estima la posición actual a partir de ella y del tiempo transcurrido. `drift()` muestra el desfase y `role()` identifica si el reproductor es independiente, autoridad o seguidor. La corrección no altera la velocidad para converger gradualmente.

### Formato de los mensajes

Los mensajes son records de tamaño fijo y orden big-endian en el paquete `org.watermedia.api.media.players.sync`. `Packet.of(ByteBuffer)` consume sólo los bytes del mensaje, dejando intactos los bytes posteriores para que tu transporte pueda incluir campos adicionales.

| Mensaje | Tamaño | Dirección |
| --- | ---: | --- |
| `Sync` | 29 bytes | Autoridad → seguidores |
| `Config` | 11 bytes | Autoridad → seguidores |
| `Watch` / `Unwatch` | 10 bytes | Seguidor → autoridad |
| `Report` | 20 bytes | Seguidor → autoridad |
| `Control` | 19 bytes | Seguidor → autoridad |

## Ejemplos compilados y verificación

Los loggers del proyecto se llaman `watermedia` y `watermedia_binaries`; configura esos nombres para obtener diagnósticos DEBUG. Los mensajes reducen las URLs multimedia a su origen y ocultan los valores de encabezados. Las trazas con URLs privadas conservan el tipo, la pila, las causas y las excepciones suprimidas como texto censurado. Los rechazos repetidos del servidor y las iteraciones lentas de reproducción se resumen en ventanas de diez segundos; los conteos pendientes se reportan al cerrar.

[ApiGuideExample.java](../src/test/java/org/watermedia/test/docs/ApiGuideExample.java) contiene ejemplos compilados de las fábricas de motores y de ambos roles de sincronización. [ApiGuideExampleTest.java](../src/test/java/org/watermedia/test/docs/ApiGuideExampleTest.java) ejecuta el ejemplo de imagen sin contexto gráfico. Los ejemplos de esta guía usan variables del anfitrión; no son una aplicación completa para copiar y ejecutar sin esa integración.

Las pruebas de reproducción y audio espacial usan FFmpeg y OpenAL reales cuando están disponibles; las ejecuciones con `require_natives=true` exigen los binarios de FFmpeg. La integración visual y acústica en Minecraft, los contextos gráficos y los dispositivos de audio todavía requieren comprobación en el entorno anfitrión.
