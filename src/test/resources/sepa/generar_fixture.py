#!/usr/bin/env python3
"""
Genera sepa_fixture.zip: un archivo SEPA chico con el mismo formato que el real
(datos.produccion.gob.ar, dataset sepa-precios).

Uso:
    python3 generar_fixture.py <carpeta_del_dia_descomprimida> [salida.zip]

<carpeta_del_dia_descomprimida> es la carpeta YYYY-MM-DD/ que queda al descomprimir el zip diario
(contiene sepa_N_comercio-sepa-XX_....zip). Se toman filas reales de Carrefour (10), Coto (12) y DIA (15)
y se fabrican algunas para cubrir casos borde:
  - promo con descuento >= 70 % (RN-19, se descarta)
  - EAN con dígito de control inválido (RN-06, se descarta)
  - fila con productos_ean = 0 (código interno, se descarta)
Se conservan el BOM, los finales de línea (LF en Carrefour, CRLF en Coto y DIA), el pie
"Ultima actualización: ..." y el orden variable de los archivos internos, y se agrega un zip interno de 0 bytes.

Resultado esperado con una zona de 5 km alrededor del Obelisco (-34.6037, -58.3816):
  comercios: Carrefour 10-2-189, Carrefour 10-3-761, Coto 12-1-159 (Carrefour 10-4-504 en Córdoba y
             Coto 12-1-185 en Neuquén quedan afuera; DIA no es una cadena configurada)
  productos distintos: 7, observaciones: 12
"""
import io
import sys
import zipfile
from pathlib import Path

FECHA = "2026-09-23"
CARREFOUR = "sepa_2_comercio-sepa-10_2026-09-23_01-05-08.zip"
COTO = "sepa_1_comercio-sepa-12_2026-09-23_09-05-11.zip"
DIA = "sepa_1_comercio-sepa-15_2026-09-23_09-05-11.zip"
VACIO = "sepa_2_comercio-sepa-36_2026-09-23_01-05-08.zip"

FIDEOS, SAL, CHOCOLATE, ROYAL = "7790070336361", "7790072001038", "7790580105822", "7622300148119"
GASEOSA, ACONDICIONADOR, LECHE, MAYONESA = "7791720025048", "7798159713973", "7790742352101", "7791620187730"


def leer(zf, nombre):
    return zf.read(nombre).decode("utf-8-sig")


def buscar_filas(zf, criterio, maximo):
    """Recorre productos.csv en streaming y devuelve las filas (sin fin de línea) que cumplen el criterio."""
    encontradas = {}
    with zf.open("productos.csv") as fh:
        for linea in io.TextIOWrapper(fh, encoding="utf-8-sig", newline=""):
            campos = linea.rstrip("\r\n").split("|")
            if len(campos) < 17:
                continue
            clave = criterio(campos)
            if clave and clave not in encontradas:
                encontradas[clave] = linea.rstrip("\r\n")
                if len(encontradas) >= maximo:
                    break
    return encontradas


def encabezado_y_pie(texto):
    lineas = texto.replace("\r\n", "\n").split("\n")
    pie = next((l for l in lineas if l.lower().startswith(("ultima", "última"))), "Ultima actualizacion: " + FECHA)
    return lineas[0], pie


def filas_sucursal(texto, ids):
    lineas = texto.replace("\r\n", "\n").split("\n")
    return [l for l in lineas[1:] if l.count("|") > 5 and l.split("|")[2] in ids]


def csv(encabezado, filas, pie, bom, eol):
    cuerpo = eol.join([encabezado] + filas + ["", pie, ""])
    return (("﻿" if bom else "") + cuerpo).encode("utf-8")


def reemplazar(fila, indice, valor):
    campos = fila.split("|")
    campos[indice] = valor
    return "|".join(campos)


def zip_interno(archivos):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zf:
        for nombre, contenido in archivos:
            zf.writestr(nombre, contenido)
    return buffer.getvalue()


