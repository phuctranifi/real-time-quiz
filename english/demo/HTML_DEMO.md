# HTML Demo Guide

## 🎯 Interactive HTML Demo for Quiz Platform

Two beautiful, interactive HTML pages to test the quiz platform with real-time leaderboard updates!

---

## 📁 Files

- **`user1.html`** - Purple theme, default username: "alice"
- **`user2.html`** - Pink theme, default username: "bob"

---

## 🚀 Quick Start

### 1. Make Sure the Server is Running

```bash
# In the project root
./gradlew bootRun
```

Wait for the message: `Started EnglishApplication`

### 2. Open the HTML Files

**Option A: Open in Browser Directly**
```bash
# macOS
open demo/user1.html
open demo/user2.html

# Linux
xdg-open demo/user1.html
xdg-open demo/user2.html

# Windows
start demo/user1.html
start demo/user2.html
```

**Option B: Drag and Drop**
- Drag `demo/user1.html` into your browser
- Drag `demo/user2.html` into another browser window

### 3. Connect and Play!

**In User 1 window (alice):**
1. Click "Connect & Join" button
2. Wait for "🟢 Connected" status
3. Click "✅ Correct Answer" or "❌ Incorrect Answer" buttons
4. Watch the leaderboard update in real-time!

**In User 2 window (bob):**
1. Click "Connect & Join" button
2. Submit answers
3. See both users on the leaderboard!

---

## 🎮 Features

### ✅ What You Can Do

1. **Connect to Quiz**
   - Enter username (default: alice/bob)
   - Enter quiz ID (default: quiz123)
   - Click "Connect & Join"

2. **Submit Answers**
   - Click "✅ Correct Answer" for +10 points
   - Click "❌ Incorrect Answer" for 0 points
   - See instant feedback in the log

3. **View Real-Time Leaderboard**
   - See all users ranked by score
   - Your entry is highlighted
   - Top 3 get medals (🥇🥈🥉)
   - Updates automatically when anyone submits

4. **Track Your Stats**
   - Current score
   - Correct answers count
   - Incorrect answers count

5. **Monitor Activity**
   - Real-time log at the bottom
   - Color-coded messages (success/error/info)
   - Timestamps for all events

---

## 🎨 UI Features

### User 1 (alice)
- **Theme:** Purple gradient (elegant)
- **Color:** #667eea → #764ba2
- **Default Username:** alice

### User 2 (bob)
- **Theme:** Pink gradient (vibrant)
- **Color:** #f093fb → #f5576c
- **Default Username:** bob

### Common Features
- ✅ Responsive design
- ✅ Smooth animations
- ✅ Color-coded status indicators
- ✅ Real-time updates
- ✅ Medal system for top 3
- ✅ Activity log with timestamps

---

## 📊 Demo Scenarios

### Scenario 1: Single User Testing

1. Open `user1.html`
2. Click "Connect & Join"
3. Submit several correct answers
4. Watch your score increase
5. See yourself on the leaderboard

### Scenario 2: Two Users Competing

1. Open `user1.html` in one browser window
2. Open `user2.html` in another window
3. Connect both users
4. Submit answers from both windows
5. Watch the leaderboard update in real-time on BOTH windows!

**Try this:**
- Alice submits 3 correct answers (30 points)
- Bob submits 5 correct answers (50 points)
- See Bob move to #1 on both screens!

### Scenario 3: Multiple Users (Same Quiz)

1. Open `user1.html` - keep username "alice"
2. Open `user2.html` - keep username "bob"
3. Open `user1.html` again - change username to "charlie"
4. Connect all three
5. Submit answers from different windows
6. Watch the leaderboard update everywhere!

### Scenario 4: Testing Different Quizzes

1. Open `user1.html` - use quiz ID "quiz123"
2. Open `user2.html` - change quiz ID to "quiz456"
3. Connect both
4. Notice they have separate leaderboards!

---

## 🔍 What to Observe

### ✅ Expected Behavior

**When you connect:**
```
[timestamp] Connecting to WebSocket...
[timestamp] Connected to server!
[timestamp] Broadcast: LEADERBOARD_UPDATE
[timestamp] Leaderboard updated (1 entries)
```

**When you submit a correct answer:**
```
[timestamp] Submitting correct answer...
[timestamp] ✅ Correct! New score: 10
[timestamp] Broadcast: LEADERBOARD_UPDATE
[timestamp] Leaderboard updated (2 entries)
```

**Leaderboard updates:**
- Your entry is highlighted in color
- Entries are sorted by score (highest first)
- Top 3 get medals
- Updates appear on ALL connected clients

---

## 🐛 Troubleshooting

### Issue: "Connection Failed"

**Check:**
1. Is the Spring Boot application running?
   ```bash
   curl http://localhost:8080/actuator/health
   ```
