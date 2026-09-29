package ar.changuito.exportador;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

import ar.changuito.exportador.RecolectorSepa.Producto;
import ar.changuito.exportador.RecolectorSepa.Sucursal;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

/**
 * Escribe los archivos del contrato v1 (docs/MODO-LOCAL.md): {@code v1/indice.json}, {@code v1/catalogo.json.gz}
 * y {@code v1/celdas/<celda>.json.gz}. Los .gz se comprimen al máximo y sin fecha en el encabezado (el
 * {@code GZIPOutputStream} del JDK escribe MTIME = 0), así el mismo contenido da siempre el mismo sha256 y la app
 * no vuelve a bajar lo que no cambió. El sha256 y los bytes son los del archivo .gz, tal como se descarga.
 */
public final class EscritorSalida {

    public static final String VERSION = "v1";

    private static final JsonFactory JSON = JsonFactory.builder().build();

    /** Archivo escrito: ruta relativa a la raíz de publicación, tamaño y sha256 (hex) del archivo en disco. */
    public record Archivo(String ruta, long bytes, String sha256) {
    }

    /** Celda escrita, para el índice. */
    public record CeldaEscrita(String id, int sucursales, int listas, Archivo archivo) {
    }

    @FunctionalInterface
    interface Contenido {
        void escribir(JsonGenerator json) throws IOException;
    }

    private EscritorSalida() {
    }

    // ---------------------------------------------------------------- celdas

    /** Precio distinto de la celda: una fila de la tabla {@code precios}. */
    record Tupla(String ean, long lista, long promo, String leyenda) implements Comparable<Tupla> {

        private static final Comparator<Tupla> ORDEN = Comparator.comparing(Tupla::ean)
                .thenComparingLong(Tupla::lista)
                .thenComparingLong(Tupla::promo)
                .thenComparing(Tupla::leyenda, Comparator.nullsFirst(Comparator.naturalOrder()));

        static Tupla de(ListaPrecios lista, int i) {
            return new Tupla(lista.ean(i), lista.listaCentavos(i), lista.promoCentavos(i), lista.leyenda(i));
        }

        @Override
        public int compareTo(Tupla otra) {
            return ORDEN.compare(this, otra);
        }
    }

    /**
     * Sucursales ordenadas por id; listas idénticas deduplicadas por hash y ordenadas por id.
     * <p>
     * Nota exportador (docs/MODO-LOCAL.md): cada precio distinto de la celda se escribe una sola vez en la tabla
     * {@code precios} (ordenada por EAN) y cada lista es un arreglo de índices a esa tabla, en orden creciente y
     * codificado por diferencias: el primero es el índice y cada uno de los siguientes es la distancia al anterior.
     */
    public static CeldaEscrita escribirCelda(Path raiz, String celda, LocalDate fecha, List<Sucursal> sucursales)
            throws IOException {
        String ruta = VERSION + "/celdas/" + celda + ".json.gz";
        List<Sucursal> ordenadas = new ArrayList<>(sucursales);
        ordenadas.sort(Comparator.comparing(Sucursal::id));
        Map<String, ListaPrecios> listas = new TreeMap<>();
        for (Sucursal s : ordenadas) {
            ListaPrecios previa = listas.putIfAbsent(s.lista().id(), s.lista());
            if (previa != null && !previa.hash().equals(s.lista().hash())) {
                throw new IllegalStateException("Dos listas distintas con el mismo id en la celda " + celda);
            }
        }
        Archivo archivo = escribirGz(raiz, ruta, json -> celda(json, fecha, ordenadas, listas));
        return new CeldaEscrita(celda, ordenadas.size(), listas.size(), archivo);
    }

