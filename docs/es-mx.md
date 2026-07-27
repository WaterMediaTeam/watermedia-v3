# MRL
MRL es un wrapper de URI, actúa como una galería. MediaAPI captura y gestiona las instancias, asegurando una única instancia de MRL para cada URL igual.
```java
MRL mrl = MediaAPI.getMRL("https://imgur.com/gallery/abc123");
// EN CASO DE QUE QUIERAS PRE-CARGAR MUCHAS URLs COMO UN PLAYLIST, PUEDES HACERLO USANDO
MRL.preload(URI.create("https://example.com/video1.mp4"), URI.create("https://example.com/video2.mp4"));
```

## ESTADOS
MRL tiene 3 estados diferentes
- `mrl.busy()` - El MRL aún está cargando sources desde la plataforma. Aún no está listo y no ha ocurrido ningún error.
- `mrl.ready()` - Los sources se han cargado exitosamente. Ahora puedes acceder a los sources y crear players.
- `mrl.error()` - La carga falló. La plataforma no pudo resolver la URI, o una excepción ocurrió durante la carga.

En caso de que necesites una forma bloqueante de obtener el estado ready,
puedes usar `mrl.await(timeout)`, retornará si el MRL está `busy` o no,
depende de ti checar si tiene `error()` o no.

## Sources e Indexes
Los MRLs pueden contener múltiples sources.
Cada source representa una pieza individual de media (un video, una imagen o un audio track).
Los sources se acceden por su index (posición base-cero en el array).
```java
// TODOS LOS SOURCES
MRL.Source[] sources = mrl.sources();

// CANTIDAD DE SOURCES
int count = mrl.sourceCount();

// OBTENER UN SOURCE ESPECÍFICO SI EXISTE (RETORNA NULL CUANDO NO HAY SOURCE)
MRL.Source source = mrl.source(0);  // PRIMERO
MRL.Source second = mrl.source(1);  // SEGUNDO

// OBTENER EL PRIMER SOURCE POR TIPO
MRL.Source video = mrl.videoSource(); // VIDEO
MRL.Source image = mrl.imageSource(); // IMAGEN
MRL.Source audio = mrl.audioSource(); // AUDIO
MRL.Source[] videos = mrl.sourcesByType(MRL.MediaType.VIDEO);  // TODOS LOS VIDEO SOURCES
```

## MÚLTIPLES SOURCES
Algunas plataformas soportadas por watermedia (como imgur) resuelven una sola URL en múltiples sources.
Resultando en esto:
```
URL: https://imgur.com/gallery/abc123
  -> Source[0]: IMAGEN (cat.png)       <- index 0
  -> Source[1]: VIDEO (dog.mp4)        <- index 1
  -> Source[2]: IMAGEN (bird.gif)      <- index 2
```

Para abrir un source específico, necesitas especificar el index del source
```java
MRL mrl = MediaAPI.getMRL("https://imgur.com/gallery/abc123");
// CHECAR QUE ESTÉ LISTO Y SIN ERRORES

// CREAR UNA INSTANCIA DE PLAYER PARA EL SEGUNDO SOURCE, EN ESTE CASO, UN VIDEO DE PERRO
MediaPlayer player = mrl.createPlayer(1, renderThread, renderThreadEx, glEngine, alEngine, true, true);
```
-# NOTA: el método error() siempre retorna true cuando no hay sources disponibles a pesar de ser válidos y resolubles


# MEDIA PLAYERS
Los MediaPlayers ahora solo pueden ser creados ÚNICA Y EXCLUSIVAMENTE por MRLs, así que siempre necesitas usar MRLs.
El uso es simple
```
// EL INDEX SE PUEDE OMITIR PARA SIEMPRE ELEGIR EL PRIMER INDEX
MediaPlayer player = mrl.createPlayer(renderThread, renderThreadEx, glEngine, alEngine, true, true);

// INDEX ESPECIFICADO
MediaPlayer player = mrl.createPlayer(2, renderThread, renderThreadEx, null, null, true, false);
```
El método puede retornar NULL si el source index no existe (el MRL solo tiene 2 sources) y si no hay un engine disponible para ese tipo de source (te falta el JAR de Binarios de WaterMedia o FFMPEG falló al cargar)

