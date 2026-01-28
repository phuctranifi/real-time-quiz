# Quiz Platform Demo Guide

**Complete step-by-step guide to run a live demo of the multi-instance quiz platform**

---

## Prerequisites

### Required Software
- ✅ Java 17 or higher
- ✅ Redis 7.x
- ✅ Node.js (for WebSocket client testing) - Optional
- ✅ Docker (easiest way to run Redis) - Recommended

### Check Prerequisites
```bash
# Check Java version
java -version  # Should be 17+

# Check if Docker is installed (for Redis)
docker --version

# OR check if Redis is installed locally
redis-cli --version
```

---

## Step 1: Start Redis

### Option A: Using Docker (Recommended)
```bash
# Start Redis in a container
docker run -d \
  --name quiz-redis \
  -p 6379:6379 \
  redis:7-alpine

# Verify Redis is running
docker ps | grep quiz-redis

# Test Redis connection
docker exec -it quiz-redis redis-cli PING
# Should return: PONG
```

### Option B: Using Local Redis
```bash
# Start Redis server
redis-server

# In another terminal, test connection
redis-cli PING
# Should return: PONG
```

---

## Step 2: Build the Application

```bash
# Navigate to project directory
cd /Users/tranvanphuc/Desktop/github/vocabulary/english

# Build the application (skip tests for faster build)
./gradlew clean build -x test

# Verify build success
# Should see: BUILD SUCCESSFUL
```

---

## Step 3: Start the Application

### Single Instance (Simple Demo)
```bash
# Start the application on port 8080
./gradlew bootRun

# Wait for startup message:
# "Started EnglishApplication in X seconds"
```

### Multi-Instance (Advanced Demo)
Open 3 separate terminals:

**Terminal 1 - Instance A (Port 8080):**
```bash
./gradlew bootRun
```

**Terminal 2 - Instance B (Port 8081):**
```bash
SERVER_PORT=8081 SPRING_APPLICATION_INSTANCE_ID=instance-b ./gradlew bootRun
```

**Terminal 3 - Instance C (Port 8082):**
```bash
SERVER_PORT=8082 SPRING_APPLICATION_INSTANCE_ID=instance-c ./gradlew bootRun
```

---

## Step 4: Monitor Redis Events (Optional)

Open a new terminal to watch Redis Pub/Sub events in real-time:

```bash
# Using Docker
docker exec -it quiz-redis redis-cli PSUBSCRIBE "quiz:*:events"

# OR using local Redis
redis-cli PSUBSCRIBE "quiz:*:events"
```

**What you'll see:**
- `USER_JOINED` events when users join
- `SCORE_UPDATED` events when users submit answers

---

## Step 5: Run the Demo

### Option A: Using Node.js WebSocket Client (Recommended)

I'll create a Node.js demo script for you. First, create the demo directory:

```bash
# Create demo directory
mkdir -p demo
cd demo

# Initialize npm project
npm init -y

# Install dependencies
npm install sockjs-client @stomp/stompjs
```

Then use the demo script (see `demo/quiz-client.js` below).

### Option B: Using Browser Console (Quick Test)

1. Open your browser (Chrome/Firefox)
2. Navigate to any webpage
3. Open Developer Console (F12)
4. Paste the browser test script (see below)

### Option C: Using wscat (Command Line)

```bash
# Install wscat globally
npm install -g wscat

# Connect to WebSocket
wscat -c ws://localhost:8080/ws/quiz

# After connection, you'll need to manually send STOMP frames
# (This is more complex - use Option A or B instead)
```

---

## Demo Scripts

### Node.js Demo Client

Create `demo/quiz-client.js`:

```javascript
const SockJS = require('sockjs-client');
const { Client } = require('@stomp/stompjs');

// Configuration
const SERVER_URL = process.env.SERVER_URL || 'http://localhost:8080';
const QUIZ_ID = process.env.QUIZ_ID || 'quiz123';
const USER_ID = process.env.USER_ID || `user-${Math.random().toString(36).substr(2, 9)}`;

console.log(`🎮 Quiz Client Starting...`);
console.log(`   Server: ${SERVER_URL}`);
console.log(`   Quiz ID: ${QUIZ_ID}`);
console.log(`   User ID: ${USER_ID}`);
console.log('');

// Create STOMP client
const client = new Client({
  webSocketFactory: () => new SockJS(`${SERVER_URL}/ws/quiz`),
  
  onConnect: (frame) => {
    console.log('✅ Connected to server!');
    console.log('');
    
    // Subscribe to quiz broadcast messages
    client.subscribe(`/topic/quiz/${QUIZ_ID}`, (message) => {
      const data = JSON.parse(message.body);
      
      if (data.type === 'LEADERBOARD_UPDATE') {
        console.log('📊 LEADERBOARD UPDATE:');
        console.log('─────────────────────────────────────');
        data.leaderboard.forEach((entry, index) => {
          const medal = index === 0 ? '🥇' : index === 1 ? '🥈' : index === 2 ? '🥉' : '  ';
          console.log(`${medal} #${entry.rank} ${entry.userId.padEnd(20)} ${entry.score} points`);
        });
        console.log('─────────────────────────────────────');
        console.log('');
      }
    });
    
    // Subscribe to personal messages
    client.subscribe('/user/queue/reply', (message) => {
      const data = JSON.parse(message.body);
      
      switch (data.type) {
        case 'JOIN_SUCCESS':
          console.log(`✅ Successfully joined quiz: ${data.quizId}`);
          console.log('');
          break;
          
        case 'ANSWER_RESULT':
          const emoji = data.correct ? '✅' : '❌';
          console.log(`${emoji} Answer: ${data.correct ? 'CORRECT' : 'INCORRECT'}`);
          console.log(`   New Score: ${data.newScore} points`);
          console.log('');
          break;
          
        case 'ERROR':
          console.error(`❌ Error: ${data.error}`);
          if (data.details) {
            console.error(`   Details: ${data.details}`);
          }
          console.log('');
          break;
      }
    });
    
    // Join the quiz
    console.log(`📝 Joining quiz ${QUIZ_ID}...`);
    client.publish({
      destination: '/app/quiz/join',
      body: JSON.stringify({
        type: 'JOIN',
        quizId: QUIZ_ID,
        userId: USER_ID
      })
    });
    
    // Submit answers periodically
    let answerCount = 0;
    const answerInterval = setInterval(() => {
      answerCount++;
      
      // Random correct/incorrect (70% correct)
      const correct = Math.random() > 0.3;
      
      console.log(`📤 Submitting answer #${answerCount} (${correct ? 'correct' : 'incorrect'})...`);
      client.publish({
        destination: '/app/quiz/submit',
        body: JSON.stringify({
          type: 'SUBMIT_ANSWER',
          quizId: QUIZ_ID,
          userId: USER_ID,
          correct: correct
        })
      });
      
      // Stop after 10 answers
      if (answerCount >= 10) {
        clearInterval(answerInterval);
        console.log('');
        console.log('✅ Demo complete! (Submitted 10 answers)');
        console.log('   Press Ctrl+C to exit');
      }
    }, 3000); // Submit every 3 seconds
  },
  
  onStompError: (frame) => {
    console.error('❌ STOMP Error:', frame.headers['message']);
    console.error('   Details:', frame.body);
  },
  
  onWebSocketClose: () => {
    console.log('🔌 Disconnected from server');
  },
  
  debug: (str) => {
    // Uncomment to see STOMP protocol details
    // console.log('DEBUG:', str);
  }
});

// Handle graceful shutdown
process.on('SIGINT', () => {
  console.log('');
  console.log('👋 Shutting down...');
  client.deactivate();
  process.exit(0);
});

// Activate the client
client.activate();
```

### Browser Console Script

Paste this in your browser console:

```javascript
// Load required libraries
const script1 = document.createElement('script');
script1.src = 'https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js';
document.head.appendChild(script1);

const script2 = document.createElement('script');
script2.src = 'https://cdn.jsdelivr.net/npm/@stomp/stompjs@7/bundles/stomp.umd.min.js';
document.head.appendChild(script2);

