# Quiz Platform Demo

Interactive demo client for the Quiz Platform WebSocket API.

## Quick Start

### 1. Start Redis

```bash
# Using Docker (recommended)
docker run -d --name quiz-redis -p 6379:6379 redis:7-alpine

# OR using local Redis
redis-server
```

### 2. Start the Application

```bash
# From project root
./gradlew bootRun
```

### 3. Install Demo Dependencies

```bash
cd demo
npm install
```

### 4. Run the Demo

**Option A: Interactive Menu (Recommended)**
```bash
./run-demo.sh
```

**Option B: Direct Command**
```bash
node quiz-client.js
```

**Option C: NPM Scripts**
```bash
npm start                # Single user demo
npm run demo:alice       # User "alice" on port 8080
npm run demo:bob         # User "bob" on port 8081
npm run demo:charlie     # User "charlie" on port 8082
```

---

## Demo Modes

### Mode 1: Single User Demo

**What it does:**
- Connects 1 user to the server
- Joins a quiz
- Submits 10 answers (70% correct rate)
- Displays real-time leaderboard updates

**How to run:**
```bash
./run-demo.sh
# Select option 1
```

**Expected output:**
```
✅ Connected to server!
✅ Successfully joined quiz: quiz123
📤 Submitting answer #1/10 (correct)...
✅ Answer #1: CORRECT
   New Score: 10 points (+10)
📊 LEADERBOARD UPDATE #1
────────────────────────────────────────────────────────
🥇 #1 user-abc123              10 points ← YOU
────────────────────────────────────────────────────────
```

---

### Mode 2: Multi-User Demo (Same Instance)

**What it does:**
- Connects 3 users to the same server instance
- All users join the same quiz
- Users submit answers at different intervals
- All users see each other's updates in real-time

**How to run:**
```bash
./run-demo.sh
# Select option 2
```

**Expected behavior:**
- Alice, Bob, and Charlie all connect to port 8080
- Each user sees leaderboard updates from all other users
- Leaderboard is sorted by score in real-time

---

### Mode 3: Multi-Instance Demo (Advanced)

**What it does:**
- Connects 3 users to 3 different server instances
- Demonstrates Redis Pub/Sub synchronization
- All users see updates regardless of which instance they're connected to

**Prerequisites:**
Start 3 application instances:

**Terminal 1:**
```bash
./gradlew bootRun
```

**Terminal 2:**
```bash
SERVER_PORT=8081 SPRING_APPLICATION_INSTANCE_ID=instance-b ./gradlew bootRun
```

**Terminal 3:**
```bash
SERVER_PORT=8082 SPRING_APPLICATION_INSTANCE_ID=instance-c ./gradlew bootRun
```

**Then run the demo:**
```bash
./run-demo.sh
# Select option 3
```

**Expected behavior:**
- Alice connects to instance A (port 8080)
- Bob connects to instance B (port 8081)
- Charlie connects to instance C (port 8082)
- All users see the same leaderboard in real-time
- Updates propagate across instances via Redis Pub/Sub

---

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_URL` | `http://localhost:8080` | WebSocket server URL |
| `QUIZ_ID` | `quiz123` | Quiz identifier |
| `USER_ID` | Random | User identifier |
| `ANSWER_INTERVAL` | `3000` | Milliseconds between answers |
| `MAX_ANSWERS` | `10` | Number of answers to submit |

### Examples

**Connect to different server:**
```bash
SERVER_URL=http://localhost:8081 node quiz-client.js
```

**Use specific user ID:**
```bash
USER_ID=alice node quiz-client.js
```

**Submit answers faster:**
```bash
ANSWER_INTERVAL=1000 MAX_ANSWERS=20 node quiz-client.js
```

**Join different quiz:**
```bash
QUIZ_ID=quiz456 node quiz-client.js
```

---

## Monitoring

### Watch Redis Events

```bash
# Using Docker
docker exec -it quiz-redis redis-cli PSUBSCRIBE "quiz:*:events"

# Using local Redis
redis-cli PSUBSCRIBE "quiz:*:events"
```

