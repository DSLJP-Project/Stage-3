# Stage 3 — Distributed Search Engine

**Big Data · Grado en Ciencia e Ingeniería de Datos · ULPGC**

**Demo:** [pendiente — YouTube link aquí]

---

## Description

Distributed search engine built with Docker Compose. Books are downloaded from Project Gutenberg by a set of crawler nodes, stored in a replicated datalake, and indexed into a Hazelcast in-memory inverted index. Search queries are served through three load-balanced instances behind Nginx.

## Tech stack

- **Java 21** + Javalin (REST services)
- **Apache ActiveMQ** — async messaging between crawlers and indexers
- **Hazelcast 5** — distributed in-memory inverted index (3 nodes, MultiMap, backup-count=2)
- **MongoDB 6** — book metadata store
- **Nginx** — load balancer over 3 search instances (least_conn)
- **Docker Compose** — full cluster orchestration

## Requirements

- Docker 24+
- Docker Compose v2
- Java 21
- Maven 3.9+

---

## Build and run

From the project root:

```bash
mvn clean package
docker compose up --build -d
```

After ~30 seconds all services should be up. Verify with:

```bash
# Nginx responds
curl http://localhost:8080/

# Individual service health
curl http://localhost:7001/status   # ingestion
curl http://localhost:7002/status   # indexing
curl http://localhost:8080/status   # search (via nginx)
curl http://localhost:8080/ready    # {"hazelcast":true,"db":true} — if hazelcast:false, wait 10s and retry
```

You can also check the ActiveMQ web console at **http://localhost:8161** (admin / admin).

---

## Crawling books

Books are partitioned across the three crawlers using `book_id % 3`.

Before sending a crawl request, compute `book_id % 3` and use the corresponding crawler port.

| `book_id % 3` | Crawler   | Port |
| ------------- | --------- | ---- |
| 0             | crawler-0 | 7007 |
| 1             | crawler-1 | 7008 |
| 2             | crawler-2 | 7009 |

Examples:

* `1661 % 3 = 2` → crawler-2 → port 7009
* `2600 % 3 = 2` → crawler-2 → port 7009
* `5200 % 3 = 1` → crawler-1 → port 7008
* `84 % 3 = 0` → crawler-0 → port 7007

```bash
curl -X POST http://localhost:7009/crawl/1661   # Sherlock Holmes
curl -X POST http://localhost:7009/crawl/2600   # War and Peace
curl -X POST http://localhost:7008/crawl/5200
curl -X POST http://localhost:7007/crawl/84
```

> **Note:** On some Windows/PowerShell environments `curl -X POST` may not work because `curl` is aliased. In that case, use one of these alternatives:

PowerShell:

```powershell
Invoke-WebRequest -Method POST -Uri http://localhost:7009/crawl/1661 -UseBasicParsing
```


After each request, the crawler downloads the book, saves it to the datalake, and publishes a message to the `document.ingested` queue. The indexing service picks it up and updates the Hazelcast index. Allow ~30 seconds per book before searching.

You can check that the message reached the broker in the ActiveMQ console under **Queues → document.ingested**.

---

## Searching

All search queries go through Nginx on port 8080, which distributes them across the three search instances.

```bash
# Basic keyword search
curl "http://localhost:8080/search?q=sherlock"

# Filter by author
curl "http://localhost:8080/search?q=love&author=Jane+Austen"

# Exact phrase
curl "http://localhost:8080/search/phrase?phrase=great+adventure"

# Boolean AND
curl "http://localhost:8080/search/advanced?q=war+AND+peace"
```

---

## Benchmarks

**Baseline** — measures ingestion, indexing and search latency with the 8 pre-loaded books (~30 s):
```bash
curl http://localhost:7004/benchmark/run/baseline
```

**Scaling** — runs the same workload at 1, 3, 6 and 9 concurrent threads:
```bash
curl http://localhost:7004/benchmark/run/scaling
```

**Load test** — sustained load for a given duration:
```bash
curl "http://localhost:7004/benchmark/run/load?threads=6&seconds=30"
```

**Failure test** — open two terminals. In the first, start the benchmark. In the second, kill a node mid-run:
```bash
# Terminal 1
curl "http://localhost:7004/benchmark/run/failure?seconds=30"

# Terminal 2 (after ~15 s)
docker stop hazelcast-3
Start-Sleep -Seconds 5   # or: sleep 5
docker start hazelcast-3
```
The result shows requests that failed during the outage and the measured recovery time.

**Microbenchmarks** — JMH-level timings for individual operations (buildInvertedIndex, extractTitle, etc.):
```bash
curl http://localhost:7005/microbenchmark/run
```

---

## Fault tolerance

**Kill a Hazelcast node** — the remaining two nodes hold all data (backup-count=2):
```bash
docker stop hazelcast-2
curl "http://localhost:8080/search?q=adventure"   # still works
docker start hazelcast-2                          # rejoins and rebalances automatically
```

**Kill a search instance** — Nginx detects the failure and reroutes traffic:
```bash
docker stop search-2
curl "http://localhost:8080/search?q=adventure"   # routed to search-1 or search-3
docker start search-2
```

---

## Ports

| Service | Port |
|---|---|
| Nginx (search entry point) | 8080 |
| Crawler 0 / 1 / 2 | 7007 / 7008 / 7009 |
| Ingestion 0 | 7001 |
| Indexing service | 7002 |
| Benchmarks | 7004 |
| Microbenchmarks | 7005 |
| ActiveMQ web console | 8161 (admin / admin) |
| MongoDB | 27017 |

---

## Stop

```bash
docker compose down
```