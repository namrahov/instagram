# Kubernetes: video workers autoscaled by KEDA (section 5)

Two Deployments from one image:

| Deployment | `VIDEO_WORKER_ENABLED` | Scaled by |
|---|---|---|
| `instagram-api` | `false` — serves HTTP, publishes outbox events | fixed `replicas` (or a CPU HPA) |
| `video-worker` | `true` — one Kafka consumer per pod, runs ffmpeg | KEDA, on the lag of group `video-processors` |

KEDA reads the consumer lag of `video-processing` every 15 s and sets the worker count to
`lag / lagThreshold`, between 10 and 100 (= partitions). See the comments in `video-worker-scaledobject.yaml`.

## Run

1. Install KEDA (once per cluster):
   ```
   helm repo add kedacore https://kedacore.github.io/charts
   helm install keda kedacore/keda --namespace keda --create-namespace
   ```
2. Build the image (the cluster must be able to pull it):
   ```
   ./gradlew bootJar
   docker build -t instagram:0.0.1-SNAPSHOT .
   ```
3. Secrets: copy `k8s/.env.example` to `k8s/.env` and fill it in.
4. Point the addresses in `k8s/config.yaml` at your PostgreSQL, Kafka and MinIO.
5. Apply: `kubectl apply -k k8s`

## Watch it scale

```
kubectl -n instagram get scaledobject video-worker
kubectl -n instagram get hpa -w
kubectl -n instagram get pods -l component=video-worker -w
```