def carrefour(origen):
    with zipfile.ZipFile(origen / CARREFOUR) as zf:
        comercio_txt = leer(zf, "comercio.csv")
        sucursales_txt = leer(zf, "sucursales.csv")
        productos_hdr, productos_pie = encabezado_y_pie(leer_primeras(zf, "productos.csv"))
        interes = {("2", "189"): {FIDEOS, SAL, CHOCOLATE, ROYAL, GASEOSA, ACONDICIONADOR},
                   ("3", "761"): {FIDEOS}, ("4", "504"): {MAYONESA}}
        filas = buscar_filas(zf, lambda c: (c[1], c[2], c[3]) if c[3] in interes.get((c[1], c[2]), ()) else None, 8)
    productos = [filas[k] for k in sorted(filas)]
    acond = filas[("2", "189", ACONDICIONADOR)]
    # RN-19: promo con descuento >= 70 % (lista 4090, promo 1000 → 75,5 %) se descarta.
    productos = [p for p in productos if p != acond]
    productos.append(reemplazar(reemplazar(acond, 13, "1000.00"), 14, "Llevando 4 pagás en 12 cuotas"))
    # RN-06: EAN con dígito de control inválido.
    productos.append(reemplazar(filas[("2", "189", SAL)], 3, SAL[:-1] + "9"))
    # Código interno (productos_ean = 0).
    productos.append("10|2|189|2000000000019|0|PAN FRANCES X KG|1|KG|S/D|3500.00|3500.00|1|KGM||||")
    c_hdr, c_pie = encabezado_y_pie(comercio_txt)
    s_hdr, s_pie = encabezado_y_pie(sucursales_txt)
    comercio_filas = [l for l in comercio_txt.replace("\r\n", "\n").split("\n")[1:] if l.count("|") > 5]
    return zip_interno([
        ("productos.csv", csv(productos_hdr, productos, productos_pie, True, "\n")),
        ("comercio.csv", csv(c_hdr, comercio_filas, c_pie, True, "\n")),
        ("sucursales.csv", csv(s_hdr, filas_sucursal(sucursales_txt, {"189", "761", "504"}), s_pie, True, "\n")),
    ])


def coto(origen):
    with zipfile.ZipFile(origen / COTO) as zf:
        comercio_txt = leer(zf, "comercio.csv")
        sucursales_txt = leer(zf, "sucursales.csv")
        productos_hdr, productos_pie = encabezado_y_pie(leer_primeras(zf, "productos.csv"))
        buscados = {FIDEOS, SAL, CHOCOLATE, ROYAL, LECHE}
        filas = buscar_filas(zf, lambda c: c[3] if c[3] in buscados and (c[3] != LECHE or c[13]) else None, 5)
        interno = buscar_filas(zf, lambda c: "interno" if c[4] == "0" else None, 1)
    productos = [reemplazar(filas[e], 2, "159") for e in [FIDEOS, SAL, CHOCOLATE, ROYAL, LECHE]]
    productos.append(reemplazar(interno["interno"], 2, "159"))
    productos.append(reemplazar(filas[FIDEOS], 2, "185"))  # Neuquén: fuera de zona
    c_hdr, c_pie = encabezado_y_pie(comercio_txt)
    s_hdr, s_pie = encabezado_y_pie(sucursales_txt)
    comercio_filas = [l for l in comercio_txt.replace("\r\n", "\n").split("\n")[1:] if l.count("|") > 5]
    return zip_interno([
        ("comercio.csv", csv(c_hdr, comercio_filas, c_pie, False, "\r\n")),
        ("sucursales.csv", csv(s_hdr, filas_sucursal(sucursales_txt, {"159", "185"}), s_pie, False, "\r\n")),
        ("productos.csv", csv(productos_hdr, productos, productos_pie, False, "\r\n")),
    ])


def dia(origen):
    with zipfile.ZipFile(origen / DIA) as zf:
        comercio_txt = leer(zf, "comercio.csv")
        sucursales_txt = leer(zf, "sucursales.csv")
        productos_hdr, productos_pie = encabezado_y_pie(leer_primeras(zf, "productos.csv"))
        filas = buscar_filas(zf, lambda c: c[3] if c[2] == "454" else None, 2)
    c_hdr, c_pie = encabezado_y_pie(comercio_txt)
    s_hdr, s_pie = encabezado_y_pie(sucursales_txt)
    comercio_filas = [l for l in comercio_txt.replace("\r\n", "\n").split("\n")[1:] if l.count("|") > 5]
    return zip_interno([
        ("comercio.csv", csv(c_hdr, comercio_filas, c_pie, False, "\r\n")),
        ("productos.csv", csv(productos_hdr, list(filas.values()), productos_pie, False, "\r\n")),
        ("sucursales.csv", csv(s_hdr, filas_sucursal(sucursales_txt, {"454"}), s_pie, False, "\r\n")),
    ])


def leer_primeras(zf, nombre):
    """Encabezado de un CSV enorme sin leerlo entero; el pie se toma de comercio.csv del mismo zip."""
    with zf.open(nombre) as fh:
        primera = io.TextIOWrapper(fh, encoding="utf-8-sig", newline="").readline().rstrip("\r\n")
    pie = encabezado_y_pie(zf.read("comercio.csv").decode("utf-8-sig"))[1]
    return primera + "\n" + pie


def main():
    origen = Path(sys.argv[1])
    salida = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).with_name("sepa_fixture.zip")
    with zipfile.ZipFile(salida, "w", zipfile.ZIP_STORED) as zf:
        zf.writestr(FECHA + "/", b"")
        zf.writestr(f"{FECHA}/{CARREFOUR}", carrefour(origen))
        zf.writestr(f"{FECHA}/{VACIO}", b"")
        zf.writestr(f"{FECHA}/{COTO}", coto(origen))
        zf.writestr(f"{FECHA}/{DIA}", dia(origen))
    print("fixture:", salida, salida.stat().st_size, "bytes")


if __name__ == "__main__":
    main()
