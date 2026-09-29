package ar.changuito.exportador;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ar.changuito.exportador.sepa.CoincidenciaCadenas;
import ar.changuito.exportador.sepa.Ean;
import ar.changuito.exportador.sepa.FilaCsv;
import ar.changuito.exportador.sepa.Log;
import ar.changuito.exportador.sepa.PrecioSepa;
import ar.changuito.exportador.sepa.Textos;
import ar.changuito.exportador.sepa.VisitanteSepa;

/**
 * Recorre el archivo SEPA y junta, para TODAS las sucursales del país de las cadenas configuradas (RN-21),
 * su lista de precios canónica (RN-19 aplicada) y el catálogo de productos. Las listas idénticas se comparten
 * por hash desde el momento en que se construyen, así la memoria no crece con las sucursales repetidas.
 * <p>
 * Mismos criterios que {@code SepaImportador} del backend: EAN con dígito de control válido y
 * {@code productos_ean = 1} (RN-06), precio de lista positivo, descripción no vacía, UPC-A normalizado a EAN-13.
 */
public final class RecolectorSepa implements VisitanteSepa {

    private static final Set<String> MARCAS_VACIAS = Set.of("", "S/D", "SD", "GENERICO", "SIN MARCA", "-");
    private static final Pattern FECHA_ARCHIVO = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})/");

    public record Sucursal(String id, String cadena, String bandera, String nombre, String direccion,
                           double lat, double lon, String celda, ListaPrecios lista) {
    }

    public record Producto(String ean, String descripcion, String marca, BigDecimal cantidad, String unidad) {
    }

    /** Contadores para el reporte de la corrida. */
    public static final class Estadisticas {
        public int comercios;
        public int sucursalesLeidas;
        public int sucursalesSinCoordenadas;
        public int sucursalesSinProductos;
        public int sucursalesRepetidas;
        public long filasProducto;
        public long filasDescartadas;
        public long promosAplicadas;
    }

    private record Bandera(String cadena, String nombre) {
    }

    private record Pendiente(String id, String cadena, String bandera, String nombre, String direccion,
                             double lat, double lon, ListaPrecios.Constructor precios) {
    }

    private final CoincidenciaCadenas cadenas;
    private final BigDecimal descuentoMaximo;
    private final boolean enCentavos;
    private final Map<String, Bandera> banderas = new HashMap<>();
    private final Map<String, Pendiente> pendientes = new LinkedHashMap<>();
    private final Map<String, Sucursal> sucursales = new LinkedHashMap<>();
    private final Map<String, ListaPrecios> listas = new HashMap<>();
    private final Map<String, Producto> catalogo = new HashMap<>();
    private final Map<String, String> leyendas = new HashMap<>();
    private final Estadisticas estadisticas = new Estadisticas();
    private String fechaArchivo;

    public RecolectorSepa(List<String> cadenas, BigDecimal descuentoMaximo, boolean enCentavos) {
        this.cadenas = new CoincidenciaCadenas(cadenas);
        this.descuentoMaximo = descuentoMaximo;
        this.enCentavos = enCentavos;
    }

    @Override
    public boolean interesa(String archivo, List<FilaCsv> filas) {
        anotarFecha(archivo);
        return filas.stream().anyMatch(fila -> cadena(fila).isPresent());
    }

    @Override
    public boolean comercio(String archivo, List<FilaCsv> filas) {
        banderas.clear();
        for (FilaCsv fila : filas) {
            cadena(fila).ifPresent(cadena -> banderas.put(fila.get("id_comercio") + "-" + fila.get("id_bandera"),
                    new Bandera(cadena, fila.get("comercio_bandera_nombre").replaceAll("\\s+", " "))));
        }
        if (!banderas.isEmpty()) {
            estadisticas.comercios++;
        }
        return !banderas.isEmpty();
    }

    @Override
    public Set<String> sucursales(List<FilaCsv> filas) {
        Set<String> claves = new LinkedHashSet<>();
        for (FilaCsv fila : filas) {
            Bandera bandera = banderas.get(fila.get("id_comercio") + "-" + fila.get("id_bandera"));
            if (bandera == null) {
                continue;
            }
            estadisticas.sucursalesLeidas++;
            Double lat = coordenada(fila.get("sucursales_latitud"));
            Double lon = coordenada(fila.get("sucursales_longitud"));
            String clave = VisitanteSepa.clave(fila);
            if (lat == null || lon == null || !Celda.enArgentina(lat, lon)) {
                estadisticas.sucursalesSinCoordenadas++;
                Log.debug("SEPA: sucursal {} sin coordenadas válidas ({}, {}), se saltea", clave,
                        fila.get("sucursales_latitud"), fila.get("sucursales_longitud"));
                continue;
            }
            String id = bandera.cadena() + "-" + clave;
            if (sucursales.containsKey(id) || pendientes.containsKey(id)) {
                estadisticas.sucursalesRepetidas++;
                Log.warn("SEPA: la sucursal {} aparece dos veces, queda la primera", id);
                continue;
            }
            pendientes.put(id, new Pendiente(id, bandera.cadena(), bandera.nombre(),
                    fila.get("sucursales_nombre").replaceAll("\\s+", " "), direccion(fila), lat, lon,
                    new ListaPrecios.Constructor()));
            claves.add(clave);
        }
        return claves;
    }

    @Override
    public void producto(String claveSucursal, FilaCsv fila) {
        estadisticas.filasProducto++;
        Bandera bandera = banderas.get(fila.get("id_comercio") + "-" + fila.get("id_bandera"));
        Pendiente sucursal = bandera == null ? null : pendientes.get(bandera.cadena() + "-" + claveSucursal);
        String codigo = fila.get("id_producto");
        String descripcion = fila.get("productos_descripcion");
        if (sucursal == null || !"1".equals(fila.get("productos_ean")) || !Ean.valido(codigo) || descripcion.isEmpty()) {
            estadisticas.filasDescartadas++;
            return;
        }
        Optional<PrecioSepa> precio = PrecioSepa.calcular(fila.get("productos_precio_lista"),
                fila.get("productos_precio_unitario_promo1"), fila.get("productos_leyenda_promo1"),
                fila.get("productos_precio_unitario_promo2"), fila.get("productos_leyenda_promo2"),
                descuentoMaximo, enCentavos);
        if (precio.isEmpty()) {
            estadisticas.filasDescartadas++;
            return;
        }
        String ean = Ean.normalizar(codigo);
        Producto producto = catalogo.computeIfAbsent(ean, e -> nuevoProducto(e, descripcion, fila));
        PrecioSepa p = precio.get();
        long promo = ListaPrecios.SIN_PROMO;
        String leyenda = null;
        if (p.promo() != null) {
            promo = centavos(p.precio());
            leyenda = leyendas.computeIfAbsent(p.promo(), l -> l);
            estadisticas.promosAplicadas++;
        }
        sucursal.precios().agregar(producto.ean(), centavos(p.lista()), promo, leyenda);
    }

    /** Terminado productos.csv del comercio, sus sucursales quedan con la lista canónica y compartida. */
    @Override
    public void finComercio(String archivo) {
        for (Pendiente p : pendientes.values()) {
            if (p.precios().tamanio() == 0) {
                estadisticas.sucursalesSinProductos++;
                Log.debug("SEPA: sucursal {} sin productos con EAN válido, se saltea", p.id());
                continue;
            }
            ListaPrecios lista = p.precios().construir();
            ListaPrecios compartida = listas.putIfAbsent(lista.hash(), lista);
            if (compartida != null && !compartida.equals(lista)) {
                throw new IllegalStateException("Colisión de sha256 entre listas de precios: " + lista.hash());
            }
            sucursales.put(p.id(), new Sucursal(p.id(), p.cadena(), p.bandera(), p.nombre(), p.direccion(),
                    p.lat(), p.lon(), Celda.id(p.lat(), p.lon()), compartida == null ? lista : compartida));
        }
        pendientes.clear();
        Log.info("SEPA: {} listo ({} sucursales en total, {} listas distintas, {} productos)",
                archivo, sucursales.size(), listas.size(), catalogo.size());
    }

    public List<Sucursal> sucursales() {
        return new ArrayList<>(sucursales.values());
    }

    public Map<String, Producto> catalogo() {
        return catalogo;
    }

    public int listasDistintas() {
        return listas.size();
    }

    public Estadisticas estadisticas() {
        return estadisticas;
    }

    /** Fecha de la carpeta {@code YYYY-MM-DD/} del zip diario, si se pudo leer. */
    public Optional<LocalDate> fechaArchivo() {
        return Optional.ofNullable(fechaArchivo).map(LocalDate::parse);
    }

    private void anotarFecha(String archivo) {
        if (fechaArchivo == null) {
            Matcher m = FECHA_ARCHIVO.matcher(archivo);
            if (m.find()) {
                fechaArchivo = m.group(1);
            }
        }
    }

    private Optional<String> cadena(FilaCsv fila) {
        return cadenas.cadena(fila.get("comercio_razon_social"), fila.get("comercio_bandera_nombre"),
                fila.get("comercio_bandera_url"));
    }

    private static Producto nuevoProducto(String ean, String descripcion, FilaCsv fila) {
        Presentaciones.Presentacion pres = Presentaciones.de(fila.get("productos_cantidad_presentacion"),
                fila.get("productos_unidad_medida_presentacion"), descripcion);
        return new Producto(ean, Textos.truncar(Textos.capitalizar(descripcion.replaceAll("\\s+", " ")), 200),
                marca(fila.get("productos_marca")), pres == null ? null : pres.cantidad(),
                pres == null ? null : pres.unidad());
    }

    static long centavos(BigDecimal pesos) {
        return pesos.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    static String marca(String marca) {
        String m = Textos.sinTildesMayus(marca);
        return MARCAS_VACIAS.contains(m) ? null : Textos.truncar(Textos.capitalizar(marca.trim()), 100);
    }

    /** Igual que el backend: "calle número, localidad", sin repetir el número si la calle ya lo trae. */
    static String direccion(FilaCsv fila) {
        String calle = fila.get("sucursales_calle").replaceAll("\\s+", " ");
        String numero = fila.get("sucursales_numero");
        String localidad = fila.get("sucursales_localidad").replaceAll("\\s+", " ");
        StringBuilder sb = new StringBuilder(calle);
        if (!numero.isEmpty() && !calle.endsWith(numero)) {
            sb.append(' ').append(numero);
        }
        if (!localidad.isEmpty()) {
            sb.append(sb.isEmpty() ? "" : ", ").append(localidad);
        }
        return Textos.truncar(sb.toString().trim(), 255);
    }

    private static Double coordenada(String texto) {
        if (Textos.vacio(texto)) {
            return null;
        }
        try {
            double valor = Double.parseDouble(texto.trim().replace(',', '.'));
            return Double.isFinite(valor) && valor != 0.0 ? valor : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
