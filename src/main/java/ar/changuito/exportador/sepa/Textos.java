package ar.changuito.exportador.sepa;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Utilidades de texto: mayúsculas sin tildes para comparar. */
public final class Textos {

    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern ESPACIOS = Pattern.compile("\\s+");

    private Textos() {
    }

    public static String sinTildesMayus(String texto) {
        if (texto == null) {
            return "";
        }
        String sinMarcas = MARCAS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("");
        return ESPACIOS.matcher(sinMarcas.toUpperCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /** Clave de comparación de nombres de comercio: sin tildes, sin signos. */
    public static String normalizarNombre(String texto) {
        return ESPACIOS.matcher(sinTildesMayus(texto).replaceAll("[^A-Z0-9 ]", " ")).replaceAll(" ").trim();
    }

    public static String capitalizar(String texto) {
        if (texto == null || texto.isBlank()) {
            return texto;
        }
        StringBuilder sb = new StringBuilder();
        for (String palabra : ESPACIOS.split(texto.trim().toLowerCase(Locale.ROOT))) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(palabra.charAt(0))).append(palabra.substring(1));
        }
        return sb.toString();
    }

    public static boolean vacio(String texto) {
        return texto == null || texto.isBlank();
    }

    public static String truncar(String texto, int max) {
        if (texto == null || texto.length() <= max) {
            return texto;
        }
        return texto.substring(0, max);
    }
}