Puede retornar 3 variantes de un media player
- TxMediaPlayer: Un MediaPlayer basado en texturas (imágenes e imágenes animadas)
- FFMediaPlayer: Usa FFmpeg como backend para playback completo de video/audio.
- ServerMediaPlayer: un player headless de wall-clock que actúa como la autoridad de tiempo del lado del server para playback sincronizado (ver PLAYBACK SINCRONIZADO abajo)

### ARGUMENTOS
- sourceIndex (int) - el index del source a reproducir
- renderThread (Thread) - la instancia del render thread (disponible en ``Minecraft.thread``)
- renderThreadEx (Executor) - el executor que corre tasks en el render thread (``Minecraft.getInstance()``)
- glEngine (GLEngine) - la instancia que creas con MediaAPI.glEngine(renderThread, renderExecutor). El engine es autocontenido: captura y restaura el estado GL del host alrededor de cada subida, así que GlStateManager (o el tracker de Sodium) nunca se desincroniza y no hace falta ningún proxy.
- alEngine (ALEngine) - por ahora, esto no necesita ser creado y puedes pasarle un null
- video (boolean) - habilita el output de video, útil para no desperdiciar recursos de GPU en un player de solo audio, esto solo desactiva el soporte, no fuerza al media a tener o encontrar el media
- audio (boolean) - igual que video

## GLEngine y ALEngine
Estos engines abstraen las llamadas de OpenGL/OpenAL para que diferentes plataformas (versiones de Minecraft, custom renderers) puedan proveer sus propias implementaciones:

## GLEngine se encarga de:
Creación de texturas con filtering apropiado (LINEAR) y wrapping (CLAMP_TO_EDGE)
Upload de texturas con double-buffer PBO (Pixel Buffer Object) para performance
Eliminación de texturas

## ALEngine se encarga de:
Creación de audio sources y buffers
Upload de audio en streaming con buffer queuing
Control de volumen, velocidad, pausa/play
Cleanup de recursos

# Estado del MediaPlayer
Un MediaPlayer pasa por estos estados durante su lifecycle:

### Status
- WAITING - Player creado, esperando recursos o condiciones para comenzar la carga.
- LOADING - Cargando activamente datos de media desde la red o disco.
- BUFFERING - Buffereando datos para asegurar un playback fluido.
- PLAYING - El playback se está reproduciendo activamente.
- PAUSED - El playback está pausado, se puede resumir.
- STOPPED - Playback detenido, se puede reiniciar desde el inicio.
- ENDED - El media llegó al final. Se puede reiniciar o va a loopear si el repeat está habilitado.
- ERROR - Ocurrió un error. El playback no puede continuar.

Puedes checar el status usando `player#status()` o los métodos de conveniencia

# PLAYBACK SINCRONIZADO (BRIDGE)
WaterMedia entrega el sistema de sync completo; tú entregas el carrier de bytes. Un **`Bridge`** es un solo método, `send(ByteBuffer)`, y todo lo que recibas del otro lado se lo pasas a `player.sync(payload)`. No hay loop que escribir, ni estado que pollear, ni matemática de corrección que implementar.

Nada sobre quiénes son los pares llega a WaterMedia: tu bridge sabe a qué sesión sirve y rutea en consecuencia, por eso la forma natural es una clase pequeña que guarde esa llave en vez de un lambda.

```java
public final class MediaBridge implements Bridge {
    private final ResourceLocation session;
    public MediaBridge(ResourceLocation session) { this.session = session; }
    public void send(ByteBuffer payload) { Network.send(this.session, payload); }
}
```

Un player creado con bridge deja de ser independiente:
- En el server, `ServerMediaPlayer` se vuelve la **autoridad**: registra espectadores, broadcastea su estado y aplica peticiones de control.
- En el cliente, cualquier player se vuelve un **follower**: replica la autoridad y se mantiene alineado con ella. Sus llamadas de control (`start`, `pause`, `seek`, `speed`, `repeat`…) ya no se aplican localmente — viajan como petición hacia arriba y regresan como estado autoritativo.

