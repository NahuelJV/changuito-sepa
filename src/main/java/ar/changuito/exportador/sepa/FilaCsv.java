package ar.changuito.exportador.sepa;

import java.util.Map;

/** Una fila de un CSV SEPA, accesible por nombre de columna. Las columnas faltantes devuelven "". */
public final class FilaCsv {

    private final Map<String, Integer> columnas;
    private final String[] valores;

    FilaCsv(Map<String, Integer> columnas, String[] valores) {
        this.columnas = columnas;
        this.valores = valores;
    }

    public String get(String columna) {
        Integer indice = columnas.get(columna);
        if (indice == null || indice >= valores.length) {
            return "";
        }
        return valores[indice].trim();
    }

    @Override
    public String toString() {
        return String.join("|", valores);
    }
}