    private static void celda(JsonGenerator json, LocalDate fecha, List<Sucursal> sucursales,
                              Map<String, ListaPrecios> listas) throws IOException {
        TreeMap<Tupla, Integer> tabla = new TreeMap<>();
        for (ListaPrecios lista : listas.values()) {
            for (int i = 0; i < lista.tamanio(); i++) {
                tabla.put(Tupla.de(lista, i), 0);
            }
        }
        int indice = 0;
        for (Map.Entry<Tupla, Integer> e : tabla.entrySet()) {
            e.setValue(indice++);
        }
        json.writeStartObject();
        json.writeStringField("fecha", fecha.toString());
        escribirSucursales(json, sucursales, false);
        json.writeArrayFieldStart("precios");
        for (Tupla t : tabla.keySet()) {
            escribirTupla(json, t);
        }
        json.writeEndArray();
        json.writeObjectFieldStart("listas");
        for (Map.Entry<String, ListaPrecios> e : listas.entrySet()) {
            json.writeArrayFieldStart(e.getKey());
            ListaPrecios lista = e.getValue();
            int anterior = 0;
            for (int i = 0; i < lista.tamanio(); i++) {
                int actual = tabla.get(Tupla.de(lista, i));
                json.writeNumber(i == 0 ? actual : actual - anterior);
                anterior = actual;
            }
            json.writeEndArray();
        }
        json.writeEndObject();
        json.writeEndObject();
    }

    /**
     * Bytes que pesaría la celda comprimida con el formato v1 original: cada sucursal con su propia lista de
     * tuplas {@code [ean, precioLista, precioPromo, leyenda]}, sin tabla de precios ni deduplicación.
     */
    public static long medirCeldaSinDeduplicar(LocalDate fecha, List<Sucursal> sucursales) throws IOException {
        List<Sucursal> ordenadas = new ArrayList<>(sucursales);
        ordenadas.sort(Comparator.comparing(Sucursal::id));
        Contador contador = new Contador();
        comprimir(contador, json -> {
            json.writeStartObject();
            json.writeStringField("fecha", fecha.toString());
            escribirSucursales(json, ordenadas, true);
            json.writeObjectFieldStart("listas");
            for (Sucursal s : ordenadas) {
                json.writeArrayFieldStart(s.id());
                for (int i = 0; i < s.lista().tamanio(); i++) {
                    escribirTupla(json, Tupla.de(s.lista(), i));
                }
                json.writeEndArray();
            }
            json.writeEndObject();
            json.writeEndObject();
        });
        return contador.bytes;
    }

