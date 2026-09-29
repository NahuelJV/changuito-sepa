package ar.changuito.exportador;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import ar.changuito.exportador.sepa.CkanSepaClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contrato del exportador v1 (docs/MODO-LOCAL.md) con el fixture de filas reales del 2026-09-23:
 * rutas, campos y su orden, orden de los arreglos, gzip y sha256.
 */
class FormatoV1Test {

    static final String FIXTURE = "/sepa/sepa_fixture.zip";
    static final Instant AHORA = Instant.parse("2026-09-29T09:10:00.123Z");

    @TempDir
    Path tmp;
    Path zip;

    @BeforeEach
    void copiarFixture() throws IOException {
        zip = tmp.resolve("sepa.zip");
        try (InputStream in = FormatoV1Test.class.getResourceAsStream(FIXTURE)) {
            Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Exportador.Resultado exportar(Path zip, Path salida, Instant ahora, String... extra) throws IOException {
        String[] base = {"--zip", zip.toString(), "--salida", salida.toString()};
        String[] args = Stream.concat(Stream.of(base), Stream.of(extra)).toArray(String[]::new);
        return new Exportador(Opciones.parsear(args), new CkanSepaClient(), Clock.fixed(ahora, ZoneOffset.UTC)).exportar();
    }

    @Test
    void indiceConLosCamposYElOrdenDelContrato() throws Exception {
        Path salida = tmp.resolve("salida");
        exportar(zip, salida, AHORA);
        JsonNode indice = new SalidaLeida(salida).indice();

        assertThat(SalidaLeida.campos(indice)).containsExactly("version", "fecha", "generadoEn", "cadenas", "catalogo", "celdas");
        assertThat(indice.get("version").asInt()).isEqualTo(1);
        assertThat(indice.get("version").isInt()).isTrue();
        assertThat(indice.get("fecha").asText()).isEqualTo("2026-09-23");
        assertThat(indice.get("generadoEn").asText()).isEqualTo("2026-09-29T09:10:00Z");
        assertThat(indice.get("cadenas").toString()).isEqualTo("[\"CARREFOUR\",\"COTO\"]");

        JsonNode catalogo = indice.get("catalogo");
        assertThat(SalidaLeida.campos(catalogo)).containsExactly("archivo", "bytes", "sha256");
        assertThat(catalogo.get("archivo").asText()).isEqualTo("v1/catalogo.json.gz");

        // Carrefour Córdoba (-31.46, -64.21), Obelisco y Coto Neuquén (-38.94, -68.06); DIA no es una cadena configurada.
        JsonNode celdas = indice.get("celdas");
        assertThat(SalidaLeida.campos(celdas)).containsExactly("-315_-643", "-347_-584", "-390_-681");
        JsonNode obelisco = celdas.get("-347_-584");
        assertThat(SalidaLeida.campos(obelisco)).containsExactly("archivo", "sucursales", "bytes", "sha256");
        assertThat(obelisco.get("archivo").asText()).isEqualTo("v1/celdas/-347_-584.json.gz");
        assertThat(obelisco.get("sucursales").asInt()).isEqualTo(3);

        try (Stream<Path> archivos = Files.walk(salida)) {
            assertThat(archivos.filter(Files::isRegularFile).map(p -> salida.relativize(p).toString().replace('\\', '/')))
                    .containsExactlyInAnyOrder("v1/indice.json", "v1/catalogo.json.gz", "v1/celdas/-315_-643.json.gz",
                            "v1/celdas/-347_-584.json.gz", "v1/celdas/-390_-681.json.gz");
        }
    }

    @Test
    void celdaConSucursalesOrdenadasTablaDePreciosYListasPorIndice() throws Exception {
        Path salida = tmp.resolve("salida");
        exportar(zip, salida, AHORA);
        JsonNode celda = new SalidaLeida(salida).celda("-347_-584");

        assertThat(SalidaLeida.campos(celda)).containsExactly("fecha", "sucursales", "precios", "listas");
        assertThat(celda.get("fecha").asText()).isEqualTo("2026-09-23");

        JsonNode sucursales = celda.get("sucursales");
        assertThat(sucursales).extracting(s -> s.get("id").asText())
                .containsExactly("CARREFOUR-10-2-189", "CARREFOUR-10-3-761", "COTO-12-1-159");
        JsonNode market = sucursales.get(0);
        assertThat(SalidaLeida.campos(market))
                .containsExactly("id", "cadena", "bandera", "nombre", "direccion", "lat", "lon", "lista");
        assertThat(market.get("cadena").asText()).isEqualTo("CARREFOUR");
        assertThat(market.get("bandera").asText()).isEqualTo("Market");
        assertThat(market.get("nombre").asText()).isEqualTo("Av. Corrientes");
        assertThat(market.get("direccion").asText()).isEqualTo("Av. Corrientes 1160, Ciudad Autónoma de Buenos Aires");
        assertThat(market.get("lat").asDouble()).isEqualTo(-34.603902);
        assertThat(market.get("lon").asDouble()).isEqualTo(-58.383077);
        assertThat(market.get("lista").asText()).hasSize(ListaPrecios.LARGO_ID).matches("[0-9a-f]+");
        JsonNode coto = sucursales.get(2);
        assertThat(coto.get("bandera").asText()).isEqualTo("COTO CICSA");
        assertThat(coto.get("nombre").asText()).isEqualTo("MITRE");

        // Tabla de precios: filas [ean, precioLista, precioPromo|null, leyendaPromo|null] ordenadas por EAN, sin repetir.
        JsonNode precios = celda.get("precios");
        assertThat(precios).extracting(SalidaLeida::texto).containsExactly(
                "[\"7622300148119\",2860,null,null]",
                "[\"7790070336361\",1749,null,null]",
                "[\"7790070336361\",1750,null,null]",
                "[\"7790070336361\",1839,null,null]",
                "[\"7790072001038\",4105,null,null]",
                "[\"7790072001038\",4106,null,null]",
                "[\"7790580105822\",10989,null,null]",
                "[\"7790580105822\",13239.99,null,null]",
                "[\"7790742352101\",2456.47,2050,\"DEL 22/09/2026 AL 30/09/2026\"]",
                "[\"7791720025048\",1950,1790,\"Promo A valida desde el 23/09/2026 hasta 01/10/2026\"]",
                "[\"7798159713973\",4090,null,null]");

        // Listas: índices crecientes a la tabla, el primero absoluto y los siguientes como diferencia.
        JsonNode listas = celda.get("listas");
        assertThat(SalidaLeida.campos(listas)).isSorted().hasSize(3);
        assertThat(listas.get(market.get("lista").asText()).toString()).isEqualTo("[0,1,3,2,3,1]");

        Map<String, List<JsonNode>> porSucursal = SalidaLeida.listasPorSucursal(celda);
        assertThat(porSucursal.get("CARREFOUR-10-2-189")).extracting(SalidaLeida::texto).containsExactly(
                "[\"7622300148119\",2860,null,null]",
                "[\"7790070336361\",1749,null,null]",
                "[\"7790072001038\",4105,null,null]",
                "[\"7790580105822\",10989,null,null]",
                "[\"7791720025048\",1950,1790,\"Promo A valida desde el 23/09/2026 hasta 01/10/2026\"]",
                "[\"7798159713973\",4090,null,null]");
        assertThat(porSucursal.get("CARREFOUR-10-3-761")).extracting(SalidaLeida::texto)
                .containsExactly("[\"7790070336361\",1839,null,null]");
        assertThat(porSucursal.get("COTO-12-1-159")).extracting(SalidaLeida::texto).containsExactly(
                "[\"7622300148119\",2860,null,null]",
                "[\"7790070336361\",1750,null,null]",
                "[\"7790072001038\",4106,null,null]",
                "[\"7790580105822\",13239.99,null,null]",
                "[\"7790742352101\",2456.47,2050,\"DEL 22/09/2026 AL 30/09/2026\"]");
    }

    @Test
    void rn06_quedanAfueraLosEanInvalidosYLosCodigosInternos() throws Exception {
        Path salida = tmp.resolve("salida");
        exportar(zip, salida, AHORA);
        SalidaLeida leida = new SalidaLeida(salida);
        String todo = leida.celda("-347_-584").toString() + leida.catalogo();

        assertThat(todo).doesNotContain("7790072001039")   // dígito de control inválido
                .doesNotContain("2000000000019")          // Carrefour, productos_ean = 0
                .doesNotContain("2800000722012");         // Coto, productos_ean = 0
    }

    @Test
    void rn19_laPromoDeUnaCuotaNoSePublicaYLaValidaSi() throws Exception {
        Path salida = tmp.resolve("salida");
        exportar(zip, salida, AHORA);
        Map<String, List<JsonNode>> listas = SalidaLeida.listasPorSucursal(new SalidaLeida(salida).celda("-347_-584"));

        // Acondicionador: lista 4090 y "promo" 1000 (75,5 % de descuento, es el valor de una cuota) → sin promo.
        assertThat(listas.get("CARREFOUR-10-2-189")).extracting(SalidaLeida::texto)
                .contains("[\"7798159713973\",4090,null,null]")
                .contains("[\"7791720025048\",1950,1790,\"Promo A valida desde el 23/09/2026 hasta 01/10/2026\"]");
    }

    @Test
    void catalogoConTuplasEanDescripcionMarcaCantidadUnidadOrdenadasPorEan() throws Exception {
        Path salida = tmp.resolve("salida");
        exportar(zip, salida, AHORA);
        JsonNode catalogo = new SalidaLeida(salida).catalogo();

        assertThat(catalogo.isArray()).isTrue();
        assertThat(catalogo).extracting(SalidaLeida::texto).containsExactly(
                "[\"7622300148119\",\"Polvo Para Hornear Royal Pote X 100 Grs\",\"Royal\",100,\"G\"]",
                "[\"7790070336361\",\"Fideos Penne Rigate N45 Lucchetti X 500 Grs\",\"Lucchetti\",500,\"G\"]",
                "[\"7790072001038\",\"Sal Fina Celusal Salero X 500 Grs\",\"Celusal\",500,\"G\"]",
                "[\"7790580105822\",\"Tableta Chocolate P Taza Aguila X 225 Grs\",\"Aguila\",225,\"G\"]",
                "[\"7790742352101\",\"Leche Desc.protein 46g La Serenisima Sch 1 Ltr\",\"La Serenisima\",1,\"L\"]",
                "[\"7791620187730\",\"Mayonesa D Fiesta X 500 Cc\",\"Festa\",500,\"ML\"]",
                "[\"7791720025048\",\"Gaseosa Regular Pomelo Carrefour X 2.25 Lt\",\"Carrefour\",2.25,\"L\"]",
                "[\"7798159713973\",\"Acondicionador Algas Hidratante Carrefour X 950cc\",\"Carrefour\",950,\"ML\"]");
    }

    @Test
    void losArchivosSonGzipYElIndiceTraeSusBytesYSha256() throws Exception {
        Path salida = tmp.resolve("salida");
        exportar(zip, salida, AHORA);
        JsonNode indice = new SalidaLeida(salida).indice();

        List<JsonNode> archivos = new java.util.ArrayList<>();
        archivos.add(indice.get("catalogo"));
        indice.get("celdas").forEach(archivos::add);
        for (JsonNode a : archivos) {
            Path archivo = salida.resolve(a.get("archivo").asText());
            byte[] bytes = Files.readAllBytes(archivo);
            assertThat(bytes[0] & 0xff).isEqualTo(0x1f);
            assertThat(bytes[1] & 0xff).isEqualTo(0x8b);
            assertThat(a.get("bytes").asLong()).isEqualTo(bytes.length);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertThat(a.get("sha256").asText()).isEqualTo(sha).hasSize(64);
        }
    }

    @Test
    void sha256_elMismoContenidoDaLosMismosArchivosAunqueCambieLaHora() throws Exception {
        Path primera = tmp.resolve("a");
        Path segunda = tmp.resolve("b");
        exportar(zip, primera, AHORA);
        exportar(zip, segunda, AHORA.plusSeconds(3600));
        JsonNode a = new SalidaLeida(primera).indice();
        JsonNode b = new SalidaLeida(segunda).indice();

        assertThat(a.get("generadoEn")).isNotEqualTo(b.get("generadoEn"));
        assertThat(a.get("catalogo")).isEqualTo(b.get("catalogo"));
        assertThat(a.get("celdas")).isEqualTo(b.get("celdas"));
    }

    @Test
    void unaNuevaCorridaReemplazaLaSalidaSinDejarCeldasViejas() throws Exception {
        Path salida = tmp.resolve("salida");
        exportar(zip, salida, AHORA);
        exportar(zip, salida, AHORA, "--cadenas", "COTO");

        try (Stream<Path> archivos = Files.list(salida.resolve("v1/celdas"))) {
            assertThat(archivos.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder("-347_-584.json.gz", "-390_-681.json.gz");
        }
        try (Stream<Path> raiz = Files.list(salida)) {
            assertThat(raiz.map(p -> p.getFileName().toString())).containsExactly("v1");
        }
    }

    @Test
    void rn21_soloSePublicanLasCadenasConfiguradas() throws Exception {
        Path soloCoto = tmp.resolve("coto");
        Exportador.Resultado coto = exportar(zip, soloCoto, AHORA, "--cadenas", "coto");
        assertThat(coto.sucursalesPorCadena()).containsExactly(Map.entry("COTO", 2));
        JsonNode indice = new SalidaLeida(soloCoto).indice();
        assertThat(indice.get("cadenas").toString()).isEqualTo("[\"COTO\"]");
        assertThat(SalidaLeida.listasPorSucursal(new SalidaLeida(soloCoto).celda("-347_-584"))).containsOnlyKeys("COTO-12-1-159");

        // La lista es configurable: DIA (sin tocar código) también se reconoce por razón social y bandera.
        Path conDia = tmp.resolve("dia");
        Exportador.Resultado dia = exportar(zip, conDia, AHORA, "--cadenas", "CARREFOUR,COTO,DIA");
        assertThat(dia.sucursalesPorCadena()).containsEntry("DIA", 1);
        assertThat(SalidaLeida.listasPorSucursal(new SalidaLeida(conDia).celda("-347_-584")))
                .containsKey("DIA-15-1-454");
    }
}
