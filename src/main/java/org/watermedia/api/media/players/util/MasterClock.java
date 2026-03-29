package org.watermedia.api.media.players.util;

/**
 * Clock basado en pts_drift como ffplay.
 *
 * En lugar de almacenar masterTimeMs + baseTimeMs y calcular elapsed:
 *   time = masterTimeMs + (now - baseTimeMs)
 * almacena pts_drift = pts - wallclock_at_update:
 *   time = pts_drift + now
 *
 * Ventajas:
 * 1. NO ACUMULA ERROR: cada update() recalibra el drift completo.
 * 2. NO NECESITA CAP: el clock siempre converge al siguiente update.
 * 3. UN SOLO MÉTODO time(): una sola fuente de verdad.
 * 4. VELOCIDAD VARIABLE: multiplicar speed es trivial.
 *
 * Internamente todo es double en SECONDS (como ffplay).
 * Métodos timeMs()/updateMs() son convenience wrappers.
 */
public final class MasterClock {
    private volatile double pts;
    private volatile double ptsDrift;
    private volatile double lastUpdated;
    private volatile double speed = 1.0;
    private volatile boolean paused;
    private volatile boolean frozen; // AFTER force(): time() RETURNS pts UNTIL FIRST update()
    private volatile int serial;

    // Frame timing (seteado al abrir el stream)
    private volatile double frameDurationSec = 1.0 / 30.0;
    private volatile float fps = 30.0f;
    private volatile long skipThresholdMs = 165;


    // ═══════════════════════════════════════════
    //  WALLCLOCK
    // ═══════════════════════════════════════════

    /** Wallclock en seconds con precisión de nanosegundo. */
    private static double wallclock() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    // ═══════════════════════════════════════════
    //  CLOCK READ
    // ═══════════════════════════════════════════

    /**
     * Tiempo actual del clock en seconds.
     * Para speed=1.0: pts + (now - wallclock_at_update)
     *
     * No cap needed: the consumption loop's non-blocking timing check
     * (diff <= 0.002) provides natural real-time pacing. The clock advances
     * at wall-clock speed via ptsDrift. Audio is consumed only when the
     * clock reaches the frame's PTS — this inherently prevents racing.
     *
     * A cap would cause deadlocks when audio PTS has gaps > cap value,
     * because time() could never reach the next frame's PTS.
     */
    public double time() {
        if (this.paused || this.frozen) return this.pts;
        double now = wallclock();
        double elapsed = now - this.lastUpdated;
        return this.ptsDrift + now - elapsed * (1.0 - this.speed);
    }

    /** Convenience: tiempo en milisegundos. */
    public long timeMs() {
        return (long) (this.time() * 1000.0);
    }

    // ═══════════════════════════════════════════
    //  CLOCK WRITE
    // ═══════════════════════════════════════════

    /**
     * Actualizar el clock con un PTS del stream (seconds).
     * Llamar cada vez que el audio decode procesa un frame.
     *
     * @param pts    PTS del frame en seconds.
     * @param serial serial del PacketQueue que originó este frame.
     */
    public void update(double pts, int serial) {
        // REJECT LARGE BACKWARD JUMPS — POST-SEEK AUDIO FROM KEYFRAME
        // (PTS << TARGET) WOULD PULL THE CLOCK BACKWARD
        if (pts < this.pts - this.frameDurationSec) return;

        double now = wallclock();
        this.pts = pts;
        this.ptsDrift = pts - now;
        this.lastUpdated = now;
        this.serial = serial;
        // UNFREEZE: FIRST ACCEPTED AUDIO UPDATE AFTER force() → CLOCK RESUMES
        this.frozen = false;
    }

    /** Convenience: update con milisegundos. */
    public void updateMs(long ptsMs, int serial) {
        this.update(ptsMs / 1000.0, serial);
    }

    // ═══════════════════════════════════════════
    //  PACING (para video-only sin audio)
    // ═══════════════════════════════════════════

    /**
     * Para streams sin audio: duerme hasta que sea momento de mostrar
     * el frame con el PTS dado.
     *
     * @param framePtsSec PTS del frame en seconds.
     * @return true si es momento de mostrar, false si fue interrumpido.
     */
    public boolean waitUntil(double framePtsSec) {
        while (!Thread.currentThread().isInterrupted()) {
            double now = this.time();
            double diff = framePtsSec - now;

            if (diff <= 0.002) return true; // 2ms de tolerancia

            long sleepMs = Math.min((long) (diff * 1000.0), 10);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════
    //  CONTROL
    // ═══════════════════════════════════════════

    /** Inicia el clock desde tiempo 0 avanzando con wallclock. */
    public void start() {
        double now = wallclock();
        this.pts = 0;
        this.ptsDrift = -now;
        this.lastUpdated = now;
        this.paused = false;
        this.frozen = false;
    }

    public void pause() {
        if (!this.paused) {
            this.pts = this.time();
            this.paused = true;
        }
    }

    public void resume() {
        if (this.paused) {
            double now = wallclock();
            this.ptsDrift = this.pts - now;
            this.lastUpdated = now;
            this.paused = false;
        }
    }

    /**
     * FORCE CLOCK TO A SPECIFIC TIME (SEEK). THE CLOCK IS FROZEN AT THIS VALUE
     * UNTIL THE FIRST update() RECALIBRATES IT. THIS PREVENTS time() FROM
     * ADVANCING DURING SEEK PROCESSING (HTTP RECONNECTION, SYNC DRAIN),
     * WHICH WOULD CAUSE VISIBLE CLOCK JUMPS FOR USERS AND SYNC ISSUES FOR DEVS.
     */
    public void force(double ptsSec, int serial) {
        this.pts = ptsSec;
        this.serial = serial;
        this.frozen = true;
    }

    public void forceMs(long ptsMs, int serial) {
        this.force(ptsMs / 1000.0, serial);
    }

    public void reset() {
        double now = wallclock();
        this.pts = 0;
        this.ptsDrift = -now;
        this.lastUpdated = now;
        this.paused = false;
        this.frozen = false;
        this.serial = 0;
        this.speed = 1.0;
    }

    // ═══════════════════════════════════════════
    //  FRAME TIMING
    // ═══════════════════════════════════════════

    /** Setear FPS del stream. Llamar al abrir el video stream. */
    public void fps(float fps) {
        this.fps = Math.max(fps, 1.0f);
        this.frameDurationSec = 1.0 / this.fps;
        // Skip threshold: ~5 frames worth
        this.skipThresholdMs = (long) (this.frameDurationSec * 5.0 * 1000.0);
    }

    public float fps() { return this.fps; }
    public long frameDurationMs() { return (long) (this.frameDurationSec * 1000.0); }
    public double frameDurationSec() { return this.frameDurationSec; }
    public long skipThresholdMs() { return this.skipThresholdMs; }
    public int serial() { return this.serial; }
    public boolean paused() { return this.paused; }
    public boolean frozen() { return this.frozen; }
    public double speed() { return this.speed; }

    public void speed(double speed) {
        double currentTime = this.time();
        this.speed = speed;
        this.force(currentTime, this.serial);
    }
}
