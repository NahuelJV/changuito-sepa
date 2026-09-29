package ar.changuito.exportador.sepa;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;


/**
 * RN-21: decide si una bandera de SEPA pertenece a alguna cadena configurada ({@code changuito.sepa.cadenas}).
 * Compara por palabra completa, sin tildes ni mayúsculas, contra razón social, nombre y URL de la bandera,
 * así "CARREFOUR" reconoce a INC S.A. por {@code www.carrefour.com.ar} y "COTO" a "COTO CICSA".
 */
public final class CoincidenciaCadenas {

    private final Map<String, Pattern> patrones = new LinkedHashMap<>();

    public CoincidenciaCadenas(List<String> cadenas) {
        for (String cadena : cadenas) {
            String nombre = Textos.sinTildesMayus(cadena);
            if (!nombre.isEmpty()) {
                patrones.put(nombre, Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(nombre) + "(?![A-Z0-9])"));
            }
        }
    }

    public Optional<String> cadena(String razonSocial, String bandera, String url) {
        String texto = Textos.sinTildesMayus(razonSocial) + " | " + Textos.sinTildesMayus(bandera) + " | "
                + Textos.sinTildesMayus(url);
        return patrones.entrySet().stream()
                .filter(e -> e.getValue().matcher(texto).find())
                .map(Map.Entry::getKey)
                .findFirst();
    }

    public boolean vacia() {
        return patrones.isEmpty();
    }
}