```java
// SERVER — TU BRIDGE BROADCASTEA LOS BYTES A TODOS LOS CLIENTES VIENDO ESTE MEDIA
ServerMediaPlayer server = MediaAPI.createPlayer(new MediaBridge(session), Capability.LOCKSTEP);
server.start();                 // ESO ES TODO — SNAPSHOTS Y HEARTBEATS SALEN SOLOS

// HANDLER DE PACKETS DEL SERVER
server.sync(payload);

// CLIENTE — TU BRIDGE MANDA LOS BYTES AL SERVER
MediaPlayer player = MediaAPI.createPlayer(mrl, gfx, sfx, new MediaBridge(session));

// HANDLER DE PACKETS DEL CLIENTE
player.sync(payload);
```
`sync(ByteBuffer)` es seguro desde tu network thread: ahí decodifica y valida (la frontera de confianza), y todo lo demás ocurre en el tick propio de 50ms de WaterMedia, nunca en el hilo del juego.

### LA SECUENCIA
1. Se crea el player del cliente y se anuncia con un saludo; entra a la sesión como espectador cargando. Un recién llegado nunca interrumpe el media que ya corre para los demás.
2. La autoridad responde con el `Config` de la sesión (las capabilities otorgadas) más un snapshot fresco, así el que llega tarde aterriza de inmediato en el timestamp correcto.
3. Cada follower reporta su propio estado hacia arriba en cada transición, más un keepalive. El primer cliente que conoce el media reporta su duración y su flag de live, y la autoridad los adopta (el primer reporte no-cero gana por sesión).
4. La autoridad broadcastea un snapshot cada vez que su estado cambia, más un heartbeat de ~5s. Los packets fuera de orden se rechazan por revision; los heartbeats se re-aplican.
5. Al hacer release el follower se despide. El que desaparece sin decirlo lo barre un timeout de silencio (`watcherTimeout(ms)`, 15s por defecto), así un cliente que se cae nunca congela a la audiencia.

### CAPABILITIES
Las capabilities se declaran en la autoridad y se anuncian a todos los followers; el reloj y el espejo de estado siempre están activos. Los followers pueden leer qué se les otorgó con `player.granted(capability)` — útil para apagar un control que la audiencia no tiene permitido manejar.

- `LOCKSTEP` — una sola experiencia para todos: mientras algún espectador ya listo esté cargando o bufereando, la autoridad presenta `BUFFERING` con el reloj congelado y toda la audiencia espera; se reanuda exactamente donde se congeló. Los clientes fallidos se ignoran, y el espectador que entra a mitad de playback solo empieza a contar cuando reporta estar listo.
- `CONTROLS` — los followers pueden manejar la sesión: un `pause()` en cualquier cliente viaja a la autoridad, que decide y broadcastea el resultado a todos. Los permisos son tuyos: filtra antes de llamar `sync()`, o controla tu propia UI. Sin esta capability, las llamadas de control en un follower simplemente se descartan.
- `VOLUME` — la autoridad también dicta volumen y mute. Sin ella ambos quedan locales del cliente, como el escalado y el LOD siempre lo están.

### AJUSTES
Un solo umbral, una sola corrección: si el drift pasa la tolerancia (`tolerance(ms)`, 1s por defecto), la reproducción salta a donde está la sesión con un `seekQuick`. Nada más — sin recortar la velocidad para converger suavemente. Eso se probó y se descartó: se percibe como que el video a veces va lento, y de todos modos el cliente solo puede quedarse *atrás*, porque la autoridad es un reloj pelón sin decodificación ni buffers. El drift es circular en media con repeat para que la frontera del loop nunca finja un hueco enorme, las correcciones tienen rate-limit mientras el pipeline se reacomoda, y ninguna corre mientras el player local carga o buferea.

Corregir necesita dos posiciones: dónde **debería** estar la reproducción y dónde está realmente. `authorityTime()` es la primera — el último snapshot envejecido hasta una posición viva, porque los snapshots llegan con segundos de separación y un objetivo viejo jalaría cada corrección hacia atrás; `authority()` te entrega ese snapshot crudo. La segunda es el player mismo, con su propio estado (puede seguir en LOADING) y su propia posición de decodificación. El hueco se inspecciona con `drift()`, y qué es este player con `role()`.

### EL WIRE
Los packets son records pequeños de tamaño fijo y big-endian en `org.watermedia.api.media.players.sync`, decodificados por `Packet.of(ByteBuffer)`: `Sync` (29 B, el snapshot autoritativo), `Config` (11 B), `Watch`/`Unwatch` (10 B), `Report` (20 B) y `Control` (19 B). El decode toma solo los bytes del propio packet y deja el resto en el buffer, así que puedes embeber un payload dentro de un frame más grande con tus propios campos de ruteo.