# WebSocket API Documentation

## Overview

The WebSocket layer provides real-time communication for quiz functionality using STOMP protocol over WebSocket.

**Endpoint:** `ws://localhost:8080/ws/quiz` (with SockJS fallback)

**Protocol:** STOMP over WebSocket

**Message Format:** JSON

---

## Architecture

### Multi-Instance Support

The WebSocket layer is designed for multi-instance deployment:

- **Session Registry**: Instance-local session tracking
- **Room Manager**: Instance-local room memberships
- **Service Layer**: Stateless, delegates to Redis for shared state
- **Leaderboard**: Stored in Redis (not in memory)

**Deployment Notes:**
- Configure sticky sessions at load balancer level
- OR use Redis Pub/Sub to broadcast events across instances
- Session state is instance-local; reconnections may go to different instances

### Component Separation

```
Client → WebSocket Controller → Service Layer → Redis
         ↓                       ↓
         Session Registry        Pub/Sub
         Room Manager            Sorted Sets (Leaderboard)
```

---

## Connection

### Endpoint

```
ws://localhost:8080/ws/quiz
```

With SockJS fallback:
```
http://localhost:8080/ws/quiz
```

### JavaScript Example

```javascript
const socket = new SockJS('http://localhost:8080/ws/quiz');
const stompClient = Stomp.over(socket);

stompClient.connect({}, function(frame) {
    console.log('Connected: ' + frame);
    
    // Subscribe to personal replies
    stompClient.subscribe('/user/queue/reply', function(message) {
        const response = JSON.parse(message.body);
        console.log('Received:', response);
    });
    
    // Subscribe to quiz room broadcasts
    stompClient.subscribe('/topic/quiz/quiz123', function(message) {
        const update = JSON.parse(message.body);
        console.log('Leaderboard update:', update);
    });
});
```

---

## Message Types

### Client → Server

#### 1. JOIN

Join a quiz room.

**Destination:** `/app/quiz/join`

**Payload:**
```json
{
  "type": "JOIN",
  "quizId": "quiz123",
  "userId": "user456"
}
```

**Response:** `JOIN_SUCCESS` or `ERROR` to `/user/queue/reply`

**Broadcast:** `LEADERBOARD_UPDATE` to `/topic/quiz/{quizId}`

---

#### 2. SUBMIT_ANSWER

Submit an answer for scoring.

**Destination:** `/app/quiz/submit`

**Payload:**
```json
{
  "type": "SUBMIT_ANSWER",
  "quizId": "quiz123",
  "userId": "user456",
  "correct": true
}
```

**Response:** `ANSWER_RESULT` to `/user/queue/reply`

**Broadcast:** `LEADERBOARD_UPDATE` to `/topic/quiz/{quizId}`

---

### Server → Client

#### 1. JOIN_SUCCESS

Confirmation of successful join.

**Destination:** `/user/queue/reply`

**Payload:**
```json
{
  "type": "JOIN_SUCCESS",
  "quizId": "quiz123",
  "userId": "user456",
  "message": "Successfully joined quiz quiz123"
}
```

---

#### 2. ANSWER_RESULT

Result of answer submission.

**Destination:** `/user/queue/reply`

**Payload:**
```json
{
  "type": "ANSWER_RESULT",
  "quizId": "quiz123",
  "userId": "user456",
  "correct": true,
  "newScore": 10
}
```

---

#### 3. LEADERBOARD_UPDATE

Broadcast leaderboard update to all participants.

**Destination:** `/topic/quiz/{quizId}`

**Payload:**
```json
{
  "type": "LEADERBOARD_UPDATE",
  "quizId": "quiz123",
  "leaderboard": [
    {
      "userId": "user456",
      "score": 100,
      "rank": 1
    },
    {
      "userId": "user789",
      "score": 80,
      "rank": 2
    }
  ]
}
```

---

#### 4. ERROR

Error message.

**Destination:** `/user/queue/reply`

**Payload:**
```json
{
  "type": "ERROR",
  "error": "Invalid quiz ID",
  "details": null
}
```

---

## Complete Client Example

```javascript
const socket = new SockJS('http://localhost:8080/ws/quiz');
const stompClient = Stomp.over(socket);

const quizId = 'quiz123';
const userId = 'user456';

stompClient.connect({}, function(frame) {
    console.log('Connected');
    
    // Subscribe to personal replies
    stompClient.subscribe('/user/queue/reply', function(message) {
        const response = JSON.parse(message.body);
        handleResponse(response);
    });
    
    // Subscribe to quiz room broadcasts
    stompClient.subscribe('/topic/quiz/' + quizId, function(message) {
        const update = JSON.parse(message.body);
        if (update.type === 'LEADERBOARD_UPDATE') {
            updateLeaderboard(update.leaderboard);
        }
    });
    
    // Join the quiz
    stompClient.send('/app/quiz/join', {}, JSON.stringify({
        type: 'JOIN',
        quizId: quizId,
        userId: userId
    }));
});

function submitAnswer(correct) {
    stompClient.send('/app/quiz/submit', {}, JSON.stringify({
        type: 'SUBMIT_ANSWER',
        quizId: quizId,
        userId: userId,
        correct: correct
    }));
}

function handleResponse(response) {
    switch(response.type) {
        case 'JOIN_SUCCESS':
            console.log('Joined successfully:', response.message);
            break;
        case 'ANSWER_RESULT':
            console.log('Answer result:', response.correct, 'New score:', response.newScore);
            break;
        case 'ERROR':
            console.error('Error:', response.error);
            break;
    }
}

function updateLeaderboard(leaderboard) {
    console.log('Leaderboard:', leaderboard);
    // Update UI with leaderboard data
}
```

---

## Implementation Details

### Components

1. **QuizWebSocketController** - Handles incoming messages, routes to service layer
2. **WebSocketSessionRegistry** - Tracks active sessions (instance-local)
3. **QuizRoomManager** - Manages room memberships (instance-local)
4. **WebSocketEventListener** - Handles connect/disconnect events
5. **QuizService** - Business logic interface (stateless)

### Message Flow

```
1. Client connects to /ws/quiz
2. Client subscribes to /user/queue/reply and /topic/quiz/{quizId}
3. Client sends JOIN message to /app/quiz/join
4. Server validates, updates registry/room, calls service
5. Server sends JOIN_SUCCESS to client
6. Server broadcasts LEADERBOARD_UPDATE to room
7. Client sends SUBMIT_ANSWER to /app/quiz/submit
8. Server validates, calls service, updates Redis
9. Server sends ANSWER_RESULT to client
10. Server broadcasts LEADERBOARD_UPDATE to room
```

### Error Handling

All errors are sent as ERROR messages to `/user/queue/reply`:
- Invalid quiz ID
- Invalid user ID
- User not in quiz room
- Service layer exceptions

---

## Testing

### Manual Testing with wscat

```bash
# Install wscat
npm install -g wscat

# Connect
wscat -c ws://localhost:8080/ws/quiz

# Send STOMP frames manually (complex, use JavaScript client instead)
```

### Recommended: Use JavaScript client in browser console

See complete client example above.

---

## Next Steps

1. **Implement QuizService** - Replace stub with Redis-backed implementation
2. **Add Redis Pub/Sub** - Broadcast events across instances
3. **Add Authentication** - Validate user tokens
4. **Add Rate Limiting** - Prevent spam
5. **Add Metrics** - Track connections, messages, errors

