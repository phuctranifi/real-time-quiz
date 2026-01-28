# Redis Pub/Sub Integration

## Overview

Redis Pub/Sub enables **real-time cross-instance synchronization** for multi-server deployments. When a user action occurs on one backend instance, all instances are notified and broadcast updates to their connected WebSocket clients.

---

## Architecture

### Multi-Instance Broadcasting

```
┌─────────────────────────────────────────────────────────────────┐
│                         Redis Server                             │
│                                                                   │
│  Channel: quiz:quiz123:events                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  Published Events:                                       │   │
│  │  - USER_JOINED {quizId, userId, timestamp, ...}         │   │
│  │  - SCORE_UPDATED {quizId, userId, score, timestamp, ...}│   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
         ▲                        │                        │
         │ publish                │ subscribe              │ subscribe
         │                        ▼                        ▼
    ┌────────────┐         ┌────────────┐          ┌────────────┐
    │ Instance A │         │ Instance B │          │ Instance C │
    │            │         │            │          │            │
    │ Publisher  │         │ Subscriber │          │ Subscriber │
    │ Subscriber │         │            │          │            │
    └────────────┘         └────────────┘          └────────────┘
         │                        │                        │
         │ broadcast              │ broadcast              │ broadcast
         ▼                        ▼                        ▼
    WebSocket              WebSocket                WebSocket
    Clients                Clients                  Clients
    (User 1, 2)            (User 3, 4)              (User 5, 6)
```

### How It Works

1. **User Action**: User on Instance A submits an answer
2. **Local Processing**: Instance A updates Redis leaderboard (Sorted Set)
3. **Event Publishing**: Instance A publishes `SCORE_UPDATED` event to Redis channel
4. **Redis Broadcasting**: Redis sends event to ALL subscribed instances (A, B, C)
5. **Event Reception**: Each instance's `QuizEventSubscriber` receives the event
6. **Local Broadcasting**: Each instance broadcasts leaderboard to its WebSocket clients
7. **Result**: All users see the update in real-time, regardless of which instance they're connected to

---

## Components

### 1. QuizEvent Model

Event data structure for Redis Pub/Sub messages.

**File**: `src/main/java/com/quiz/english/model/QuizEvent.java`

```java
@Data
public class QuizEvent {
    private QuizEventType type;      // USER_JOINED or SCORE_UPDATED
    private String quizId;            // Quiz identifier
    private String userId;            // User who triggered the event
    private Integer score;            // New score (for SCORE_UPDATED)
    private Instant timestamp;        // When event occurred
    private String sourceInstanceId;  // Which instance published (for debugging)
}
```

**Event Types**:
- `USER_JOINED` - User joined a quiz
- `SCORE_UPDATED` - User's score changed

---

### 2. RedisPubSubConfig

Configures Redis message listeners and channel subscriptions.

**File**: `src/main/java/com/quiz/english/config/RedisPubSubConfig.java`

**Channel Pattern**: `quiz:*:events`
- Matches: `quiz:quiz123:events`, `quiz:quiz456:events`, etc.
- Each instance subscribes to ALL quiz events
- Subscriber filters and processes relevant events

**Key Configuration**:
```java
@Bean
public RedisMessageListenerContainer quizEventListenerContainer(...) {
    container.addMessageListener(
        quizEventListenerAdapter, 
        new PatternTopic("quiz:*:events")
    );
    return container;
}
```

---

### 3. QuizEventPublisher

Service to publish events to Redis Pub/Sub.

**File**: `src/main/java/com/quiz/english/redis/QuizEventPublisher.java`

**Methods**:
- `publishUserJoined(quizId, userId)` - Publish USER_JOINED event
- `publishScoreUpdated(quizId, userId, score)` - Publish SCORE_UPDATED event

**Usage**:
```java
// In QuizService
eventPublisher.publishUserJoined("quiz123", "user456");
eventPublisher.publishScoreUpdated("quiz123", "user456", 100);
```

**Channel Naming**: `quiz:{quizId}:events`

---

### 4. QuizEventSubscriber

Subscriber that receives events from Redis and dispatches to WebSocket layer.

**File**: `src/main/java/com/quiz/english/redis/QuizEventSubscriber.java`

**Responsibilities**:
1. Receive events from Redis Pub/Sub
2. Deserialize event data
3. Fetch updated leaderboard from Redis
4. Broadcast to local WebSocket clients

