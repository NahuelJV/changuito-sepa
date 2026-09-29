package ar.changuito.exportador.sepa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Cliente CKAN contra un servidor local que imita las fallas vistas en la prueba real con el portal. */
class CkanSepaClientTest {

    private static final String CKAN = """
            {"success":true,"result":{"resources":[
              {"format":"ZIP","description":"Precios SEPA Minoristas martes, 2026-09-22","url":"URL/download/sepa_martes.zip"},
              {"format":"ZIP","description":"Precios SEPA Minoristas miercoles, 2026-09-23","url":"URL/download/sepa_miercoles.zip"},
              {"format":"PDF","description":"Anexo 2026-09-23","url":"URL/anexo.pdf"}]}}""";

    private HttpServer server;
    private String base;
    private final AtomicInteger consultas = new AtomicInteger();
    private final AtomicInteger descargas = new AtomicInteger();
    private final CountDownLatch liberar = new CountDownLatch(1);

    @BeforeEach
    void levantar() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        // La primera consulta queda colgada (como el portal); la segunda responde al instante.
        server.createContext("/api/3/action/package_show", ex -> {
            if (consultas.incrementAndGet() == 1) {
                esperar();
            }
            byte[] cuerpo = CKAN.replace("URL", base).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, cuerpo.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(cuerpo);
            }
        });
        // Manda 1 KB y después deja de mandar bytes sin cerrar la conexión.
        server.createContext("/download/trabada.zip", ex -> {
            ex.sendResponseHeaders(200, 10_000_000);
            OutputStream out = ex.getResponseBody();
            out.write(new byte[1024]);
            out.flush();
            esperar();
        });
        // La primera descarga se corta a mitad de camino; la segunda llega completa.
        server.createContext("/download/cortada.zip", ex -> {
            byte[] cuerpo = new byte[64 * 1024];
            java.util.Arrays.fill(cuerpo, (byte) 7);
            if (descargas.incrementAndGet() == 1) {
                ex.sendResponseHeaders(200, cuerpo.length);
                OutputStream out = ex.getResponseBody();
                out.write(cuerpo, 0, 1000);
                out.flush();
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, cuerpo.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(cuerpo);
            }
        });
        server.start();
    }

    @AfterEach
    void bajar() {
        liberar.countDown();
        server.stop(0);
    }

    @Test
    void siCkanQuedaColgadoSeReintentaEnseguidaYEncuentraElZipDelDia() {
        CkanSepaClient client = cliente(Duration.ofSeconds(5), Duration.ofMillis(500));
        URI uri = client.buscarRecurso(LocalDate.of(2026, 9, 23)).orElseThrow();
        assertThat(uri.toString()).endsWith("/download/sepa_miercoles.zip");
        assertThat(consultas.get()).isEqualTo(2);
        assertThat(client.buscarRecurso(LocalDate.of(2026, 9, 17))).isEmpty();
    }

    @Test
    void unaDescargaQueDejaDeAvanzarSeCortaConIOException() throws Exception {
        CkanSepaClient client = cliente(Duration.ofMillis(600), Duration.ofSeconds(5));
        long inicio = System.nanoTime();
        try (InputStream in = client.abrir(URI.create(base + "/download/trabada.zip"))) {
            assertThatThrownBy(() -> in.transferTo(OutputStream.nullOutputStream())).isInstanceOf(IOException.class);
        }
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - inicio)).isLessThan(5);
    }

    @Test
    void terminadaLaDescargaLaVigilanciaSeApagaAunqueElStreamSigaAbierto() throws Exception {
        // Regresión de la prueba real: el stream sigue abierto mientras se procesan los temporales y la vigilancia
        // no debe cortarlo (ni avisar) después de haber llegado al final.
        try (DescargaVigilada in = new DescargaVigilada(new java.io.ByteArrayInputStream(new byte[4096]), Duration.ofMillis(200))) {
            in.transferTo(OutputStream.nullOutputStream());
            Thread.sleep(700);
            assertThat(in.cortada()).isFalse();
            assertThat(in.read()).isEqualTo(-1);
        }
    }

    @Test
    void siLaDescargaSeCortaSeReintentaYQuedaCompletaEnDisco(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        CkanSepaClient client = cliente(Duration.ofSeconds(5), Duration.ofSeconds(5));
        java.nio.file.Path destino = tmp.resolve("sepa.zip");
        long bytes = client.descargar(URI.create(base + "/download/cortada.zip"), destino, 3);
        assertThat(bytes).isEqualTo(64 * 1024);
        assertThat(java.nio.file.Files.size(destino)).isEqualTo(64 * 1024);
        assertThat(descargas.get()).isEqualTo(2);
    }

    private CkanSepaClient cliente(Duration inactividad, Duration timeoutCkan) {
        return new CkanSepaClient(base + "/api/3/action/package_show", "sepa-precios", inactividad, timeoutCkan,
                Duration.ZERO);
    }

    private void esperar() {
        try {
            liberar.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
