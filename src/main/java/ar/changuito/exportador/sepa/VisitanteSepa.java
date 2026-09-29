package ar.changuito.exportador.sepa;

import java.util.List;
import java.util.Set;

/** Recibe el contenido de cada comercio del archivo SEPA en el orden comercio → sucursales → productos. */
public interface VisitanteSepa {

    /**
     * Filtro previo, sin efectos, que se evalúa mientras se lee la descarga: si da false, el zip del comercio
     * ni se guarda. Por defecto interesa todo.
     */
    default boolean interesa(String archivo, List<FilaCsv> comercio) {
        return true;
    }

    /**
     * @param comercio filas de comercio.csv (una por bandera)
     * @return true si el comercio interesa; false saltea sus sucursales y productos
     */
    boolean comercio(String archivo, List<FilaCsv> comercio);

    /** @return claves {@code id_comercio-id_bandera-id_sucursal} cuyos productos interesan (vacío saltea productos.csv) */
    Set<String> sucursales(List<FilaCsv> sucursales);

    /** Solo se llama para filas de sucursales devueltas por {@link #sucursales}. */
    void producto(String claveSucursal, FilaCsv fila);

    /** Se llama al terminar productos.csv de un comercio cuyas sucursales interesaron. */
    default void finComercio(String archivo) {
    }

    static String clave(FilaCsv fila) {
        return fila.get("id_comercio") + "-" + fila.get("id_bandera") + "-" + fila.get("id_sucursal");
    }
}
