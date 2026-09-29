package ar.changuito.exportador;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import ar.changuito.exportador.EscritorSalida.Archivo;
import ar.changuito.exportador.EscritorSalida.CeldaEscrita;
import ar.changuito.exportador.RecolectorSepa.Sucursal;
import ar.changuito.exportador.sepa.CkanSepaClient;
import ar.changuito.exportador.sepa.Log;
import ar.changuito.exportador.sepa.SepaArchivoParser;

/**
 * Corrida completa: obtiene el zip SEPA (portal o archivo local), lo recorre y publica en {@code <salida>/v1}.
 * La salida nueva se arma aparte y recién al final reemplaza a la anterior, así una corrida fallida no deja
 * archivos a medio escribir ni celdas viejas mezcladas.
 */
public final class Exportador {

    public static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");

    private final Opciones opciones;
    private final CkanSepaClient client;
    private final Clock clock;

    public Exportador(Opciones opciones) {
        this(opciones, new CkanSepaClient(opciones.ckanUrl(), opciones.datasetId()), Clock.systemUTC());
    }

    Exportador(Opciones opciones, CkanSepaClient client, Clock clock) {
        this.opciones = opciones;
        this.client = client;
        this.clock = clock;
    }

    /** El portal no publica el archivo de la fecha pedida (código de salida 3). */
    public static final class NoPublicado extends RuntimeException {
        public NoPublicado(String mensaje) {
            super(mensaje);
        }
    }

    public record Resultado(LocalDate fecha, int sucursales, int celdas, int listasDistintas, int productos,
                            long bytesDescarga, Archivo catalogo, Archivo indice, List<CeldaEscrita> celdasEscritas,
                            long bytesSinDeduplicar, Duration descarga, Duration lectura, Duration escritura,
                            Map<String, Integer> sucursalesPorCadena, RecolectorSepa.Estadisticas estadisticas) {

        public long bytesCeldas() {
            return celdasEscritas.stream().mapToLong(c -> c.archivo().bytes()).sum();
        }

        public long bytesTotales() {
            return bytesCeldas() + catalogo.bytes() + indice.bytes();
        }
    }

    public Resultado exportar() throws IOException {
        Instant inicio = Instant.now(clock);
        Path temporales = opciones.temporales();
        if (temporales != null) {
            Files.createDirectories(temporales);
        }

        // 1. Descarga completa (o zip local).
        Path zip = opciones.zip();
        Path descargaTemporal = null;
        LocalDate fechaPedida = opciones.fecha();
        long bytesDescarga;
        Duration descarga = Duration.ZERO;
        try {
            if (zip == null) {
                LocalDate fecha = fechaPedida != null ? fechaPedida : LocalDate.now(clock.withZone(ARGENTINA));
                fechaPedida = fecha;
                URI recurso = client.buscarRecurso(fecha)
                        .orElseThrow(() -> new NoPublicado("El portal no publica el archivo SEPA del " + fecha));
                Log.info("SEPA {}: bajando {}", fecha, recurso);
                zip = opciones.guardarDescarga() != null ? opciones.guardarDescarga()
                        : (descargaTemporal = temporales == null ? Files.createTempFile("sepa-dia-", ".zip")
                        : Files.createTempFile(temporales, "sepa-dia-", ".zip"));
                if (zip.getParent() != null) {
                    Files.createDirectories(zip.getParent());
                }
                Instant t = Instant.now(clock);
                bytesDescarga = client.descargar(recurso, zip, opciones.intentos());
                descarga = Duration.between(t, Instant.now(clock));
            } else {
                bytesDescarga = Files.size(zip);
            }

            // 2. Lectura: primero se leen los zips de cada comercio a temporales, después se procesan.
            Instant t = Instant.now(clock);
            RecolectorSepa recolector = new RecolectorSepa(opciones.cadenas(), opciones.descuentoMaximo(),
                    opciones.preciosEnCentavos());
            try (InputStream in = new BufferedInputStream(Files.newInputStream(zip), 1 << 16)) {
                new SepaArchivoParser(temporales).recorrer(in, recolector);
            }
            Duration lectura = Duration.between(t, Instant.now(clock));
            LocalDate fecha = fecha(fechaPedida, recolector);

            List<Sucursal> sucursales = recolector.sucursales();
            if (sucursales.isEmpty()) {
                throw new IllegalStateException("El archivo no tiene sucursales de " + opciones.cadenas()
                        + " con precios: no se publica nada");
            }
            Map<String, Integer> porCadena = new TreeMap<>();
            opciones.cadenas().forEach(c -> porCadena.put(c, 0));
            sucursales.forEach(s -> porCadena.merge(s.cadena(), 1, Integer::sum));
            porCadena.forEach((cadena, n) -> {
                if (n == 0) {
                    Log.warn("SEPA {}: no hay sucursales de {} en el archivo", fecha, cadena);
                }
            });

            // 3. Escritura en una carpeta aparte y reemplazo de la salida anterior.
            t = Instant.now(clock);
            Map<String, List<Sucursal>> porCelda = new TreeMap<>();
            for (Sucursal s : sucursales) {
                porCelda.computeIfAbsent(s.celda(), c -> new ArrayList<>()).add(s);
            }
            Path salida = opciones.salida();
            Files.createDirectories(salida);
            Path nueva = salida.resolve(".v1-nueva");
            borrar(nueva);
            Archivo catalogo = EscritorSalida.escribirCatalogo(nueva, recolector.catalogo());
            List<CeldaEscrita> celdas = escribirCeldas(nueva, fecha, porCelda);
            long sinDeduplicar = opciones.medirSinDeduplicar() ? medirSinDeduplicar(fecha, porCelda) : -1;
            Archivo indice = EscritorSalida.escribirIndice(nueva, fecha, Instant.now(clock), opciones.cadenas(),
                    catalogo, celdas);
            reemplazar(nueva.resolve(EscritorSalida.VERSION), salida.resolve(EscritorSalida.VERSION));
            borrar(nueva);
            Duration escritura = Duration.between(t, Instant.now(clock));

            Log.info("SEPA {}: listo en {} s", fecha, Duration.between(inicio, Instant.now(clock)).toSeconds());
            return new Resultado(fecha, sucursales.size(), celdas.size(), recolector.listasDistintas(),
                    recolector.catalogo().size(), bytesDescarga, catalogo, indice, celdas, sinDeduplicar,
                    descarga, lectura, escritura, porCadena, recolector.estadisticas());
        } finally {
            if (descargaTemporal != null) {
                Files.deleteIfExists(descargaTemporal);
            }
        }
    }