// Wait for libraries to load
setTimeout(() => {
  const userId = 'user-' + Math.random().toString(36).substr(2, 9);
  const quizId = 'quiz123';
  
  console.log('🎮 Starting Quiz Client...');
  console.log('User ID:', userId);
  console.log('Quiz ID:', quizId);
  
  const client = new StompJs.Client({
    brokerURL: 'ws://localhost:8080/ws/quiz',
    
    onConnect: () => {
      console.log('✅ Connected!');
      
      // Subscribe to leaderboard updates
      client.subscribe(`/topic/quiz/${quizId}`, (msg) => {
        const data = JSON.parse(msg.body);
        if (data.type === 'LEADERBOARD_UPDATE') {
          console.log('📊 LEADERBOARD:');
          data.leaderboard.forEach(e => {
            console.log(`  #${e.rank} ${e.userId}: ${e.score} pts`);
          });
        }
      });
      
      // Subscribe to personal messages
      client.subscribe('/user/queue/reply', (msg) => {
        const data = JSON.parse(msg.body);
        console.log('📨', data);
      });
      
      // Join quiz
      client.publish({
        destination: '/app/quiz/join',
        body: JSON.stringify({
          type: 'JOIN',
          quizId: quizId,
          userId: userId
        })
      });
      
      // Submit answers every 3 seconds
      let count = 0;
      window.answerInterval = setInterval(() => {
        count++;
        const correct = Math.random() > 0.3;
        
        console.log(`📤 Submitting answer #${count} (${correct ? 'correct' : 'incorrect'})`);
        client.publish({
          destination: '/app/quiz/submit',
          body: JSON.stringify({
            type: 'SUBMIT_ANSWER',
            quizId: quizId,
            userId: userId,
            correct: correct
          })
        });
        
        if (count >= 10) {
          clearInterval(window.answerInterval);
          console.log('✅ Demo complete!');
        }
      }, 3000);
    },
    
    onStompError: (frame) => {
      console.error('❌ Error:', frame);
    }
  });
  
  client.activate();
  window.quizClient = client;
  
  console.log('💡 Tip: Use window.quizClient to interact manually');
}, 2000);
```

---

## Running the Demo

### Scenario 1: Single User Demo

1. Start Redis (Step 1)
2. Start application (Step 3 - single instance)
3. Run browser console script (Step 5, Option B)
4. Watch the console for:
   - Connection confirmation
   - JOIN_SUCCESS message
   - LEADERBOARD_UPDATE messages
   - ANSWER_RESULT messages

### Scenario 2: Multi-User Demo (Same Instance)

1. Start Redis
2. Start application (single instance)
3. Open 3 browser tabs
4. Run the browser console script in each tab
5. Watch all tabs receive leaderboard updates in real-time

### Scenario 3: Multi-Instance Demo (Advanced)

1. Start Redis
2. Start 3 application instances (ports 8080, 8081, 8082)
3. Open 3 terminals for Node.js clients:

**Terminal A:**
```bash
cd demo
USER_ID=alice SERVER_URL=http://localhost:8080 node quiz-client.js
```

**Terminal B:**
```bash
cd demo
USER_ID=bob SERVER_URL=http://localhost:8081 node quiz-client.js
```

**Terminal C:**
```bash
cd demo
USER_ID=charlie SERVER_URL=http://localhost:8082 node quiz-client.js
```

4. Watch all clients receive updates from all instances!

---

## What to Observe

### ✅ Expected Behavior

1. **User Joins:**
   - Client receives `JOIN_SUCCESS` message
   - All clients receive `LEADERBOARD_UPDATE` with new user at 0 points

2. **User Submits Correct Answer:**
   - Client receives `ANSWER_RESULT` with `correct: true` and `newScore: 10`
   - All clients receive `LEADERBOARD_UPDATE` with updated scores
   - Leaderboard is sorted by score (highest first)

3. **User Submits Incorrect Answer:**
   - Client receives `ANSWER_RESULT` with `correct: false` and same score
   - No leaderboard update (score didn't change)

4. **Multi-Instance Sync:**
   - Users on different instances see the same leaderboard
   - Updates propagate in < 100ms
   - Redis Pub/Sub monitor shows events being published

### 📊 Monitoring

**Application Logs:**
```bash
# Watch for these log messages:
# - "User user123 joined quiz quiz123"
# - "Published USER_JOINED event"
# - "Received event from channel quiz:quiz123:events"
# - "Broadcasting leaderboard update"
```

**Redis Monitor:**
```bash
# Should see:
# - ZADD commands (adding users to leaderboard)
# - ZINCRBY commands (incrementing scores)
# - PUBLISH commands (publishing events)
```

**Metrics Endpoint:**
```bash
# Check metrics
curl http://localhost:8080/actuator/metrics/quiz.websocket.connections

# Check health
curl http://localhost:8080/actuator/health
```

---

## Troubleshooting

### Issue: Cannot connect to WebSocket

**Solution:**
```bash
# Check if application is running
curl http://localhost:8080/actuator/health

# Check logs for errors
# Look for "Started EnglishApplication" message
```

### Issue: Redis connection failed

**Solution:**
```bash
# Test Redis connection
redis-cli PING

# Check Redis is running
docker ps | grep redis

# Check application logs for Redis errors
```

### Issue: No leaderboard updates

**Solution:**
```bash
# Check Redis Pub/Sub
redis-cli PSUBSCRIBE "quiz:*:events"

# Submit an answer and verify event is published
# Check application logs for "Published SCORE_UPDATED event"
```

### Issue: Rate limit exceeded

**Solution:**
```bash
# Reduce answer submission frequency in demo script
# OR increase rate limit in application.properties:
# quiz.rate-limit.capacity=20
```

---

## Next Steps

After running the demo:

1. ✅ **Explore the code** - See how WebSocket, Redis, and Pub/Sub work together
2. ✅ **Test failure scenarios** - Stop Redis and see circuit breaker in action
3. ✅ **Monitor metrics** - Check `/actuator/prometheus` for production metrics
4. ✅ **Scale horizontally** - Add more instances and see seamless synchronization
5. ✅ **Build a UI** - Create a React/Vue frontend using the WebSocket client code

---

## Demo Cleanup

```bash
# Stop application (Ctrl+C in each terminal)

# Stop Redis (Docker)
docker stop quiz-redis
docker rm quiz-redis

# OR stop Redis (local)
redis-cli SHUTDOWN
```

---

**Happy Testing! 🚀**

