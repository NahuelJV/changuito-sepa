package ar.changuito.exportador;

import static ar.changuito.exportador.ZipSepaDePrueba.producto;
import static ar.changuito.exportador.ZipSepaDePrueba.sucursal;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deduplicación de listas idénticas dentro de la celda y tabla de precios compartida. */
class DeduplicacionTest {

    private static final String LECHE = "7790742352101";
    private static final String FIDEOS = "7790070336361";
    private static final String SAL = "7790072001038";
    /** UPC-A (12 dígitos): se publica como EAN-13 con un 0 adelante. */
    private static final String UPC = "036000291452";

    @TempDir
    Path tmp;

    private Path zipConTresSucursales() throws Exception {
        String comercio = "10|3|30687310434|INC S.A.|Express|www.carrefour.com.ar|2026-09-28T08:30:02-03:00|1.0";
        return new ZipSepaDePrueba("2026-09-28").comercio("10",
                List.of(comercio),
                List.of(sucursal("10", "3", "1", "Uno", -34.6037, -58.3816),
                        sucursal("10", "3", "2", "Dos", -34.6050, -58.3850),
                        sucursal("10", "3", "3", "Tres", -34.6010, -58.3800)),
                List.of(
                        // Sucursales 1 y 2: mismos productos y precios, en distinto orden en el CSV.
                        producto("10-3-1", LECHE, "LECHE ENTERA 1 LT", "1290.00", "1190.00", "2x1 llevando 2"),
                        producto("10-3-1", FIDEOS, "FIDEOS X 500 GRS", "1749.00", "", ""),
                        producto("10-3-1", UPC, "GASEOSA X 2.25 LT", "1950.50", "", ""),
                        producto("10-3-2", FIDEOS, "FIDEOS X 500 GRS", "1749.00", "", ""),
                        producto("10-3-2", UPC, "GASEOSA X 2.25 LT", "1950.50", "", ""),
                        producto("10-3-2", LECHE, "LECHE ENTERA 1 LT", "1290.00", "1190.00", "2x1 llevando 2"),
                        // Si un EAN se repite en la sucursal, vale la primera fila.
                        producto("10-3-2", FIDEOS, "FIDEOS X 500 GRS", "9999.00", "", ""),
                        // Sucursal 3: la leche sale distinto.
                        producto("10-3-3", LECHE, "LECHE ENTERA 1 LT", "1300.00", "", ""),
                        producto("10-3-3", FIDEOS, "FIDEOS X 500 GRS", "1749.00", "", ""),
                        producto("10-3-3", SAL, "SAL FINA X 500 GRS", "4105.00", "", "")))
                .escribir(tmp.resolve("sepa.zip"));
    }

    @Test
    void lasListasIdenticasSeEscribenUnaSolaVezYLasSucursalesLaComparten() throws Exception {
        Path salida = tmp.resolve("salida");
        Exportador.Resultado r = FormatoV1Test.exportar(zipConTresSucursales(), salida, FormatoV1Test.AHORA);
        JsonNode celda = new SalidaLeida(salida).celda("-347_-584");

        assertThat(r.sucursales()).isEqualTo(3);
        assertThat(r.listasDistintas()).isEqualTo(2);
        JsonNode sucursales = celda.get("sucursales");
        assertThat(sucursales.get(0).get("lista")).isEqualTo(sucursales.get(1).get("lista"));
        assertThat(sucursales.get(2).get("lista")).isNotEqualTo(sucursales.get(0).get("lista"));
        assertThat(SalidaLeida.campos(celda.get("listas"))).hasSize(2);

        Map<String, List<JsonNode>> listas = SalidaLeida.listasPorSucursal(celda);
        assertThat(listas.get("CARREFOUR-10-3-1")).extracting(SalidaLeida::texto).containsExactly(
                "[\"0036000291452\",1950.5,null,null]",
                "[\"7790070336361\",1749,null,null]",
                "[\"7790742352101\",1290,1190,\"2x1 llevando 2\"]");
        assertThat(listas.get("CARREFOUR-10-3-2")).isEqualTo(listas.get("CARREFOUR-10-3-1"));
        assertThat(listas.get("CARREFOUR-10-3-3")).extracting(SalidaLeida::texto).containsExactly(
                "[\"7790070336361\",1749,null,null]",
                "[\"7790072001038\",4105,null,null]",
                "[\"7790742352101\",1300,null,null]");
    }

    @Test
    void cadaPrecioDistintoApareceUnaSolaVezEnLaTablaDeLaCelda() throws Exception {
        Path salida = tmp.resolve("salida");
        FormatoV1Test.exportar(zipConTresSucursales(), salida, FormatoV1Test.AHORA);
        JsonNode celda = new SalidaLeida(salida).celda("-347_-584");

        // Los fideos a 1749 están en las tres sucursales pero son una sola fila de la tabla.
        assertThat(celda.get("precios")).extracting(SalidaLeida::texto).containsExactly(
                "[\"0036000291452\",1950.5,null,null]",
                "[\"7790070336361\",1749,null,null]",
                "[\"7790072001038\",4105,null,null]",
                "[\"7790742352101\",1290,1190,\"2x1 llevando 2\"]",
                "[\"7790742352101\",1300,null,null]");
    }

    @Test
    void elIdDeLaListaDependeSoloDelContenido() {
        ListaPrecios.Constructor a = new ListaPrecios.Constructor();
        a.agregar(LECHE, 129000, 119000, "2x1");
        a.agregar(FIDEOS, 174900, ListaPrecios.SIN_PROMO, "ignorada sin promo");
        ListaPrecios.Constructor b = new ListaPrecios.Constructor();
        b.agregar(FIDEOS, 174900, ListaPrecios.SIN_PROMO, null);
        b.agregar(LECHE, 129000, 119000, "2x1");
        ListaPrecios.Constructor c = new ListaPrecios.Constructor();
        c.agregar(FIDEOS, 174900, ListaPrecios.SIN_PROMO, null);
        c.agregar(LECHE, 129000, 119000, "3x2");

        ListaPrecios la = a.construir();
        assertThat(la).isEqualTo(b.construir());
        assertThat(la.id()).isEqualTo(b.construir().id()).hasSize(ListaPrecios.LARGO_ID);
        assertThat(la.hash()).hasSize(64).startsWith(la.id());
        assertThat(la.id()).isNotEqualTo(c.construir().id());
        assertThat(la.ean(0)).isEqualTo(FIDEOS);
        assertThat(la.leyenda(0)).isNull();
    }
}
