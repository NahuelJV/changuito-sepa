package ar.changuito.exportador.sepa;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class PrecioSepaTest {

    private static final BigDecimal MAXIMO = new BigDecimal("0.70");

    @Test
    void rn19_sinPromoSeUsaElPrecioDeLista() {
        PrecioSepa p = PrecioSepa.calcular("1749.00", "", "", "", "", MAXIMO, false).orElseThrow();
        assertThat(p.precio()).isEqualByComparingTo("1749.00");
        assertThat(p.lista()).isEqualByComparingTo("1749.00");
        assertThat(p.promo()).isNull();
    }

    @Test
    void rn19_promoConDescuentoMenorAl70PorCientoSeToma() {
        PrecioSepa p = PrecioSepa.calcular("1950.00", "1790.00", "Promo A valida desde el 23/09/2026", "", "", MAXIMO, false)
                .orElseThrow();
        assertThat(p.precio()).isEqualByComparingTo("1790.00");
        assertThat(p.lista()).isEqualByComparingTo("1950.00");
        assertThat(p.promo()).isEqualTo("Promo A valida desde el 23/09/2026");
    }

    @Test
    void rn19_promoConDescuentoDe70PorCientoOMasSeDescartaPorqueSueleSerUnaCuota() {
        PrecioSepa p = PrecioSepa.calcular("4090.00", "1000.00", "12 cuotas", "", "", MAXIMO, false).orElseThrow();
        assertThat(p.precio()).isEqualByComparingTo("4090.00");
        assertThat(p.promo()).isNull();

        PrecioSepa justo70 = PrecioSepa.calcular("1000", "300", "70 %", "", "", MAXIMO, false).orElseThrow();
        assertThat(justo70.precio()).isEqualByComparingTo("1000");
    }

    @Test
    void rn19_entreDosPromosValidasGanaLaMasBaja() {
        PrecioSepa p = PrecioSepa.calcular("1000", "900", "10 %", "800", "20 %", MAXIMO, false).orElseThrow();
        assertThat(p.precio()).isEqualByComparingTo("800");
        assertThat(p.promo()).isEqualTo("20 %");

        PrecioSepa unaInvalida = PrecioSepa.calcular("1000", "900", "10 %", "100", "cuota", MAXIMO, false).orElseThrow();
        assertThat(unaInvalida.precio()).isEqualByComparingTo("900");
    }

    @Test
    void rn19_promoMayorOIgualALaListaNoEsPromo() {
        assertThat(PrecioSepa.calcular("1000", "1000", "igual", "1200", "mayor", MAXIMO, false).orElseThrow().precio())
                .isEqualByComparingTo("1000");
    }

    @Test
    void preciosEnCentavosSePasanAPesos() {
        assertThat(PrecioSepa.calcular("174900", "", "", "", "", MAXIMO, true).orElseThrow().precio())
                .isEqualByComparingTo("1749.00");
    }

    @Test
    void sinPrecioDeListaNoHayObservacion() {
        assertThat(PrecioSepa.calcular("", "100", "x", "", "", MAXIMO, false)).isEmpty();
        assertThat(PrecioSepa.calcular("0", "", "", "", "", MAXIMO, false)).isEmpty();
        assertThat(PrecioSepa.calcular("abc", "", "", "", "", MAXIMO, false)).isEmpty();
    }
}
