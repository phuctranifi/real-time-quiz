# Quiz Platform - Architecture Documentation Index

## 📚 Welcome to the Architecture Documentation

This is your complete guide to understanding the **Quiz Platform Architecture** - a production-grade, horizontally scalable, real-time quiz application.

---

## 🗺️ Documentation Map

### 📊 Start Here

1. **[ARCHITECTURE_SUMMARY.md](ARCHITECTURE_SUMMARY.md)** ⭐ **START HERE**
   - Executive summary
   - Architecture highlights
   - Key design decisions
   - Performance characteristics
   - **Best for**: Getting a quick overview

2. **Interactive Diagrams** 🎨 **VISUAL LEARNERS**
   - Component Interaction Diagram
   - Data Flow Sequence Diagram
   - Technology Stack Diagram
   - Redis Operations Diagram
   - Request-Response Cycle with Timing
   - **Best for**: Understanding system visually

---

### 📖 Deep Dive

3. **[ARCHITECTURE.md](ARCHITECTURE.md)** 📚 **COMPREHENSIVE GUIDE**
   - Complete architecture documentation (300+ lines)
   - Detailed component descriptions
   - Data flow explanations
   - Technology justifications
   - Design patterns
   - Scalability analysis
   - Security considerations
   - Deployment architecture
   - **Best for**: Understanding every detail

4. **[ARCHITECTURE_QUICK_REFERENCE.md](ARCHITECTURE_QUICK_REFERENCE.md)** ⚡ **QUICK LOOKUP**
   - Component map
   - Data flow summary
   - Technology stack table
   - WebSocket message formats
   - Redis operations
   - Scoring rules
   - Deployment commands
   - Troubleshooting guide
   - **Best for**: Quick reference during development

---

## 🎯 Choose Your Path

### Path 1: "I want a quick overview"
```
1. Read ARCHITECTURE_SUMMARY.md (10 min)
2. View Component Interaction Diagram (5 min)
3. View Data Flow Sequence Diagram (5 min)
Total: 20 minutes
```

### Path 2: "I want to understand the system deeply"
```
1. Read ARCHITECTURE_SUMMARY.md (10 min)
2. View all Interactive Diagrams (15 min)
3. Read ARCHITECTURE.md (45 min)
4. Explore source code with architecture in mind (30 min)
Total: 100 minutes
```

### Path 3: "I need to implement/debug something"
```
1. Use ARCHITECTURE_QUICK_REFERENCE.md for lookups
2. Check specific sections in ARCHITECTURE.md
3. Refer to diagrams as needed
Total: As needed
```

---

## 📊 Interactive Diagrams

### 1. Component Interaction Diagram
**What it shows**: All components and their relationships  
**Color coding**:
- 🔵 Blue: Client Layer (Web browsers)
- 🟢 Green: Application Layer (Spring Boot instances)
- 🟠 Orange: Data Layer (Redis)
- 🔴 Red: Event Bus Layer (Pub/Sub)
- 🟣 Purple: Monitoring Layer (Metrics)

**Key insights**:
- Multi-instance architecture
- No server-to-server communication
- Redis as central hub
- Event-driven synchronization

---

### 2. Data Flow Sequence Diagram
**What it shows**: Complete flow from user join to leaderboard update  
**Participants**:
- Web Client (user1.html)
- Load Balancer
- WebSocket Handler
- Quiz Controller
- Rate Limiter
- Quiz Service
- Question Bank
- Circuit Breaker
- Redis
- Event Publisher/Subscriber
- Other instances

**Key insights**:
- Step-by-step message flow
- Redis operations (ZADD, ZINCRBY, ZREVRANGE)
- Pub/Sub event propagation
- Cross-instance synchronization

---

### 3. Technology Stack Diagram
**What it shows**: Layer-by-layer technology breakdown  
**Layers**:
- Presentation Layer (HTML5, CSS3, JavaScript, SockJS, STOMP.js)
- Communication Layer (WebSocket, STOMP, HTTP)
- Application Layer (Spring Boot, Spring WebSocket, Lettuce, Resilience4j)
- Business Logic Layer (Controllers, Services, Repositories, Events)
- Data Layer (Redis, ZSET, Pub/Sub)
- Monitoring Layer (Micrometer, Prometheus, Actuator)
- Build & Runtime (Java 17, Gradle, Docker)

