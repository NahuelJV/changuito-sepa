package ar.changuito.exportador;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Arma un zip diario SEPA chico con el formato real (zip de zips con los tres CSV separados por '|'). */
final class ZipSepaDePrueba {

    static final String COMERCIO = "id_comercio|id_bandera|comercio_cuit|comercio_razon_social|comercio_bandera_nombre"
            + "|comercio_bandera_url|comercio_ultima_actualizacion|comercio_version_sepa";
    static final String SUCURSALES = "id_comercio|id_bandera|id_sucursal|sucursales_nombre|sucursales_tipo"
            + "|sucursales_calle|sucursales_numero|sucursales_latitud|sucursales_longitud|sucursales_observaciones"
            + "|sucursales_barrio|sucursales_codigo_postal|sucursales_localidad|sucursales_provincia";
    static final String PRODUCTOS = "id_comercio|id_bandera|id_sucursal|id_producto|productos_ean|productos_descripcion"
            + "|productos_cantidad_presentacion|productos_unidad_medida_presentacion|productos_marca"
            + "|productos_precio_lista|productos_precio_referencia|productos_cantidad_referencia"
            + "|productos_unidad_medida_referencia|productos_precio_unitario_promo1|productos_leyenda_promo1"
            + "|productos_precio_unitario_promo2|productos_leyenda_promo2";

    private final String fecha;
    private final List<Object[]> comercios = new ArrayList<>();

    ZipSepaDePrueba(String fecha) {
        this.fecha = fecha;
    }

    /** Agrega un comercio: filas de datos (sin encabezado) de cada CSV. */
    ZipSepaDePrueba comercio(String numero, List<String> comercio, List<String> sucursales, List<String> productos) {
        comercios.add(new Object[]{numero, comercio, sucursales, productos});
        return this;
    }

    static String sucursal(String comercio, String bandera, String id, String nombre, double lat, double lon) {
        return comercio + "|" + bandera + "|" + id + "|" + nombre + "|Supermercado|Calle Falsa|123|" + lat + "|" + lon
                + "|||1000|Capital Federal|AR-C";
    }

    static String producto(String sucursal, String ean, String descripcion, String lista, String promo, String leyenda) {
        String[] s = sucursal.split("-");
        return s[0] + "|" + s[1] + "|" + s[2] + "|" + ean + "|1|" + descripcion + "|1|UNI|MARCA|" + lista + "|" + lista
                + "|1|UNI|" + promo + "|" + leyenda + "||";
    }

    Path escribir(Path destino) throws IOException {
        try (OutputStream archivo = Files.newOutputStream(destino); ZipOutputStream zip = new ZipOutputStream(archivo)) {
            zip.putNextEntry(new ZipEntry(fecha + "/"));
            zip.closeEntry();
            for (Object[] c : comercios) {
                zip.putNextEntry(new ZipEntry(fecha + "/sepa_1_comercio-sepa-" + c[0] + "_" + fecha + "_09-05-11.zip"));
                zip.write(interno(csv(COMERCIO, lista(c[1])), csv(SUCURSALES, lista(c[2])), csv(PRODUCTOS, lista(c[3]))));
                zip.closeEntry();
            }
        }
        return destino;
    }

    @SuppressWarnings("unchecked")
    private static List<String> lista(Object o) {
        return (List<String>) o;
    }

    private String csv(String encabezado, List<String> filas) {
        StringBuilder sb = new StringBuilder(encabezado).append('\n');
        filas.forEach(f -> sb.append(f).append('\n'));
        sb.append('\n').append("Ultima actualizacion: ").append(fecha).append("T01:00:00-03:00\n");
        return sb.toString();
    }

    private static byte[] interno(String comercio, String sucursales, String productos) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (String[] archivo : new String[][]{{"comercio.csv", comercio}, {"sucursales.csv", sucursales},
                    {"productos.csv", productos}}) {
                zip.putNextEntry(new ZipEntry(archivo[0]));
                zip.write(archivo[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }
}
