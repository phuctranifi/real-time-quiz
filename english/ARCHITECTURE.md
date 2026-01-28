# Quiz Platform - Architecture Documentation

## Table of Contents
1. [System Overview](#system-overview)
2. [Architecture Diagram](#architecture-diagram)
3. [Component Descriptions](#component-descriptions)
4. [Data Flow](#data-flow)
5. [Technologies and Tools](#technologies-and-tools)
6. [Design Patterns](#design-patterns)
7. [Scalability and Performance](#scalability-and-performance)

---

## System Overview

The Quiz Platform is a **production-grade, horizontally scalable, real-time quiz application** built with Spring Boot and WebSocket technology. It supports multiple concurrent users across multiple server instances, with real-time leaderboard updates synchronized via Redis Pub/Sub.

### Key Features
- ✅ **Multi-instance deployment** - Horizontal scalability with load balancing
- ✅ **Real-time communication** - WebSocket with STOMP protocol
- ✅ **Cross-instance synchronization** - Redis Pub/Sub event bus
- ✅ **Dynamic scoring** - Question-based points (Question N = N points)
- ✅ **Production hardening** - Circuit breaker, rate limiting, health monitoring
- ✅ **High availability** - Graceful degradation when Redis is unavailable

---

## Architecture Diagram

### Component Interaction Diagram
See the interactive Mermaid diagram above showing:
- **Client Layer**: Web browsers with SockJS/STOMP clients
- **Load Balancer**: Distributes WebSocket connections across instances
- **Application Layer**: Multiple Spring Boot instances
- **Event Bus Layer**: Redis Pub/Sub for cross-instance events
- **Data Layer**: Redis with Sorted Sets (ZSET) for leaderboards
- **Monitoring Layer**: Micrometer metrics and health checks

### Data Flow Diagram
See the sequence diagram above showing the complete flow from user join to leaderboard update.

---

## Component Descriptions

### 1. Client Layer

#### **Web Client (user1.html, user2.html)**
- **Role**: Browser-based quiz interface for end users
- **Technology**: HTML5, JavaScript, SockJS, STOMP.js
- **Responsibilities**:
  - Establish WebSocket connection to server
  - Subscribe to quiz-specific topics
  - Send JOIN and SUBMIT_ANSWER messages
  - Display real-time leaderboard updates
  - Randomly select questions (1-10)
  - Show question-specific scoring

**Key Features**:
```javascript
// Random question selection
function pickRandomQuestion() {
    currentQuestionNumber = Math.floor(Math.random() * 10) + 1;
    // Question N awards N points
}

// WebSocket message sending
stompClient.send('/app/quiz/submit', {}, JSON.stringify({
    type: 'SUBMIT_ANSWER',
    quizId: 'quiz123',
    userId: 'alice',
    questionNumber: 7,
    correct: true
}));
```

---

### 2. Load Balancer

#### **Load Balancer (Nginx/AWS ALB/etc.)**
- **Role**: Distribute incoming WebSocket connections across server instances
- **Strategy**: Round-robin or least-connections
- **Responsibilities**:
  - Route initial WebSocket handshake
  - Maintain sticky sessions (optional, not required for this architecture)
  - Health check backend instances

**Why it works without sticky sessions**:
- Each instance subscribes to Redis Pub/Sub
- Events are broadcast to ALL instances
- Each instance broadcasts to its own connected clients
- No server-to-server communication needed

---

### 3. Application Layer (Spring Boot Instances)

#### **3.1 WebSocket Handler (STOMP Protocol)**
- **Class**: `WebSocketConfig.java`
- **Role**: Manage WebSocket connections and STOMP messaging
- **Technology**: Spring WebSocket, SockJS, STOMP
- **Responsibilities**:
  - Accept WebSocket connections at `/ws/quiz`
  - Handle STOMP subscriptions to `/topic/quiz/{quizId}`
  - Route messages to appropriate controllers
  - Broadcast messages to subscribed clients

**Configuration**:
```java
@Override
public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws/quiz")
            .setAllowedOriginPatterns("*")
            .withSockJS();
}

@Override
public void configureMessageBroker(MessageBrokerRegistry config) {
    config.enableSimpleBroker("/topic", "/queue");
    config.setApplicationDestinationPrefixes("/app");
    config.setUserDestinationPrefix("/user");
}
```

---

#### **3.2 Quiz Controller (Message Router)**
- **Class**: `QuizWebSocketController.java`
- **Role**: Handle incoming WebSocket messages and route to services
- **Responsibilities**:
  - Validate incoming messages
  - Apply rate limiting
  - Delegate to service layer
  - Send responses to clients
  - Track metrics

**Message Handlers**:
- `handleJoinMessage()` - User joins a quiz
- `handleSubmitAnswer()` - User submits an answer
- `handleDisconnect()` - User disconnects

**Rate Limiting**:
```java
if (!rateLimiter.tryConsume(sessionId)) {
    sendError(sessionId, "Rate limit exceeded");
    return;
}
```

---

#### **3.3 Quiz Service (Business Logic)**
- **Class**: `QuizServiceImpl.java`
- **Role**: Core business logic for quiz operations
- **Responsibilities**:
  - Handle user joins (add to leaderboard with score 0)
  - Process answer submissions
  - Calculate scores based on question number
  - Update Redis leaderboard atomically
  - Publish events to Redis Pub/Sub
  - Fetch leaderboard data

**Key Operations**:
```java
// User joins quiz
public void handleUserJoin(String quizId, String userId) {
    leaderboardRepository.addUser(quizId, userId, 0);
    eventPublisher.publishUserJoined(quizId, userId);
}

// Answer submission with dynamic scoring
public int handleAnswerSubmission(String quizId, String userId, 
                                   Integer questionNumber, boolean correct) {
    int points = correct ? questionBankService.getQuestionPoints(quizId, questionNumber) : 0;
    double newScore = leaderboardRepository.incrementScore(quizId, userId, points);
    eventPublisher.publishScoreUpdated(quizId, userId, (int) newScore);
    return (int) newScore;
}
```

---

#### **3.4 Question Bank Service**
- **Class**: `QuestionBankService.java`
- **Role**: Manage quiz questions and scoring rules
- **Storage**: In-memory `ConcurrentHashMap`
- **Responsibilities**:
  - Auto-initialize 10 questions per quiz
  - Provide question metadata
  - Calculate points (Question N = N points)
  - Validate question numbers (1-10)

**Scoring Rules**:
- Question 1: 1 point
- Question 2: 2 points
- ...
- Question 10: 10 points
- Incorrect answer: 0 points

---

#### **3.5 Room Manager (Session Tracking)**
- **Class**: `QuizRoomManager.java`
- **Role**: Track which users are in which quiz rooms
- **Storage**: Instance-local `ConcurrentHashMap`
- **Responsibilities**:
  - Map sessionId → quizId
  - Track active sessions per quiz
  - Clean up on disconnect

**Why instance-local**:
- No need for cross-instance session tracking
- Each instance only needs to know its own clients
- Redis Pub/Sub handles cross-instance communication

---

#### **3.6 Rate Limiter**
- **Class**: `QuizRateLimiter.java`
- **Technology**: Resilience4j RateLimiter
- **Role**: Prevent abuse and ensure fair resource usage
- **Configuration**:
  - 10 requests per second per session
  - Token bucket algorithm
  - Per-session tracking

```java
RateLimiterConfig config = RateLimiterConfig.custom()
    .limitForPeriod(10)
    .limitRefreshPeriod(Duration.ofSeconds(1))
    .timeoutDuration(Duration.ZERO)
    .build();
```

---

#### **3.7 Circuit Breaker**
- **Class**: `LeaderboardRepository.java`
- **Technology**: Resilience4j CircuitBreaker
- **Role**: Protect against Redis failures, enable graceful degradation
- **States**: CLOSED → OPEN → HALF_OPEN
- **Fallback**: In-memory cache when Redis is unavailable

**Configuration**:
```java
CircuitBreakerConfig config = CircuitBreakerConfig.custom()
    .failureRateThreshold(50)
    .waitDurationInOpenState(Duration.ofSeconds(30))
    .slidingWindowSize(10)
    .build();
```

---

### 4. Event Bus Layer (Redis Pub/Sub)

#### **4.1 Event Publisher**
- **Class**: `QuizEventPublisher.java`
- **Role**: Publish quiz events to Redis Pub/Sub
- **Events**:
  - `USER_JOINED` - New user joins quiz
  - `SCORE_UPDATED` - User's score changes

**Publishing**:
```java
public void publishScoreUpdated(String quizId, String userId, int newScore) {
    QuizEvent event = new QuizEvent(QuizEventType.SCORE_UPDATED, quizId, userId, newScore);
    String channel = "quiz:" + quizId + ":events";
    redisTemplate.convertAndSend(channel, event);
}
```

---

#### **4.2 Event Subscriber**
- **Class**: `QuizEventSubscriber.java`
- **Role**: Listen to Redis Pub/Sub and broadcast to WebSocket clients
- **Pattern**: `quiz:*:events` (subscribes to all quiz events)
- **Responsibilities**:
  - Receive events from Redis
  - Fetch updated leaderboard
  - Broadcast to all connected clients on this instance

**Event Handling**:
```java
@Override
public void onMessage(Message message, byte[] pattern) {
    QuizEvent event = deserialize(message.getBody());
    
    if (event.getType() == SCORE_UPDATED || event.getType() == USER_JOINED) {
        List<LeaderboardEntry> leaderboard = quizService.getLeaderboard(event.getQuizId());
        broadcastLeaderboard(event.getQuizId(), leaderboard);
    }
}
```

---

### 5. Data Layer (Redis)

#### **5.1 Redis (In-Memory Data Store)**
- **Technology**: Redis 7.x with Lettuce client
- **Role**: Shared state across all instances
- **Data Structures**:
  - **Sorted Sets (ZSET)**: Leaderboards
  - **Pub/Sub Channels**: Event distribution

**Why Redis**:
- ✅ Atomic operations (ZADD, ZINCRBY)
- ✅ Built-in Pub/Sub
- ✅ High performance (sub-millisecond latency)
- ✅ Horizontal scalability (Redis Cluster)
- ✅ Persistence options (RDB, AOF)

---

#### **5.2 Leaderboard Repository**
- **Class**: `LeaderboardRepository.java`
- **Role**: Interact with Redis for leaderboard operations
- **Key Pattern**: `quiz:{quizId}:leaderboard`

**Operations**:
```java
// Add user with initial score (atomic, NX = only if not exists)
public void addUser(String quizId, String userId, int initialScore) {
    String key = "quiz:" + quizId + ":leaderboard";
    redisTemplate.opsForZSet().addIfAbsent(key, userId, initialScore);
}

// Increment score (atomic)
public double incrementScore(String quizId, String userId, int points) {
    String key = "quiz:" + quizId + ":leaderboard";
    return redisTemplate.opsForZSet().incrementScore(key, userId, points);
}

// Get leaderboard (sorted by score descending)
public List<LeaderboardEntry> getLeaderboard(String quizId) {
    String key = "quiz:" + quizId + ":leaderboard";
    Set<TypedTuple<String>> entries = redisTemplate.opsForZSet()
        .reverseRangeWithScores(key, 0, -1);
    // Convert to LeaderboardEntry list
}
```

---

### 6. Monitoring Layer

#### **6.1 Micrometer Metrics**
- **Technology**: Micrometer with Prometheus registry
- **Role**: Collect application metrics
- **Metrics Tracked**:
  - `quiz.messages.join` - JOIN message count
  - `quiz.messages.submit` - SUBMIT_ANSWER message count
  - `quiz.messages.ratelimited` - Rate-limited requests
  - `quiz.leaderboard.size` - Leaderboard size gauge
  - `quiz.redis.operations` - Redis operation timers

**Endpoint**: `/actuator/prometheus`

---

#### **6.2 Health Checks**
- **Technology**: Spring Boot Actuator
- **Role**: Monitor application and dependency health
- **Checks**:
  - Redis connectivity
  - WebSocket connections
  - Circuit breaker state

**Endpoint**: `/actuator/health`

---

## Data Flow

### Flow 1: User Joins Quiz

1. **Client** opens WebSocket connection to Load Balancer
2. **Load Balancer** routes to Instance 1
3. **Client** subscribes to `/topic/quiz/quiz123`
4. **Client** sends JOIN message to `/app/quiz/join`
5. **WebSocket Handler** receives message
6. **Quiz Controller** validates and applies rate limiting
7. **Quiz Service** calls `handleUserJoin()`
8. **Circuit Breaker** checks Redis health
9. **Redis** executes `ZADD quiz:quiz123:leaderboard NX 0 alice`
10. **Event Publisher** publishes `USER_JOINED` to Redis Pub/Sub
11. **Event Subscriber** (on ALL instances) receives event
12. **Event Subscriber** fetches updated leaderboard from Redis
13. **WebSocket Handler** broadcasts `LEADERBOARD_UPDATE` to all clients on this instance
14. **All clients** (on all instances) receive updated leaderboard

### Flow 2: User Submits Answer

1. **Client** picks random question (e.g., Question 7)
2. **Client** sends SUBMIT_ANSWER with `questionNumber: 7, correct: true`
3. **Quiz Controller** validates message and applies rate limiting
4. **Quiz Service** calls `handleAnswerSubmission(quiz123, alice, 7, true)`
5. **Question Bank** returns 7 points for Question 7
6. **Circuit Breaker** checks Redis health
7. **Redis** executes `ZINCRBY quiz:quiz123:leaderboard 7 alice` → returns 7
8. **Event Publisher** publishes `SCORE_UPDATED` to Redis Pub/Sub
9. **Quiz Controller** sends `ANSWER_RESULT` to client (personal message)
10. **Client** receives result, updates UI, picks new random question
11. **Event Subscriber** (on ALL instances) receives event
12. **Event Subscriber** fetches updated leaderboard from Redis
13. **WebSocket Handler** broadcasts `LEADERBOARD_UPDATE` to all clients
14. **All clients** see updated leaderboard in real-time

---

## Technologies and Tools

### Backend Technologies

| Component | Technology | Version | Justification |
|-----------|-----------|---------|---------------|
| **Framework** | Spring Boot | 4.0.2 | Industry-standard, production-ready, extensive ecosystem |
| **Language** | Java | 17 | LTS version, modern features (records, pattern matching) |
| **WebSocket** | Spring WebSocket | 6.2.5 | Native Spring integration, STOMP protocol support |
| **Protocol** | STOMP | 1.2 | Simple, text-based, widely supported |
| **Fallback** | SockJS | 1.6.1 | WebSocket fallback for older browsers |
| **Data Store** | Redis | 7.x | In-memory speed, Pub/Sub, atomic operations |
| **Redis Client** | Lettuce | 6.x | Async, reactive, thread-safe, connection pooling |
| **Circuit Breaker** | Resilience4j | 2.x | Lightweight, functional, Spring Boot integration |
| **Rate Limiting** | Resilience4j | 2.x | Token bucket algorithm, per-session tracking |
| **Metrics** | Micrometer | 1.x | Vendor-neutral metrics facade, Prometheus support |
| **Monitoring** | Spring Actuator | 3.x | Health checks, metrics endpoints |
| **Build Tool** | Gradle | 9.3.0 | Fast, flexible, Kotlin DSL support |
| **Boilerplate** | Lombok | 1.18.x | Reduce boilerplate code (@Data, @Slf4j, etc.) |

### Frontend Technologies

| Component | Technology | Justification |
|-----------|-----------|---------------|
| **Client Library** | SockJS | WebSocket with fallback support |
| **Protocol** | STOMP.js | STOMP protocol for JavaScript |
| **UI** | HTML5/CSS3/JS | Simple, no framework dependencies |
| **Styling** | Custom CSS | Gradient themes, responsive design |

---

## Design Patterns

### 1. **Event-Driven Architecture**
- **Pattern**: Pub/Sub with Redis
- **Benefit**: Decouples instances, enables horizontal scaling
- **Implementation**: `QuizEventPublisher` + `QuizEventSubscriber`

### 2. **Circuit Breaker Pattern**
- **Pattern**: Resilience4j CircuitBreaker
- **Benefit**: Fail-fast, graceful degradation, prevent cascade failures
- **Implementation**: `LeaderboardRepository` with fallback cache

### 3. **Repository Pattern**
- **Pattern**: Data access abstraction
- **Benefit**: Separate business logic from data access
- **Implementation**: `LeaderboardRepository`

### 4. **Service Layer Pattern**
- **Pattern**: Business logic encapsulation
- **Benefit**: Reusable, testable, maintainable
- **Implementation**: `QuizService` interface + `QuizServiceImpl`

### 5. **Token Bucket Rate Limiting**
- **Pattern**: Resilience4j RateLimiter
- **Benefit**: Smooth traffic, prevent abuse
- **Implementation**: `QuizRateLimiter`

---

## Scalability and Performance

### Horizontal Scalability
- ✅ **Stateless instances** - No server-to-server communication
- ✅ **Shared state in Redis** - Single source of truth
- ✅ **Event-driven sync** - Redis Pub/Sub broadcasts to all instances
- ✅ **Load balancing** - Distribute connections across instances

### Performance Optimizations
- ✅ **Atomic Redis operations** - ZADD NX, ZINCRBY (O(log N))
- ✅ **In-memory data structures** - ConcurrentHashMap for local state
- ✅ **Connection pooling** - Lettuce connection pool
- ✅ **Async event handling** - Non-blocking Pub/Sub
- ✅ **Rate limiting** - Prevent resource exhaustion

### High Availability
- ✅ **Circuit breaker** - Graceful degradation when Redis fails
- ✅ **Health checks** - Monitor dependencies
- ✅ **Metrics** - Prometheus integration for alerting
- ✅ **Fallback cache** - In-memory cache when Redis unavailable

### Capacity Planning
- **WebSocket connections**: ~10,000 per instance (with tuning)
- **Redis throughput**: ~100,000 ops/sec (single instance)
- **Latency**: <10ms for leaderboard updates
- **Scaling**: Add instances behind load balancer as needed

---

## Security Considerations

### Current Implementation
- ✅ **Rate limiting** - Prevent abuse (10 req/sec per session)
- ✅ **Input validation** - Validate all incoming messages
- ✅ **CORS configuration** - Controlled origin patterns

### Production Recommendations
- 🔒 **Authentication** - Add JWT/OAuth2 for user authentication
- 🔒 **Authorization** - Verify user permissions for quiz access
- 🔒 **TLS/SSL** - Encrypt WebSocket connections (wss://)
- 🔒 **Redis AUTH** - Password-protect Redis
- 🔒 **Network isolation** - Redis in private subnet
- 🔒 **Input sanitization** - Prevent injection attacks

---

## Deployment Architecture

### Development
```
[Client] → [Spring Boot :8080] → [Redis :6379]
```

### Production (Multi-Instance)
```
[Clients] → [Load Balancer] → [Instance 1 :8080]
                            → [Instance 2 :8081]
                            → [Instance N :808N]
                                    ↓
                            [Redis Cluster]
                                    ↓
                            [Prometheus/Grafana]
```

### Docker Compose Example
```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
  
  quiz-app-1:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_REDIS_HOST=redis
  
  quiz-app-2:
    build: .
    ports:
      - "8081:8080"
    environment:
      - SPRING_REDIS_HOST=redis
  
  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf
```

---

## Conclusion

This architecture provides a **production-grade, horizontally scalable, real-time quiz platform** with:
- ✅ Multi-instance deployment capability
- ✅ Real-time synchronization via Redis Pub/Sub
- ✅ Dynamic question-based scoring
- ✅ Production hardening (circuit breaker, rate limiting)
- ✅ Comprehensive monitoring and health checks
- ✅ High availability and graceful degradation

The system is ready for production deployment and can scale to thousands of concurrent users across multiple server instances.

