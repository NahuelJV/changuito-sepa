package ar.changuito.exportador.sepa;

/** RN-06: validación del dígito de control de EAN-8, UPC-A (12), EAN-13 y GTIN-14. */
public final class Ean {

    private Ean() {
    }

    public static boolean valido(String codigo) {
        if (codigo == null) {
            return false;
        }
        String c = codigo.trim();
        if (!(c.length() == 8 || c.length() == 12 || c.length() == 13 || c.length() == 14) || !c.chars().allMatch(Character::isDigit)) {
            return false;
        }
        if (c.chars().allMatch(ch -> ch == '0')) {
            return false;
        }
        int suma = 0;
        // Desde el dígito anterior al de control hacia la izquierda, pesos 3,1,3,1...
        for (int i = c.length() - 2, peso = 3; i >= 0; i--, peso = peso == 3 ? 1 : 3) {
            suma += (c.charAt(i) - '0') * peso;
        }
        int control = (10 - (suma % 10)) % 10;
        return control == c.charAt(c.length() - 1) - '0';
    }

    /** Normaliza a EAN-13 cuando es un UPC-A (12 dígitos); el resto queda igual. */
    public static String normalizar(String codigo) {
        String c = codigo.trim();
        return c.length() == 12 ? "0" + c : c;
    }
}
