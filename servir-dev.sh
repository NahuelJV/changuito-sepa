#!/usr/bin/env bash
# Sirve la salida de desarrollo del exportador por HTTP, para que la app la baje como SEPA_BASE_URL:
#   emulador Android:        http://10.0.2.2:8000/
#   celular en la misma wifi: http://<IP de la PC>:8000/
# Uso: tools/sepa-exportador/servir-dev.sh   (PUERTO=8001 para cambiar el puerto)
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/salida-dev"
PUERTO="${PUERTO:-8000}"

if [[ ! -f "$DIR/v1/indice.json" ]]; then
  echo "No hay datos en $DIR." >&2
  echo "Generalos primero (ver tools/sepa-exportador/README.md), por ejemplo:" >&2
  echo "  cd tools/sepa-exportador && ./mvnw -q -DskipTests package && java -jar target/sepa-exportador.jar --salida salida-dev --fecha AAAA-MM-DD" >&2
  exit 1
fi

fecha="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["fecha"])' "$DIR/v1/indice.json")"
echo "Sirviendo SEPA del $fecha desde $DIR"
echo "  índice: http://localhost:$PUERTO/v1/indice.json  (emulador: http://10.0.2.2:$PUERTO/v1/indice.json)"
exec python3 -m http.server "$PUERTO" --bind 0.0.0.0 --directory "$DIR"
