# WaterMedia v3 - Documentación Completa

## 📑 TABLA DE CONTENIDOS
- [📖 INTRODUCCIÓN](#-introducción)
- [🔗 1. MRL (MEDIA RESOURCE LOCATOR)](#-1-mrl-media-resource-locator)
- [🎬 2. MEDIAPLAYER](#-2-mediaplayer)
- [🛠️ 3. HERRAMIENTAS ADICIONALES](#️-3-herramientas-adicionales)
- [🌐 4. PLATAFORMAS SOPORTADAS](#-4-plataformas-soportadas)
- [🎮 5. WATERMEDIAAPP](#-5-watermediaapp)
- [🏗️ 6. ARQUITECTURA Y MEJORES PRÁCTICAS](#️-6-arquitectura-y-mejores-prácticas)
- [📚 RECURSOS ADICIONALES](#-recursos-adicionales)

---

## 📖 INTRODUCCIÓN

**WaterMedia v3** es un framework multimedia profesional diseñado para aplicaciones Java que requieren reproducción de video, audio e imágenes. Construido sobre FFmpeg, OpenGL y OpenAL, proporciona:

- ✅ **Aceleración por hardware** (CUDA, D3D11VA, VAAPI, VideoToolbox)
- ✅ **Reproducción adaptativa** con cambio de calidad en tiempo real
- ✅ **Multi-threading optimizado** para alto rendimiento
- ✅ **Sincronización audio/video** precisa
- ✅ **Soporte multi-plataforma** (Windows, Linux, macOS)
- ✅ **Sistema extensible** de plataformas y decoders

---

## 🔗 1. MRL (MEDIA RESOURCE LOCATOR)

Un **MRL** es un contenedor thread-safe que gestiona URIs de medios con caching inteligente y carga asíncrona. El MRL se encarga automáticamente de detectar la plataforma, extraer sources, calidades y metadatos.

### Creación de un MRL

```java
import org.watermedia.api.media.MRL;
import java.net.URI;

// Crear MRL desde una URL
URI uri = URI.create("https://example.com/video.mp4");
MRL mrl = MRL.create(uri);

// Esperar a que esté listo usando un loop (recomendado para game loops)
int maxTicks = 100; // Máximo 5 segundos (100 ticks * 50ms)
int tickCount = 0;

while (mrl.busy() && tickCount < maxTicks) {
    Thread.sleep(50); // 50ms por tick
    tickCount++;
}

// Verificar el resultado
if (mrl.ready()) {
    System.out.println("MRL listo!");
} else if (mrl.error()) {
    System.err.println("Error cargando MRL");
} else {
    System.err.println("Timeout esperando el MRL");
}
```

**Método alternativo (mrl.await)** - Bloqueante, menos recomendado:

```java
// Solo usar si no estás en un game loop o render thread
if (mrl.await(5000) && !mrl.error()) {
    // MRL listo
}
```

### Estados de un MRL

Un MRL puede estar en uno de los siguientes estados:

| Estado | Método | Descripción |
|--------|--------|-------------|
| **BUSY** | `mrl.busy()` | El MRL está cargando datos actualmente |
| **READY** | `mrl.ready()` | El MRL ha cargado exitosamente y está listo |
| **ERROR** | `mrl.error()` | Ocurrió un error durante la carga |
| **EXPIRED** | `mrl.expired()` | El cache del MRL ha expirado (TTL: 30 min) |

**Ejemplo de verificación con loop:**

```java
MRL mrl = MRL.create(uri);

// Loop no-bloqueante (ideal para Minecraft o game loops)
private void checkMRL(MRL mrl) {
    if (mrl.busy()) {
        // Mostrar indicador de carga
        renderLoadingIndicator();
        return;
    }

    if (mrl.ready()) {
        // Iniciar reproducción
        startPlayback(mrl);
    } else if (mrl.error()) {
        // Mostrar error
        showError("Error al cargar el medio");
    }
}

// Verificar si el cache expiró
if (mrl.expired()) {
    mrl = MRL.create(uri); // Recrear el MRL
}
```

### Acceder a Sources

Los **Sources** son generados automáticamente por las plataformas registradas. Contienen todas las calidades, slaves (subtítulos/audio) y metadatos del medio:

```java
// Obtener todos los sources disponibles
Source[] sources = mrl.getSources();

if (sources.length > 0) {
    Source firstSource = sources[0];

    // Información del source
    MediaType type = firstSource.type(); // VIDEO, AUDIO, IMAGE, etc.
    Quality[] qualities = firstSource.qualities(); // Calidades disponibles
    Slave[] slaves = firstSource.slaves(); // Audio/subtítulos adicionales
    Metadata metadata = firstSource.metadata(); // Información del medio
}
```

### Seleccionar URIs por Calidad

```java
Source source = mrl.getSources()[0];

// Obtener la mejor calidad disponible
URI bestUri = source.bestUri();

// Obtener la peor calidad disponible (para conexiones lentas)
URI worstUri = source.worstUri();

// Obtener una calidad específica
URI hdUri = source.uri(Quality.HIGH); // 720p

// Con fallback automático a la calidad más cercana
URI requestedUri = source.uri(Quality.HIGHEST); // Intenta 1080p
if (requestedUri == null) {
    requestedUri = source.uri(Quality.HIGH); // Fallback a 720p
}
```

### Calidades Disponibles

```java
public enum Quality {
    Q144P,      // 144p - Calidad mínima
    LOWEST,     // 240p
    LOW,        // 360p
    MEDIUM,     // 480p - SD
    HIGH,       // 720p - HD
    HIGHER,     // 1080p - Full HD
    HIGHEST,    // 1440p - 2K
    Q4K,        // 2160p - 4K
    Q8K         // 4320p - 8K
}
```

### Metadatos

Los metadatos proporcionan información descriptiva del medio:

```java
Metadata metadata = source.metadata();

// Información disponible
String title = metadata.title();           // Título del video
String description = metadata.description(); // Descripción
URI thumbnail = metadata.thumbnail();       // URI de la miniatura
long publishedAt = metadata.publishedAt();  // Timestamp de publicación
long duration = metadata.duration();        // Duración en milisegundos
String author = metadata.author();          // Autor/creador

// Ejemplo de uso
System.out.printf("Reproduciendo: %s (%s)%n",
    title,
    formatDuration(duration)
);
System.out.printf("Por: %s%n", author);
```

### Diferencias: Sources, Qualities y Slaves

#### Sources (Fuentes)

Un **Source** representa una fuente de medio completa. Las plataformas generan sources automáticamente con múltiples calidades y tracks adicionales.

**Características:**
- Contiene un `MediaType` (VIDEO, AUDIO, IMAGE, SUBTITLES, UNKNOWN)
- Almacena múltiples calidades en un `EnumMap<Quality, URI>`
- Puede tener múltiples `Slave` asociados
- Incluye `Metadata` opcional

#### Qualities (Calidades)

Las **calidades** son diferentes resoluciones/bitrates de la misma fuente de video.

```java
// Un source puede tener múltiples calidades
URI uri720p = source.uri(Quality.HIGH);    // 720p
URI uri1080p = source.uri(Quality.HIGHER); // 1080p

// El MediaPlayer puede cambiar de calidad durante la reproducción
mediaPlayer.switchQuality(Quality.HIGHER);
```

**Caso de Uso:**
- Streaming adaptativo según ancho de banda
- Permitir al usuario elegir calidad
- Optimización para dispositivos con recursos limitados

#### Slaves (Esclavos)

Los **Slaves** son tracks adicionales sincronizados con el video principal. Las plataformas pueden proporcionar slaves automáticamente:

**Tipos de Slaves:**
- `AUDIO` - Pistas de audio alternativas (idiomas, comentarios, etc.)
- `SUBTITLES` - Subtítulos en diferentes idiomas

**Acceso a Slaves:**

```java
Source source = mrl.getSources()[0];
Slave[] slaves = source.slaves();

for (Slave slave : slaves) {
    SlaveType type = slave.type(); // AUDIO o SUBTITLES
    URI slaveUri = slave.uri();

    if (type == SlaveType.SUBTITLES) {
        System.out.println("Subtítulos disponibles: " + slaveUri);
    }
}
```

**Diferencia con Sources Múltiples:**
- **Multiple Sources**: Diferentes versiones del mismo contenido (ej: YouTube + Vimeo)
- **Slaves**: Complementan un source específico (subtítulos del video de YouTube)

---

## 🎬 2. MEDIAPLAYER

### Creación de un MediaPlayer

El proceso completo para crear un MediaPlayer requiere varios pasos y componentes:

```java
import org.watermedia.api.media.*;
import org.watermedia.api.media.players.*;
import org.watermedia.api.media.engines.*;
import java.util.concurrent.Executor;

public class MediaPlayerSetup {

    public MediaPlayer createPlayer(String videoUrl) {
        // 1. Crear MRL y esperar con loop
        URI uri = URI.create(videoUrl);
        MRL mrl = MRL.create(uri);

        // Esperar en loop (recomendado)
        int maxTicks = 100;
        int tickCount = 0;
        while (mrl.busy() && tickCount < maxTicks) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                break;
            }
            tickCount++;
        }

        if (!mrl.ready() || mrl.error()) {
            throw new RuntimeException("Failed to load MRL");
        }

        // 2. Obtener render thread y executor
        Thread renderThread = Thread.currentThread(); // Tu thread OpenGL/OpenAL
        Executor renderExecutor = command -> {
            // Ejecutar en render thread (ajustar según tu framework)
            RenderSystem.recordRenderCall(command::run);
        };

        // 3. Crear GLEngine
        GLEngine glEngine = GLEngine.builder()
            .glGenBuffers(GL15::glGenBuffers)
            .glBindBuffer(GL15::glBindBuffer)
            .glBufferData(GL15::glBufferData)
            .glBufferSubData(GL15::glBufferSubData)
            .glMapBuffer(GL15::glMapBuffer)
            .glUnmapBuffer(GL15::glUnmapBuffer)
            .glDeleteBuffers(GL15::glDeleteBuffers)
            .build();
        glEngine.prepare();

        // 4. Crear ALEngine
        ALEngine alEngine = ALEngine.builder()
            .alGenSources(AL10::alGenSources)
            .alDeleteSources(AL10::alDeleteSources)
            .alSourceQueueBuffers(AL10::alSourceQueueBuffers)
            .alSourceUnqueueBuffers(AL10::alSourceUnqueueBuffers)
            .alGetSourcei(AL10::alGetSourcei)
            .alGenBuffers(AL10::alGenBuffers)
            .alBufferData(AL10::alBufferData)
            .alDeleteBuffers(AL10::alDeleteBuffers)
            .alSourcePlay(AL10::alSourcePlay)
            .build();

        // 5. Crear MediaPlayer con TODOS los argumentos requeridos
        MediaPlayer player = mrl.createPlayer(
            renderThread,    // Thread de OpenGL/OpenAL
            renderExecutor,  // Executor para render thread
            glEngine,        // Engine de video
            alEngine,        // Engine de audio
            true,            // Habilitar video
            true             // Habilitar audio
        );

        return player;
    }
}
```

**Argumentos de createPlayer():**

| Argumento | Tipo | Descripción |
|-----------|------|-------------|
| `renderThread` | Thread | Thread donde se ejecuta OpenGL/OpenAL |
| `renderExecutor` | Executor | Executor que envía tareas al render thread |
| `glEngine` | GLEngine | Engine para renderizado de video |
| `alEngine` | ALEngine | Engine para reproducción de audio |
| `video` | boolean | Si se debe procesar video |
| `audio` | boolean | Si se debe procesar audio |

### Threads y Executors

WaterMedia utiliza una arquitectura multi-threaded para maximizar el rendimiento:

#### Render Thread (Thread Principal)

**CRÍTICO:** Todas las operaciones de OpenGL/OpenAL deben ejecutarse en el render thread:

```java
// En Minecraft con Fabric/Forge
RenderSystem.recordRenderCall(() -> {
    glEngine.prepare(); // OK - En render thread
    mediaPlayer.start(); // OK - Internamente seguro
});

// En aplicaciones GLFW
GLFWUtil.onRenderThread(() -> {
    glEngine.upload(textureId, width, height);
});
```

#### Executors Recomendados

```java
import java.util.concurrent.*;

// 1. Para operaciones asíncronas de MRL (opcional)
ExecutorService mrlExecutor = Executors.newFixedThreadPool(4);

// 2. Render thread executor (REQUERIDO para MediaPlayer)
Executor renderThreadExecutor = command -> {
    RenderSystem.recordRenderCall(command::run);
};
```

#### Threading del MediaPlayer

Cada tipo de MediaPlayer maneja threads de forma diferente:

**FFMediaPlayer (Video/Audio):**
```java
// Crea un thread dedicado por instancia
// Thread interno: "FFMediaPlayer-Thread-<id>"
// - Decodifica video/audio
// - Sincroniza A/V
// - Maneja buffering
```

**TxMediaPlayer (Imágenes/GIFs):**
```java
// Usa un thread compartido para todas las instancias
// Thread compartido: "TxMediaPlayer-SharedThread"
// - Actualiza frames de múltiples players (100 FPS max)
// - Bajo overhead de recursos
```

### Instanciación de Engines

#### GLEngine (Video Rendering)

El GLEngine usa **PBO triple-buffering** para uploads asíncronos a GPU:

```java
import org.watermedia.api.media.engines.GLEngine;

// Crear GLEngine con builder
GLEngine glEngine = GLEngine.builder()
    .glGenBuffers(GL15::glGenBuffers)           // Función de OpenGL
    .glBindBuffer(GL15::glBindBuffer)
    .glBufferData(GL15::glBufferData)
    .glBufferSubData(GL15::glBufferSubData)
    .glMapBuffer(GL15::glMapBuffer)
    .glUnmapBuffer(GL15::glUnmapBuffer)
    .glDeleteBuffers(GL15::glDeleteBuffers)
    .build();

// Preparar GLEngine (llamar UNA VEZ antes de usar)
glEngine.prepare();

// En tu loop de render (EN RENDER THREAD)
if (mediaPlayer.getStatus() == Status.PLAYING) {
    glEngine.upload(textureId, width, height);

    // Renderizar tu textura con OpenGL
    renderTexture(textureId, x, y, width, height);
}

// Limpiar cuando termines
glEngine.release();
```

**Ejemplo Completo con LWJGL3:**

```java
import org.lwjgl.opengl.*;

public class VideoRenderer {
    private GLEngine glEngine;
    private int textureId;

    public void init() {
        // Crear texture de OpenGL
        textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        // Crear GLEngine
        glEngine = GLEngine.builder()
            .glGenBuffers(GL15::glGenBuffers)
            .glBindBuffer(GL15::glBindBuffer)
            .glBufferData(GL15::glBufferData)
            .glBufferSubData(GL15::glBufferSubData)
            .glMapBuffer((target, access) -> GL15.glMapBuffer(target, access, null))
            .glUnmapBuffer(GL15::glUnmapBuffer)
            .glDeleteBuffers(GL15::glDeleteBuffers)
            .build();

        glEngine.prepare();
    }

    public void render(MediaPlayer player) {
        if (player.getStatus() == Status.PLAYING) {
            // Upload automático con PBO
            glEngine.upload(textureId, player.getWidth(), player.getHeight());

            // Renderizar
            renderQuad(textureId);
        }
    }

    public void cleanup() {
        glEngine.release();
        GL11.glDeleteTextures(textureId);
    }
}
```

#### ALEngine (Audio Playback)

El ALEngine maneja la reproducción de audio con OpenAL:

```java
import org.watermedia.api.media.engines.ALEngine;
import org.lwjgl.openal.*;

// Crear ALEngine con builder
ALEngine alEngine = ALEngine.builder()
    .alGenSources(AL10::alGenSources)
    .alDeleteSources(AL10::alDeleteSources)
    .alSourceQueueBuffers(AL10::alSourceQueueBuffers)
    .alSourceUnqueueBuffers(AL10::alSourceUnqueueBuffers)
    .alGetSourcei(AL10::alGetSourcei)
    .alGenBuffers(AL10::alGenBuffers)
    .alBufferData(AL10::alBufferData)
    .alDeleteBuffers(AL10::alDeleteBuffers)
    .alSourcePlay(AL10::alSourcePlay)
    .build();

// ALEngine se pasa al createPlayer()
// No necesitas llamar métodos manualmente en uso normal
```

**Nota:** El ALEngine es principalmente usado internamente por FFMediaPlayer. Raramente necesitarás interactuar con él directamente.

### Comportamiento del Sonido

#### Control de Volumen

```java
// Establecer volumen (0-100)
mediaPlayer.volume(75);  // 75%
mediaPlayer.volume(100); // 100% (máximo)
mediaPlayer.volume(0);   // 0% (silencio)

// Obtener volumen actual
int currentVolume = mediaPlayer.volume();
```

#### Mute/Unmute

```java
// Silenciar audio (mantiene el nivel de volumen)
mediaPlayer.mute(true);

// Reactivar audio
mediaPlayer.mute(false);

// Toggle mute
boolean wasMuted = mediaPlayer.isMuted();
mediaPlayer.mute(!wasMuted);
```

#### Comportamiento Especial

**Al desactivar el sonido (mute):**

1. **FFMediaPlayer:**
   - El audio **sigue decodificándose** en segundo plano
   - Se mantiene la sincronización A/V
   - El ALEngine deja de reproducir pero los buffers siguen actualizándose
   - **Ventaja:** Unmute instantáneo sin desincronización

2. **TxMediaPlayer:**
   - No tiene audio, por lo que mute/unmute no tienen efecto

**Ejemplo de Uso:**

```java
// Mutear temporalmente durante una cinemática
mediaPlayer.mute(true);
playCinematic();

// Restaurar audio
mediaPlayer.mute(false);

// El audio está perfectamente sincronizado, sin delays
```

### Control de Reproducción

#### Reproducción Básica

```java
// Iniciar reproducción
mediaPlayer.start();

// Iniciar en pausa (útil para pre-cargar)
mediaPlayer.startPaused();

// Pausar
mediaPlayer.pause();

// Resumir
mediaPlayer.resume();

// Toggle play/pause
mediaPlayer.togglePlay();

// Detener completamente
mediaPlayer.stop();
```

#### Seeking (Navegación Temporal)

```java
// Seek a una posición específica (en ms)
mediaPlayer.seek(30000); // 30 segundos

// Seek rápido (menos preciso pero más rápido)
mediaPlayer.seekQuick(60000); // 1 minuto

// Skip relativo (+/- tiempo en ms)
mediaPlayer.skipTime(5000);  // +5 segundos
mediaPlayer.skipTime(-5000); // -5 segundos

// Forward/Rewind (cantidad en ms)
mediaPlayer.forward(10000);  // Adelantar 10 segundos
mediaPlayer.rewind(10000);   // Retroceder 10 segundos
```

#### Control de Frames

```java
// Navegar frame por frame (solo FFMediaPlayer)
mediaPlayer.pause(); // Debe estar pausado

mediaPlayer.nextFrame();     // Siguiente frame
mediaPlayer.previousFrame(); // Frame anterior
```

#### Velocidad de Reproducción

```java
// Establecer velocidad (0.0 - 4.0x)
mediaPlayer.speed(1.0f);  // Velocidad normal
mediaPlayer.speed(0.5f);  // Medio speed (cámara lenta)
mediaPlayer.speed(2.0f);  // Doble velocidad
mediaPlayer.speed(0.25f); // 1/4 velocidad

// Obtener velocidad actual
float currentSpeed = mediaPlayer.speed();
```

#### Repetición

```java
// Configurar modo de repetición
mediaPlayer.setRepeat(Repeat.NONE); // No repetir
mediaPlayer.setRepeat(Repeat.ONE);  // Repetir medio actual
mediaPlayer.setRepeat(Repeat.ALL);  // Repetir lista (si aplicable)
```

#### Cambio de Calidad

```java
// Cambiar calidad durante reproducción (seamless)
mediaPlayer.switchQuality(Quality.HIGHER); // Cambiar a 1080p

// El MediaPlayer:
// 1. Guarda la posición actual
// 2. Cambia al nuevo stream
// 3. Hace seek a la posición guardada
// 4. Continúa reproduciendo sin interrupciones
```

#### Estados del MediaPlayer

```java
Status status = mediaPlayer.getStatus();

switch (status) {
    case WAITING -> System.out.println("Esperando inicialización");
    case LOADING -> System.out.println("Cargando medio...");
    case BUFFERING -> System.out.println("Buffering...");
    case PLAYING -> System.out.println("Reproduciendo");
    case PAUSED -> System.out.println("Pausado");
    case STOPPED -> System.out.println("Detenido");
    case ENDED -> System.out.println("Reproducción finalizada");
    case ERROR -> System.out.println("Error en reproducción");
}
```

#### Información de Reproducción

```java
// Obtener tiempo actual (ms)
long currentTime = mediaPlayer.currentTime();

// Obtener duración total (ms)
long duration = mediaPlayer.duration();

// Obtener dimensiones del video
int width = mediaPlayer.getWidth();
int height = mediaPlayer.getHeight();

// Verificar si tiene video
boolean hasVideo = mediaPlayer.hasVideo();

// Verificar si tiene audio
boolean hasAudio = mediaPlayer.hasAudio();
```

#### Ejemplo Completo de Control

```java
public class PlayerController {
    private final MediaPlayer player;
    private final GLEngine glEngine;

    public void setupControls() {
        // Play/Pause
        keyboard.onKey(GLFW.GLFW_KEY_SPACE, () -> player.togglePlay());

        // Seeking
        keyboard.onKey(GLFW.GLFW_KEY_LEFT, () -> player.rewind(5000));
        keyboard.onKey(GLFW.GLFW_KEY_RIGHT, () -> player.forward(5000));

        // Volumen
        keyboard.onKey(GLFW.GLFW_KEY_UP, () -> {
            int vol = Math.min(100, player.volume() + 5);
            player.volume(vol);
        });
        keyboard.onKey(GLFW.GLFW_KEY_DOWN, () -> {
            int vol = Math.max(0, player.volume() - 5);
            player.volume(vol);
        });

        // Mute
        keyboard.onKey(GLFW.GLFW_KEY_M, () -> player.mute(!player.isMuted()));

        // Velocidad
        keyboard.onKey(GLFW.GLFW_KEY_MINUS, () -> {
            float speed = Math.max(0.25f, player.speed() - 0.25f);
            player.speed(speed);
        });
        keyboard.onKey(GLFW.GLFW_KEY_PLUS, () -> {
            float speed = Math.min(4.0f, player.speed() + 0.25f);
            player.speed(speed);
        });

        // Calidad
        keyboard.onKey(GLFW.GLFW_KEY_Q, () -> showQualitySelector());
    }

    public void render() {
        if (player.getStatus() == Status.PLAYING) {
            glEngine.upload(textureId, player.getWidth(), player.getHeight());
            renderVideo(textureId);
            renderControls();
        }
    }

    private void renderControls() {
        long current = player.currentTime();
        long total = player.duration();
        float progress = (float) current / total;

        // Renderizar barra de progreso
        drawProgressBar(progress);

        // Renderizar tiempo
        String timeText = formatTime(current) + " / " + formatTime(total);
        drawText(timeText, x, y);

        // Renderizar volumen
        drawVolumeIcon(player.volume(), player.isMuted());
    }
}
```

---

## 🛠️ 3. HERRAMIENTAS ADICIONALES

### Sistema de Decoders

WaterMedia incluye un sistema extensible de decoders basado en **ServiceLoader**.

#### Decoders Incluidos

| Decoder | Formatos | Características |
|---------|----------|-----------------|
| **GifDecoder** | GIF | Soporte de animación, loop infinito |
| **JpegDecoder** | JPEG, JPG | Decodificación rápida |
| **PngDecoder** | PNG, APNG | Soporte para PNG animados (APNG) |
| **WebPDecoder** | WebP | Imágenes estáticas y animadas |

**Nota:** Todos los decoders producen salida en formato **BGRA**.

#### Uso de Decoders

```java
import org.watermedia.api.decode.*;
import java.nio.ByteBuffer;

// Obtener decoder para una extensión
Decoder decoder = DecoderAPI.getDecoder("gif");

if (decoder != null) {
    // Decodificar imagen
    Decoder.Result result = decoder.decode(inputStream);

    if (result != null) {
        // Obtener frames
        Decoder.Frame[] frames = result.frames();

        for (Decoder.Frame frame : frames) {
            ByteBuffer pixels = frame.pixels();  // Datos BGRA
            int width = frame.width();
            int height = frame.height();
            int delay = frame.delay();            // Delay en ms (para animaciones)

            // Upload a OpenGL
            uploadToGPU(pixels, width, height);
            Thread.sleep(delay);
        }
    }
}
```

#### Verificar Soporte

```java
// Verificar si un formato es soportado
if (DecoderAPI.getDecoder("webp") != null) {
    System.out.println("WebP es soportado");
}

// Test de decodificación
Decoder decoder = DecoderAPI.getDecoder("png");
if (decoder.test(inputStream)) {
    System.out.println("El archivo es un PNG válido");
}
```

#### Crear un Decoder Personalizado

```java
import org.watermedia.api.decode.Decoder;
import java.io.InputStream;

public class CustomDecoder implements Decoder {

    @Override
    public boolean supported(String extension) {
        return extension.equals("custom");
    }

    @Override
    public Result decode(InputStream input) {
        try {
            // Tu lógica de decodificación
            ByteBuffer pixels = decodeCustomFormat(input);

            Frame frame = new Frame(pixels, width, height, 0);
            return new Result(new Frame[]{frame});
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean test(InputStream input) {
        // Verificar magic bytes o header
        return checkCustomFormatHeader(input);
    }
}
```

**Registrar el Decoder:**

Crear archivo: `src/main/resources/META-INF/services/org.watermedia.api.decode.Decoder`

```
com.example.CustomDecoder
```

### MathUtil

Librería de utilidades matemáticas optimizadas para multimedia.

#### Funciones de Easing

30+ funciones de easing para animaciones suaves:

```java
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.MathUtil.EasingType;

// Interpolación con easing
float progress = 0.5f; // 0.0 - 1.0

// Easing básico
float linear = MathUtil.ease(progress, EasingType.LINEAR);
float easeIn = MathUtil.ease(progress, EasingType.EASE_IN);
float easeOut = MathUtil.ease(progress, EasingType.EASE_OUT);
float easeInOut = MathUtil.ease(progress, EasingType.EASE_IN_OUT);

// Easing avanzado
float sine = MathUtil.ease(progress, EasingType.SINE_IN_OUT);
float cubic = MathUtil.ease(progress, EasingType.CUBIC_IN_OUT);
float elastic = MathUtil.ease(progress, EasingType.ELASTIC_IN_OUT);
float bounce = MathUtil.ease(progress, EasingType.BOUNCE_OUT);
float back = MathUtil.ease(progress, EasingType.BACK_IN_OUT);
```

**Tipos de Easing Disponibles:**

```java
// Básico
LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT

// Sine
SINE_IN, SINE_OUT, SINE_IN_OUT

// Cubic
CUBIC_IN, CUBIC_OUT, CUBIC_IN_OUT

// Quad
QUAD_IN, QUAD_OUT, QUAD_IN_OUT

// Quint
QUINT_IN, QUINT_OUT, QUINT_IN_OUT

// Circle
CIRCLE_IN, CIRCLE_OUT, CIRCLE_IN_OUT

// Expo
EXPO_IN, EXPO_OUT, EXPO_IN_OUT

// Back (overshoot)
BACK_IN, BACK_OUT, BACK_IN_OUT

// Bounce
BOUNCE_IN, BOUNCE_OUT, BOUNCE_IN_OUT

// Elastic
ELASTIC_IN, ELASTIC_OUT, ELASTIC_IN_OUT
```

#### Ejemplo de Animación con Easing

```java
public class FadeAnimation {
    private long startTime;
    private long duration = 1000; // 1 segundo

    public void start() {
        startTime = System.currentTimeMillis();
    }

    public float getAlpha() {
        long elapsed = System.currentTimeMillis() - startTime;
        float progress = Math.min(1.0f, (float) elapsed / duration);

        // Fade in suave con cubic easing
        return MathUtil.ease(progress, EasingType.CUBIC_OUT);
    }
}
```

#### Conversiones de Tiempo (Minecraft)

```java
// Convertir ticks de Minecraft a milisegundos
long ticks = 20; // 1 segundo en Minecraft
long ms = MathUtil.tickToMs(ticks); // 1000ms

// Convertir milisegundos a ticks
long millis = 500;
long minecraftTicks = MathUtil.msToTick(millis); // 10 ticks
```

#### Escalado Temporal

```java
// Escalar tiempo con factor
long scaledTime = MathUtil.scaleTempo(1000, 2.0f); // 2000ms (doble velocidad)

// Desescalar tiempo
long originalTime = MathUtil.scaleDesTempo(2000, 2.0f); // 1000ms
```

#### Operaciones Matemáticas

```java
// Modulo seguro (siempre positivo)
int result = MathUtil.floorMod(-5, 3); // 1 (no -2)

// Crear color ARGB
int color = MathUtil.argb(255, 255, 0, 0); // Rojo opaco
int semiTransparent = MathUtil.argb(128, 0, 255, 0); // Verde semi-transparente
```

#### Sin/Cos Rápidos

```java
// Funciones trigonométricas optimizadas con lookup table
float fastSin = MathUtil.sin(angle); // ~3x más rápido que Math.sin()
float fastCos = MathUtil.cos(angle); // ~3x más rápido que Math.cos()

// Útil para cálculos en loops de render
for (int i = 0; i < 1000; i++) {
    float x = MathUtil.cos(i * 0.1f) * radius;
    float y = MathUtil.sin(i * 0.1f) * radius;
    drawPoint(x, y);
}
```

---

## 🌐 4. PLATAFORMAS SOPORTADAS

### Plataformas Actuales

WaterMedia actualmente soporta las siguientes plataformas:

| Plataforma | Estado | Características |
|------------|--------|-----------------|
| **YouTube** | ⚠️ Parcial | Validación de URL implementada, extracción pendiente |
| **Imgur** | ✅ Funcional | Imágenes estáticas y GIFs |
| **Kick** | ✅ Funcional | Streaming de video en vivo |
| **Streamable** | ✅ Funcional | Videos cortos |
| **WaterPlatform** | ✅ Funcional | Plataforma personalizada |
| **Default** | ✅ Funcional | Fallback para URLs directas (MP4, MP3, etc.) |

#### Uso de Plataformas

```java
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.MRL;

// MediaAPI selecciona automáticamente la plataforma correcta
// y extrae sources internamente
MRL mrl = MRL.create(URI.create("https://youtube.com/watch?v=..."));

// Esperar con loop
while (mrl.busy()) {
    Thread.sleep(50);
}

if (mrl.ready()) {
    // Sources están disponibles automáticamente
    Source[] sources = mrl.getSources();
}
```

### Plataformas Planificadas

Las siguientes plataformas están planificadas para soporte futuro:

#### Plataformas de Video
- ✅ **YouTube** - Soporte completo pendiente
- ⏳ **Twitch** - Streaming en vivo y VODs
- ⏳ **Facebook** - Videos de Facebook Watch
- ⏳ **Instagram** - Videos e historias
- ⏳ **TikTok** - Videos cortos
- ⏳ **Twitter** - Videos incrustados

#### Plataformas Alternativas
- ⏳ **Odysee** - Videos descentralizados
- ⏳ **Rumble** - Plataforma de video alternativa
- ⏳ **Bitchute** - Videos peer-to-peer
- ⏳ **D.tube** - Videos en blockchain
- ⏳ **VidLii** - Plataforma retro de videos

#### Plataformas Asiáticas
- ⏳ **BiliBili** - Plataforma china de videos

#### Servicios de Almacenamiento
- ⏳ **Mediafire** - Archivos directos
- ⏳ **Dropbox** - Links compartidos
- ⏳ **Zippyshareday** - Hosting de archivos

#### Plataformas de Imágenes/GIFs
- ✅ **Imgur** - Implementado
- ⏳ **Giphy** - GIFs animados

#### Plataformas Especializadas
- ⏳ **Streamable** - Videos deportivos
- ⏳ **Sendvid** - Sharing de videos
- ⏳ **Pornhub** - Contenido adulto
- ⏳ **File** - Archivos locales con extensión especial

**Leyenda:**
- ✅ Implementado
- ⚠️ Implementación parcial
- ⏳ Planificado

### Crear tu Propia Plataforma

Implementa la interfaz `IPlatform` para agregar soporte a plataformas personalizadas. Las plataformas son responsables de extraer sources, calidades y metadatos:

```java
import org.watermedia.api.media.platform.IPlatform;
import org.watermedia.api.media.MRL.*;
import java.net.URI;

public class CustomPlatform implements IPlatform {

    @Override
    public String name() {
        return "CustomPlatform";
    }

    @Override
    public boolean validate(URI uri) {
        // Validar si esta plataforma puede manejar la URI
        String host = uri.getHost();
        return host != null && host.contains("custom.com");
    }

    @Override
    public Source[] getSources(URI uri) {
        try {
            // Extraer información de la plataforma
            VideoInfo info = fetchVideoInfo(uri);

            // Crear source con múltiples calidades
            Source source = Source.of(MediaType.VIDEO)
                .quality(Quality.HIGHEST, URI.create(info.url1080p))
                .quality(Quality.HIGH, URI.create(info.url720p))
                .quality(Quality.MEDIUM, URI.create(info.url480p))
                .slave(new Slave(
                    SlaveType.SUBTITLES,
                    URI.create(info.subtitlesUrl)
                ))
                .metadata(new Metadata(
                    info.title,
                    info.description,
                    URI.create(info.thumbnail),
                    info.uploadDate,
                    info.duration,
                    info.author
                ))
                .build();

            return new Source[]{source};

        } catch (Exception e) {
            return new Source[0]; // Error: retornar array vacío
        }
    }

    private VideoInfo fetchVideoInfo(URI uri) {
        // Implementar lógica de scraping/API
        // Ejemplo: usar JSoup, HttpClient, etc.
        return VideoInfo.fetch(uri);
    }
}
```

#### Registrar tu Plataforma

**Método 1: ServiceLoader (Recomendado)**

Crear archivo: `src/main/resources/META-INF/services/org.watermedia.api.media.platform.IPlatform`

```
com.example.CustomPlatform
```

**Método 2: Registro Manual**

```java
import org.watermedia.api.media.MediaAPI;

public class ModInitializer {
    public void onInitialize() {
        MediaAPI.registerPlatform(new CustomPlatform());
    }
}
```

#### Prioridad de Plataformas

Las plataformas se evalúan en orden de registro. La primera plataforma que retorne `true` en `validate()` será usada.

```java
// Orden actual de prioridad:
1. YoutubePlatform
2. ImgurPlatform
3. KickPlatform
4. StreamablePlatform
5. WaterPlatform
6. CustomPlatforms (en orden de registro)
7. DefaultPlatform (siempre al final, acepta todo)
```

**Tip:** Registra plataformas específicas antes que genéricas para mayor control.

---

## 🎮 5. WATERMEDIAAPP

### Introducción

**WaterMediaApp** es una aplicación de prueba interactiva para WaterMedia, construida con GLFW. Proporciona un entorno completo para:

- Probar reproducción de videos, audio e imágenes
- Validar plataformas soportadas
- Depurar problemas de reproducción
- Demostrar las capacidades de WaterMedia

**Tecnologías:**
- **GLFW** - Ventana y input
- **OpenGL 1.1** - Renderizado básico
- **OpenAL** - Audio
- **ImGui-style UI** - Interfaz minimalista

### Funciones

#### Menú Principal

El menú principal ofrece tres secciones:

**1. Multimedia**
- Probar URLs personalizadas
- Selector de MRLs predefinidos
- Cambio de calidad en tiempo real
- Control completo de reproducción

**2. Herramientas (Tools)**
- **Upload Logs**: Sube logs a mclo.gs con integración de GitHub
- **Cleanup Temp**: Limpia archivos temporales de WaterMedia

**3. Presets**
- Lista de URLs de prueba predefinidas
- Diferentes plataformas y tipos de medio
- Fácil acceso para testing rápido

#### Controles de Reproducción

| Tecla/Acción | Función |
|--------------|---------|
| **Espacio** | Play/Pause |
| **Click (Barra)** | Seek a posición |
| **Scroll (Volumen)** | Ajustar volumen |
| **Click (Volumen)** | Mute/Unmute |
| **Q** | Abrir selector de calidad |
| **ESC** | Volver al menú |

#### Selector de MRL

```
┌─────────────────────────────────┐
│ MRL Selector                    │
├─────────────────────────────────┤
│ > Video 1 [READY]               │
│   Video 2 [LOADING...]          │
│   Video 3 [ERROR]               │
│   Image 1 [READY]               │
└─────────────────────────────────┘
```

**Estados visuales:**
- `[READY]` - Listo para reproducir (verde)
- `[LOADING...]` - Cargando (amarillo)
- `[ERROR]` - Error de carga (rojo)

#### Selector de Calidad

```
┌─────────────────────────────────┐
│ Select Quality                  │
├─────────────────────────────────┤
│   144p                          │
│   360p                          │
│ > 720p (Current)                │
│   1080p                         │
│   1440p                         │
└─────────────────────────────────┘
```

El cambio de calidad es **seamless** (sin interrupciones).

#### Overlay de Depuración

Muestra información en tiempo real:

```
Status: PLAYING
Time: 01:23 / 05:45 (24%)
Quality: 720p
FPS: 60
Volume: 75%
Speed: 1.0x
```

### Utilidad

#### Para Desarrolladores

**Testing de Integración:**
```java
// Usar WaterMediaApp para probar tu implementación
public class MyPlatformTest {
    public static void main(String[] args) {
        // Registrar tu plataforma
        MediaAPI.registerPlatform(new MyCustomPlatform());

        // Lanzar WaterMediaApp
        WaterMediaApp.main(args);

        // Probar tu URL en la app
    }
}
```

**Depuración:**
- Ver logs en tiempo real
- Validar estados de MRL
- Verificar sincronización A/V
- Probar cambios de calidad

#### Para Testing de Plataformas

1. **Probar extracción de sources:**
- La app muestra cuántos sources se extrajeron
- Indica qué calidades están disponibles
- Muestra metadatos extraídos

2. **Verificar reproducción:**
- Play/Pause funcionando
- Seeking preciso
- Sincronización correcta
- Sin memory leaks

#### Upload de Logs

```
[Tools Menu]
> Upload Logs

Uploading to mclo.gs...
Success!
URL: https://mclo.gs/abc123
Auto-opening in browser...

[Optional] Report Issue on GitHub?
> Yes - Opens GitHub issue form with log pre-attached
  No  - Copy URL to clipboard
```

**Útil para:**
- Reportar bugs con logs adjuntos
- Compartir problemas con el equipo
- Depuración remota

#### Cleanup de Temporales

```
[Tools Menu]
> Cleanup Temp Folder

Analyzing temp folder...
Found 1.2 GB in 450 files

Delete all temp files?
> Yes
  No

Deleted 1.2 GB
Temp folder cleaned!
```

**Libera espacio de:**
- Frames extraídos temporales
- Archivos de cache descargados
- Buffers de decodificación

---

## 🏗️ 6. ARQUITECTURA Y MEJORES PRÁCTICAS

### Patrones de Diseño Utilizados

#### Factory Pattern
```java
// MRL crea MediaPlayers según el tipo de medio
MediaPlayer player = mrl.createPlayer(renderThread, executor, glEngine, alEngine, true, true);
// Retorna: FFMediaPlayer, TxMediaPlayer, o ServerMediaPlayer
```

#### Builder Pattern
```java
// Configuración fluida de objetos complejos
GLEngine engine = GLEngine.builder()
    .glGenBuffers(GL15::glGenBuffers)
    .glBindBuffer(GL15::glBindBuffer)
    .build();
```

#### ServiceLoader Pattern
```java
// Decoders y plataformas extensibles
Decoder decoder = DecoderAPI.getDecoder("gif");
```

### Threading Best Practices

```java
// ✅ CORRECTO: Operaciones GL/AL en render thread
renderThreadExecutor.execute(() -> {
    glEngine.upload(textureId, width, height);
});

// ❌ INCORRECTO: Operaciones GL/AL en thread arbitrario
CompletableFuture.runAsync(() -> {
    glEngine.upload(textureId, width, height); // CRASH!
});
```

### Gestión de Recursos

```java
public class MediaPlayerManager {
    private final List<MediaPlayer> players = new ArrayList<>();
    private final GLEngine glEngine;

    public void cleanup() {
        // Limpiar en orden correcto
        for (MediaPlayer player : players) {
            player.stop();
            player.release(); // Libera recursos internos
        }
        players.clear();

        glEngine.release(); // Libera PBOs de OpenGL
    }
}
```

### Manejo de Errores

```java
public MediaPlayer createPlayerSafely(URI uri) {
    MRL mrl = MRL.create(uri);

    // Esperar con loop (recomendado)
    int maxTicks = 100;
    int tickCount = 0;
    while (mrl.busy() && tickCount < maxTicks) {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            break;
        }
        tickCount++;
    }

    // Verificar estado
    if (mrl.error()) {
        throw new MediaException("Failed to load media");
    }

    if (!mrl.ready()) {
        throw new TimeoutException("MRL loading timeout");
    }

    // Verificar sources
    Source[] sources = mrl.getSources();
    if (sources.length == 0) {
        throw new MediaException("No sources available");
    }

    return mrl.createPlayer(renderThread, executor, glEngine, alEngine, true, true);
}
```

### Optimización de Performance

#### PBO Triple-Buffering (GLEngine)
```java
// WaterMedia usa PBOs automáticamente
// No necesitas hacer nada especial, pero ten en cuenta:

// ✅ RÁPIDO: PBO async upload (frames subsiguientes)
glEngine.upload(textureId, width, height);

// ⚠️ MÁS LENTO: Primer frame siempre es sync
// Esto es normal y necesario
```

#### Frame Skipping Adaptativo
```java
// FFMediaPlayer automáticamente:
// - Salta frames si está >5 frames atrás
// - Siempre renderiza 1 de cada 5 frames mínimo
// - Mantiene sincronización A/V

// No necesitas intervenir, pero puedes monitorear:
if (player.getStatus() == Status.BUFFERING) {
    showBufferingIndicator();
}
```

#### Decodificación Multi-threaded
```java
// FFMediaPlayer ajusta threads según resolución:
// ≤720p:  4 threads
// ≤1080p: 6 threads
// ≤1440p: 8 threads
// 4K+:    16 threads

// Configurado automáticamente, no requiere intervención
```

### Aceleración por Hardware

```java
// FFMediaPlayer detecta y usa aceleración automáticamente:
// - Windows: D3D11VA, DXVA2, CUDA
// - Linux: VAAPI, VDPAU, CUDA
// - macOS: VideoToolbox

// Para forzar software decoding (debugging):
System.setProperty("watermedia.hwaccel", "false");
```

### Ejemplo Completo: Integración con Minecraft

```java
import net.minecraft.client.MinecraftClient;
import org.watermedia.api.media.*;
import org.watermedia.api.media.players.*;
import org.watermedia.api.media.engines.*;

public class MinecraftVideoPlayer {
    private MediaPlayer player;
    private GLEngine glEngine;
    private ALEngine alEngine;
    private int textureId;
    private MRL mrl;
    private int loadingTicks = 0;
    private static final int MAX_LOADING_TICKS = 100; // 5 segundos

    public void init(String videoUrl) {
        // 1. Crear MRL (asíncrono)
        mrl = MRL.create(URI.create(videoUrl));

        // 2. Setup render thread y executor
        Thread renderThread = MinecraftClient.getInstance().thread;
        Executor renderExecutor = command ->
            MinecraftClient.getInstance().execute(command);

        // 3. Crear GLEngine
        glEngine = GLEngine.builder()
            .glGenBuffers(GL15C::glGenBuffers)
            .glBindBuffer(GL15C::glBindBuffer)
            .glBufferData(GL15C::glBufferData)
            .glBufferSubData(GL15C::glBufferSubData)
            .glMapBuffer(GL15C::glMapBuffer)
            .glUnmapBuffer(GL15C::glUnmapBuffer)
            .glDeleteBuffers(GL15C::glDeleteBuffers)
            .build();
        glEngine.prepare();

        // 4. Crear ALEngine
        alEngine = ALEngine.builder()
            .alGenSources(AL10::alGenSources)
            .alDeleteSources(AL10::alDeleteSources)
            .alSourceQueueBuffers(AL10::alSourceQueueBuffers)
            .alSourceUnqueueBuffers(AL10::alSourceUnqueueBuffers)
            .alGetSourcei(AL10::alGetSourcei)
            .alGenBuffers(AL10::alGenBuffers)
            .alBufferData(AL10::alBufferData)
            .alDeleteBuffers(AL10::alDeleteBuffers)
            .alSourcePlay(AL10::alSourcePlay)
            .build();

        // 5. Crear texture
        textureId = GL11.glGenTextures();
    }

    // Llamar cada tick (50ms)
    public void tick() {
        if (player != null) return; // Ya inicializado

        if (mrl.busy()) {
            loadingTicks++;
            if (loadingTicks >= MAX_LOADING_TICKS) {
                // Timeout
                System.err.println("Timeout cargando video");
                cleanup();
            }
            return;
        }

        if (mrl.error()) {
            System.err.println("Error cargando video");
            cleanup();
            return;
        }

        if (mrl.ready()) {
            // Crear player con TODOS los argumentos
            Thread renderThread = MinecraftClient.getInstance().thread;
            Executor renderExecutor = command ->
                MinecraftClient.getInstance().execute(command);

            player = mrl.createPlayer(
                renderThread,
                renderExecutor,
                glEngine,
                alEngine,
                true,  // video
                true   // audio
            );

            player.start();
        }
    }

    public void render(MatrixStack matrices, int x, int y, int width, int height) {
        if (player != null && player.getStatus() == Status.PLAYING) {
            // Upload frame a GPU
            glEngine.upload(textureId, player.getWidth(), player.getHeight());

            // Renderizar quad con la textura
            renderTexture(matrices, textureId, x, y, width, height);
        } else if (mrl != null && mrl.busy()) {
            // Renderizar loading indicator
            renderLoadingSpinner(matrices, x, y);
        }
    }

    public void cleanup() {
        if (player != null) {
            player.stop();
            player.release();
        }
        if (glEngine != null) {
            glEngine.release();
        }
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
        }
    }
}
```

---

## 📚 RECURSOS ADICIONALES

**GitHub:**
- Repositorio: https://github.com/WaterMediaTeam/watermedia
- Issues: https://github.com/WaterMediaTeam/watermedia/issues
- Discussions: https://github.com/WaterMediaTeam/watermedia/discussions

**Discord:**
- [Link si existe]

**Documentación Adicional:**
- Consulta `CONTRIBUTING.md` para guías de contribución
- Revisa los ejemplos en el repositorio

**Contribuciones:**

Las contribuciones son bienvenidas. Para contribuir:

1. Fork el repositorio
2. Crea una rama para tu feature (`git checkout -b feature/amazing-feature`)
3. Commit tus cambios (`git commit -m 'Add amazing feature'`)
4. Push a la rama (`git push origin feature/amazing-feature`)
5. Abre un Pull Request

**Licencia:**

Consulta el archivo `LICENSE` en el repositorio para información sobre la licencia.

---

*Documentación generada para WaterMedia v3 - © 2025 WaterMedia Team*
