package ar.changuito.exportador.sepa;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;


/**
 * Lector de los CSV de SEPA: separador '|', primera línea de encabezado (con BOM opcional),
 * finales LF o CRLF, líneas en blanco y un pie "Ultima actualización: ...". Lee línea a línea.
 */
public final class LectorCsvSepa {

    private static final Pattern SEPARADOR = Pattern.compile("\\|");
    private static final char BOM = '﻿';

    private LectorCsvSepa() {
    }

    /** Recorre las filas de datos; devuelve cuántas entregó. */
    public static long leer(InputStream in, Consumer<FilaCsv> consumidor) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        Map<String, Integer> columnas = null;
        long filas = 0;
        String linea;
        while ((linea = reader.readLine()) != null) {
            if (!linea.isEmpty() && linea.charAt(0) == BOM) {
                linea = linea.substring(1);
            }
            if (linea.isBlank()) {
                continue;
            }
            if (columnas == null) {
                columnas = encabezado(linea);
                continue;
            }
            if (esPie(linea)) {
                continue;
            }
            String[] valores = SEPARADOR.split(linea, -1);
            if (valores.length < 2) {
                continue;
            }
            consumidor.accept(new FilaCsv(columnas, valores));
            filas++;
        }
        return filas;
    }

    public static List<FilaCsv> leerTodo(InputStream in) throws IOException {
        List<FilaCsv> filas = new ArrayList<>();
        leer(in, filas::add);
        return filas;
    }

    private static Map<String, Integer> encabezado(String linea) {
        String[] nombres = SEPARADOR.split(linea, -1);
        Map<String, Integer> columnas = new HashMap<>();
        for (int i = 0; i < nombres.length; i++) {
            columnas.putIfAbsent(nombres[i].trim().toLowerCase(Locale.ROOT), i);
        }
        return columnas;
    }

    private static boolean esPie(String linea) {
        return !linea.contains("|") || Textos.sinTildesMayus(linea).startsWith("ULTIMA ACTUALIZACION");
    }
}