    private LocalDate fecha(LocalDate pedida, RecolectorSepa recolector) {
        LocalDate delArchivo = recolector.fechaArchivo().orElse(null);
        if (pedida != null) {
            if (delArchivo != null && !delArchivo.equals(pedida)) {
                Log.warn("SEPA: se pidió el {} pero el archivo es del {}; se publica con la fecha del archivo",
                        pedida, delArchivo);
                return delArchivo;
            }
            return pedida;
        }
        if (delArchivo == null) {
            throw new IllegalStateException("No se pudo leer la fecha del archivo: indicala con --fecha");
        }
        return delArchivo;
    }

    private List<CeldaEscrita> escribirCeldas(Path raiz, LocalDate fecha, Map<String, List<Sucursal>> porCelda)
            throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, opciones.hilos()));
        try {
            List<Future<CeldaEscrita>> futuros = new ArrayList<>();
            // Las celdas más pobladas primero, para repartir mejor el trabajo entre los hilos.
            porCelda.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, List<Sucursal>> e) -> -e.getValue().size()))
                    .forEach(e -> futuros.add(pool.submit(
                            () -> EscritorSalida.escribirCelda(raiz, e.getKey(), fecha, e.getValue()))));
            List<CeldaEscrita> celdas = new ArrayList<>();
            for (Future<CeldaEscrita> f : futuros) {
                celdas.add(esperar(f));
            }
            celdas.sort(Comparator.comparing(CeldaEscrita::id));
            return celdas;
        } finally {
            pool.shutdownNow();
        }
    }

    private long medirSinDeduplicar(LocalDate fecha, Map<String, List<Sucursal>> porCelda) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, opciones.hilos()));
        try {
            List<Future<Long>> futuros = new ArrayList<>();
            porCelda.values().forEach(s -> futuros.add(pool.submit(() -> EscritorSalida.medirCeldaSinDeduplicar(fecha, s))));
            long total = 0;
            for (Future<Long> f : futuros) {
                total += esperar(f);
            }
            return total;
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> T esperar(Future<T> futuro) throws IOException {
        try {
            return futuro.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Escritura interrumpida", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException(e.getCause());
        }
    }

    /** Cambia la carpeta publicada por la nueva: la vieja se aparta primero y se borra después. */
    private static void reemplazar(Path nueva, Path destino) throws IOException {
        Path vieja = destino.resolveSibling(".v1-vieja");
        borrar(vieja);
        if (Files.exists(destino)) {
            Files.move(destino, vieja, StandardCopyOption.ATOMIC_MOVE);
        }
        Files.move(nueva, destino, StandardCopyOption.ATOMIC_MOVE);
        borrar(vieja);
    }

    static void borrar(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> archivos = Files.walk(dir)) {
            for (Path p : archivos.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
