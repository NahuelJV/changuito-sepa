package ar.changuito.exportador.sepa;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


/**
 * Stream de descarga con vigilancia de inactividad: el HttpClient del JDK no tiene timeout de lectura para el
 * cuerpo, así que si el portal deja de mandar bytes la lectura quedaría bloqueada para siempre. Si pasa
 * {@code inactividadMaxima} sin avanzar, se cierra el stream y la lectura pendiente falla con IOException
 * (la importación lo trata como un intento fallido y reintenta).
 */
public final class DescargaVigilada extends FilterInputStream {

    private static final ScheduledExecutorService VIGIA = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sepa-vigia-descarga");
        t.setDaemon(true);
        return t;
    });

    private final long inactividadNanos;
    private final ScheduledFuture<?> control;
    private volatile long ultimoAvance = System.nanoTime();
    private volatile boolean cortada;
    private volatile boolean cerrada;

    public DescargaVigilada(InputStream in, Duration inactividadMaxima) {
        super(in);
        this.inactividadNanos = inactividadMaxima.toNanos();
        long cada = Math.max(50, Math.min(5000, inactividadMaxima.toMillis() / 4));
        this.control = VIGIA.scheduleWithFixedDelay(this::revisar, cada, cada, TimeUnit.MILLISECONDS);
    }

    private void revisar() {
        if (cerrada || System.nanoTime() - ultimoAvance < inactividadNanos) {
            return;
        }
        cortada = true;
        Log.warn("SEPA: la descarga no avanzó en {} s, se corta", TimeUnit.NANOSECONDS.toSeconds(inactividadNanos));
        try {
            close();
        } catch (IOException e) {
            Log.debug("SEPA: error cerrando la descarga trabada: {}", e.getMessage());
        }
    }

    @Override
    public int read() throws IOException {
        return avance(verificar(super.read()));
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return avance(verificar(super.read(b, off, len)));
    }

    /** Al llegar al final ya no hay nada que vigilar: la descarga terminó aunque el stream siga abierto. */
    private int avance(int resultado) {
        ultimoAvance = System.nanoTime();
        if (resultado < 0) {
            control.cancel(false);
        }
        return resultado;
    }

    @Override
    public long skip(long n) throws IOException {
        long s = super.skip(n);
        ultimoAvance = System.nanoTime();
        return s;
    }

    private int verificar(int resultado) throws IOException {
        if (cortada) {
            throw new IOException("La descarga SEPA quedó sin avanzar y se cortó");
        }
        return resultado;
    }

    public boolean cortada() {
        return cortada;
    }

    @Override
    public void close() throws IOException {
        cerrada = true;
        control.cancel(false);
        super.close();
    }
}
