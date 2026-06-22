# Ejecución de Stage 3 en el laboratorio (5 PCs)

Este runbook convierte el proyecto en un clúster Docker Swarm real. No hay un directorio del host compartido: cada crawler guarda sus documentos en su disco local y los replica por HTTP a los otros dos crawlers antes de publicar el evento de indexación.

## 1. Topología fijada

| Equipo | IP Ethernet | Etiqueta Swarm | Servicios fijos |
| --- | --- | --- | --- |
| PC1 | `10.26.14.244` | `pc1` | manager Swarm, ActiveMQ, Nginx, Control |
| PC2 | `10.26.14.243` | `pc2` | crawler-0/datalake A, Hazelcast-1, search-1 |
| PC3 | `10.26.14.242` | `pc3` | crawler-1/datalake B, Hazelcast-2, search-2 |
| PC4 | `10.26.14.245` | `pc4` | crawler-2/datalake C, Hazelcast-3, indexer-0 |
| PC5 | `10.26.14.246` | `pc5` | MongoDB, indexer-1, search-3, benchmarks |

El datalake tiene `R=3`: una copia primaria y dos réplicas. Hazelcast también tiene una primaria más dos backups síncronos para cada shard. Los indexadores consumen la misma cola de ActiveMQ como consumidores competidores y se reparten los documentos.

## 2. Preparación en los cinco PCs

1. Comprueba que todos pueden alcanzar al resto por Ethernet:

   ```bash
   ping -c 2 10.26.14.244
   ping -c 2 10.26.14.243
   ping -c 2 10.26.14.242
   ping -c 2 10.26.14.245
   ping -c 2 10.26.14.246
   docker version
   ```

2. Copia exactamente este repositorio a la misma ruta en los cinco PCs. No es necesario que sea la misma ruta, pero sí que cada uno tenga los mismos archivos y pueda ejecutar Docker y Maven.

3. Si hay firewall local, permite entre las cinco IPs los puertos de Swarm: TCP `2377`, TCP/UDP `7946` y UDP `4789`. El primer puerto es de control; los dos últimos transportan el descubrimiento y la red overlay. No expongas esos puertos fuera de la red de laboratorio.

4. En cada PC, construye las imágenes locales. Hazlo antes de desplegar el stack; Swarm no copia automáticamente imágenes construidas localmente a los workers.

   ```bash
   chmod +x deploy/build-images.sh deploy/scale-search.sh
   ./deploy/build-images.sh
   ```

   Las imágenes públicas base se descargarán una vez por PC. Si el laboratorio no tiene salida a Internet, precárgalas antes (`mongo:6`, `nginx:1.27-alpine`, `rmohr/activemq:latest` y `hazelcast/hazelcast:5.3`).

## 3. Crear el clúster Swarm

En **PC1**:

```bash
docker swarm init --advertise-addr 10.26.14.244
docker swarm join-token worker
```

El segundo comando muestra una orden `docker swarm join ...`. Ejecuta exactamente esa orden en PC2, PC3, PC4 y PC5. Después, de nuevo en PC1:

```bash
docker node ls
```

Etiqueta los nodos, sustituyendo los identificadores por los que muestre `docker node ls`:

```bash
docker node update --label-add stage3_pc=pc1 <ID_PC1>
docker node update --label-add stage3_pc=pc2 <ID_PC2>
docker node update --label-add stage3_pc=pc3 <ID_PC3>
docker node update --label-add stage3_pc=pc4 <ID_PC4>
docker node update --label-add stage3_pc=pc5 <ID_PC5>
docker node inspect <ID_PC3> --format '{{ .Spec.Labels }}'
```

La última orden debe incluir `stage3_pc:pc3`. Estas etiquetas son importantes: impiden que una réplica del datalake termine en el mismo PC que la primaria.

## 4. Desplegar y comprobar

En **PC1**, desde la raíz del repositorio:

```bash
cd deploy
docker stack config -c stack.yml >/dev/null
docker stack deploy --resolve-image never -c stack.yml stage3
watch -n 2 'docker service ls; docker stack ps stage3 --no-trunc'
```

Espera a que todos los servicios muestren su número de réplicas esperado. En otra terminal de PC1 comprueba el estado lógico:

```bash
curl http://10.26.14.244:7006/control/status
curl http://10.26.14.244:7006/control/ready
curl http://10.26.14.244:8080/
docker service logs -f stage3_hazelcast-1
```

`/control/ready` debe responder `ok` para los tres datalakes, indexación, búsqueda y servicios de benchmark. El primer arranque puede tardar mientras ActiveMQ, MongoDB y Hazelcast forman el clúster; no grabes todavía hasta que todo sea `ok`.

## 5. Prueba funcional antes de grabar

El libro 1342 (`1342 % 3 = 1`) pertenece a `crawler-1`, en PC3. El control module lo enruta automáticamente:

```bash
curl -X POST http://10.26.14.244:7006/control/crawl/1342
curl http://10.26.14.245:7002/index/status/1342
curl 'http://10.26.14.244:8080/search?q=elizabeth'
```

La primera respuesta debe ser `202` e incluir tres `source_urls`. La segunda terminará respondiendo `200` cuando el evento JMS haya sido procesado. La tercera devuelve el resultado a través de Nginx.

Comprueba físicamente las tres copias (una primaria en PC3 y dos réplicas):

