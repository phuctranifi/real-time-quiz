# WebSocket Message Formats Reference

## Quick Reference

This document provides a quick reference for all WebSocket message formats used in the quiz platform.

---

## Client → Server Messages

### 1. JOIN

**Destination:** `/app/quiz/join`

**Format:**
```json
{
  "type": "JOIN",
  "quizId": "quiz123",
  "userId": "user456"
}
```

**Response:** `JOIN_SUCCESS` (personal) + `LEADERBOARD_UPDATE` (broadcast)

---

### 2. SUBMIT_ANSWER

**Destination:** `/app/quiz/submit`

**Format:**
```json
{
  "type": "SUBMIT_ANSWER",
  "quizId": "quiz123",
  "userId": "user456",
  "correct": true
}
```

**Response:** `ANSWER_RESULT` (personal) + `LEADERBOARD_UPDATE` (broadcast)

---

## Server → Client Messages

### 1. JOIN_SUCCESS (Personal Response)

**Destination:** `/user/{sessionId}/queue/reply`

**Format:**
```json
{
  "type": "JOIN_SUCCESS",
  "quizId": "quiz123",
  "userId": "user456",
  "message": "Successfully joined quiz quiz123"
}
```

**Recipient:** Only the user who joined

---

### 2. ANSWER_RESULT (Personal Response)

**Destination:** `/user/{sessionId}/queue/reply`

**Format:**
```json
{
  "type": "ANSWER_RESULT",
  "quizId": "quiz123",
  "userId": "user456",
  "correct": true,
  "newScore": 50
}
```

**Recipient:** Only the user who submitted the answer

---

### 3. LEADERBOARD_UPDATE (Broadcast)

**Destination:** `/topic/quiz/{quizId}`

**Format:**
```json
{
  "type": "LEADERBOARD_UPDATE",
  "quizId": "quiz123",
  "leaderboard": [
    {
      "userId": "user1",
      "score": 100,
      "rank": 1
    },
    {
      "userId": "user2",
      "score": 80,
      "rank": 2
    },
    {
      "userId": "user3",
      "score": 60,
      "rank": 3
    },
    {
      "userId": "user4",
      "score": 40,
      "rank": 4
    },
    {
      "userId": "user5",
      "score": 20,
      "rank": 5
    }
  ]
}
```

**Recipients:** All users subscribed to the quiz (across all instances)

**Note:** Only top N entries are included (default: 10, configurable)

---

### 4. ERROR (Personal Response)

**Destination:** `/user/{sessionId}/queue/reply`

**Format:**
```json
{
  "type": "ERROR",
  "error": "Invalid quiz ID",
  "details": null
}
```

**Recipient:** Only the user who triggered the error

---

## Redis Pub/Sub Events (Internal)

These events are used for cross-instance communication and are NOT sent directly to clients.

### 1. USER_JOINED Event

**Channel:** `quiz:{quizId}:events`

**Format:**
```json
{
  "type": "USER_JOINED",
  "quizId": "quiz123",
  "userId": "user456",
  "score": null,
  "timestamp": "2026-01-27T10:30:45.123Z",
  "sourceInstanceId": "instance-a"
}
```

**Subscribers:** All backend instances

**Action:** Each instance fetches leaderboard and broadcasts to its clients

---

### 2. SCORE_UPDATED Event

**Channel:** `quiz:{quizId}:events`

**Format:**
```json
{
  "type": "SCORE_UPDATED",
  "quizId": "quiz123",
  "userId": "user456",
  "score": 50,
  "timestamp": "2026-01-27T10:30:45.123Z",
  "sourceInstanceId": "instance-a"
}
```

**Subscribers:** All backend instances

**Action:** Each instance fetches leaderboard and broadcasts to its clients

---

## JavaScript Client Example

### Complete Client Implementation

```javascript
// Import SockJS and StompJS
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';

// Create WebSocket client
const client = new Client({
  // Use SockJS for fallback support
  webSocketFactory: () => new SockJS('http://localhost:8080/ws/quiz'),
  
  // Connection callback
  onConnect: (frame) => {
    console.log('Connected:', frame);
    
    // Subscribe to quiz updates (broadcast)
    client.subscribe('/topic/quiz/quiz123', (message) => {
      const data = JSON.parse(message.body);
      console.log('Broadcast message:', data);
      
      if (data.type === 'LEADERBOARD_UPDATE') {
        updateLeaderboardUI(data.leaderboard);
      }
    });
    
    // Subscribe to personal responses
    client.subscribe('/user/queue/reply', (message) => {
      const data = JSON.parse(message.body);
      console.log('Personal message:', data);
      
      switch (data.type) {
        case 'JOIN_SUCCESS':
          console.log('Joined quiz:', data.quizId);
          break;
        case 'ANSWER_RESULT':
          console.log('Answer result:', data.correct ? 'Correct!' : 'Incorrect');
          console.log('New score:', data.newScore);
          break;
        case 'ERROR':
          console.error('Error:', data.error);
          break;
      }
    });
    
    // Join quiz
    joinQuiz('quiz123', 'user456');
  },
  
  // Error callback
  onStompError: (frame) => {
    console.error('STOMP error:', frame);
  },
  
  // Disconnect callback
  onDisconnect: () => {
    console.log('Disconnected');
  }
});

// Activate the client
client.activate();

// Helper functions
function joinQuiz(quizId, userId) {
  client.publish({
    destination: '/app/quiz/join',
    body: JSON.stringify({
      type: 'JOIN',
      quizId: quizId,
      userId: userId
    })
  });
}

function submitAnswer(quizId, userId, correct) {
  client.publish({
    destination: '/app/quiz/submit',
    body: JSON.stringify({
      type: 'SUBMIT_ANSWER',
      quizId: quizId,
      userId: userId,
      correct: correct
    })
  });
}

function updateLeaderboardUI(leaderboard) {
  console.log('Leaderboard updated:');
  leaderboard.forEach(entry => {
    console.log(`${entry.rank}. ${entry.userId}: ${entry.score} points`);
  });
}

// Example usage
setTimeout(() => {
  // Submit a correct answer after 2 seconds
  submitAnswer('quiz123', 'user456', true);
}, 2000);
```

