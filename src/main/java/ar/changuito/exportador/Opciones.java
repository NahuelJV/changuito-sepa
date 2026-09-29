package ar.changuito.exportador;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import ar.changuito.exportador.sepa.CkanSepaClient;

/** Opciones de la línea de comandos. */
public record Opciones(LocalDate fecha, Path zip, List<String> cadenas, Path salida, BigDecimal descuentoMaximo,
                       boolean preciosEnCentavos, Path temporales, Path guardarDescarga, int intentos,
                       String ckanUrl, String datasetId, boolean medirSinDeduplicar, int hilos, boolean verbose) {

    public static final List<String> CADENAS_POR_DEFECTO = List.of("CARREFOUR", "COTO");
    public static final BigDecimal DESCUENTO_MAXIMO = new BigDecimal("0.70");

    public static final String USO = """
            Uso: java -jar sepa-exportador.jar --salida <dir> [opciones]

              --salida <dir>          Carpeta de publicación: se reemplaza <dir>/v1 (obligatoria)
              --fecha <AAAA-MM-DD>    Archivo SEPA de esa fecha (por defecto, el de hoy en hora argentina)
              --zip <archivo>         Usa un zip SEPA local en vez de bajarlo del portal
              --cadenas <A,B>         Cadenas a exportar, RN-21 (por defecto CARREFOUR,COTO)
              --descuento-maximo <x>  RN-19: la promo se toma si el descuento es menor (por defecto 0.70)
              --precios-en-centavos   Los precios del archivo vienen en centavos (hoy SEPA los publica en pesos)
              --guardar-descarga <f>  Conserva el zip bajado en <f> (para repetir la corrida con --zip)
              --temporales <dir>      Carpeta para los temporales (por defecto, la del sistema)
              --intentos <n>          Intentos de descarga (por defecto 3)
              --hilos <n>             Hilos para comprimir las celdas (por defecto, los procesadores)
              --medir-sin-dedup       Mide cuánto pesarían las celdas en el formato v1 original
              --ckan-url <url>        API package_show de CKAN (por defecto la de datos.produccion.gob.ar)
              --dataset <id>          Dataset de CKAN (por defecto sepa-precios)
              --verbose               Log detallado
              --help                  Esta ayuda

            Códigos de salida: 0 ok, 1 error, 2 uso incorrecto, 3 el portal no publica el archivo de esa fecha.
            """;

    public static Opciones parsear(String... args) {
        LocalDate fecha = null;
        Path zip = null;
        List<String> cadenas = CADENAS_POR_DEFECTO;
        Path salida = null;
        BigDecimal descuento = DESCUENTO_MAXIMO;
        boolean centavos = false;
        Path temporales = null;
        Path guardar = null;
        int intentos = 3;
        String ckan = CkanSepaClient.CKAN_URL;
        String dataset = CkanSepaClient.DATASET_ID;
        boolean medir = false;
        int hilos = Runtime.getRuntime().availableProcessors();
        boolean verbose = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--fecha" -> fecha = fecha(valor(args, ++i, a));
                case "--zip" -> zip = Path.of(valor(args, ++i, a));
                case "--cadenas" -> cadenas = cadenas(valor(args, ++i, a));
                case "--salida" -> salida = Path.of(valor(args, ++i, a));
                case "--descuento-maximo" -> descuento = descuento(valor(args, ++i, a));
                case "--precios-en-centavos" -> centavos = true;
                case "--temporales" -> temporales = Path.of(valor(args, ++i, a));
                case "--guardar-descarga" -> guardar = Path.of(valor(args, ++i, a));
                case "--intentos" -> intentos = entero(valor(args, ++i, a), a);
                case "--hilos" -> hilos = entero(valor(args, ++i, a), a);
                case "--ckan-url" -> ckan = valor(args, ++i, a);
                case "--dataset" -> dataset = valor(args, ++i, a);
                case "--medir-sin-dedup" -> medir = true;
                case "--verbose" -> verbose = true;
                default -> throw new UsoIncorrecto("Opción desconocida: " + a);
            }
        }
        if (salida == null) {
            throw new UsoIncorrecto("Falta --salida");
        }
        return new Opciones(fecha, zip, cadenas, salida, descuento, centavos, temporales, guardar, intentos, ckan,
                dataset, medir, hilos, verbose);
    }

    static List<String> cadenas(String texto) {
        List<String> lista = Arrays.stream(texto.split(","))
                .map(String::trim).filter(c -> !c.isEmpty()).map(c -> c.toUpperCase(Locale.ROOT)).distinct().toList();
        if (lista.isEmpty()) {
            throw new UsoIncorrecto("--cadenas no puede estar vacío");
        }
        return lista;
    }

    private static String valor(String[] args, int i, String opcion) {
        if (i >= args.length || args[i].startsWith("--")) {
            throw new UsoIncorrecto("Falta el valor de " + opcion);
        }
        return args[i];
    }

    private static LocalDate fecha(String texto) {
        try {
            return LocalDate.parse(texto);
        } catch (DateTimeParseException e) {
            throw new UsoIncorrecto("Fecha inválida (AAAA-MM-DD): " + texto);
        }
    }

    private static BigDecimal descuento(String texto) {
        try {
            BigDecimal d = new BigDecimal(texto);
            if (d.signum() <= 0 || d.compareTo(BigDecimal.ONE) > 0) {
                throw new UsoIncorrecto("--descuento-maximo va entre 0 y 1: " + texto);
            }
            return d;
        } catch (NumberFormatException e) {
            throw new UsoIncorrecto("--descuento-maximo inválido: " + texto);
        }
    }

    private static int entero(String texto, String opcion) {
        try {
            int n = Integer.parseInt(texto);
            if (n < 1) {
                throw new UsoIncorrecto(opcion + " debe ser mayor que 0");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new UsoIncorrecto(opcion + " inválido: " + texto);
        }
    }

    /** Error de uso: se informa con la ayuda y código 2. */
    public static final class UsoIncorrecto extends RuntimeException {
        public UsoIncorrecto(String mensaje) {
            super(mensaje);
        }
    }
}
