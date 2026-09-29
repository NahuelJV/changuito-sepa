package ar.changuito.exportador;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PresentacionesTest {

    @Test
    void conUnidadGenericaSeTomaLaDeLaDescripcion() {
        // Carrefour informa "1 UNI" y la presentación real va en la descripción.
        assertThat(texto("1", "UNI", "FIDEOS PENNE RIGATE N45 LUCCHETTI X 500 GRS")).isEqualTo("500 G");
        assertThat(texto("1", "UNI", "GASEOSA REGULAR POMELO CARREFOUR X 2.25 LT")).isEqualTo("2.25 L");
        assertThat(texto("1", "UNI", "MAYONESA D FIESTA X 500 CC")).isEqualTo("500 ML");
    }

    @Test
    void conUnidadRealSeTomanLasColumnas() {
        assertThat(texto("500", "grm", "FIDEO PENNE")).isEqualTo("500 G");
        assertThat(texto("1", "ltr", "LECHE")).isEqualTo("1 L");
        assertThat(texto("1.000", "KG", "YERBA")).isEqualTo("1 KG");
        assertThat(texto("100.00", "GR", "PRESERVATIVOS")).isEqualTo("100 G");
    }

    @Test
    void elMultiplicadorDeUnMultipackNoEsLaPresentacion() {
        assertThat(texto("1", "UN", "AGUA MINERAL PACK 6 X 1.5 L")).isEqualTo("1.5 L");
    }

    @Test
    void sinPresentacionReconocibleQuedaNula() {
        assertThat(Presentaciones.de("1.00", "UD", "DISPLAY LIBROS 16PAG")).isNull();
        assertThat(Presentaciones.de("", "", "")).isNull();
    }

    private static String texto(String cantidad, String unidad, String descripcion) {
        Presentaciones.Presentacion p = Presentaciones.de(cantidad, unidad, descripcion);
        return p == null ? null : p.cantidad().toPlainString() + " " + p.unidad();
    }
}
