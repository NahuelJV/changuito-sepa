# sepa-exportador
> Copia del exportador `tools/sepa-exportador` de la app Changuito. Los datos generados están en la rama [`datos`](https://github.com/NahuelJV/changuito-sepa/tree/datos) y se leen desde `https://raw.githubusercontent.com/NahuelJV/changuito-sepa/datos/v1/indice.json`.

CLI de Java 21 (Maven, sin Spring) que prepara los datos SEPA para el modo local de Changuito. Genera los archivos estáticos del **contrato del exportador v1** que se describe en [`docs/MODO-LOCAL.md`](../../docs/MODO-LOCAL.md):

- `v1/indice.json`
- `v1/catalogo.json.gz`
- `v1/celdas/<celda>.json.gz`, uno por celda de 0,1° × 0,1°, para todas las sucursales del país de las cadenas configuradas

Qué hace, en orden:
1. Busca el archivo del día en el CKAN de datos.produccion.gob.ar (dataset `sepa-precios`) y lo **baja completo a disco**. La consulta tiene un timeout de 20 s y 3 reintentos. La descarga se corta si pasan 2 min sin recibir bytes, y se reintenta.
2. Lo procesa en dos fases, con el mismo parser que el backend:
   - Recorre el zip de zips y guarda en temporales solo los comercios de las cadenas configuradas.
   - Lee sus CSV línea a línea.
3. Aplica las mismas reglas que el importador del backend:
   - RN-21: cadenas configurables. Se reconocen por razón social, bandera o URL (Carrefour es "INC S.A.", con `www.carrefour.com.ar`).
   - RN-19: la promo se toma solo si el descuento es menor al 70 %. Entre dos promos válidas, gana la más baja.
   - RN-06: EAN con dígito de control válido y `productos_ean = 1`. Los UPC-A se pasan a EAN-13.
4. Escribe la salida en una carpeta aparte y después reemplaza `<salida>/v1` de una sola vez. Una corrida que falla no deja archivos a medio escribir ni celdas viejas.

El código de `src/main/java/ar/changuito/exportador/sepa/` es una copia adaptada de `backend/src/main/java/ar/changuito/sepa/`: `CkanSepaClient`, `DescargaVigilada`, `SepaArchivoParser`, `LectorCsvSepa`, `PrecioSepa`, `CoincidenciaCadenas`, más `Textos` y `Ean`. El backend no se toca.

## Uso

```bash
cd tools/sepa-exportador
export JAVA_HOME=~/.local/opt/jdk21
./mvnw -q package                      # compila, corre los tests y arma target/sepa-exportador.jar

# Archivo de hoy (hora argentina), bajado del portal
java -jar target/sepa-exportador.jar --salida salida-dev

# Una fecha puntual, conservando el zip para repetir la corrida sin volver a bajarlo
java -jar target/sepa-exportador.jar --salida salida-dev --fecha 2026-09-28 --guardar-descarga /tmp/sepa_2026-09-28.zip

# Un zip local; la fecha se lee de la carpeta AAAA-MM-DD/ del zip
java -jar target/sepa-exportador.jar --salida salida-dev --zip /tmp/sepa_2026-09-28.zip --cadenas CARREFOUR,COTO
```

| Opción | Qué hace |
| --- | --- |
| `--salida <dir>` | Carpeta de publicación. Se reemplaza `<dir>/v1`. Obligatoria |
| `--fecha AAAA-MM-DD` | Archivo de esa fecha. Por defecto, el de hoy en hora argentina |
| `--zip <archivo>` | Usa un zip SEPA local en vez de bajarlo |
| `--cadenas A,B` | Cadenas a exportar (RN-21). Por defecto `CARREFOUR,COTO` |
| `--descuento-maximo 0.70` | Tope de descuento de RN-19 |
| `--precios-en-centavos` | Para cuando el archivo trae los precios en centavos (hoy vienen en pesos) |
| `--guardar-descarga <f>` | Conserva el zip bajado |
| `--temporales <dir>` | Carpeta de temporales. En disco quedan, como mucho, los zips de las cadenas configuradas (≈ 140 MB) |
| `--intentos <n>` | Intentos de descarga. Por defecto 3 |
| `--hilos <n>` | Hilos para comprimir las celdas |
| `--medir-sin-dedup` | Informa también cuánto pesarían las celdas en el formato v1 original |
| `--ckan-url`, `--dataset` | Otro CKAN, por ejemplo para pruebas |
| `--verbose` | Log detallado |

Códigos de salida:
- `0`: ok.
- `1`: error.
- `2`: uso incorrecto.
- `3`: el portal no publica el archivo de esa fecha. En ese caso no se toca la salida.

Al terminar, la corrida imprime un reporte con la duración, las sucursales por cadena, las celdas, los tamaños y las celdas más pesadas.

## Probar la app contra datos locales

```bash
tools/sepa-exportador/servir-dev.sh    # python3 -m http.server 8000 sobre tools/sepa-exportador/salida-dev
```

La app usa `SEPA_BASE_URL = http://10.0.2.2:8000/` desde el emulador, o `http://<IP de la PC>:8000/` desde un celular en la misma wifi. `salida-dev/` está en `.gitignore` y tiene la corrida real del 2026-09-28.

## Formato

Es el contrato v1 de `docs/MODO-LOCAL.md`, con el cambio anotado ahí como "Nota exportador": **tabla de precios por celda y listas como índices por diferencias**. En resumen:

```
v1/indice.json
{ "version": 1, "fecha": "2026-09-28", "generadoEn": "2026-09-29T22:42:23Z", "cadenas": ["CARREFOUR", "COTO"],
  "catalogo": { "archivo": "v1/catalogo.json.gz", "bytes": 695161, "sha256": "…" },
  "celdas": { "-347_-584": { "archivo": "v1/celdas/-347_-584.json.gz", "sucursales": 72, "bytes": 272251, "sha256": "…" } } }

v1/celdas/-347_-584.json.gz
{ "fecha": "2026-09-28",
  "sucursales": [ { "id": "COTO-12-1-101", "cadena": "COTO", "bandera": "COTO CICSA", "nombre": "AVELLANEDA",
                    "direccion": "Italia 62, Avellaneda", "lat": -34.6650066, "lon": -58.3623175, "lista": "cb0df449922f" } ],
  "precios": [ ["7790742000002", 1290, 1190, "2x1 llevando 2"], ["7790742000002", 1300, null, null], … ],
  "listas": { "cb0df449922f": [0, 3, 1, 1, …] } }      // índices a "precios": el primero y después las diferencias

v1/catalogo.json.gz
[ ["7790742352101", "Leche Desc.protein 46g La Serenisima Sch 1 Ltr", "La Serenisima", 1, "L"], … ]
```

Detalles:
- **Id de celda:** `Math.floor(lat*10)_Math.floor(lon*10)`, en double. Es un piso, no un truncamiento. El Obelisco cae en `-347_-584`, y una sucursal justo en el borde (-34.7) cae en `-347`.
- **gzip:** los `.gz` van con compresión máxima y sin fecha en el encabezado. El mismo contenido da siempre el mismo sha256, así que la app no vuelve a bajar lo que no cambió.
- **`bytes` y `sha256`:** son los del archivo `.gz`.
- **Precios:** en pesos, con la mínima cantidad de decimales (`1290`, `1950.5`).
- **Orden:** las sucursales van por `id`, las listas por id, `precios` por EAN y el catálogo por EAN.

## Tamaños medidos (corrida real, archivo del 2026-09-28)

Corrida en la PC de desarrollo (12 núcleos) con `-Xmx2g`. Pico de memoria: 830 MB de RSS.

| | |
| --- | --- |
| Duración total | **53 s** (descarga de 306 MB en 9,6 s + lectura 38 s + escritura 4 s) |
| Sucursales | **827** (Carrefour 707, Coto 120). De las 852 leídas, se descartaron 24 sin productos con EAN válido y 1 sin coordenadas |
| Filas de productos | 4.562.196 (135.990 descartadas; 208.735 con promo válida) |
| Celdas | **107** |
| Catálogo | **679 KB** (41.866 productos) |
| Celda del Obelisco `-347_-584` | **266 KB** (72 sucursales) |
| Índice | 22 KB |
| **Total en disco** | **12,1 MB** (celdas 11,4 MB) |

Las 5 celdas más pesadas:

| Celda | Tamaño | Sucursales |
| --- | ---: | ---: |
| `-346_-585` | 585 KB | 161 |
| `-347_-585` | 545 KB | 144 |
| `-347_-586` | 326 KB | 42 |
| `-346_-586` | 310 KB | 26 |
| `-345_-586` | 306 KB | 16 |

Con el formato v1 original, cada lista era un arreglo de tuplas. La deduplicación por lista idéntica **no ahorraba nada**: había 827 listas distintas para 827 sucursales, porque difieren en el surtido y en algunos precios. Con ese formato:
- `-346_-585` pesaba 4,04 MB.
- `-347_-585` pesaba 3,26 MB.
- El Obelisco pesaba 1,57 MB.
- El total de celdas era 26,2 MB.

Por pasar el límite de 3 MB, el formato cambió a una tabla de precios por celda con listas de índices, y bajó un **56 %** (86 % en las celdas densas). Pasar los precios a centavos no cambia el tamaño comprimido: se probó y la diferencia fue menor al 0,5 %.

## Publicación diaria (GitHub Actions)

El repo público de datos es [NahuelJV/changuito-sepa](https://github.com/NahuelJV/changuito-sepa):
- `main`: una copia de esta carpeta en la raíz, más el workflow.
- `datos`: solo los archivos `v1/`, reescritos cada día con un único commit y force-push. La rama no acumula historia.

Para activarlo:
1. Copiar el contenido de `tools/sepa-exportador/` a la raíz de `main` del repo de datos, **sin** `salida-dev/` ni `target/`. `mvnw` tiene que conservar el permiso de ejecución (`git update-index --chmod=+x mvnw` si hace falta).
2. Copiar `publicar-diario.yml` a `.github/workflows/publicar-diario.yml`. Si el exportador queda en una subcarpeta, ajustar `EXPORTADOR_DIR`.
3. En *Settings → Actions → General → Workflow permissions*, elegir **Read and write permissions**. El workflow publica con el `GITHUB_TOKEN` y no necesita otros secretos.
4. Correrlo a mano la primera vez: *Actions → Publicar datos SEPA → Run workflow*. Se le puede pasar una fecha. Eso crea la rama `datos`.

Qué hace el workflow:
- Corre todos los días a las **20:00 hora argentina** (`cron: "0 23 * * *"`, en UTC). El archivo del día se publica cerca de las 16:20.
- Compila, corre los tests y exporta.
- Publica solo si todo salió bien. Si el portal todavía no publicó el archivo (código 3), termina con un aviso y no toca `datos`: la app sigue con los archivos del día anterior.

URL resultante, que es la `SEPA_BASE_URL` de producción:

```
https://raw.githubusercontent.com/NahuelJV/changuito-sepa/datos/
https://raw.githubusercontent.com/NahuelJV/changuito-sepa/datos/v1/indice.json
```

Tener en cuenta:
- `raw.githubusercontent.com` cachea unos 5 minutos.
- GitHub pausa los workflows programados de un repo público tras 60 días sin actividad. Si pasa, se reactiva desde *Actions*.
- El portal a veces tarda en aceptar conexiones. El cliente reintenta, pero si falla desde los runners de GitHub, el workflow queda en rojo y se puede relanzar a mano.

## Licencia de los datos

Los precios provienen de **Precios Claros – Base SEPA** (Sistema Electrónico de Publicidad de Precios Argentinos), publicada en <https://datos.produccion.gob.ar/dataset/sepa-precios>:
- Autor: Subsecretaría de Defensa del Consumidor y Lealtad Comercial.
- Organización: Secretaría de Comercio Interior, República Argentina.
- Licencia: [Creative Commons Atribución 4.0 (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/).

Atribución que acompaña a los datos, en la app y en la rama `datos`:

> Fuente: Precios Claros – Base SEPA, Secretaría de Comercio Interior (datos.produccion.gob.ar), CC BY 4.0. Datos filtrados (Carrefour y Coto, EAN válidos, promos con descuento menor al 70 %) y reorganizados por zona por Changuito; no es una publicación oficial.

Los precios los informa cada comercio. Según los términos de Precios Claros (<https://www.preciosclaros.gob.ar/terminos_y_condiciones.html>), el Estado no garantiza que sean exactos.

## Tests

`./mvnw test` corre 49 tests JUnit 5:
- **Formato exacto con el fixture de filas reales:** campos y su orden, rutas, orden de los arreglos, tabla de precios e índices.
- **Id de celda:** Obelisco, negativos, piso contra truncamiento, bordes.
- **Deduplicación:** listas idénticas en distinto orden, EAN repetido, UPC-A.
- **Reglas:** RN-19 (cuota descartada, promo válida), RN-21 (cadenas configurables) y RN-06.
- **gzip y sha256:** bytes y sha256 del índice contra los archivos; mismo sha con otro `generadoEn`.
- **Reemplazo de la salida sin celdas viejas.**
- **Cliente CKAN:** consulta colgada, descarga trabada o cortada con reintento.
- **Corrida completa contra un CKAN local:** códigos de salida.

El fixture `src/test/resources/sepa/sepa_fixture.zip` y su generador son los del backend.
