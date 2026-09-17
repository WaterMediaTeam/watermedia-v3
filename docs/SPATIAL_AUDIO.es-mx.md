# Audio espacial y Sound Physics Remastered

[English](SPATIAL_AUDIO.md) | Español (México)

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

## Adaptador para Sound Physics Remastered

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

## Verificación

`SpatialAudioTest` cubre el rechazo de estados inválidos, la negativa de Java Sound, las coordenadas y la atenuación de una fuente OpenAL real, la restauración del origen antes del callback, la limpieza de efectos, el manejo de excepciones, los contextos diferentes y las llamadas tardías después de liberar la fuente. También genera un WAV estéreo y lo reproduce con `FFMediaPlayer`, verificando PCM mono en los búferes reales de OpenAL. Las pruebas de dispositivo requieren una salida OpenAL disponible; el caso de decodificación necesita además los binarios nativos de FFmpeg.

Todavía hace falta una prueba dentro del juego para comprobar que la oclusión y las reflexiones se escuchen como se espera, que el ejecutor de sonido del host serialice las llamadas, el movimiento del oyente, los ajustes de categoría y la recarga del dispositivo. El código fuente de referencia se revisó durante la integración, pero este checkout no contiene un entorno ejecutable con Minecraft y Sound Physics integrados.