```bash
curl http://10.26.14.243:7007/documents/1342 > /dev/null && echo 'Replica PC2 OK'
curl http://10.26.14.242:7008/documents/1342 > /dev/null && echo 'Primaria PC3 OK'
curl http://10.26.14.245:7009/documents/1342 > /dev/null && echo 'Replica PC4 OK'
```

También prueba el reindexado distribuido. El indexer 0 limpia una vez el índice y ambos indexadores esperan una barrera de Hazelcast antes de repoblar sus particiones:

```bash
curl -X POST http://10.26.14.244:7006/control/reindex
docker service logs --tail 80 stage3_indexer-0
docker service logs --tail 80 stage3_indexer-1
```

## 6. Benchmarks que hay que conservar

El servicio de benchmark vive en PC5. Ejecuta cada fase y guarda tanto la salida JSON como los CSV/JSON internos del contenedor:

```bash
curl http://10.26.14.246:7004/benchmark/run/baseline | tee baseline-response.json
curl 'http://10.26.14.246:7004/benchmark/run/load?threads=12&seconds=60' | tee load-response.json
```

Para la escalabilidad, mide el mismo load test con 1, 2 y 3 servicios de búsqueda. En PC1:

```bash
./deploy/scale-search.sh 1
curl 'http://10.26.14.246:7004/benchmark/run/load?threads=12&seconds=60' > scaling-1-search.json

./deploy/scale-search.sh 2
curl 'http://10.26.14.246:7004/benchmark/run/load?threads=12&seconds=60' > scaling-2-search.json

./deploy/scale-search.sh 3
curl 'http://10.26.14.246:7004/benchmark/run/load?threads=12&seconds=60' > scaling-3-search.json
```

El resultado de cada ejecución está además en el volumen de PC5:

```bash
docker run --rm -v stage3_benchmark-results:/results alpine ls -lah /results
```

No presentes esos valores como una mejora garantizada: compara RPS, p95 y errores de las tres repeticiones y explica los límites que observéis.

## 7. Prueba de caída que se debe grabar

1. Antes de iniciar la caída, ingesta y busca el libro 1342 como en el paso 5. Así sus tres copias ya existen.
2. Lanza durante 60 segundos el observador de fallos desde PC5:

   ```bash
   curl 'http://10.26.14.246:7004/benchmark/run/failure?seconds=60' > failure-pc3.json &
   ```

3. A los 10-15 segundos, en **PC3** mata solo el contenedor de búsqueda para una demostración limpia de failover de Nginx:

   ```bash
   docker kill $(docker ps -q --filter label=com.docker.swarm.service.name=stage3_search-2)
   ```

   Swarm crea una tarea nueva y, mientras tanto, Nginx reintenta con `search-1` o `search-3`. Graba la respuesta continua de `/search?q=elizabeth`, los logs de Nginx y `docker service ps stage3_search-2`.

4. Para demostrar tolerancia de datos, haz una segunda toma controlada: apaga PC3 o desconecta su Ethernet **solo después de haber copiado el libro**. Las consultas de 1342 deben seguir funcionando porque Hazelcast conserva dos backups y los documentos continúan en PC2 y PC4. El crawler primario de PC3 no podrá aceptar trabajos nuevos hasta recuperarse; dilo explícitamente: el objetivo de esta toma es recuperación de lecturas y datos, no reasignación automática de propiedad de crawlers.

5. Reconecta/arranca PC3. Comprueba con `docker node ls` que vuelva a `Ready` y con `docker service logs stage3_hazelcast-2` que el miembro se reincorpore.

## 8. Guion de vídeo de 4-7 minutos

1. **0:00-0:35 - Topología.** Enseña esta tabla, los cinco PCs conectados y `docker node ls`. Explica las tres copias del datalake, los tres Hazelcast y los tres search services.
2. **0:35-1:20 - Despliegue.** Enseña `docker stack deploy`, `docker service ls` y `/control/ready` en verde/`ok`.
3. **1:20-2:20 - Pipeline.** Lanza `/control/crawl/1342`; muestra la respuesta `replicated_and_queued`, los logs del crawler e indexer y una búsqueda por Nginx.
4. **2:20-2:50 - Réplicas.** Ejecuta los tres `curl /documents/1342` de PC2, PC3 y PC4. Es la evidencia visual de que no hay volumen local compartido disfrazado de datalake distribuido.
5. **2:50-3:45 - Escalabilidad y carga.** Muestra las tres configuraciones de búsqueda y una tabla/gráfica de RPS, p95 y errores. Menciona el número de threads y duración.
6. **3:45-5:10 - Fallo y recuperación.** Deja las consultas o el benchmark de fallos corriendo, mata `search-2`, enseña que Nginx sigue devolviendo respuestas y que Swarm recrea la tarea. Si hay tiempo, enseña la caída de PC3 con el documento ya replicado.
7. **5:10-5:45 - Decisiones y límites.** Justifica hash modular para ownership de crawlers, R=3, dos indexadores como consumidores competidores, MultiMap SET y Nginx `least_conn`.

## 9. Límites que debéis declarar honestamente

El requisito de tolerancia de fallos queda demostrado para la caída de PC3: datalake, Hazelcast y rutas de búsqueda mantienen las consultas de datos ya replicados. En esta versión ActiveMQ y MongoDB permanecen servicios únicos ubicados en PC1 y PC5. El enunciado permite un broker ActiveMQ singular, pero si se exige explícitamente que también sobrevivan sus PCs, el siguiente incremento debe ser ActiveMQ HA y MongoDB replica set; no afirméis que esos dos componentes son HA si no se han desplegado así.
