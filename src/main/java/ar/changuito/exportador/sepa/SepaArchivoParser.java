package ar.changuito.exportador.sepa;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;


/**
 * Recorre el zip diario de SEPA: {@code YYYY-MM-DD/sepa_N_comercio-sepa-XX_....zip}, un zip por comercio con
 * {@code comercio.csv}, {@code sucursales.csv} y {@code productos.csv} en orden variable.
 * <p>
 * Dos fases, para no dejar la descarga parada mientras se escribe en la base (con el servidor real, una pausa de
 * varios minutos hace que corte la conexión: "closed"):
 * <ol>
 *   <li>Se lee toda la descarga sin pausas. Cada zip interno se copia a un temporal y, leyendo solo su
 *       {@code comercio.csv}, se decide si interesa ({@link VisitanteSepa#interesa}); los que no, se borran enseguida.</li>
 *   <li>Con la descarga ya terminada, se procesan los temporales que quedaron: comercio y sucursales primero, y
 *       después productos, línea a línea. Cada temporal se borra al terminar (y todos, si algo falla).</li>
 * </ol>
 * Nada se carga entero en memoria; en disco queda como máximo la suma de los zips de las cadenas configuradas.
 * <p>
 * Copia del parser del backend ({@code ar.changuito.sepa.service.SepaArchivoParser}); solo se agregó el aviso
 * {@link VisitanteSepa#finComercio}.
 */
public class SepaArchivoParser {

    private final Path directorioTemporal;

    public SepaArchivoParser() {
        this(null);
    }

    public SepaArchivoParser(Path directorioTemporal) {
        this.directorioTemporal = directorioTemporal;
    }

    private record ZipInterno(String nombre, Path temporal) {
    }

    /** @return cantidad de comercios (zips internos no vacíos) leídos */
    public int recorrer(InputStream archivoDiario, VisitanteSepa visitante) throws IOException {
        List<ZipInterno> aProcesar = new ArrayList<>();
        try {
            long bytesTotales = 0;
            int entradas = 0;
            ZipInputStream externo = new ZipInputStream(archivoDiario);
            ZipEntry entrada;
            while ((entrada = externo.getNextEntry()) != null) {
                if (entrada.isDirectory() || !entrada.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    continue;
                }
                entradas++;
                Path temporal = directorioTemporal == null
                        ? Files.createTempFile("sepa-", ".zip")
                        : Files.createTempFile(directorioTemporal, "sepa-", ".zip");
                boolean conservar = false;
                try {
                    long bytes;
                    try (OutputStream out = Files.newOutputStream(temporal)) {
                        bytes = externo.transferTo(out);
                    }
                    bytesTotales += bytes;
                    if (bytes == 0) {
                        Log.debug("SEPA: {} vacío, se saltea", entrada.getName());
                    } else if (interesa(entrada.getName(), temporal, visitante)) {
                        aProcesar.add(new ZipInterno(entrada.getName(), temporal));
                        conservar = true;
                    }
                } finally {
                    if (!conservar) {
                        Files.deleteIfExists(temporal);
                    }
                }
            }
            // El directorio central del zip (unos KB) queda sin leer: se consume para terminar la descarga
            // antes de empezar a escribir en la base.
            archivoDiario.transferTo(OutputStream.nullOutputStream());
            Log.info("SEPA: descarga leída ({} MB, {} comercios, {} de las cadenas configuradas)",
                    bytesTotales / (1024 * 1024), entradas, aProcesar.size());

            int comercios = 0;
            for (ZipInterno zip : aProcesar) {
                try {
                    if (procesarComercio(zip.nombre(), zip.temporal(), visitante)) {
                        comercios++;
                    }
                } finally {
                    Files.deleteIfExists(zip.temporal());
                }
            }
            return comercios;
        } finally {
            for (ZipInterno zip : aProcesar) {
                try {
                    Files.deleteIfExists(zip.temporal());
                } catch (IOException e) {
                    Log.warn("SEPA: no se pudo borrar el temporal {}: {}", zip.temporal(), e.getMessage());
                }
            }
        }
    }

    /** Fase 1: solo lee comercio.csv del zip interno. Un zip roto o incompleto no interesa (se avisa en el log). */
    private boolean interesa(String nombre, Path zipComercio, VisitanteSepa visitante) throws IOException {
        ZipFile zip;
        try {
            zip = new ZipFile(zipComercio.toFile());
        } catch (ZipException e) {
            Log.warn("SEPA: {} no es un zip válido ({}), se saltea", nombre, e.getMessage());
            return false;
        }
        try (zip) {
            ZipEntry comercio = buscar(zip, "comercio.csv");
            if (comercio == null) {
                Log.warn("SEPA: {} no tiene comercio.csv, se saltea", nombre);
                return false;
            }
            boolean interesa = visitante.interesa(nombre, leerTodo(zip, comercio));
            if (!interesa) {
                Log.debug("SEPA: {} no es de una cadena configurada", nombre);
            }
            return interesa;
        }
    }

    private boolean procesarComercio(String nombre, Path zipComercio, VisitanteSepa visitante) throws IOException {
        ZipFile zip;
        try {
            zip = new ZipFile(zipComercio.toFile());
        } catch (ZipException e) {
            Log.warn("SEPA: {} no es un zip válido ({}), se saltea", nombre, e.getMessage());
            return false;
        }
        try (zip) {
            ZipEntry comercio = buscar(zip, "comercio.csv");
            ZipEntry sucursales = buscar(zip, "sucursales.csv");
            ZipEntry productos = buscar(zip, "productos.csv");
            if (comercio == null || sucursales == null || productos == null) {
                Log.warn("SEPA: {} no tiene los tres CSV esperados, se saltea", nombre);
                return false;
            }
            List<FilaCsv> filasComercio = leerTodo(zip, comercio);
            if (!visitante.comercio(nombre, filasComercio)) {
                return true;
            }
            Set<String> claves = visitante.sucursales(leerTodo(zip, sucursales));
            if (claves.isEmpty()) {
                return true;
            }
            try (InputStream in = zip.getInputStream(productos)) {
                LectorCsvSepa.leer(in, fila -> {
                    String clave = VisitanteSepa.clave(fila);
                    if (claves.contains(clave)) {
                        visitante.producto(clave, fila);
                    }
                });
            }
            visitante.finComercio(nombre);
            return true;
        }
    }

    private static List<FilaCsv> leerTodo(ZipFile zip, ZipEntry entrada) throws IOException {
        try (InputStream in = zip.getInputStream(entrada)) {
            return LectorCsvSepa.leerTodo(in);
        }
    }

    private static ZipEntry buscar(ZipFile zip, String nombre) {
        Enumeration<? extends ZipEntry> entradas = zip.entries();
        while (entradas.hasMoreElements()) {
            ZipEntry e = entradas.nextElement();
            String n = e.getName().toLowerCase(Locale.ROOT);
            if (!e.isDirectory() && (n.equals(nombre) || n.endsWith("/" + nombre))) {
                return e;
            }
        }
        return null;
    }
}