2. Is Redis running?
   ```bash
   redis-cli PING
   ```

**Solution:**
```bash
# Start Redis
redis-server &

# Start application
./gradlew bootRun
```

### Issue: "Answer buttons are disabled"

**Reason:** The JOIN_SUCCESS message might not be received (known issue with personal messages).

**Workaround:** The answer buttons should enable after joining. If not, the leaderboard updates still work! Just click the buttons and watch the leaderboard update.

### Issue: "Leaderboard not updating"

**Check the log:**
- Do you see "Broadcast: LEADERBOARD_UPDATE" messages?
- If yes, the system is working!

**Refresh the page and reconnect if needed.**

### Issue: "CORS errors in console"

**Solution:** Make sure you're accessing the HTML files via `file://` protocol or a local web server. The WebSocket connection to `localhost:8080` should work from any origin.

---

## 🎯 Testing Checklist

Use this checklist to verify all features:

- [ ] User 1 can connect
- [ ] User 2 can connect
- [ ] Both users appear on leaderboard
- [ ] Submitting correct answer increases score
- [ ] Submitting incorrect answer keeps score same
- [ ] Leaderboard updates in real-time on both windows
- [ ] Leaderboard is sorted by score
- [ ] Current user is highlighted
- [ ] Top 3 get medals
- [ ] Stats update correctly
- [ ] Log shows all events
- [ ] Disconnect works
- [ ] Reconnect works

---

## 📸 Screenshots

### User 1 (Alice) - Purple Theme
```
╔════════════════════════════════════════╗
║         🎯 Quiz Platform               ║
║         User 1 (Port 8080)             ║
╚════════════════════════════════════════╝

🟢 Connected

Username: alice
Quiz ID: quiz123

[Connect & Join] [Disconnect]

┌─────────────────────────────────────┐
│ Your Score    Correct    Incorrect  │
│     30           3           1      │
└─────────────────────────────────────┘

Submit Answer
[✅ Correct Answer] [❌ Incorrect Answer]

🏆 Leaderboard
🥇 #1 alice (You)        30 pts
🥈 #2 bob                20 pts
```

### User 2 (Bob) - Pink Theme
```
╔════════════════════════════════════════╗
║         🎯 Quiz Platform               ║
║         User 2 (Port 8080)             ║
╚════════════════════════════════════════╝

🟢 Connected

Username: bob
Quiz ID: quiz123

[Connect & Join] [Disconnect]

┌─────────────────────────────────────┐
│ Your Score    Correct    Incorrect  │
│     20           2           0      │
└─────────────────────────────────────┘

Submit Answer
[✅ Correct Answer] [❌ Incorrect Answer]

🏆 Leaderboard
🥇 #1 alice              30 pts
🥈 #2 bob (You)          20 pts
```

---

## 🚀 Advanced Usage

### Custom Usernames

Change the username in the input field before connecting:
- user1.html: Change "alice" to any name
- user2.html: Change "bob" to any name

### Different Quiz IDs

Test multiple quizzes simultaneously:
- Window 1: quiz123
- Window 2: quiz456
- Each quiz has its own leaderboard!

### Monitor Redis Events

While testing, watch Redis events in a terminal:
```bash
redis-cli PSUBSCRIBE "quiz:*:events"
```

You'll see:
```
1) "pmessage"
2) "quiz:*:events"
3) "quiz:quiz123:events"
4) "{\"type\":\"SCORE_UPDATED\",\"userId\":\"alice\",\"score\":30,...}"
```

### Check Leaderboard in Redis

```bash
redis-cli ZREVRANGE quiz:quiz123:leaderboard 0 -1 WITHSCORES
```

Output:
```
1) "alice"
2) "30"
3) "bob"
4) "20"
```

---

## 🎓 Learning Points

### What This Demonstrates

1. **WebSocket Real-Time Communication**
   - Bidirectional messaging
   - STOMP protocol over SockJS
   - Personal and broadcast channels

2. **Event-Driven Architecture**
   - User actions trigger events
   - Events broadcast to all clients
   - Leaderboard updates automatically

3. **Redis Integration**
   - Scores stored in Redis Sorted Sets
   - Atomic operations (ZINCRBY)
   - Pub/Sub for cross-instance sync

4. **Production Features**
   - Connection status monitoring
   - Error handling
   - Activity logging
   - Real-time statistics

---

## 📝 Next Steps

1. ✅ **Test the demo** - Open both HTML files and play!
2. ✅ **Customize** - Change colors, add features
3. ✅ **Build a real UI** - Use this as reference for React/Vue
4. ✅ **Add more features** - Questions, timer, chat, etc.

---

**Enjoy testing the quiz platform!** 🎉

**Files:**
- `demo/user1.html` - User 1 interface
- `demo/user2.html` - User 2 interface
- `demo/HTML_DEMO.md` - This guide

