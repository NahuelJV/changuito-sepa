package ar.changuito.exportador.sepa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lectura en streaming del formato real de SEPA (fixture armado con filas reales del 2026-09-23). */
class SepaArchivoParserTest {

    private static final String FIXTURE = "/sepa/sepa_fixture.zip";

    @Test
    void avisaElFinDeCadaComercioDespuesDeSusProductos() throws Exception {
        List<String> eventos = new ArrayList<>();
        Registro registro = new Registro(true) {
            @Override
            public void producto(String claveSucursal, FilaCsv fila) {
                if (eventos.isEmpty() || !eventos.get(eventos.size() - 1).equals("producto")) {
                    eventos.add("producto");
                }
            }

            @Override
            public void finComercio(String archivo) {
                eventos.add("fin");
            }
        };
        recorrer(registro);
        assertThat(eventos).containsExactly("producto", "fin", "producto", "fin", "producto", "fin");
    }

    @Test
    void recorreLosTresComerciosYSalteaElZipVacio() throws Exception {
        Registro registro = new Registro(true);
        int comercios = recorrer(registro);

        assertThat(comercios).isEqualTo(3);
        assertThat(registro.archivos).hasSize(3).allMatch(a -> a.startsWith("2026-09-23/"));
        assertThat(registro.razonesSociales).containsExactlyInAnyOrder(
                "INC S.A.", "COTO CENTRO INTEGRAL DE COMERCIALIZACION S.A.", "DIA Argentina S.A.");
    }

    @Test
    void toleraBomCrlfYPieDeUltimaActualizacion() throws Exception {
        Registro registro = new Registro(true);
        recorrer(registro);

        // Con BOM, la primera columna igual se llama id_comercio; el pie y las líneas en blanco no son filas.
        assertThat(registro.productosPorComercio).containsEntry("10", 10).containsEntry("12", 7).containsEntry("15", 2);
        assertThat(registro.sucursales).contains("10-2-189", "10-3-761", "10-4-504", "12-1-159", "12-1-185", "15-1-454");
        // CRLF: la última columna no arrastra '\r'.
        assertThat(registro.ultimasColumnas).allMatch(v -> !v.contains("\r"));
    }

    @Test
    void siElComercioNoInteresaNoLeeSucursalesNiProductos() throws Exception {
        Registro registro = new Registro(false);
        recorrer(registro);

        assertThat(registro.sucursales).isEmpty();
        assertThat(registro.productosPorComercio).isEmpty();
    }

    @Test
    void soloEntregaProductosDeLasSucursalesElegidas() throws Exception {
        Registro registro = new Registro(true);
        registro.soloSucursales = Set.of("10-2-189");
        recorrer(registro);

        assertThat(registro.productosPorComercio).containsOnlyKeys("10").containsEntry("10", 8);
    }

    /**
     * Regresión de la prueba real del 2026-09-24: procesar mientras se descargaba dejaba la conexión parada
     * varios minutos (zip de Carrefour) y el portal la cortaba ("closed"). Ahora la descarga se lee entera
     * antes de escribir un solo producto.
     */
    @Test
    void laDescargaSeLeeEnteraAntesDeProcesarElPrimerProducto() throws Exception {
        List<Boolean> descargaTerminadaAlProcesar = new ArrayList<>();
        try (InputStream fixture = SepaArchivoParserTest.class.getResourceAsStream(FIXTURE);
             FinDeStream in = new FinDeStream(fixture)) {
            Registro registro = new Registro(true) {
                @Override
                public void producto(String claveSucursal, FilaCsv fila) {
                    descargaTerminadaAlProcesar.add(in.terminado);
                    super.producto(claveSucursal, fila);
                }
            };
            new SepaArchivoParser().recorrer(in, registro);
        }
        assertThat(descargaTerminadaAlProcesar).isNotEmpty().containsOnly(true);
    }

    @Test
    void losZipsQueNoInteresanNoSeGuardanYNoQuedanTemporales(@TempDir Path tmp) throws Exception {
        List<String> guardados = new ArrayList<>();
        Registro soloCoto = new Registro(true) {
            @Override
            public boolean interesa(String archivo, List<FilaCsv> comercio) {
                return comercio.stream().anyMatch(f -> f.get("comercio_razon_social").startsWith("COTO"));
            }

            @Override
            public boolean comercio(String archivo, List<FilaCsv> comercio) {
                guardados.add(archivo);
                return super.comercio(archivo, comercio);
            }
        };
        try (InputStream in = SepaArchivoParserTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(new SepaArchivoParser(tmp).recorrer(in, soloCoto)).isEqualTo(1);
        }
        assertThat(guardados).hasSize(1);
        assertThat(soloCoto.razonesSociales).containsExactly("COTO CENTRO INTEGRAL DE COMERCIALIZACION S.A.");
        try (Stream<Path> archivos = Files.list(tmp)) {
            assertThat(archivos).isEmpty();
        }
    }

    @Test
    void siFallaElProcesamientoIgualSeBorranLosTemporales(@TempDir Path tmp) throws Exception {
        Registro falla = new Registro(true) {
            @Override
            public void producto(String claveSucursal, FilaCsv fila) {
                throw new IllegalStateException("base caída");
            }
        };
        try (InputStream in = SepaArchivoParserTest.class.getResourceAsStream(FIXTURE)) {
            assertThatThrownBy(() -> new SepaArchivoParser(tmp).recorrer(in, falla)).hasMessage("base caída");
        }
        try (Stream<Path> archivos = Files.list(tmp)) {
            assertThat(archivos).isEmpty();
        }
    }

    /** Marca cuándo se leyó el último byte de la "descarga". */
    private static final class FinDeStream extends FilterInputStream {
        volatile boolean terminado;

        FinDeStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            terminado |= b < 0;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            terminado |= n < 0;
            return n;
        }
    }

    private static int recorrer(VisitanteSepa visitante) throws Exception {
        try (InputStream in = SepaArchivoParserTest.class.getResourceAsStream(FIXTURE)) {
            return new SepaArchivoParser().recorrer(in, visitante);
        }
    }

    private static class Registro implements VisitanteSepa {
        private final boolean aceptar;
        private final List<String> archivos = new ArrayList<>();
        private final Set<String> razonesSociales = new HashSet<>();
        private final Set<String> sucursales = new HashSet<>();
        private final Map<String, Integer> productosPorComercio = new LinkedHashMap<>();
        private final List<String> ultimasColumnas = new ArrayList<>();
        private Set<String> soloSucursales;

        Registro(boolean aceptar) {
            this.aceptar = aceptar;
        }

        @Override
        public boolean comercio(String archivo, List<FilaCsv> comercio) {
            archivos.add(archivo);
            comercio.forEach(f -> razonesSociales.add(f.get("comercio_razon_social")));
            return aceptar;
        }

        @Override
        public Set<String> sucursales(List<FilaCsv> filas) {
            Set<String> claves = new HashSet<>();
            filas.forEach(f -> claves.add(VisitanteSepa.clave(f)));
            sucursales.addAll(claves);
            return soloSucursales == null ? claves : soloSucursales;
        }

        @Override
        public void producto(String claveSucursal, FilaCsv fila) {
            productosPorComercio.merge(fila.get("id_comercio"), 1, Integer::sum);
            ultimasColumnas.add(fila.toString());
        }
    }
}
