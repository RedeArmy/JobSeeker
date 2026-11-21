#!/usr/bin/env bash
# Pruebas de check-migration-immutability.sh sobre repositorios temporales: sin ellas, un script roto
# dejaría pasar cualquier edición de una migración publicada.
set -uo pipefail

raiz="$(cd "$(dirname "$0")/.." && pwd)"
script="$raiz/scripts/check-migration-immutability.sh"
fallos=0

nuevo_repo() {
  local dir; dir="$(mktemp -d)"
  git -C "$dir" init -q -b main
  git -C "$dir" config user.email "t@example.test"; git -C "$dir" config user.name "t"
  mkdir -p "$dir/auth-module/src/main/resources/db/migration"
  echo "create table a (id int);" > "$dir/auth-module/src/main/resources/db/migration/V20261121100000__auth_create_a.sql"
  git -C "$dir" add -A; git -C "$dir" commit -q -m base
  echo "$dir"
}

# caso <nombre> <código-esperado> <comandos-en-el-repo>
caso() {
  local nombre="$1" esperado="$2" preparar="$3" dir codigo
  dir="$(nuevo_repo)"
  git -C "$dir" checkout -q -b rama
  ( cd "$dir" && eval "$preparar" ) >/dev/null 2>&1
  git -C "$dir" add -A; git -C "$dir" commit -q --allow-empty -m cambio
  ( cd "$dir" && bash "$script" main ) >/dev/null 2>&1; codigo=$?
  if [ "$codigo" -eq "$esperado" ]; then echo "  ok   $nombre"; else echo "  FALLA $nombre (esperado $esperado, obtenido $codigo)"; fallos=$((fallos + 1)); fi
  rm -rf "$dir"
}

M="auth-module/src/main/resources/db/migration"
caso "sin cambios en migraciones" 0 "echo x > otro.txt"
caso "agregar una migración nueva" 0 "echo 'create table b (id int);' > $M/V20261121100100__auth_create_b.sql"
caso "editar una migración publicada" 1 "echo '-- editado' >> $M/V20261121100000__auth_create_a.sql"
caso "renombrar una migración publicada" 1 "git mv $M/V20261121100000__auth_create_a.sql $M/V20261121100000__auth_create_z.sql"
caso "borrar una migración publicada" 1 "git rm -q $M/V20261121100000__auth_create_a.sql"

# Una rama atrasada no debe ver como «borradas» las migraciones que la base añadió después.
dir="$(nuevo_repo)"
git -C "$dir" checkout -q -b rama
echo y > "$dir/otro.txt"; git -C "$dir" add -A; git -C "$dir" commit -q -m rama
git -C "$dir" checkout -q main
echo 'create table c (id int);' > "$dir/$M/V20261121100200__auth_create_c.sql"; git -C "$dir" add -A; git -C "$dir" commit -q -m "base avanza"
git -C "$dir" checkout -q rama
( cd "$dir" && bash "$script" main ) >/dev/null 2>&1; codigo=$?
if [ "$codigo" -eq 0 ]; then echo "  ok   rama atrasada respecto de la base"; else echo "  FALLA rama atrasada (obtenido $codigo)"; fallos=$((fallos + 1)); fi
( cd "$dir" && bash "$script" referencia-inexistente ) >/dev/null 2>&1; codigo=$?
if [ "$codigo" -eq 0 ]; then echo "  ok   referencia base inexistente se omite"; else echo "  FALLA referencia inexistente (obtenido $codigo)"; fallos=$((fallos + 1)); fi
rm -rf "$dir"

[ "$fallos" -eq 0 ] && echo "Todas las pruebas pasaron." || { echo "$fallos prueba(s) fallaron."; exit 1; }
