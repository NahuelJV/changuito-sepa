package ar.changuito.exportador;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Lista de precios de una sucursal en forma canónica: ordenada por EAN, sin EAN repetidos, precios en centavos.
 * Dos sucursales con la misma lista (mismos EAN, precios y promos) comparten el mismo {@link #hash()}, y así la
 * celda la escribe una sola vez.
 */
public final class ListaPrecios {

    /** Precio de promo ausente. */
    public static final long SIN_PROMO = -1;
    /** Largo del id de lista que se usa en el JSON (prefijo del sha256 del contenido canónico). */
    public static final int LARGO_ID = 12;

    private final String[] eans;
    private final long[] listas;
    private final long[] promos;
    private final String[] leyendas;
    private final String hash;

    private ListaPrecios(String[] eans, long[] listas, long[] promos, String[] leyendas) {
        this.eans = eans;
        this.listas = listas;
        this.promos = promos;
        this.leyendas = leyendas;
        this.hash = calcularHash();
    }

    public int tamanio() {
        return eans.length;
    }

    public String ean(int i) {
        return eans[i];
    }

    public long listaCentavos(int i) {
        return listas[i];
    }

    /** @return centavos de la promo o {@link #SIN_PROMO} */
    public long promoCentavos(int i) {
        return promos[i];
    }

    public String leyenda(int i) {
        return leyendas[i];
    }

    /** sha256 (hex) del contenido canónico. */
    public String hash() {
        return hash;
    }

    /** Id corto con el que la celda referencia la lista. */
    public String id() {
        return hash.substring(0, LARGO_ID);
    }

    private String calcularHash() {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < eans.length; i++) {
            sb.setLength(0);
            sb.append(eans[i]).append('|').append(listas[i]).append('|').append(promos[i]).append('|')
                    .append(leyendas[i] == null ? "" : leyendas[i]).append('\n');
            sha.update(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ListaPrecios otra && hash.equals(otra.hash) && Arrays.equals(eans, otra.eans)
                && Arrays.equals(listas, otra.listas) && Arrays.equals(promos, otra.promos)
                && Arrays.equals(leyendas, otra.leyendas);
    }

    @Override
    public int hashCode() {
        return hash.hashCode();
    }

    /** Acumula los productos de una sucursal en el orden del CSV; {@link #construir()} la deja canónica. */
    public static final class Constructor {

        private String[] eans = new String[256];
        private long[] listas = new long[256];
        private long[] promos = new long[256];
        private String[] leyendas = new String[256];
        private int n;

        public void agregar(String ean, long listaCentavos, long promoCentavos, String leyenda) {
            if (n == eans.length) {
                int nuevo = n * 2;
                eans = Arrays.copyOf(eans, nuevo);
                listas = Arrays.copyOf(listas, nuevo);
                promos = Arrays.copyOf(promos, nuevo);
                leyendas = Arrays.copyOf(leyendas, nuevo);
            }
            eans[n] = Objects.requireNonNull(ean);
            listas[n] = listaCentavos;
            promos[n] = promoCentavos;
            leyendas[n] = promoCentavos == SIN_PROMO ? null : leyenda;
            n++;
        }

        public int tamanio() {
            return n;
        }

        /** Ordena por EAN; si un EAN se repite en la sucursal, queda la primera fila del CSV. */
        public ListaPrecios construir() {
            Integer[] orden = new Integer[n];
            for (int i = 0; i < n; i++) {
                orden[i] = i;
            }
            // Orden estable: ante EAN iguales conserva el orden del CSV.
            Arrays.sort(orden, (a, b) -> eans[a].compareTo(eans[b]));
            String[] e = new String[n];
            long[] l = new long[n];
            long[] p = new long[n];
            String[] ley = new String[n];
            int m = 0;
            for (Integer i : orden) {
                if (m > 0 && e[m - 1].equals(eans[i])) {
                    continue;
                }
                e[m] = eans[i];
                l[m] = listas[i];
                p[m] = promos[i];
                ley[m] = leyendas[i];
                m++;
            }
            return new ListaPrecios(Arrays.copyOf(e, m), Arrays.copyOf(l, m), Arrays.copyOf(p, m), Arrays.copyOf(ley, m));
        }
    }
}
