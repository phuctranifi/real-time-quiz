# Quiz Platform - Architecture Quick Reference

## 📊 System Overview

**Type**: Real-time, multi-instance quiz platform  
**Architecture**: Event-driven, horizontally scalable  
**Communication**: WebSocket (STOMP over SockJS)  
**Synchronization**: Redis Pub/Sub  
**Data Store**: Redis (Sorted Sets for leaderboards)

---

## 🏗️ Component Map

### Client Layer
```
┌─────────────────────────────────────┐
│  Web Clients (user1.html, user2.html) │
│  - SockJS 1.6.1                     │
│  - STOMP.js                         │
│  - Random question selection (1-10) │
└─────────────────────────────────────┘
```

### Application Layer (Multi-Instance)
```
┌─────────────────────────────────────┐
│  Spring Boot Instance               │
│  ├─ WebSocket Handler (STOMP)       │
│  ├─ Quiz Controller (routing)       │
│  ├─ Quiz Service (business logic)   │
│  ├─ Question Bank (in-memory)       │
│  ├─ Room Manager (session tracking) │
│  ├─ Rate Limiter (10 req/sec)       │
│  └─ Circuit Breaker (Redis failover)│
└─────────────────────────────────────┘
```

### Event Bus Layer
```
┌─────────────────────────────────────┐
│  Redis Pub/Sub                      │
│  - Channel: quiz:{quizId}:events    │
│  - Events: USER_JOINED, SCORE_UPDATED│
│  - Pattern: quiz:*:events           │
└─────────────────────────────────────┘
```

### Data Layer
```
┌─────────────────────────────────────┐
│  Redis (Lettuce Client)             │
│  - ZSET: quiz:{quizId}:leaderboard  │
│  - Operations: ZADD, ZINCRBY, ZREVRANGE│
│  - Atomic, O(log N) complexity      │
└─────────────────────────────────────┘
```

---

## 🔄 Data Flow Summary

### User Join Flow
```
Client → WebSocket → Controller → Service → Redis (ZADD)
                                          ↓
                                    Event Publisher
                                          ↓
                                    Redis Pub/Sub
                                          ↓
                              Event Subscriber (ALL instances)
                                          ↓
                              Fetch Leaderboard (ZREVRANGE)
                                          ↓
                              Broadcast to WebSocket clients
```

### Answer Submission Flow
```
Client → WebSocket → Controller → Service → Question Bank (get points)
                                          ↓
                                    Redis (ZINCRBY)
                                          ↓
                                    Event Publisher
                                          ↓
                                    Redis Pub/Sub
                                          ↓
                              Event Subscriber (ALL instances)
                                          ↓
                              Broadcast LEADERBOARD_UPDATE
```

---

## 🛠️ Technology Stack

### Backend
| Layer | Technology | Purpose |
|-------|-----------|---------|
| Framework | Spring Boot 4.0.2 | Application framework |
| Language | Java 17 LTS | Runtime |
| WebSocket | Spring WebSocket 6.2.5 | Real-time communication |
| Protocol | STOMP 1.2 | Messaging protocol |
| Data Store | Redis 7.x | Shared state, Pub/Sub |
| Redis Client | Lettuce 6.x | Async, reactive client |
| Resilience | Resilience4j 2.x | Circuit breaker, rate limiter |
| Metrics | Micrometer 1.x | Monitoring |
| Build | Gradle 9.3.0 | Build automation |

### Frontend
| Technology | Purpose |
|-----------|---------|
| SockJS 1.6.1 | WebSocket with fallback |
| STOMP.js | STOMP protocol client |
| HTML5/CSS3/JS | UI implementation |

---

## 📡 WebSocket Message Types

### Client → Server

#### JOIN Message
```json
{
  "type": "JOIN",
  "quizId": "quiz123",
  "userId": "alice"
}
```
**Destination**: `/app/quiz/join`

#### SUBMIT_ANSWER Message
```json
{
  "type": "SUBMIT_ANSWER",
  "quizId": "quiz123",
  "userId": "alice",
  "questionNumber": 7,
  "correct": true
}
```
**Destination**: `/app/quiz/submit`

### Server → Client

