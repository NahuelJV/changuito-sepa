package ar.changuito.exportador;

import static ar.changuito.exportador.ZipSepaDePrueba.producto;
import static ar.changuito.exportador.ZipSepaDePrueba.sucursal;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Id de celda {@code floor(lat*10)_floor(lon*10)} del contrato v1. */
class CeldaTest {

    @TempDir
    Path tmp;

    @Test
    void elObeliscoCaeEnMenos347Menos584() {
        assertThat(Celda.id(-34.6037, -58.3816)).isEqualTo("-347_-584");
    }

    @Test
    void conCoordenadasNegativasEsPisoYNoTruncamiento() {
        // Truncar daría -346_-583: el piso de -346.037 es -347.
        assertThat(Celda.id(-34.6037, -58.3816)).isNotEqualTo("-346_-583");
        assertThat(Celda.id(-34.6001, -58.3001)).isEqualTo("-347_-584");
        assertThat(Celda.id(-0.05, -0.05)).isEqualTo("-1_-1");
        assertThat(Celda.id(0.05, 0.05)).isEqualTo("0_0");
        assertThat(Celda.id(-38.942149918, -68.06446909)).isEqualTo("-390_-681");
        assertThat(Celda.id(-54.8019, -68.3030)).isEqualTo("-549_-684");
    }

    @Test
    void enElBordeLaSucursalCaeEnLaCeldaDelBorde() {
        assertThat(Celda.id(-34.7, -58.4)).isEqualTo("-347_-584");
        assertThat(Celda.id(-34.70001, -58.40001)).isEqualTo("-348_-585");
        assertThat(Celda.id(-34.69999, -58.39999)).isEqualTo("-347_-584");
        assertThat(Celda.id(-34.0, -58.0)).isEqualTo("-340_-580");
    }

    @Test
    void todosLosBordesDeUnDecimalDeLaArgentinaDanElIndiceExacto() {
        // En double, k/10 * 10 vuelve exactamente a k: la app, con floor(lat*10), calcula el mismo id.
        for (int k = -740; k <= -200; k++) {
            assertThat(Celda.indice(k / 10.0)).as("borde %d", k).isEqualTo(k);
        }
    }

    @Test
    void lasSucursalesSeRepartenPorCeldaYLasDeCoordenadasErroneasQuedanAfuera() throws Exception {
        String comercio = "12|1|30548083156|COTO CENTRO INTEGRAL DE COMERCIALIZACION S.A.|COTO CICSA|http://www.coto.com.ar/|x|1.0";
        Path zip = new ZipSepaDePrueba("2026-09-28").comercio("12", List.of(comercio),
                List.of(sucursal("12", "1", "1", "Borde", -34.7, -58.4),
                        sucursal("12", "1", "2", "Apenas al sur", -34.70001, -58.4),
                        sucursal("12", "1", "3", "Sin coordenadas", 0.0, 0.0),
                        sucursal("12", "1", "4", "Invertidas", -58.4, -34.6)),
                List.of(producto("12-1-1", "7790070336361", "FIDEOS X 500 GRS", "1750", "", ""),
                        producto("12-1-2", "7790070336361", "FIDEOS X 500 GRS", "1750", "", ""),
                        producto("12-1-3", "7790070336361", "FIDEOS X 500 GRS", "1750", "", ""),
                        producto("12-1-4", "7790070336361", "FIDEOS X 500 GRS", "1750", "", "")))
                .escribir(tmp.resolve("sepa.zip"));
        Path salida = tmp.resolve("salida");
        Exportador.Resultado r = FormatoV1Test.exportar(zip, salida, FormatoV1Test.AHORA);

        JsonNode celdas = new SalidaLeida(salida).indice().get("celdas");
        assertThat(SalidaLeida.campos(celdas)).containsExactly("-347_-584", "-348_-584");
        assertThat(r.estadisticas().sucursalesSinCoordenadas).isEqualTo(2);
        assertThat(new SalidaLeida(salida).celda("-347_-584").get("sucursales").get(0).get("id").asText())
                .isEqualTo("COTO-12-1-1");
    }
}