    private static void escribirSucursales(JsonGenerator json, List<Sucursal> sucursales, boolean listaPorSucursal)
            throws IOException {
        json.writeArrayFieldStart("sucursales");
        for (Sucursal s : sucursales) {
            json.writeStartObject();
            json.writeStringField("id", s.id());
            json.writeStringField("cadena", s.cadena());
            json.writeStringField("bandera", s.bandera());
            json.writeStringField("nombre", s.nombre());
            json.writeStringField("direccion", s.direccion());
            json.writeNumberField("lat", s.lat());
            json.writeNumberField("lon", s.lon());
            json.writeStringField("lista", listaPorSucursal ? s.id() : s.lista().id());
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    /** {@code [ean, precioLista, precioPromo|null, leyendaPromo|null]}, precios en pesos. */
    private static void escribirTupla(JsonGenerator json, Tupla t) throws IOException {
        json.writeStartArray();
        json.writeString(t.ean());
        json.writeNumber(pesos(t.lista()));
        if (t.promo() == ListaPrecios.SIN_PROMO) {
            json.writeNull();
            json.writeNull();
        } else {
            json.writeNumber(pesos(t.promo()));
            json.writeString(t.leyenda());
        }
        json.writeEndArray();
    }

    // ---------------------------------------------------------------- catálogo

    /** {@code [[ean, descripción, marca, cantidad, unidad], ...]} ordenado por EAN. */
    public static Archivo escribirCatalogo(Path raiz, Map<String, Producto> catalogo) throws IOException {
        List<Producto> productos = new ArrayList<>(catalogo.values());
        productos.sort(Comparator.comparing(Producto::ean));
        return escribirGz(raiz, VERSION + "/catalogo.json.gz", json -> {
            json.writeStartArray();
            for (Producto p : productos) {
                json.writeStartArray();
                json.writeString(p.ean());
                json.writeString(p.descripcion());
                if (p.marca() == null) {
                    json.writeNull();
                } else {
                    json.writeString(p.marca());
                }
                if (p.cantidad() == null) {
                    json.writeNull();
                    json.writeNull();
                } else {
                    json.writeNumber(p.cantidad().toPlainString());
                    json.writeString(p.unidad());
                }
                json.writeEndArray();
            }
            json.writeEndArray();
        });
    }

    // ---------------------------------------------------------------- índice

    public static Archivo escribirIndice(Path raiz, LocalDate fecha, Instant generadoEn, List<String> cadenas,
                                         Archivo catalogo, List<CeldaEscrita> celdas) throws IOException {
        String ruta = VERSION + "/indice.json";
        Path destino = raiz.resolve(ruta);
        Files.createDirectories(destino.getParent());
        List<CeldaEscrita> ordenadas = new ArrayList<>(celdas);
        ordenadas.sort(Comparator.comparing(CeldaEscrita::id));
        MessageDigest sha = sha256();
        try (OutputStream out = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(destino)), sha);
             JsonGenerator json = JSON.createGenerator(out, JsonEncoding.UTF8)) {
            json.useDefaultPrettyPrinter();
            json.writeStartObject();
            json.writeNumberField("version", 1);
            json.writeStringField("fecha", fecha.toString());
            json.writeStringField("generadoEn", generadoEn.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
            json.writeArrayFieldStart("cadenas");
            for (String c : cadenas) {
                json.writeString(c);
            }
            json.writeEndArray();
            json.writeObjectFieldStart("catalogo");
            json.writeStringField("archivo", catalogo.ruta());
            json.writeNumberField("bytes", catalogo.bytes());
            json.writeStringField("sha256", catalogo.sha256());
            json.writeEndObject();
            json.writeObjectFieldStart("celdas");
            for (CeldaEscrita c : ordenadas) {
                json.writeObjectFieldStart(c.id());
                json.writeStringField("archivo", c.archivo().ruta());
                json.writeNumberField("sucursales", c.sucursales());
                json.writeNumberField("bytes", c.archivo().bytes());
                json.writeStringField("sha256", c.archivo().sha256());
                json.writeEndObject();
            }
            json.writeEndObject();
            json.writeEndObject();
            json.writeRaw('\n');
        }
        return new Archivo(ruta, Files.size(destino), HexFormat.of().formatHex(sha.digest()));
    }

    // ---------------------------------------------------------------- utilidades

    /** Precio en pesos con la mínima cantidad de decimales: 129000 → 1290, 119050 → 1190.5, 200599 → 2005.99. */
    static String pesos(long centavos) {
        if (centavos % 100 == 0) {
            return Long.toString(centavos / 100);
        }
        return BigDecimal.valueOf(centavos, 2).stripTrailingZeros().toPlainString();
    }

    static Archivo escribirGz(Path raiz, String ruta, Contenido contenido) throws IOException {
        Path destino = raiz.resolve(ruta);
        Files.createDirectories(destino.getParent());
        MessageDigest sha = sha256();
        try (OutputStream archivo = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(destino), 1 << 16), sha)) {
            comprimir(archivo, contenido);
        }
        return new Archivo(ruta, Files.size(destino), HexFormat.of().formatHex(sha.digest()));
    }

    private static void comprimir(OutputStream destino, Contenido contenido) throws IOException {
        GZIPOutputStream gz = new GZIPOutputStream(destino, 1 << 16) {
            {
                def.setLevel(Deflater.BEST_COMPRESSION);
            }
        };
        try (JsonGenerator json = JSON.createGenerator(new BufferedOutputStream(gz, 1 << 16), JsonEncoding.UTF8)) {
            contenido.escribir(json);
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** sha256 (hex) de un archivo, como lo verifica la app. */
    public static String sha256(Path archivo) {
        MessageDigest sha = sha256();
        try (var in = Files.newInputStream(archivo)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                sha.update(buf, 0, n);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static final class Contador extends OutputStream {
        long bytes;

        @Override
        public void write(int b) {
            bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bytes += len;
        }
    }
}
