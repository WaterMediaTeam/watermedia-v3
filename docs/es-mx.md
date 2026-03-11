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
- ServerMediaPlayer: No es solo un placeholder, es una clase utilitaria para correr un orquestador de tiempo del lado del server, **AÚN NO IMPLEMENTADO**

### ARGUMENTOS
- sourceIndex (int) - el index del source a reproducir
- renderThread (Thread) - la instancia del render thread (disponible en ``Minecraft.thread``)
- renderThreadEx (Executor) - el executor que corre tasks en el render thread (``Minecraft.getInstance()``)
- glEngine (GLEngine) - la instancia que DEBES crear usando GLEngine.Builder. Esto es necesario porque Minecraft depende fuertemente de GlStateManager, así que necesitas redirigir todas las llamadas GL de watermedia hacia los equivalentes de GlStateManager.
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