---

## Message Flow Examples

### Example 1: User Joins Quiz

```
Client                          Server
  │                               │
  │  1. STOMP CONNECT             │
  ├──────────────────────────────>│
  │                               │
  │  2. CONNECTED                 │
  │<──────────────────────────────┤
  │                               │
  │  3. SUBSCRIBE /topic/quiz/... │
  ├──────────────────────────────>│
  │                               │
  │  4. SUBSCRIBE /user/queue/... │
  ├──────────────────────────────>│
  │                               │
  │  5. SEND /app/quiz/join       │
  │     {type: JOIN, ...}         │
  ├──────────────────────────────>│
  │                               │
  │  6. JOIN_SUCCESS (personal)   │
  │<──────────────────────────────┤
  │                               │
  │  7. LEADERBOARD_UPDATE        │
  │     (broadcast to all)        │
  │<══════════════════════════════┤
  │                               │
```

---

### Example 2: User Submits Answer

```
Client                          Server
  │                               │
  │  1. SEND /app/quiz/submit     │
  │     {type: SUBMIT_ANSWER,     │
  │      correct: true}           │
  ├──────────────────────────────>│
  │                               │
  │  2. ANSWER_RESULT (personal)  │
  │     {newScore: 50}            │
  │<──────────────────────────────┤
  │                               │
  │  3. LEADERBOARD_UPDATE        │
  │     (broadcast to all)        │
  │<══════════════════════════════┤
  │                               │
```

**Note:** The `LEADERBOARD_UPDATE` is sent to ALL users in the quiz, across ALL instances.

---

## Testing with Browser Console

### Quick Test Script

```javascript
// Paste this in browser console

// 1. Load libraries (if not already loaded)
const script1 = document.createElement('script');
script1.src = 'https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js';
document.head.appendChild(script1);

const script2 = document.createElement('script');
script2.src = 'https://cdn.jsdelivr.net/npm/@stomp/stompjs@7/bundles/stomp.umd.min.js';
document.head.appendChild(script2);

// 2. Wait for libraries to load, then run:
setTimeout(() => {
  const client = new StompJs.Client({
    brokerURL: 'ws://localhost:8080/ws/quiz',
    onConnect: () => {
      console.log('✅ Connected!');
      
      // Subscribe to quiz updates
      client.subscribe('/topic/quiz/quiz123', (msg) => {
        console.log('📢 Broadcast:', JSON.parse(msg.body));
      });
      
      // Subscribe to personal messages
      client.subscribe('/user/queue/reply', (msg) => {
        console.log('📨 Personal:', JSON.parse(msg.body));
      });
      
      // Join quiz
      client.publish({
        destination: '/app/quiz/join',
        body: JSON.stringify({
          type: 'JOIN',
          quizId: 'quiz123',
          userId: 'testuser'
        })
      });
      
      // Submit answer after 2 seconds
      setTimeout(() => {
        client.publish({
          destination: '/app/quiz/submit',
          body: JSON.stringify({
            type: 'SUBMIT_ANSWER',
            quizId: 'quiz123',
            userId: 'testuser',
            correct: true
          })
        });
      }, 2000);
    }
  });
  
  client.activate();
  
  // Store client globally for manual testing
  window.quizClient = client;
}, 2000);

// 3. Manual commands (after connection):
// window.quizClient.publish({
//   destination: '/app/quiz/submit',
//   body: JSON.stringify({type: 'SUBMIT_ANSWER', quizId: 'quiz123', userId: 'testuser', correct: true})
// });
```

---

## Configuration

### Server Configuration

**File:** `application.properties`

```properties
# Number of top users in leaderboard broadcasts
quiz.leaderboard.top-n=10

# WebSocket allowed origins (production)
# spring.websocket.allowed-origins=https://yourdomain.com
```

---

## Summary

### Message Types

| Type | Direction | Destination | Recipient |
|------|-----------|-------------|-----------|
| JOIN | Client → Server | `/app/quiz/join` | - |
| SUBMIT_ANSWER | Client → Server | `/app/quiz/submit` | - |
| JOIN_SUCCESS | Server → Client | `/user/.../queue/reply` | Personal |
| ANSWER_RESULT | Server → Client | `/user/.../queue/reply` | Personal |
| LEADERBOARD_UPDATE | Server → Client | `/topic/quiz/{quizId}` | All (broadcast) |
| ERROR | Server → Client | `/user/.../queue/reply` | Personal |

### Key Points

✅ **Personal messages** go to `/user/{sessionId}/queue/reply`  
✅ **Broadcast messages** go to `/topic/quiz/{quizId}`  
✅ **Leaderboard updates** are sent to ALL users across ALL instances  
✅ **Top N entries** only (default: 10, configurable)  
✅ **Real-time sync** via Redis Pub/Sub  

---

**For complete integration details, see [INTEGRATION_FLOW.md](INTEGRATION_FLOW.md)**