**Key insights**:
- Technology dependencies
- Integration points
- Version information

---

### 4. Redis Operations Diagram
**What it shows**: Redis data structures and operations  
**Data structures**:
- Sorted Sets (ZSET) for leaderboards
- Pub/Sub channels for events

**Operations**:
- ZADD: Add user with initial score
- ZINCRBY: Increment user score
- ZREVRANGE: Get leaderboard (descending)
- PUBLISH: Broadcast event
- PSUBSCRIBE: Subscribe to pattern

**Key insights**:
- Key naming patterns
- Operation complexity (O notation)
- Example data

---

### 5. Request-Response Cycle with Timing
**What it shows**: Complete cycle from button click to UI update  
**Timeline**: 0ms → 15ms  
**Steps**:
1. User clicks button (T=0ms)
2. Client sends WebSocket message (T=1ms)
3. Load balancer routes (T=2ms)
4. Instance processes (T=3ms)
5. Redis update (T=4ms)
6. Event publish (T=5ms)
7. Personal response (T=6ms)
8. UI update (T=7ms)
9. Pub/Sub propagation (T=8ms)
10. Broadcast to all clients (T=10ms)
11. Complete (T=15ms)

**Key insights**:
- Sub-millisecond Redis operations
- Parallel Pub/Sub to all instances
- Total latency ~15ms

---

## 🏗️ Architecture at a Glance

### System Type
- **Category**: Real-time, event-driven, multi-instance web application
- **Domain**: Quiz/gaming platform
- **Scale**: Thousands of concurrent users
- **Deployment**: Horizontally scalable

### Core Technologies
- **Backend**: Spring Boot 4.0.2 + Java 17
- **Real-time**: WebSocket (STOMP over SockJS)
- **Data Store**: Redis 7.x (ZSET + Pub/Sub)
- **Resilience**: Resilience4j (Circuit Breaker + Rate Limiter)
- **Monitoring**: Micrometer + Prometheus

### Key Features
- ✅ Multi-instance deployment
- ✅ Real-time leaderboard updates
- ✅ Cross-instance synchronization
- ✅ Dynamic question-based scoring
- ✅ Circuit breaker for Redis failover
- ✅ Rate limiting (10 req/sec per session)
- ✅ Comprehensive metrics
- ✅ Health checks

---

## 🔑 Key Concepts

### 1. Event-Driven Architecture
**What**: Components communicate via events (Redis Pub/Sub)  
**Why**: Decouples instances, enables horizontal scaling  
**How**: Publish events → All instances subscribe → Broadcast to clients

### 2. Sorted Sets (ZSET)
**What**: Redis data structure for leaderboards  
**Why**: Automatic sorting, atomic operations, O(log N) complexity  
**How**: ZADD (add user), ZINCRBY (increment score), ZREVRANGE (get leaderboard)

### 3. Circuit Breaker
**What**: Fail-fast pattern for Redis operations  
**Why**: Prevent cascade failures, graceful degradation  
**How**: Monitor failures → Open circuit → Fallback cache → Auto-recovery

### 4. Rate Limiting
**What**: Token bucket algorithm (10 req/sec per session)  
**Why**: Prevent abuse, fair resource allocation  
**How**: Each session gets 10 tokens/sec → Consume on request → Reject if empty

### 5. Horizontal Scaling
**What**: Add more instances to handle more load  
**Why**: Linear scalability, high availability  
**How**: Stateless instances + Shared state in Redis + Event-driven sync

---

## 📁 File Structure

### Documentation Files
```
ARCHITECTURE_INDEX.md              ← You are here
ARCHITECTURE_SUMMARY.md            ← Executive summary
ARCHITECTURE.md                    ← Complete documentation
ARCHITECTURE_QUICK_REFERENCE.md    ← Quick reference guide
```

