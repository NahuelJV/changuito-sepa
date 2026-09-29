package ar.changuito.exportador;

import java.math.BigDecimal;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ar.changuito.exportador.sepa.Textos;

/**
 * Presentación del catálogo ({@code cantidad}, {@code unidad}), con la misma lógica que el importador del backend
 * ({@code SepaImportador#presentacion} + {@code Normalizador}): primero las columnas de SEPA y, si la unidad es
 * genérica ("1 UNI", como informa Carrefour), la cantidad y unidad que aparecen en la descripción ("X 500 GRS").
 * <p>
 * A diferencia del backend no se pasa a unidades base: la cantidad queda como la informa el comercio y la unidad
 * se canoniza a {@code G}, {@code KG}, {@code ML}, {@code L} o {@code UN} (por ejemplo {@code 1, "L"} o {@code 500, "G"}).
 */
public final class Presentaciones {

    private static final Set<String> UNIDADES_GENERICAS = Set.of("", "U", "UN", "UD", "UNI", "UNID", "UNIDAD", "UNIDADES");
    private static final Pattern CANTIDAD_UNIDAD = Pattern.compile(
            "(?<![A-Z0-9])(\\d+(?:[.,]\\d+)?)\\s*(KILOGRAMOS|KILOS|KILO|KGS|KGM|KG|GRAMOS|GRS|GRM|GR|G|LITROS|LITRO|LTRS|LTR|LTS|LT|L|MILILITROS|ML|CC|CM3|UNIDADES|UNID|UNI|UN|U)(?![A-Z0-9])");
    private static final Pattern MULTIPACK = Pattern.compile("(?<![A-Z0-9])(\\d{1,2})\\s*X\\s*(?=\\d)");
    private static final Pattern SEPARADORES = Pattern.compile("[^A-Z0-9., ]");
    private static final Pattern ESPACIOS = Pattern.compile("\\s+");

    public record Presentacion(BigDecimal cantidad, String unidad) {
    }

    private Presentaciones() {
    }

    public static Presentacion de(String cantidad, String unidad, String descripcion) {
        Presentacion p = null;
        String u = Textos.sinTildesMayus(unidad);
        if (!UNIDADES_GENERICAS.contains(u) && !Textos.vacio(cantidad)) {
            p = aPresentacion(cantidad.trim(), u);
        }
        return p != null ? p : deDescripcion(descripcion);
    }

    /** La última cantidad con unidad de la descripción, sin contar el multiplicador de un multipack ("6 X 1 L"). */
    static Presentacion deDescripcion(String descripcion) {
        if (Textos.vacio(descripcion)) {
            return null;
        }
        String texto = Textos.sinTildesMayus(descripcion);
        texto = texto.replaceAll("\\bC/\\s*", "CON ").replaceAll("\\bS/\\s*", "SIN ").replaceAll("\\bP/\\s*", "PARA ");
        texto = SEPARADORES.matcher(texto).replaceAll(" ");
        texto = texto.replaceAll("(?<=[A-Z])\\.|\\.(?=[A-Z ])|\\.$|,(?!\\d)|(?<!\\d),", " ");
        texto = ESPACIOS.matcher(texto).replaceAll(" ").trim();
        texto = MULTIPACK.matcher(texto).replaceAll("");
        Presentacion ultima = null;
        Matcher m = CANTIDAD_UNIDAD.matcher(texto);
        while (m.find()) {
            Presentacion p = aPresentacion(m.group(1), m.group(2));
            if (p != null) {
                ultima = p;
            }
        }
        return ultima;
    }

    private static Presentacion aPresentacion(String numero, String unidad) {
        BigDecimal cantidad;
        try {
            cantidad = new BigDecimal(numero.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
        if (cantidad.signum() <= 0) {
            return null;
        }
        String base = unidadCanonica(unidad);
        if (base == null) {
            return null;
        }
        cantidad = cantidad.stripTrailingZeros();
        if (cantidad.scale() < 0) {
            cantidad = cantidad.setScale(0);
        }
        return new Presentacion(cantidad, base);
    }

    static String unidadCanonica(String unidad) {
        return switch (unidad) {
            case "KILOGRAMOS", "KILOS", "KILO", "KGS", "KGM", "KG" -> "KG";
            case "GRAMOS", "GRS", "GRM", "GR", "G" -> "G";
            case "LITROS", "LITRO", "LTRS", "LTR", "LTS", "LT", "L" -> "L";
            case "MILILITROS", "ML", "CC", "CM3" -> "ML";
            case "UNIDADES", "UNIDAD", "UNID", "UNI", "UN", "UD", "U" -> "UN";
            default -> null;
        };
    }
}
