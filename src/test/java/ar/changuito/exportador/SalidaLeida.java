package ar.changuito.exportador;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Lee la salida publicada como lo haría la app (para los tests). */
final class SalidaLeida {

    static final ObjectMapper JSON = new ObjectMapper();

    private final Path raiz;

    SalidaLeida(Path raiz) {
        this.raiz = raiz;
    }

    JsonNode indice() {
        try {
            return JSON.readTree(raiz.resolve("v1/indice.json").toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    JsonNode gz(String ruta) {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(raiz.resolve(ruta)))) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    JsonNode celda(String id) {
        return gz("v1/celdas/" + id + ".json.gz");
    }

    JsonNode catalogo() {
        return gz("v1/catalogo.json.gz");
    }

    /** Lista de cada sucursal de la celda, ya decodificada: índices por diferencias → filas de {@code precios}. */
    static Map<String, List<JsonNode>> listasPorSucursal(JsonNode celda) {
        Map<String, List<JsonNode>> porSucursal = new LinkedHashMap<>();
        for (JsonNode s : celda.get("sucursales")) {
            porSucursal.put(s.get("id").asText(), decodificar(celda, s.get("lista").asText()));
        }
        return porSucursal;
    }

    static List<JsonNode> decodificar(JsonNode celda, String lista) {
        JsonNode precios = celda.get("precios");
        List<JsonNode> filas = new ArrayList<>();
        int indice = 0;
        boolean primero = true;
        for (JsonNode d : celda.get("listas").get(lista)) {
            indice = primero ? d.asInt() : indice + d.asInt();
            primero = false;
            filas.add(precios.get(indice));
        }
        return filas;
    }

    static List<String> campos(JsonNode objeto) {
        List<String> nombres = new ArrayList<>();
        objeto.fieldNames().forEachRemaining(nombres::add);
        return nombres;
    }

    /** Fila como texto compacto, para comparar: {@code ["7790070336361",1749,null,null]}. */
    static String texto(JsonNode fila) {
        return fila.toString();
    }
}
