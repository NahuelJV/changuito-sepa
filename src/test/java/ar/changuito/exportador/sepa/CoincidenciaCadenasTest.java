package ar.changuito.exportador.sepa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CoincidenciaCadenasTest {

    private final CoincidenciaCadenas cadenas = new CoincidenciaCadenas(List.of("CARREFOUR", "COTO"));

    @Test
    void rn21_carrefourSeReconocePorLaUrlAunqueLaRazonSocialSeaIncSa() {
        assertThat(cadenas.cadena("INC S.A.", "Market", "www.carrefour.com.ar")).contains("CARREFOUR");
        assertThat(cadenas.cadena("INC S.A.", "Hipermercado Carrefour", "")).contains("CARREFOUR");
    }

    @Test
    void rn21_cotoSeReconocePorRazonSocialYBandera() {
        assertThat(cadenas.cadena("COTO CENTRO INTEGRAL DE COMERCIALIZACION S.A.", "COTO CICSA", "http://www.coto.com.ar/"))
                .contains("COTO");
    }

    @Test
    void rn21_otrasCadenasNoSeImportan() {
        assertThat(cadenas.cadena("DIA Argentina S.A.", "Supermercados DIA", "https://www.supermercadosdia.com.ar")).isEmpty();
        assertThat(cadenas.cadena("Cencosud S.A.", "Vea", "http://www.Veadigital.com.ar")).isEmpty();
    }

    @Test
    void rn21_comparaPorPalabraCompletaSinTildesNiMayusculas() {
        assertThat(cadenas.cadena("Cotorra Hnos S.R.L.", "Cotorrita", "")).isEmpty();
        assertThat(new CoincidenciaCadenas(List.of("Cooperativa Obrera"))
                .cadena("Cooperativa Obrera Limitada de Consumo y Vivienda", "", "")).contains("COOPERATIVA OBRERA");
        assertThat(new CoincidenciaCadenas(List.of("la anónima")).cadena("S.A. IMP. Y EXP.", "La Anonima", ""))
                .contains("LA ANONIMA");
    }
}