**Event Handling**:
```java
@Override
public void onMessage(Message message, byte[] pattern) {
    QuizEvent event = objectMapper.readValue(message.getBody(), QuizEvent.class);
    
    switch (event.getType()) {
        case USER_JOINED:
            handleUserJoined(event);  // Broadcast leaderboard
            break;
        case SCORE_UPDATED:
            handleScoreUpdated(event); // Broadcast leaderboard
            break;
    }
}
```

---

## Message Flow

### Example: User Submits Answer

```
1. User on Instance A submits answer
   ↓
2. WebSocket Controller validates request
   ↓
3. QuizService.handleAnswerSubmission()
   ├─ Update score in Redis Sorted Set (TODO)
   └─ QuizEventPublisher.publishScoreUpdated()
      ↓
4. Redis receives event on channel: quiz:quiz123:events
   ↓
5. Redis broadcasts to ALL instances (A, B, C)
   ↓
6. Each instance's QuizEventSubscriber.onMessage()
   ├─ Deserialize event
   ├─ Fetch updated leaderboard from Redis
   └─ Broadcast to /topic/quiz/quiz123
      ↓
7. WebSocket clients on each instance receive update
   ↓
8. All users see updated leaderboard in real-time
```

---

## Benefits

### ✅ No Sticky Sessions Required
Users can connect to any instance. Events are broadcast to all instances, so all users receive updates.

### ✅ Horizontal Scalability
Add more instances anytime. New instances automatically subscribe to Redis channels and participate in broadcasting.

### ✅ Single Source of Truth
Redis stores the leaderboard (Sorted Sets). All instances fetch from the same source.

### ✅ Real-Time Sync
Events are published and received in milliseconds. All users see updates simultaneously.

### ✅ Fault Tolerance
If an instance crashes, users can reconnect to another instance. State is preserved in Redis.

---

## Configuration

### application.properties

```properties
# Instance ID for debugging (set via environment variable in production)
spring.application.instance-id=${HOSTNAME:localhost}

# Redis Configuration
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

### Production Deployment

**Kubernetes Example**:
```yaml
env:
  - name: SPRING_APPLICATION_INSTANCE_ID
    valueFrom:
      fieldRef:
        fieldPath: metadata.name  # Use pod name as instance ID
```

**Docker Compose Example**:
```yaml
environment:
  - SPRING_APPLICATION_INSTANCE_ID=${HOSTNAME}
```

---

## Testing Multi-Instance Setup

### Local Testing with Multiple Instances

**Terminal 1** (Instance A on port 8080):
```bash
./gradlew bootRun
```

**Terminal 2** (Instance B on port 8081):
```bash
SERVER_PORT=8081 SPRING_APPLICATION_INSTANCE_ID=instance-b ./gradlew bootRun
```

**Terminal 3** (Instance C on port 8082):
```bash
SERVER_PORT=8082 SPRING_APPLICATION_INSTANCE_ID=instance-c ./gradlew bootRun
```

### Test Scenario

1. Connect User 1 to Instance A (ws://localhost:8080/ws/quiz)
2. Connect User 2 to Instance B (ws://localhost:8081/ws/quiz)
3. Connect User 3 to Instance C (ws://localhost:8082/ws/quiz)
4. User 1 joins quiz → All users see leaderboard update
5. User 2 submits answer → All users see leaderboard update
6. User 3 submits answer → All users see leaderboard update

**Expected Result**: All users receive updates regardless of which instance they're connected to.

---

## Monitoring

### Redis CLI

Monitor published events:
```bash
redis-cli
> PSUBSCRIBE quiz:*:events
```

### Application Logs

Each instance logs:
- Published events: `Published SCORE_UPDATED event for user X in quiz Y`
- Received events: `Received event from channel quiz:X:events: type=SCORE_UPDATED, source=instance-a`
- Broadcasts: `Broadcasted leaderboard update for quiz X`

---

## Next Steps

1. **Implement Redis Leaderboard** - Use Sorted Sets for score storage
2. **Add Authentication** - Validate user tokens in events
3. **Add Metrics** - Track event publish/receive rates
4. **Add Error Handling** - Retry failed publishes, handle deserialization errors
5. **Add Event Filtering** - Optimize by filtering events at subscriber level

---

## Troubleshooting

### Events Not Received

- Check Redis connection: `redis-cli PING`
- Verify channel pattern: `PSUBSCRIBE quiz:*:events`
- Check logs for subscription errors

### Duplicate Broadcasts

- Expected behavior: Each instance broadcasts to its own clients
- If seeing duplicates on same client, check WebSocket subscriptions

### Delayed Updates

- Check Redis latency: `redis-cli --latency`
- Check network between instances and Redis
- Verify Redis is not overloaded

