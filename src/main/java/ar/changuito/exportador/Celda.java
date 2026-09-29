package ar.changuito.exportador;

/**
 * Grilla del contrato v1: celdas de 0,1° × 0,1°, con id {@code floor(lat*10)_floor(lon*10)}.
 * El Obelisco (-34.6037, -58.3816) cae en {@code -347_-584}.
 * <p>
 * Se calcula literalmente como {@code Math.floor(lat * 10)} en double, igual que en la app, para que los dos lados
 * den siempre el mismo id. Es piso y no truncamiento: -34.6037 da -347 (no -346). Una sucursal justo en el borde
 * (-34.7) cae en -347 (en double, {@code -34.7 * 10} da exactamente -347.0 para todos los bordes de un decimal
 * de la Argentina; lo verifica {@code CeldaTest}).
 */
public final class Celda {

    /** Caja de la Argentina continental e insular con margen: afuera se considera una coordenada errónea. */
    static final double LAT_MIN = -56.0;
    static final double LAT_MAX = -21.0;
    static final double LON_MIN = -74.0;
    static final double LON_MAX = -53.0;

    private Celda() {
    }

    public static String id(double lat, double lon) {
        return indice(lat) + "_" + indice(lon);
    }

    static long indice(double coordenada) {
        return (long) Math.floor(coordenada * 10);
    }

    /** Coordenadas cargadas, finitas y dentro del país (SEPA trae algunas en 0, invertidas o de otro hemisferio). */
    public static boolean enArgentina(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= LAT_MIN && lat <= LAT_MAX && lon >= LON_MIN && lon <= LON_MAX;
    }
}
