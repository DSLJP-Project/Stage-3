#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
mvn -DskipTests package

docker build -t stage3/crawler:lab crawler-service
docker build -t stage3/indexer:lab indexing-service
docker build -t stage3/search:lab search-service
docker build -t stage3/control:lab control-module
docker build -t stage3/benchmarks:lab benchmarks
docker build -t stage3/hazelcast:lab hazelcast-node

echo "All Stage 3 images are ready on $(hostname)."