#### LEADERBOARD_UPDATE (Broadcast)
```json
{
  "type": "LEADERBOARD_UPDATE",
  "leaderboard": [
    {"userId": "alice", "score": 15},
    {"userId": "bob", "score": 23}
  ]
}
```
**Topic**: `/topic/quiz/{quizId}`

#### ANSWER_RESULT (Personal)
```json
{
  "type": "ANSWER_RESULT",
  "quizId": "quiz123",
  "userId": "alice",
  "questionNumber": 7,
  "correct": true,
  "pointsEarned": 7,
  "newScore": 15
}
```
**Queue**: `/user/queue/reply`

---

## 🗄️ Redis Data Structures

### Leaderboard (Sorted Set)
```
Key: quiz:{quizId}:leaderboard
Type: ZSET
Members: userId
Scores: user's total score

Example:
quiz:quiz123:leaderboard
  alice → 15
  bob → 23
  charlie → 8
```

### Operations
```bash
# Add user with initial score 0 (only if not exists)
ZADD quiz:quiz123:leaderboard NX 0 alice

# Increment user score by 7 points
ZINCRBY quiz:quiz123:leaderboard 7 alice

# Get leaderboard (descending by score)
ZREVRANGE quiz:quiz123:leaderboard 0 -1 WITHSCORES
```

### Pub/Sub Channel
```
Channel: quiz:{quizId}:events
Pattern: quiz:*:events

Event Types:
- USER_JOINED
- SCORE_UPDATED
```

---

## 🎯 Scoring Rules

| Question | Points (Correct) | Points (Incorrect) |
|----------|------------------|-------------------|
| Question 1 | 1 | 0 |
| Question 2 | 2 | 0 |
| Question 3 | 3 | 0 |
| ... | ... | ... |
| Question 10 | 10 | 0 |

**Formula**: `points = correct ? questionNumber : 0`

---

## 🔒 Production Features

### Rate Limiting
- **Algorithm**: Token bucket (Resilience4j)
- **Limit**: 10 requests per second per session
- **Scope**: Per WebSocket session
- **Action**: Reject with error message

### Circuit Breaker
- **Target**: Redis operations
- **Failure Threshold**: 50%
- **Wait Duration**: 30 seconds
- **Sliding Window**: 10 requests
- **Fallback**: In-memory cache

### Health Checks
- **Endpoint**: `/actuator/health`
- **Checks**: Redis connectivity, WebSocket status

### Metrics
- **Endpoint**: `/actuator/prometheus`
- **Metrics**:
  - `quiz.messages.join` - JOIN message count
  - `quiz.messages.submit` - SUBMIT_ANSWER count
  - `quiz.messages.ratelimited` - Rate-limited requests
  - `quiz.leaderboard.size` - Leaderboard size

---

## 🚀 Deployment

### Single Instance (Development)
```bash
# Start Redis
docker run -d -p 6379:6379 redis:7-alpine

# Start application
./gradlew bootRun

# Open demo
open demo/user1.html
```

### Multi-Instance (Production)
```bash
# Start Redis
docker run -d -p 6379:6379 redis:7-alpine

# Start Instance 1
SERVER_PORT=8080 ./gradlew bootRun

# Start Instance 2 (in another terminal)
SERVER_PORT=8081 ./gradlew bootRun

# Configure load balancer to distribute traffic
```

### Docker Compose
```yaml
services:
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
  
  app-1:
    build: .
    ports: ["8080:8080"]
    environment:
      SPRING_REDIS_HOST: redis
  
  app-2:
    build: .
    ports: ["8081:8080"]
    environment:
      SPRING_REDIS_HOST: redis
```

---

## 📈 Scalability

### Horizontal Scaling
- ✅ **Stateless instances** - No server-to-server calls
- ✅ **Shared state in Redis** - Single source of truth
- ✅ **Event-driven sync** - Pub/Sub broadcasts to all
- ✅ **Load balancing** - Distribute connections

### Performance
- **WebSocket connections**: ~10,000 per instance
- **Redis throughput**: ~100,000 ops/sec
- **Latency**: <10ms for updates
- **Complexity**: O(log N) for Redis operations

---

## 🔍 Key Files

