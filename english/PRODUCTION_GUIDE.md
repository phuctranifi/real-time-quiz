# Production Deployment Guide

## Overview

This document provides comprehensive guidance for deploying and operating the quiz platform in production environments.

---

## Table of Contents

1. [Horizontal Scaling Approach](#horizontal-scaling-approach)
2. [Performance Considerations](#performance-considerations)
3. [Failure Modes and Recovery](#failure-modes-and-recovery)
4. [Monitoring and Metrics](#monitoring-and-metrics)
5. [Configuration](#configuration)
6. [Deployment Architecture](#deployment-architecture)
7. [Operational Procedures](#operational-procedures)

---

## Horizontal Scaling Approach

### Architecture Overview

```
                    ┌─────────────────┐
                    │  Load Balancer  │
                    │   (Layer 4/7)   │
                    └────────┬────────┘
                             │
         ┌───────────────────┼───────────────────┐
         │                   │                   │
    ┌────▼────┐         ┌────▼────┐         ┌────▼────┐
    │Instance │         │Instance │         │Instance │
    │    A    │         │    B    │         │    C    │
    │         │         │         │         │         │
    │ WS: 100 │         │ WS: 150 │         │ WS: 120 │
    │ users   │         │ users   │         │ users   │
    └────┬────┘         └────┬────┘         └────┬────┘
         │                   │                   │
         └───────────────────┼───────────────────┘
                             │
                    ┌────────▼────────┐
                    │      Redis      │
                    │  Pub/Sub + Data │
                    └─────────────────┘
```

### Scaling Principles

#### 1. **Stateless Instances**
- Each instance is completely independent
- No shared in-memory state (except Redis fallback)
- Can add/remove instances without coordination
- No session affinity required (but recommended for WebSocket)

#### 2. **Redis as Coordination Layer**
- **Pub/Sub**: Cross-instance event broadcasting
- **Sorted Sets**: Shared leaderboard data
- **Single Source of Truth**: All instances read from same Redis

#### 3. **Instance-Local Components**
- WebSocket connections (each instance manages its own)
- Session registry (instance-local)
- Room memberships (instance-local)
- Heartbeat monitoring (instance-local)
- Rate limiting (instance-local)
- Circuit breaker (instance-local)

#### 4. **Load Balancer Configuration**

**Recommended: Sticky Sessions (Session Affinity)**
```nginx
upstream quiz_backend {
    ip_hash;  # or sticky cookie
    server instance-a:8080;
    server instance-b:8080;
    server instance-c:8080;
}
```

**Benefits:**
- Reduces WebSocket reconnections
- Better user experience
- Simpler client logic

**Alternative: No Sticky Sessions**
- Clients must handle reconnection
- More resilient to instance failures
- Better load distribution

### Scaling Limits

| Component | Limit per Instance | Bottleneck |
|-----------|-------------------|------------|
| WebSocket Connections | ~10,000 | Memory, File Descriptors |
| Redis Operations | ~50,000 ops/sec | Network, Redis CPU |
| Pub/Sub Messages | ~100,000 msg/sec | Redis Network |
| Leaderboard Size | ~1,000,000 users | Redis Memory |

**Scaling Strategy:**
1. **1-10 instances**: Single Redis instance
2. **10-50 instances**: Redis Cluster or Sentinel
3. **50+ instances**: Redis Cluster + Read Replicas

---

## Performance Considerations

### Latency Breakdown

**End-to-End Latency (SUBMIT_ANSWER → Leaderboard Broadcast)**

```
Client → Server:           ~10-50ms   (network)
Rate Limit Check:          ~0.01ms    (in-memory)
Input Validation:          ~0.01ms    (in-memory)
Redis ZINCRBY:             ~1-2ms     (Redis operation)
Redis PUBLISH:             ~1-2ms     (Redis operation)
Pub/Sub Propagation:       ~5-10ms    (Redis → all instances)
Redis ZREVRANGE:           ~1-2ms     (Redis operation)
WebSocket Broadcast:       ~1-5ms     (per instance)
Server → Client:           ~10-50ms   (network)
─────────────────────────────────────────────────
Total:                     ~30-120ms  (typical)
```

### Optimization Strategies

#### 1. **Redis Connection Pooling**
```properties
spring.data.redis.lettuce.pool.max-active=8
spring.data.redis.lettuce.pool.max-idle=8
```

**Tuning:**
- Increase `max-active` for high-throughput instances
- Monitor connection pool metrics
- Typical: 8-16 connections per instance

#### 2. **Leaderboard Top-N Optimization**
```properties
quiz.leaderboard.top-n=10
```

**Impact:**
- Top 10: ~1-2ms Redis query
- Top 100: ~2-5ms Redis query
- Top 1000: ~10-20ms Redis query

**Recommendation:** Keep ≤ 50 for real-time updates

#### 3. **Rate Limiting Configuration**
```properties
quiz.rate-limit.capacity=10
quiz.rate-limit.refill-tokens=5
quiz.rate-limit.refill-period-seconds=1
```

**Tuning:**
- Increase capacity for legitimate bursts
- Decrease refill rate to prevent abuse
- Monitor `quiz.rate_limit.rejected` metric

#### 4. **Heartbeat Interval**
```properties
quiz.websocket.heartbeat.interval-seconds=30
```

**Trade-offs:**
- Lower interval: Faster stale detection, more network traffic
- Higher interval: Less traffic, slower detection
- Recommended: 30-60 seconds

### Memory Usage

**Per Instance:**
```
Base Application:          ~200 MB
Per WebSocket Connection:  ~10 KB
Per Rate Limit Bucket:     ~100 bytes
Per Heartbeat Entry:       ~50 bytes
In-Memory Fallback:        ~1 KB per user (when Redis down)
```

**Example (1000 concurrent users):**
```
Base:                      200 MB
WebSocket Sessions:        10 MB
Rate Limiters:             0.1 MB
Heartbeat Monitor:         0.05 MB
─────────────────────────────────
Total:                     ~210 MB
```

**Recommendation:** Allocate 512 MB - 1 GB per instance

### CPU Usage

**Typical Load:**
- Idle: ~5% CPU
- 100 users, 10 msg/sec: ~20% CPU
- 1000 users, 100 msg/sec: ~50% CPU
- 10000 users, 1000 msg/sec: ~80% CPU

**Bottlenecks:**
1. JSON serialization/deserialization
2. WebSocket frame processing
3. Metrics collection
4. Logging (if DEBUG level)

**Optimization:**
- Use INFO logging in production
- Enable JVM JIT optimizations
- Use G1GC for low-latency

---

## Failure Modes and Recovery

### 1. Redis Downtime

#### Failure Mode
- Redis becomes unavailable (network partition, crash, maintenance)
- Circuit breaker opens after 5 failures
- All instances switch to in-memory fallback

#### Impact
```
✅ WebSocket connections: Unaffected
✅ Message handling: Continues normally
✅ Rate limiting: Continues (instance-local)
✅ Heartbeat: Continues (instance-local)
⚠️  Leaderboard: In-memory fallback (per-instance)
⚠️  Cross-instance sync: Lost (Pub/Sub unavailable)
❌ Leaderboard consistency: Diverges across instances
```

#### Recovery
1. **Automatic**: Circuit breaker tests recovery every 30 seconds
2. **When Redis returns**: Circuit closes, instances sync from Redis
3. **Data**: Redis is source of truth, in-memory data discarded
4. **Timeline**: ~30-60 seconds to full recovery

#### Mitigation
```bash
# Deploy Redis with high availability
# Option 1: Redis Sentinel (automatic failover)
redis-sentinel sentinel.conf

# Option 2: Redis Cluster (sharding + replication)
redis-cli --cluster create ...

# Option 3: Managed Redis (AWS ElastiCache, Azure Cache, etc.)
```

### 2. Instance Failure

#### Failure Mode
- Instance crashes, OOM, network partition
- Load balancer detects failure (health check)
- Traffic redirected to healthy instances

#### Impact
```
❌ WebSocket connections on failed instance: Dropped
✅ Users on other instances: Unaffected
✅ Leaderboard data: Safe in Redis
✅ Cross-instance sync: Continues
```

#### Recovery
1. **Client-side**: Reconnect to healthy instance (via load balancer)
2. **Server-side**: No action needed (stateless)
3. **Timeline**: ~5-10 seconds (health check + reconnect)

#### Mitigation
```yaml
# Kubernetes deployment with auto-restart
apiVersion: apps/v1
kind: Deployment
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
  template:
    spec:
      containers:
      - name: quiz-app
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
```

### 3. Network Partition

#### Failure Mode
- Instance can't reach Redis but is otherwise healthy
- Circuit breaker opens
- Instance continues with in-memory fallback

#### Impact
```
✅ Instance serves WebSocket connections
⚠️  Leaderboard diverges from other instances
❌ No cross-instance sync
```

#### Recovery
- Same as Redis downtime recovery
- Partition heals → circuit closes → sync from Redis

### 4. Rate Limit Abuse

#### Failure Mode
- Malicious client sends excessive messages
- Rate limiter rejects requests
- Legitimate users unaffected

#### Impact
```
✅ System protected from DoS
✅ Other users unaffected
⚠️  Abusive user gets error messages
```

#### Monitoring
```promql
# Alert on high rate limit rejections
rate(quiz_rate_limit_rejected_total[5m]) > 100
```

### 5. Stale WebSocket Connections

#### Failure Mode
- Client loses network but doesn't close connection
- Server doesn't detect disconnect immediately
- Connection appears active but is dead

#### Recovery
1. **Heartbeat Monitor**: Detects no heartbeat for 60 seconds
2. **Cleanup**: Automatically removes stale session
3. **Timeline**: ~60-120 seconds

#### Mitigation
```properties
# Tune heartbeat settings
quiz.websocket.heartbeat.interval-seconds=30
quiz.websocket.heartbeat.timeout-multiplier=2
```

---

## Monitoring and Metrics

### Key Metrics

#### WebSocket Metrics
```promql
# Active connections per instance
quiz_websocket_connections_total - quiz_websocket_disconnections_total

# Connection rate
rate(quiz_websocket_connections_total[5m])

# Abnormal disconnects
rate(quiz_websocket_disconnections_abnormal_total[5m])

# Heartbeat health
rate(quiz_websocket_heartbeat_received_total[5m])

# Stale sessions cleaned
rate(quiz_websocket_stale_sessions_cleaned_total[5m])
```

#### Rate Limiting Metrics
```promql
# Rate limit rejection rate
rate(quiz_rate_limit_rejected_total[5m])

# Allowed vs rejected ratio
rate(quiz_rate_limit_allowed_total[5m]) / rate(quiz_rate_limit_rejected_total[5m])
```

#### Redis Metrics
```promql
# Redis health
quiz_redis_healthy

# Circuit breaker state (0=closed, 0.5=half-open, 1=open)
quiz_redis_circuit_breaker_state

# Redis operation latency
histogram_quantile(0.95, rate(quiz_redis_operation_duration_bucket[5m]))

# Redis errors
rate(quiz_redis_operation_errors_total[5m])

# Fallback usage
rate(quiz_redis_fallback_used_total[5m])
```

#### Business Metrics
```promql
# Message throughput
rate(quiz_websocket_messages_join_total[5m])
rate(quiz_websocket_messages_submit_total[5m])

# Error rate
rate(quiz_websocket_messages_errors_total[5m])
```

### Alerting Rules

```yaml
groups:
  - name: quiz_platform
    rules:
      # Redis is down
      - alert: RedisDown
        expr: quiz_redis_healthy == 0
        for: 1m
        annotations:
          summary: "Redis is unhealthy"
          
      # Circuit breaker open
      - alert: RedisCircuitOpen
        expr: quiz_redis_circuit_breaker_state == 1
        for: 2m
        annotations:
          summary: "Redis circuit breaker is open"
          
      # High rate limit rejections
      - alert: HighRateLimitRejections
        expr: rate(quiz_rate_limit_rejected_total[5m]) > 100
        for: 5m
        annotations:
          summary: "High rate of rate limit rejections"
          
      # High abnormal disconnects
      - alert: HighAbnormalDisconnects
        expr: rate(quiz_websocket_disconnections_abnormal_total[5m]) > 10
        for: 5m
        annotations:
          summary: "High rate of abnormal WebSocket disconnects"
          
      # High error rate
      - alert: HighErrorRate
        expr: rate(quiz_websocket_messages_errors_total[5m]) > 10
        for: 5m
        annotations:
          summary: "High rate of error messages"
```

### Dashboards

**Grafana Dashboard Panels:**

1. **Overview**
   - Total active connections
   - Messages per second
   - Error rate
   - Redis health

2. **Performance**
   - P50, P95, P99 latency
   - Redis operation duration
   - Message processing time

3. **Reliability**
   - Circuit breaker state
   - Fallback usage
   - Stale session cleanup rate

4. **Capacity**
   - Connections per instance
   - Memory usage
   - CPU usage
   - Rate limit utilization

---

## Configuration

### Environment-Specific Settings

#### Development
```properties
logging.level.com.quiz.english=DEBUG
quiz.websocket.heartbeat.interval-seconds=10
quiz.rate-limit.capacity=100
```

#### Staging
```properties
logging.level.com.quiz.english=INFO
quiz.websocket.heartbeat.interval-seconds=30
quiz.rate-limit.capacity=20
```

#### Production
```properties
logging.level.com.quiz.english=INFO
logging.level.root=WARN
quiz.websocket.heartbeat.interval-seconds=30
quiz.rate-limit.capacity=10
management.endpoints.web.exposure.include=health,metrics,prometheus
```

### Tuning Guide

| Scenario | Configuration | Rationale |
|----------|--------------|-----------|
| High traffic | `quiz.rate-limit.capacity=20` | Allow more bursts |
| Low latency | `quiz.leaderboard.top-n=10` | Faster Redis queries |
| Memory constrained | `quiz.websocket.heartbeat.interval-seconds=60` | Less memory overhead |
| Strict rate limiting | `quiz.rate-limit.refill-tokens=2` | Slower refill |

---

## Deployment Architecture

### Recommended Setup

```yaml
# Kubernetes deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: quiz-platform
spec:
  replicas: 3
  selector:
    matchLabels:
      app: quiz-platform
  template:
    metadata:
      labels:
        app: quiz-platform
    spec:
      containers:
      - name: quiz-app
        image: quiz-platform:latest
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_APPLICATION_INSTANCE_ID
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: SPRING_DATA_REDIS_HOST
          value: redis-service
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: quiz-service
spec:
  type: LoadBalancer
  sessionAffinity: ClientIP  # Sticky sessions
  ports:
  - port: 80
    targetPort: 8080
  selector:
    app: quiz-platform
```

---

## Operational Procedures

### Deployment

```bash
# Rolling update (zero downtime)
kubectl set image deployment/quiz-platform quiz-app=quiz-platform:v2
kubectl rollout status deployment/quiz-platform

# Rollback if needed
kubectl rollout undo deployment/quiz-platform
```

### Scaling

```bash
# Scale up
kubectl scale deployment/quiz-platform --replicas=5

# Auto-scaling
kubectl autoscale deployment/quiz-platform --min=3 --max=10 --cpu-percent=70
```

### Health Checks

```bash
# Check instance health
curl http://instance-a:8080/actuator/health

# Check metrics
curl http://instance-a:8080/actuator/metrics/quiz.websocket.connections

# Check Prometheus endpoint
curl http://instance-a:8080/actuator/prometheus
```

### Troubleshooting

```bash
# Check logs
kubectl logs -f deployment/quiz-platform

# Check Redis connectivity
redis-cli -h redis-service ping

# Check circuit breaker state
curl http://instance-a:8080/actuator/metrics/quiz.redis.circuit_breaker.state

# Check active connections
curl http://instance-a:8080/actuator/metrics/quiz.websocket.connections
```

---

## Summary

### Production Readiness Checklist

- ✅ Horizontal scaling support (stateless instances)
- ✅ Redis circuit breaker and fallback
- ✅ WebSocket heartbeat monitoring
- ✅ Rate limiting (per-session)
- ✅ Graceful disconnect handling
- ✅ Structured logging
- ✅ Comprehensive metrics (Micrometer + Prometheus)
- ✅ Health checks (Spring Actuator)
- ✅ Failure mode documentation
- ✅ Performance tuning guide
- ✅ Monitoring and alerting setup

### Next Steps

1. **Load Testing**: Test with realistic traffic patterns
2. **Chaos Engineering**: Test failure scenarios (kill Redis, kill instances)
3. **Security**: Add authentication, authorization, rate limiting by user
4. **Observability**: Set up distributed tracing (Zipkin, Jaeger)
5. **Backup**: Configure Redis persistence and backups

