package ar.changuito.exportador.sepa;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Cliente CKAN: {@code package_show?id=sepa-precios} lista un recurso ZIP por día de la semana
 * cuya {@code description} dice "Precios SEPA Minoristas miercoles, 2026-09-23".
 * <p>
 * Copia del cliente del backend ({@code ar.changuito.sepa.client.CkanSepaClient}) sin Spring: la configuración
 * llega por constructor y se agrega {@link #descargar}, que baja el zip entero a disco antes de procesarlo.
 */
public class CkanSepaClient {

    public static final String CKAN_URL = "https://datos.produccion.gob.ar/api/3/action/package_show";
    public static final String DATASET_ID = "sepa-precios";

    private static final Duration CONEXION = Duration.ofSeconds(20);
    static final Duration TIMEOUT_CKAN = Duration.ofSeconds(20);
    private static final int INTENTOS_CKAN = 3;
    /** Hasta recibir los encabezados de la descarga. */
    static final Duration TIMEOUT_DESCARGA = Duration.ofMinutes(10);
    /** Sin recibir bytes durante este tiempo, la descarga se corta y el intento se reintenta. */
    static final Duration INACTIVIDAD_MAXIMA = Duration.ofMinutes(2);
    static final Duration ESPERA_REINTENTO = Duration.ofSeconds(15);

    private final String ckanUrl;
    private final String datasetId;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http;
    private final Duration inactividadMaxima;
    private final Duration timeoutCkan;
    private final Duration esperaReintento;

    public CkanSepaClient() {
        this(CKAN_URL, DATASET_ID);
    }

    public CkanSepaClient(String ckanUrl, String datasetId) {
        this(ckanUrl, datasetId, INACTIVIDAD_MAXIMA, TIMEOUT_CKAN);
    }

    CkanSepaClient(String ckanUrl, String datasetId, Duration inactividadMaxima, Duration timeoutCkan) {
        this(ckanUrl, datasetId, inactividadMaxima, timeoutCkan, ESPERA_REINTENTO);
    }

    CkanSepaClient(String ckanUrl, String datasetId, Duration inactividadMaxima, Duration timeoutCkan,
                   Duration esperaReintento) {
        this.esperaReintento = esperaReintento;
        this.ckanUrl = ckanUrl;
        this.datasetId = datasetId;
        this.inactividadMaxima = inactividadMaxima;
        this.timeoutCkan = timeoutCkan;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONEXION)
                .build();
    }

    /**
     * El portal a veces deja colgada la consulta a CKAN (visto en la prueba real: 60 s sin respuesta y al
     * reintentar contesta en 0,2 s). Por eso cada consulta tiene un timeout corto y se reintenta enseguida
     * antes de dar el intento por fallido.
     */
    /** URL del zip diario correspondiente a la fecha, si el portal lo publica. */
    public Optional<URI> buscarRecurso(LocalDate fecha) {
        SepaException ultimo = null;
        for (int intento = 1; intento <= INTENTOS_CKAN; intento++) {
            try {
                return consultarCkan(fecha);
            } catch (SepaException e) {
                ultimo = e;
                Log.warn("SEPA: CKAN no respondió (intento {}/{}): {}", intento, INTENTOS_CKAN, e.getMessage());
            }
        }
        throw ultimo;
    }

    private Optional<URI> consultarCkan(LocalDate fecha) {
        URI uri = URI.create(ckanUrl + "?id=" + URLEncoder.encode(datasetId, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeoutCkan).GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = new DescargaVigilada(response.body(), timeoutCkan)) {
                if (response.statusCode() != 200) {
                    throw new SepaException("CKAN respondió " + response.statusCode());
                }
                JsonNode raiz = objectMapper.readTree(body);
                String iso = fecha.toString();
                for (JsonNode recurso : raiz.path("result").path("resources")) {
                    String descripcion = recurso.path("description").asText("");
                    String url = recurso.path("url").asText("");
                    boolean esZip = "ZIP".equalsIgnoreCase(recurso.path("format").asText(""))
                            || url.toLowerCase().endsWith(".zip");
                    if (esZip && descripcion.contains(iso) && !url.isBlank()) {
                        return Optional.of(URI.create(url));
                    }
                }
                Log.info("CKAN no publica el archivo SEPA del {}", iso);
                return Optional.empty();
            }
        } catch (IOException e) {
            throw new SepaException("No se pudo consultar CKAN: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SepaException("Consulta a CKAN interrumpida", e);
        }
    }

    /**
     * Baja el zip entero a {@code destino} (primero se descarga todo, después se procesa: con el servidor real,
     * dejar la conexión parada mientras se procesa hace que la corte). Reintenta ante cortes o inactividad.
     *
     * @return bytes descargados
     */
    public long descargar(URI recurso, Path destino, int intentos) {
        SepaException ultimo = null;
        for (int intento = 1; intento <= Math.max(1, intentos); intento++) {
            long inicio = System.nanoTime();
            try (InputStream in = abrir(recurso)) {
                long bytes = Files.copy(in, destino, StandardCopyOption.REPLACE_EXISTING);
                Log.info("SEPA: descarga completa ({} MB en {} s)", bytes / (1024 * 1024),
                        Duration.ofNanos(System.nanoTime() - inicio).toSeconds());
                return bytes;
            } catch (IOException | SepaException e) {
                ultimo = e instanceof SepaException se ? se : new SepaException("Descarga cortada: " + e.getMessage(), e);
                Log.warn("SEPA: falló la descarga (intento {}/{}): {}", intento, intentos, e.getMessage());
                if (intento < intentos) {
                    esperar(esperaReintento);
                }
            }
        }
        throw ultimo;
    }

    private static void esperar(Duration espera) {
        try {
            Thread.sleep(espera.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SepaException("Reintento de descarga interrumpido", e);
        }
    }

    /** Abre la descarga en streaming, vigilada por inactividad; quien llama cierra el stream. */
    public InputStream abrir(URI recurso) {
        HttpRequest request = HttpRequest.newBuilder(recurso).timeout(TIMEOUT_DESCARGA).GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new SepaException("La descarga SEPA respondió " + response.statusCode());
            }
            return new DescargaVigilada(response.body(), inactividadMaxima);
        } catch (IOException e) {
            throw new SepaException("No se pudo descargar " + recurso + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SepaException("Descarga SEPA interrumpida", e);
        }
    }
}
