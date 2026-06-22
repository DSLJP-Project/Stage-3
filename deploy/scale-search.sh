#!/usr/bin/env bash
set -euo pipefail

count="${1:?Uso: ./deploy/scale-search.sh <1|2|3>}"
case "$count" in 1|2|3) ;; *) echo "El número de search services debe ser 1, 2 o 3" >&2; exit 2;; esac

docker service scale \
  stage3_search-1=1 \
  stage3_search-2=$([ "$count" -ge 2 ] && echo 1 || echo 0) \
  stage3_search-3=$([ "$count" -ge 3 ] && echo 1 || echo 0)

echo "Esperando a que Nginx detecte $count backend(s)..."
sleep 8
docker service ls --filter name=stage3_search