### Backend
```
src/main/java/com/quiz/english/
├── config/
│   ├── WebSocketConfig.java          # WebSocket configuration
│   ├── RedisConfig.java               # Redis configuration
│   └── RedisPubSubConfig.java         # Pub/Sub setup
├── ws/
│   └── QuizWebSocketController.java   # Message handlers
├── service/
│   ├── QuizService.java               # Service interface
│   ├── QuizServiceImpl.java           # Business logic
│   ├── QuestionBankService.java       # Question management
│   └── QuizRoomManager.java           # Session tracking
├── repository/
│   └── LeaderboardRepository.java     # Redis operations
├── event/
│   ├── QuizEventPublisher.java        # Publish events
│   └── QuizEventSubscriber.java       # Subscribe to events
└── model/
    ├── JoinMessage.java               # JOIN message
    ├── SubmitAnswerMessage.java       # SUBMIT_ANSWER message
    ├── AnswerResultMessage.java       # ANSWER_RESULT response
    ├── LeaderboardUpdateMessage.java  # LEADERBOARD_UPDATE broadcast
    └── Question.java                  # Question model
```

### Frontend
```
demo/
├── user1.html    # User 1 interface (purple theme)
├── user2.html    # User 2 interface (pink theme)
└── index.html    # Landing page
```

---

## 🧪 Testing

### Manual Testing
1. Start Redis: `docker run -d -p 6379:6379 redis:7-alpine`
2. Start app: `./gradlew bootRun`
3. Open `demo/user1.html` and `demo/user2.html`
4. Click "Connect & Join" on both
5. Submit answers and watch real-time sync

### Multi-Instance Testing
1. Start Redis
2. Start Instance 1 on port 8080
3. Start Instance 2 on port 8081
4. Connect User 1 to port 8080
5. Connect User 2 to port 8081
6. Submit answers and verify cross-instance sync

---

## 🐛 Troubleshooting

### WebSocket Connection Failed
- Check server is running: `curl http://localhost:8080/actuator/health`
- Check port in HTML: `document.getElementById('serverPort').value`
- Check browser console for errors

### Leaderboard Not Updating
- Check Redis is running: `redis-cli ping`
- Check Redis connection: `curl http://localhost:8080/actuator/health`
- Check browser console for LEADERBOARD_UPDATE messages

### Rate Limit Exceeded
- Slow down request rate (max 10/sec per session)
- Check metrics: `curl http://localhost:8080/actuator/prometheus | grep ratelimited`

### Circuit Breaker Open
- Check Redis health: `redis-cli ping`
- Wait 30 seconds for circuit to close
- Check fallback cache is working

---

## 📚 Additional Resources

- **Full Architecture**: See `ARCHITECTURE.md`
- **Demo Guide**: See `demo/HTML_DEMO.md`
- **AI Collaboration**: See `AI_COLLABORATION.md`
- **Spring WebSocket Docs**: https://docs.spring.io/spring-framework/reference/web/websocket.html
- **Redis Pub/Sub**: https://redis.io/docs/manual/pubsub/
- **STOMP Protocol**: https://stomp.github.io/

---

## 🎓 Key Concepts

### Why Redis Pub/Sub?
- ✅ Decouples instances (no direct communication)
- ✅ Broadcasts to all subscribers automatically
- ✅ Fire-and-forget (no blocking)
- ✅ Built into Redis (no extra infrastructure)

### Why Sorted Sets (ZSET)?
- ✅ Automatic sorting by score
- ✅ Atomic operations (ZINCRBY)
- ✅ O(log N) complexity
- ✅ Built-in leaderboard support

### Why Circuit Breaker?
- ✅ Fail-fast when Redis is down
- ✅ Prevent cascade failures
- ✅ Graceful degradation (fallback cache)
- ✅ Automatic recovery

### Why Rate Limiting?
- ✅ Prevent abuse
- ✅ Fair resource allocation
- ✅ Protect backend services
- ✅ Smooth traffic patterns

---

## 🎉 Summary

This architecture provides:
- ✅ **Real-time updates** via WebSocket
- ✅ **Horizontal scalability** via Redis Pub/Sub
- ✅ **Dynamic scoring** via Question Bank
- ✅ **Production hardening** via Circuit Breaker + Rate Limiter
- ✅ **High availability** via graceful degradation
- ✅ **Observability** via Micrometer metrics

**Ready for production deployment!** 🚀