**You'll see:**
```
1) "pmessage"
2) "quiz:*:events"
3) "quiz:quiz123:events"
4) "{\"type\":\"USER_JOINED\",\"quizId\":\"quiz123\",\"userId\":\"alice\",...}"

1) "pmessage"
2) "quiz:*:events"
3) "quiz:quiz123:events"
4) "{\"type\":\"SCORE_UPDATED\",\"quizId\":\"quiz123\",\"userId\":\"alice\",\"score\":10,...}"
```

### Check Application Metrics

```bash
# Health check
curl http://localhost:8080/actuator/health

# WebSocket connections
curl http://localhost:8080/actuator/metrics/quiz.websocket.connections

# Rate limiter stats
curl http://localhost:8080/actuator/metrics/quiz.rate_limit.allowed
```

### View Application Logs

The application logs will show:
```
DEBUG c.q.e.ws.QuizWebSocketController - User alice joined quiz quiz123
DEBUG c.q.e.redis.QuizEventPublisher - Published USER_JOINED event for user alice in quiz quiz123
DEBUG c.q.e.redis.QuizEventSubscriber - Received event from channel quiz:quiz123:events: type=USER_JOINED
DEBUG c.q.e.redis.QuizEventSubscriber - Broadcasting leaderboard update for quiz quiz123
```

---

## Troubleshooting

### Error: Cannot connect to server

**Symptom:**
```
❌ WebSocket Error: connect ECONNREFUSED 127.0.0.1:8080
```

**Solution:**
- Make sure the application is running: `./gradlew bootRun`
- Check the port: `curl http://localhost:8080/actuator/health`

---

### Error: Redis connection failed

**Symptom:**
```
ERROR c.q.e.redis.RedisHealthMonitor - Redis health check failed
```

**Solution:**
- Start Redis: `docker run -d -p 6379:6379 redis:7-alpine`
- Test connection: `redis-cli PING`

---

### Error: Rate limit exceeded

**Symptom:**
```
❌ Error: Rate limit exceeded. Please slow down.
```

**Solution:**
- Increase answer interval: `ANSWER_INTERVAL=5000 node quiz-client.js`
- OR increase rate limit in `application.properties`:
  ```properties
  quiz.rate-limit.capacity=20
  ```

---

### No leaderboard updates

**Symptom:**
- Client connects successfully
- Answers are submitted
- No leaderboard updates appear

**Solution:**
1. Check Redis Pub/Sub is working:
   ```bash
   redis-cli PSUBSCRIBE "quiz:*:events"
   ```
2. Check application logs for errors
3. Verify circuit breaker is not open:
   ```bash
   curl http://localhost:8080/actuator/health
   ```

---

## Advanced Usage

### Run Multiple Clients Simultaneously

```bash
# Terminal 1
USER_ID=alice ANSWER_INTERVAL=3000 node quiz-client.js

# Terminal 2
USER_ID=bob ANSWER_INTERVAL=4000 node quiz-client.js

# Terminal 3
USER_ID=charlie ANSWER_INTERVAL=5000 node quiz-client.js
```

### Test Circuit Breaker

```bash
# Start client
node quiz-client.js &

# Stop Redis while client is running
docker stop quiz-redis

# Watch logs - circuit breaker should open
# In-memory fallback should activate

# Restart Redis
docker start quiz-redis

# Circuit breaker should close automatically
```

### Test Rate Limiting

```bash
# Submit answers very quickly
ANSWER_INTERVAL=100 node quiz-client.js

# You should see rate limit errors after ~10 requests
```

---

## Files

- `quiz-client.js` - Main demo client script
- `package.json` - NPM dependencies and scripts
- `run-demo.sh` - Interactive demo runner
- `README.md` - This file

---

## Next Steps

After running the demo:

1. ✅ **Explore the code** - See `quiz-client.js` for WebSocket client implementation
2. ✅ **Build a UI** - Use this client as reference for React/Vue frontend
3. ✅ **Test failure scenarios** - Stop Redis, kill instances, etc.
4. ✅ **Monitor metrics** - Check `/actuator/prometheus` endpoint
5. ✅ **Scale horizontally** - Add more instances and see synchronization

---

**Happy Testing! 🚀**