### Source Code (Key Files)
```
src/main/java/com/quiz/english/
├── config/
│   ├── WebSocketConfig.java          # WebSocket + STOMP configuration
│   ├── RedisConfig.java               # Redis connection + serialization
│   └── RedisPubSubConfig.java         # Pub/Sub setup
├── ws/
│   └── QuizWebSocketController.java   # WebSocket message handlers
├── service/
│   ├── QuizServiceImpl.java           # Core business logic
│   ├── QuestionBankService.java       # Question management
│   └── QuizRoomManager.java           # Session tracking
├── repository/
│   └── LeaderboardRepository.java     # Redis operations
├── event/
│   ├── QuizEventPublisher.java        # Publish events to Redis
│   └── QuizEventSubscriber.java       # Subscribe to Redis events
└── model/
    ├── JoinMessage.java               # JOIN message
    ├── SubmitAnswerMessage.java       # SUBMIT_ANSWER message
    ├── AnswerResultMessage.java       # ANSWER_RESULT response
    └── LeaderboardUpdateMessage.java  # LEADERBOARD_UPDATE broadcast
```

### Demo Files
```
demo/
├── user1.html    # User 1 interface (purple theme)
├── user2.html    # User 2 interface (pink theme)
└── index.html    # Landing page
```

---

## 🚀 Quick Start

### View Documentation
```bash
# Open in browser
open ARCHITECTURE_SUMMARY.md
open ARCHITECTURE.md
open ARCHITECTURE_QUICK_REFERENCE.md
```

### Run the System
```bash
# 1. Start Redis
docker run -d -p 6379:6379 redis:7-alpine

# 2. Build and run
./gradlew clean build
./gradlew bootRun

# 3. Open demo
open demo/user1.html
open demo/user2.html
```

### Test Multi-Instance
```bash
# Terminal 1: Redis
docker run -d -p 6379:6379 redis:7-alpine

# Terminal 2: Instance 1
SERVER_PORT=8080 ./gradlew bootRun

# Terminal 3: Instance 2
SERVER_PORT=8081 ./gradlew bootRun

# Browser: Test cross-instance sync
# - user1.html → port 8080
# - user2.html → port 8081
# - Submit answers and watch real-time sync!
```

---

## 🎓 Learning Path

### Beginner
1. Read **ARCHITECTURE_SUMMARY.md**
2. View **Component Interaction Diagram**
3. Try the **demo** (user1.html, user2.html)
4. Understand the **data flow**

### Intermediate
1. Read **ARCHITECTURE.md** (sections 1-4)
2. View all **diagrams**
3. Explore **source code** (controllers, services)
4. Test **multi-instance** deployment

### Advanced
1. Read **complete ARCHITECTURE.md**
2. Study **design patterns** (circuit breaker, rate limiting)
3. Analyze **Redis operations** (ZSET, Pub/Sub)
4. Implement **production enhancements** (auth, TLS, clustering)

---

## 🔍 Common Questions

### Q: How does cross-instance sync work?
**A**: Via Redis Pub/Sub. When Instance 1 updates a score, it publishes an event to Redis. All instances (including Instance 1) receive the event and broadcast the updated leaderboard to their connected clients.

### Q: Why use Redis instead of a database?
**A**: Redis provides sub-millisecond latency, atomic operations (ZINCRBY), built-in Pub/Sub, and automatic sorting (ZSET). Perfect for real-time leaderboards.

### Q: What happens if Redis goes down?
**A**: The circuit breaker opens, and the system falls back to an in-memory cache. Users can still see leaderboards (stale data), but updates won't persist until Redis recovers.

### Q: How many users can the system handle?
**A**: ~10,000 WebSocket connections per instance. With 10 instances, that's 100,000 concurrent users. Redis can handle ~100,000 ops/sec.

### Q: How do I add authentication?
**A**: Implement Spring Security with JWT tokens. Validate tokens in WebSocket handshake. See ARCHITECTURE.md section on security.

---

## 📞 Next Steps

1. **Choose your path** (overview, deep dive, or quick reference)
2. **Read the documentation** based on your needs
3. **View the diagrams** to visualize the architecture
4. **Try the demo** to see it in action
5. **Explore the code** with the architecture in mind
6. **Test multi-instance** to verify cross-instance sync
7. **Consider enhancements** for your use case

---

## 🎉 Summary

You now have access to:
- ✅ **4 comprehensive documentation files**
- ✅ **5 interactive architecture diagrams**
- ✅ **Complete source code** with inline documentation
- ✅ **Working demo** (user1.html, user2.html)
- ✅ **Multi-instance deployment** guide
- ✅ **Production-ready** architecture

**Everything you need to understand, deploy, and extend this quiz platform!** 🚀

---

**Happy learning!** 📚✨

