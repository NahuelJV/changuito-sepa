package ar.changuito.exportador;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import ar.changuito.exportador.EscritorSalida.CeldaEscrita;
import ar.changuito.exportador.sepa.Log;
import ar.changuito.exportador.sepa.SepaException;

/** Punto de entrada: {@code java -jar sepa-exportador.jar --salida <dir> [opciones]}. */
public final class Main {

    /** Celda del Obelisco, la de referencia para los tamaños. */
    static final String CELDA_OBELISCO = Celda.id(-34.6037, -58.3816);

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(ejecutar(args, System.out));
    }

    static int ejecutar(String[] args, PrintStream out) {
        if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
            out.print(Opciones.USO);
            return 0;
        }
        Opciones opciones;
        try {
            opciones = Opciones.parsear(args);
        } catch (Opciones.UsoIncorrecto e) {
            System.err.println(e.getMessage());
            System.err.print(Opciones.USO);
            return 2;
        }
        Log.habilitarDebug(opciones.verbose());
        long inicio = System.nanoTime();
        try {
            Exportador.Resultado r = new Exportador(opciones).exportar();
            reportar(r, Duration.ofNanos(System.nanoTime() - inicio), out);
            return 0;
        } catch (Exportador.NoPublicado e) {
            Log.error(e.getMessage());
            return 3;
        } catch (SepaException e) {
            Log.error("SEPA: {}", e.getMessage());
            return 1;
        } catch (Exception e) {
            Log.error("La exportación falló: {}", e);
            e.printStackTrace();
            return 1;
        }
    }

    static void reportar(Exportador.Resultado r, Duration total, PrintStream out) {
        RecolectorSepa.Estadisticas e = r.estadisticas();
        out.println();
        out.println("== Exportación SEPA " + r.fecha() + " ==");
        out.printf(Locale.ROOT, "Duración: %s total (descarga %s, lectura %s, escritura %s)%n",
                duracion(total), duracion(r.descarga()), duracion(r.lectura()), duracion(r.escritura()));
        out.printf(Locale.ROOT, "Archivo SEPA: %s%n", tamanio(r.bytesDescarga()));
        out.printf(Locale.ROOT, "Sucursales: %d %s (leídas %d; sin coordenadas válidas %d; sin productos %d; repetidas %d)%n",
                r.sucursales(), r.sucursalesPorCadena(), e.sucursalesLeidas, e.sucursalesSinCoordenadas,
                e.sucursalesSinProductos, e.sucursalesRepetidas);
        out.printf(Locale.ROOT, "Filas de productos: %d (descartadas %d; con promo RN-19 %d)%n",
                e.filasProducto, e.filasDescartadas, e.promosAplicadas);
        out.printf(Locale.ROOT, "Celdas: %d · listas distintas: %d de %d sucursales · productos en el catálogo: %d%n",
                r.celdas(), r.listasDistintas(), r.sucursales(), r.productos());
        out.printf(Locale.ROOT, "Catálogo: %s%n", tamanio(r.catalogo().bytes()));
        r.celdasEscritas().stream().filter(c -> c.id().equals(CELDA_OBELISCO)).findFirst().ifPresentOrElse(
                c -> out.printf(Locale.ROOT, "Celda del Obelisco %s: %s (%d sucursales, %d listas)%n",
                        c.id(), tamanio(c.archivo().bytes()), c.sucursales(), c.listas()),
                () -> out.println("Celda del Obelisco " + CELDA_OBELISCO + ": sin sucursales"));
        out.println("Celdas más pesadas:");
        List<CeldaEscrita> pesadas = r.celdasEscritas().stream()
                .sorted(Comparator.comparingLong((CeldaEscrita c) -> c.archivo().bytes()).reversed()).limit(5).toList();
        for (CeldaEscrita c : pesadas) {
            out.printf(Locale.ROOT, "  %-10s %10s  %3d sucursales, %3d listas%n", c.id(), tamanio(c.archivo().bytes()),
                    c.sucursales(), c.listas());
        }
        out.printf(Locale.ROOT, "Total en disco: %s (celdas %s, catálogo %s, índice %s)%n", tamanio(r.bytesTotales()),
                tamanio(r.bytesCeldas()), tamanio(r.catalogo().bytes()), tamanio(r.indice().bytes()));
        if (r.bytesSinDeduplicar() >= 0) {
            long ahorro = r.bytesSinDeduplicar() - r.bytesCeldas();
            out.printf(Locale.ROOT, "Con el formato v1 original (tuplas por sucursal, sin deduplicar) las celdas pesarían %s:"
                    + " la tabla de precios y la deduplicación ahorran %s (%.1f %%)%n",
                    tamanio(r.bytesSinDeduplicar()), tamanio(ahorro), 100.0 * ahorro / r.bytesSinDeduplicar());
        }
    }

    static String tamanio(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    static String duracion(Duration d) {
        long s = d.toSeconds();
        return s >= 60 ? String.format(Locale.ROOT, "%d min %02d s", s / 60, s % 60)
                : String.format(Locale.ROOT, "%.1f s", d.toMillis() / 1000.0);
    }
}
