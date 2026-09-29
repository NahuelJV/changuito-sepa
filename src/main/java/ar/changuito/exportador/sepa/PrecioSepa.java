package ar.changuito.exportador.sepa;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;


/**
 * RN-19: de SEPA se usa el precio de lista; una promo solo se toma si su descuento es menor al máximo
 * configurado (70 %), para no confundir el valor de una cuota con un precio rebajado. Si hay dos promos
 * válidas, gana la más baja.
 *
 * @param precio precio vigente que se registra
 * @param lista  precio de lista
 * @param promo  leyenda de la promo tomada, o null
 */
public record PrecioSepa(BigDecimal precio, BigDecimal lista, String promo) {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    public static Optional<PrecioSepa> calcular(String lista, String promo1, String leyenda1, String promo2,
                                                String leyenda2, BigDecimal descuentoMaximo, boolean enCentavos) {
        BigDecimal precioLista = numero(lista, enCentavos);
        if (precioLista == null || precioLista.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal mejor = null;
        String leyenda = null;
        BigDecimal[] promos = {numero(promo1, enCentavos), numero(promo2, enCentavos)};
        String[] leyendas = {leyenda1, leyenda2};
        for (int i = 0; i < promos.length; i++) {
            BigDecimal promo = promos[i];
            if (promo != null && promoValida(precioLista, promo, descuentoMaximo) && (mejor == null || promo.compareTo(mejor) < 0)) {
                mejor = promo;
                leyenda = Textos.vacio(leyendas[i]) ? "Promo" : Textos.truncar(leyendas[i].trim(), 200);
            }
        }
        return Optional.of(mejor == null
                ? new PrecioSepa(precioLista, precioLista, null)
                : new PrecioSepa(mejor, precioLista, leyenda));
    }

    static boolean promoValida(BigDecimal lista, BigDecimal promo, BigDecimal descuentoMaximo) {
        if (promo.signum() <= 0 || promo.compareTo(lista) >= 0) {
            return false;
        }
        BigDecimal descuento = BigDecimal.ONE.subtract(promo.divide(lista, 6, RoundingMode.HALF_UP));
        return descuento.compareTo(descuentoMaximo) < 0;
    }

    /** Números con punto decimal ("1950.00"); tolera coma decimal y separador de miles. */
    static BigDecimal numero(String texto, boolean enCentavos) {
        if (Textos.vacio(texto)) {
            return null;
        }
        String t = texto.trim().replace("$", "").replace(" ", "");
        if (t.contains(",") && t.contains(".")) {
            t = t.lastIndexOf(',') > t.lastIndexOf('.') ? t.replace(".", "").replace(',', '.') : t.replace(",", "");
        } else if (t.contains(",")) {
            t = t.replace(',', '.');
        }
        try {
            BigDecimal valor = new BigDecimal(t);
            if (enCentavos) {
                valor = valor.divide(CIEN, 2, RoundingMode.HALF_UP);
            }
            return valor.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
