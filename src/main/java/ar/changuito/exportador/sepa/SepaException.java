package ar.changuito.exportador.sepa;

/** Fallo al buscar, descargar o leer el archivo SEPA (se reintenta). */
public class SepaException extends RuntimeException {

    public SepaException(String mensaje) {
        super(mensaje);
    }

    public SepaException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
