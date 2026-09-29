package ar.changuito.exportador.sepa;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Log mínimo a stderr con marcadores {@code {}} al estilo SLF4J (el exportador no usa frameworks).
 * {@code debug} solo se muestra con {@code --verbose} o la variable {@code SEPA_EXPORTADOR_DEBUG=1}.
 */
public final class Log {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static volatile boolean debug = "1".equals(System.getenv("SEPA_EXPORTADOR_DEBUG"));
    private static volatile PrintStream salida = System.err;

    private Log() {
    }

    public static void habilitarDebug(boolean habilitado) {
        debug = habilitado;
    }

    public static void info(String mensaje, Object... args) {
        escribir("INFO ", mensaje, args);
    }

    public static void warn(String mensaje, Object... args) {
        escribir("WARN ", mensaje, args);
    }

    public static void error(String mensaje, Object... args) {
        escribir("ERROR", mensaje, args);
    }

    public static void debug(String mensaje, Object... args) {
        if (debug) {
            escribir("DEBUG", mensaje, args);
        }
    }

    static String formatear(String mensaje, Object... args) {
        StringBuilder sb = new StringBuilder(mensaje.length() + 32);
        int arg = 0;
        int desde = 0;
        int i;
        while ((i = mensaje.indexOf("{}", desde)) >= 0) {
            sb.append(mensaje, desde, i);
            sb.append(arg < args.length ? args[arg++] : "{}");
            desde = i + 2;
        }
        sb.append(mensaje.substring(desde));
        return sb.toString();
    }

    private static void escribir(String nivel, String mensaje, Object... args) {
        salida.println(LocalTime.now().format(HORA) + " " + nivel + " " + formatear(mensaje, args));
    }
}
