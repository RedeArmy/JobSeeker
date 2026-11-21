#!/usr/bin/env bash
# Descarga una herramienta de CI con versión y checksum SHA-256 fijados en este archivo.
#
# Por qué un binario y no una acción de terceros: una acción ejecuta código ajeno con el token del
# repositorio; aquí solo se ejecuta un binario cuya versión y huella están escritas en el propio
# repositorio y se revisan en el PR (ADR-0012 / criterio de acciones permitidas). Si el checksum no
# coincide, el script falla y no se instala nada.
#
# Uso: bash scripts/fetch-ci-tool.sh <gitleaks|actionlint|squawk|trivy> <directorio-destino>
# Para subir de versión: cambiar VERSION y SHA256 con los valores de `*_checksums.txt` del release.
set -euo pipefail

herramienta="${1:?uso: fetch-ci-tool.sh <gitleaks|actionlint|squawk|trivy> <directorio-destino>}"
destino="${2:?uso: fetch-ci-tool.sh <gitleaks|actionlint|squawk|trivy> <directorio-destino>}"

case "$herramienta" in
  gitleaks)
    VERSION="8.30.1"
    ARCHIVO="gitleaks_${VERSION}_linux_x64.tar.gz"
    URL="https://github.com/gitleaks/gitleaks/releases/download/v${VERSION}/${ARCHIVO}"
    SHA256="551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"
    ;;
  actionlint)
    VERSION="1.7.12"
    ARCHIVO="actionlint_${VERSION}_linux_amd64.tar.gz"
    URL="https://github.com/rhysd/actionlint/releases/download/v${VERSION}/${ARCHIVO}"
    SHA256="8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8"
    ;;
  squawk)
    # Linter de seguridad de migraciones SQL (ADR-0027). Binario suelto, sin archivo comprimido;
    # el proyecto no publica un archivo de checksums, así que la huella se tomó del `digest` que
    # GitHub registra al subir el activo y se comprobó contra el binario descargado.
    VERSION="2.73.0"
    ARCHIVO="squawk-linux-x64"
    URL="https://github.com/sbdchd/squawk/releases/download/v${VERSION}/${ARCHIVO}"
    SHA256="9ae3747ca5eafa48b4d5c535fc64b61e0735a763164193644aa6436cc97c4274"
    ;;
  trivy)
    # Escáner de vulnerabilidades de la imagen (ADR-0014, 05-verificacion-riesgos-java-25.md).
    VERSION="0.76.2"
    ARCHIVO="trivy_${VERSION}_Linux-64bit.tar.gz"
    URL="https://github.com/aquasecurity/trivy/releases/download/v${VERSION}/${ARCHIVO}"
    SHA256="bc0fc726e017b8927a62b2d3531d7a84ee01769daf5c99ed866dced54089fdbf"
    ;;
  *)
    echo "herramienta desconocida: $herramienta" >&2
    exit 2
    ;;
esac

mkdir -p "$destino"
temporal="$(mktemp -d)"
trap 'rm -rf "$temporal"' EXIT

# Reintentos solo ante fallos de red; un checksum distinto nunca se reintenta.
curl --fail --silent --show-error --location --retry 3 --retry-delay 2 \
  --output "$temporal/$ARCHIVO" "$URL"
echo "${SHA256}  ${temporal}/${ARCHIVO}" | sha256sum --check --status \
  || { echo "ERROR: el checksum de $ARCHIVO no coincide con el fijado en este script" >&2; exit 1; }

case "$ARCHIVO" in
  *.tar.gz) tar --extract --gzip --file "$temporal/$ARCHIVO" --directory "$destino" "$herramienta" ;;
  *) install -m 0755 "$temporal/$ARCHIVO" "$destino/$herramienta" ;;
esac
echo "$herramienta $VERSION instalado en $destino"
