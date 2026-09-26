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

El escalado y el nivel de detalle afectan los formatos de píxeles compatibles; las texturas BC se entregan con las dimensiones codificadas. Llama a `player.release()` cuando elimines la superficie o cierres su sesión, incluso si la reproducción falló.

## Selección de motores

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

El audio espacial se configura con `SpatialAudio` y un procesador opcional del entorno. La [guía de audio espacial y Sound Physics Remastered](SPATIAL_AUDIO.es-mx.md) incluye el adaptador, los requisitos de OpenAL, la frecuencia de actualización y las opciones del mod que pueden impedir que se apliquen efectos. La posición y los efectos pertenecen al oyente local y no viajan por el protocolo de sincronización.

## HTTPS y certificados

La reproducción HTTPS requiere las capacidades de verificación del paquete incluido o de una compilación personalizada compatible. FFmpeg verifica la cadena de certificados y la identidad DNS o IP con una copia de las autoridades de confianza de Java tomada durante el arranque. Para servidores privados, configura las propiedades estándar `javax.net.ssl.trustStore` antes de iniciar WaterMedia. Un almacén vacío o inválido impide inicializar FFmpeg; la verificación no se desactiva automáticamente.

`MediaAPI` crea el archivo PEM temporal, comprueba que el binario tenga las opciones necesarias y las aplica tanto a la entrada principal como a la pista de audio separada. Las autoridades también se transmiten a las solicitudes internas de HLS y DASH. El módulo de medios elimina el archivo al cerrar, después de liberar todos los reproductores. Un `customPath` vacío no añade el directorio de trabajo a la búsqueda de bibliotecas nativas.

Se comparten autoridades de confianza; el transporte sigue siendo OpenSSL. No se transfieren fábricas de sockets JSSE personalizadas, callbacks de validación de nombres ni políticas de revocación de Java. Tampoco se modifica la lista de protocolos permitidos.

Las credenciales pertenecen al origen de la solicitud inicial: esquema, nombre de host y puerto efectivo. En redirecciones y solicitudes internas de HLS/DASH, `Authorization`, `Proxy-Authorization`, `Cookie`, `Cookie2` y `X-WaterMedia-Token` sólo se envían a ese origen. Al cambiar de origen se genera un encabezado `Host` nuevo; otros encabezados, como User-Agent, Accept, Referer y Origin, pueden acompañar las solicitudes a una CDN.

Las cookies generadas siguen el mismo límite: las respuestas `Set-Cookie` de otro origen se ignoran, incluso si una redirección regresa después al servidor inicial. Una entrada de reproducción no establece sesiones de cookies independientes con los servidores de destino. Los registros HTTP nativos omiten solicitudes completas, encabezados y valores de cookies.

## Imágenes y contenedores DDS

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

`BCReader` lee bloques BC1, BC3 y BC7 ya comprimidos dentro de un arreglo de texturas DDS con extensión DX10. No necesita un codificador nativo; el motor gráfico receptor debe admitir el formato de bloques. El pie de animación de WaterMedia aporta los tiempos por cuadro cuando existe; las capas DDS ordinarias tienen duración cero. Se rechazan cadenas de mipmaps, volúmenes y mapas de cubo. No hay codificador BC ni opción de caché de texturas recodificadas. `CodecsAPI.available(...)` informa soporte de decodificación de píxeles, no soporte de formatos de textura en la GPU.

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

[ApiGuideExample.java](../src/test/java/org/watermedia/test/docs/ApiGuideExample.java) contiene ejemplos compilados de las fábricas de motores y de ambos roles de sincronización. [ApiGuideExampleTest.java](../src/test/java/org/watermedia/test/docs/ApiGuideExampleTest.java) ejecuta el ejemplo de imagen sin contexto gráfico. Los ejemplos de esta guía usan variables del anfitrión; no son una aplicación completa para copiar y ejecutar sin esa integración.

Las pruebas de reproducción y audio espacial usan FFmpeg y OpenAL reales cuando están disponibles; las ejecuciones con `require_natives=true` exigen los binarios de FFmpeg. La integración visual y acústica en Minecraft, los contextos gráficos y los dispositivos de audio todavía requieren comprobación en el entorno anfitrión. El [informe técnico en inglés](technical-review-2026-09-06.md) registra la validación de binarios y las limitaciones pendientes, incluido el empaquetado de dependencias.
