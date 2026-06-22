# Docker Desktop en Windows sin permisos de administrador

Esta carpeta sustituye el despliegue Docker Swarm. Cada PC ejecuta una composición Docker local y los contenedores se comunican con los demás equipos mediante las IPs físicas del laboratorio y puertos publicados.

| PC | Fichero que ejecuta |
| --- | --- |
| PC1 (`10.26.14.244`) | `pc1.compose.yml` |
| PC2 (`10.26.14.243`) | `pc2.compose.yml` |
| PC3 (`10.26.14.242`) | `pc3.compose.yml` |
| PC4 (`10.26.14.245`) | `pc4.compose.yml` |
| PC5 (`10.26.14.246`) | `pc5.compose.yml` |

No uses `docker swarm`, `docker stack` ni la antigua guía de Swarm.

## Arranque resumido

En todos los PCs, desde la raíz del repositorio:

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\windows\build-images.ps1
```

Después inicia, en este orden: PC1, PC2/PC3/PC4 y PC5.

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\windows\start-pc.ps1 pc1
```

Sustituye `pc1` por el PC correspondiente. La guía completa para el vídeo se entrega junto al proyecto como `guia_video_stage3_windows.md`.

## Requisito de red

Docker Desktop publica los puertos del contenedor en el host Windows. Si `Test-NetConnection` contra uno de los puertos de otro PC falla, la política de red o el firewall del laboratorio está bloqueando el tráfico. Sin permisos de administrador no intentéis cambiar el firewall: pedid al responsable del laboratorio que permita los puertos especificados en la guía.
