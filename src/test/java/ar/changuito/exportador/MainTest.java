package ar.changuito.exportador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Corrida completa contra un CKAN local que publica el fixture como el archivo del 2026-09-23. */
class MainTest {

    @TempDir
    Path tmp;
    private HttpServer server;
    private String ckan;

    @BeforeEach
    void levantar() throws IOException {
        byte[] fixture;
        try (InputStream in = MainTest.class.getResourceAsStream(FormatoV1Test.FIXTURE)) {
            fixture = in.readAllBytes();
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        ckan = base + "/api/3/action/package_show";
        server.createContext("/api/3/action/package_show", ex -> responder(ex, ("""
                {"success":true,"result":{"resources":[
                  {"format":"ZIP","description":"Precios SEPA Minoristas miercoles, 2026-09-23","url":"%s/download/sepa_miercoles.zip"}]}}"""
                .formatted(base)).getBytes(StandardCharsets.UTF_8)));
        server.createContext("/download/sepa_miercoles.zip", ex -> responder(ex, fixture));
        server.start();
    }

    @AfterEach
    void bajar() {
        server.stop(0);
    }

    private static void responder(com.sun.net.httpserver.HttpExchange ex, byte[] cuerpo) throws IOException {
        ex.sendResponseHeaders(200, cuerpo.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(cuerpo);
        }
    }

    @Test
    void bajaElArchivoDeLaFechaLoPublicaYReporta() throws Exception {
        Path salida = tmp.resolve("salida");
        Path descarga = tmp.resolve("descargas/sepa.zip");
        ByteArrayOutputStream reporte = new ByteArrayOutputStream();
        int codigo = Main.ejecutar(new String[]{"--fecha", "2026-09-23", "--salida", salida.toString(), "--ckan-url", ckan,
                "--guardar-descarga", descarga.toString(), "--medir-sin-dedup", "--hilos", "2"},
                new PrintStream(reporte, true, StandardCharsets.UTF_8));

        assertThat(codigo).isZero();
        assertThat(Files.exists(salida.resolve("v1/indice.json"))).isTrue();
        assertThat(Files.size(descarga)).isEqualTo(Files.size(Path.of(MainTest.class.getResource(FormatoV1Test.FIXTURE).toURI())));
        assertThat(reporte.toString(StandardCharsets.UTF_8))
                .contains("Exportación SEPA 2026-09-23")
                .contains("Sucursales: 5 {CARREFOUR=3, COTO=2}")
                .contains("Celda del Obelisco -347_-584");
    }

    @Test
    void siElPortalNoPublicaLaFechaSaleConCodigo3YNoTocaLaSalida() {
        Path salida = tmp.resolve("salida");
        int codigo = Main.ejecutar(new String[]{"--fecha", "2026-09-17", "--salida", salida.toString(), "--ckan-url", ckan},
                new PrintStream(OutputStream.nullOutputStream()));
        assertThat(codigo).isEqualTo(3);
        assertThat(Files.exists(salida.resolve("v1"))).isFalse();
    }

    @Test
    void usoIncorrectoSaleConCodigo2YLaAyudaCon0() {
        PrintStream nada = new PrintStream(OutputStream.nullOutputStream());
        assertThat(Main.ejecutar(new String[]{"--fecha", "2026-09-23"}, nada)).isEqualTo(2);
        assertThat(Main.ejecutar(new String[]{"--salida", "x", "--fecha", "23/09/2026"}, nada)).isEqualTo(2);
        assertThat(Main.ejecutar(new String[]{"--salida", "x", "--desconocida"}, nada)).isEqualTo(2);
        assertThat(Main.ejecutar(new String[]{"--help"}, nada)).isZero();
    }

    @Test
    void rn21_lasCadenasSeNormalizanAMayusculasSinRepetidos() {
        assertThat(Opciones.parsear("--salida", "x", "--cadenas", " carrefour, Coto ,,COTO").cadenas())
                .isEqualTo(List.of("CARREFOUR", "COTO"));
        assertThat(Opciones.parsear("--salida", "x").cadenas()).isEqualTo(List.of("CARREFOUR", "COTO"));
        assertThatThrownBy(() -> Opciones.parsear("--salida", "x", "--cadenas", " , ")).isInstanceOf(Opciones.UsoIncorrecto.class);
    }

    @Test
    void rn19_elDescuentoMaximoPorDefectoEsDel70PorCiento() {
        assertThat(Opciones.parsear("--salida", "x").descuentoMaximo()).isEqualByComparingTo("0.70");
        assertThat(Opciones.parsear("--salida", "x", "--descuento-maximo", "0.5").descuentoMaximo()).isEqualByComparingTo("0.5");
    }
}
