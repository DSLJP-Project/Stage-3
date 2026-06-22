# Stage 3 - Distributed Search Engine

Search engine distributed across five physical laboratory PCs. It provides a replicated crawler datalake, asynchronous indexing, a replicated Hazelcast inverted index and load-balanced search.

## Architecture

`crawler/datalake (R=3) -> ActiveMQ -> two indexers -> Hazelcast (R=3) -> three search services -> Nginx`

- The crawler that owns `bookId % 3` downloads a Project Gutenberg book, persists its primary copy and confirms two HTTP replicas before queueing `document.ingested`.
- An indexer retrieves the book from any URL in the event. If the primary crawler is unavailable, it falls back to a replica. ActiveMQ uses a transacted consumer, so an unavailable document is redelivered rather than silently discarded.
- Each term maps to one of three Hazelcast `MultiMap` shards. Its values form a set, preventing duplicate postings; stale postings are removed before re-indexing a document.
- Nginx distributes `/search` with `least_conn` and retries another backend on connection or 5xx failure.

## Laboratory deployment on Windows Docker Desktop

Use [deploy/windows/README.md](deploy/windows/README.md). It has one Docker Compose file per physical PC and requires no Docker Swarm, administrative privileges or an overlay network. All inter-PC connections use the fixed Ethernet IPs and published ports.

Build the local images on every PC with PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\windows\build-images.ps1
```

## Reproducible measurements

The benchmark service exposes:

- `GET /benchmark/run/baseline`
- `GET /benchmark/run/load?threads=12&seconds=60`
- `GET /benchmark/run/failure?seconds=60`

Run the same load configuration with one, two and three search services using `deploy/scale-search.sh`. Preserve the JSON responses and the files stored in the `stage3_benchmark-results` Docker volume for the written report.

## Demonstration video

YouTube (unlisted): `REPLACE_WITH_VIDEO_URL`

Required title: `[Stage 3] Search Engine Project - <Group Name> (ULPGC)`
