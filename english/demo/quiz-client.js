#!/usr/bin/env node

/**
 * Quiz Platform WebSocket Demo Client
 * 
 * This script demonstrates real-time quiz functionality:
 * - Connects to WebSocket server
 * - Joins a quiz
 * - Submits answers periodically
 * - Displays real-time leaderboard updates
 * 
 * Usage:
 *   node quiz-client.js
 *   USER_ID=alice QUIZ_ID=quiz123 node quiz-client.js
 *   SERVER_URL=http://localhost:8081 node quiz-client.js
 */

const SockJS = require('sockjs-client');
const { Client } = require('@stomp/stompjs');

// Configuration from environment variables
const SERVER_URL = process.env.SERVER_URL || 'http://localhost:8080';
const QUIZ_ID = process.env.QUIZ_ID || 'quiz123';
const USER_ID = process.env.USER_ID || `user-${Math.random().toString(36).substr(2, 9)}`;
const ANSWER_INTERVAL = parseInt(process.env.ANSWER_INTERVAL || '3000'); // milliseconds
const MAX_ANSWERS = parseInt(process.env.MAX_ANSWERS || '10');

// Colors for terminal output
const colors = {
  reset: '\x1b[0m',
  bright: '\x1b[1m',
  green: '\x1b[32m',
  red: '\x1b[31m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  magenta: '\x1b[35m',
  cyan: '\x1b[36m'
};

// Print startup banner
console.log(colors.cyan + colors.bright);
console.log('╔════════════════════════════════════════════════════════════╗');
console.log('║         Quiz Platform WebSocket Demo Client               ║');
console.log('╚════════════════════════════════════════════════════════════╝');
console.log(colors.reset);
console.log(`${colors.bright}Configuration:${colors.reset}`);
console.log(`  Server URL:  ${colors.cyan}${SERVER_URL}${colors.reset}`);
console.log(`  Quiz ID:     ${colors.cyan}${QUIZ_ID}${colors.reset}`);
console.log(`  User ID:     ${colors.cyan}${USER_ID}${colors.reset}`);
console.log(`  Interval:    ${colors.cyan}${ANSWER_INTERVAL}ms${colors.reset}`);
console.log(`  Max Answers: ${colors.cyan}${MAX_ANSWERS}${colors.reset}`);
console.log('');

// Statistics
const stats = {
  connected: false,
  joined: false,
  answersSubmitted: 0,
  correctAnswers: 0,
  incorrectAnswers: 0,
  leaderboardUpdates: 0,
  currentScore: 0
};

// Create STOMP client
const client = new Client({
  webSocketFactory: () => {
    console.log(`${colors.yellow}⏳ Connecting to ${SERVER_URL}/ws/quiz...${colors.reset}`);
    return new SockJS(`${SERVER_URL}/ws/quiz`);
  },
  
  onConnect: (frame) => {
    stats.connected = true;
    console.log(`${colors.green}${colors.bright}✅ Connected to server!${colors.reset}`);
    console.log('');
    
    // Subscribe to quiz broadcast messages (leaderboard updates)
    client.subscribe(`/topic/quiz/${QUIZ_ID}`, (message) => {
      const data = JSON.parse(message.body);
      
      if (data.type === 'LEADERBOARD_UPDATE') {
        stats.leaderboardUpdates++;
        displayLeaderboard(data.leaderboard);
      }
    });
    
    // Subscribe to personal messages
    client.subscribe('/user/queue/reply', (message) => {
      const data = JSON.parse(message.body);
      
      switch (data.type) {
        case 'JOIN_SUCCESS':
          stats.joined = true;
          console.log(`${colors.green}✅ Successfully joined quiz: ${colors.bright}${data.quizId}${colors.reset}`);
          console.log('');
          
          // Start submitting answers after joining
          startAnswerSubmission();
          break;
          
        case 'ANSWER_RESULT':
          handleAnswerResult(data);
          break;
          
        case 'ERROR':
          console.error(`${colors.red}${colors.bright}❌ Error: ${data.error}${colors.reset}`);
          if (data.details) {
            console.error(`${colors.red}   Details: ${data.details}${colors.reset}`);
          }
          console.log('');
          break;
      }
    });
    
    // Join the quiz
    console.log(`${colors.yellow}📝 Joining quiz ${colors.bright}${QUIZ_ID}${colors.reset}${colors.yellow}...${colors.reset}`);
    client.publish({
      destination: '/app/quiz/join',
      body: JSON.stringify({
        type: 'JOIN',
        quizId: QUIZ_ID,
        userId: USER_ID
      })
    });
  },
  
  onStompError: (frame) => {
    console.error(`${colors.red}${colors.bright}❌ STOMP Error:${colors.reset} ${frame.headers['message']}`);
    console.error(`${colors.red}   Details: ${frame.body}${colors.reset}`);
    console.log('');
  },
  
  onWebSocketClose: (event) => {
    stats.connected = false;
    console.log(`${colors.yellow}🔌 Disconnected from server${colors.reset}`);
    if (event.reason) {
      console.log(`${colors.yellow}   Reason: ${event.reason}${colors.reset}`);
    }
  },
  
  onWebSocketError: (error) => {
    console.error(`${colors.red}❌ WebSocket Error:${colors.reset}`, error.message);
  },
  
  // Uncomment to see STOMP protocol details
  debug: (str) => {
    // console.log('DEBUG:', str);
  }
});

// Display leaderboard in a nice format
function displayLeaderboard(leaderboard) {
  console.log(`${colors.blue}${colors.bright}📊 LEADERBOARD UPDATE #${stats.leaderboardUpdates}${colors.reset}`);
  console.log(colors.blue + '─'.repeat(60) + colors.reset);
  
  leaderboard.forEach((entry, index) => {
    const medal = index === 0 ? '🥇' : index === 1 ? '🥈' : index === 2 ? '🥉' : '  ';
    const isMe = entry.userId === USER_ID;
    const color = isMe ? colors.green + colors.bright : colors.reset;
    const arrow = isMe ? ' ← YOU' : '';
    
    console.log(
      `${color}${medal} #${entry.rank.toString().padStart(2)} ` +
      `${entry.userId.padEnd(25)} ` +
      `${entry.score.toString().padStart(4)} points${arrow}${colors.reset}`
    );
    
    // Update our current score
    if (isMe) {
      stats.currentScore = entry.score;
    }
  });
  
  console.log(colors.blue + '─'.repeat(60) + colors.reset);
  console.log('');
}

// Handle answer result
function handleAnswerResult(data) {
  const emoji = data.correct ? '✅' : '❌';
  const color = data.correct ? colors.green : colors.red;
  const result = data.correct ? 'CORRECT' : 'INCORRECT';
  
  if (data.correct) {
    stats.correctAnswers++;
  } else {
    stats.incorrectAnswers++;
  }
  
  console.log(`${color}${emoji} Answer #${stats.answersSubmitted}: ${colors.bright}${result}${colors.reset}`);
  console.log(`${color}   New Score: ${colors.bright}${data.newScore} points${colors.reset}${color} (+${data.newScore - stats.currentScore})${colors.reset}`);
  console.log('');
}

// Start submitting answers periodically
function startAnswerSubmission() {
  const answerInterval = setInterval(() => {
    if (stats.answersSubmitted >= MAX_ANSWERS) {
      clearInterval(answerInterval);
      displayFinalStats();
      return;
    }
    
    stats.answersSubmitted++;
    
    // Random correct/incorrect (70% correct rate)
    const correct = Math.random() > 0.3;
    
    console.log(`${colors.yellow}📤 Submitting answer #${stats.answersSubmitted}/${MAX_ANSWERS} (${correct ? 'correct' : 'incorrect'})...${colors.reset}`);
    
    client.publish({
      destination: '/app/quiz/submit',
      body: JSON.stringify({
        type: 'SUBMIT_ANSWER',
        quizId: QUIZ_ID,
        userId: USER_ID,
        correct: correct
      })
    });
  }, ANSWER_INTERVAL);
}

// Display final statistics
function displayFinalStats() {
  console.log('');
  console.log(colors.cyan + colors.bright);
  console.log('╔════════════════════════════════════════════════════════════╗');
  console.log('║                    Demo Complete!                          ║');
  console.log('╚════════════════════════════════════════════════════════════╝');
  console.log(colors.reset);
  console.log(`${colors.bright}Statistics:${colors.reset}`);
  console.log(`  Total Answers:       ${colors.cyan}${stats.answersSubmitted}${colors.reset}`);
  console.log(`  Correct Answers:     ${colors.green}${stats.correctAnswers}${colors.reset}`);
  console.log(`  Incorrect Answers:   ${colors.red}${stats.incorrectAnswers}${colors.reset}`);
  console.log(`  Accuracy:            ${colors.cyan}${((stats.correctAnswers / stats.answersSubmitted) * 100).toFixed(1)}%${colors.reset}`);
  console.log(`  Final Score:         ${colors.bright}${colors.green}${stats.currentScore} points${colors.reset}`);
  console.log(`  Leaderboard Updates: ${colors.cyan}${stats.leaderboardUpdates}${colors.reset}`);
  console.log('');
  console.log(`${colors.yellow}Press Ctrl+C to exit${colors.reset}`);
}

// Handle graceful shutdown
process.on('SIGINT', () => {
  console.log('');
  console.log(`${colors.yellow}👋 Shutting down...${colors.reset}`);
  
  if (stats.connected) {
    client.deactivate();
  }
  
  console.log(`${colors.green}✅ Goodbye!${colors.reset}`);
  process.exit(0);
});

// Handle uncaught errors
process.on('uncaughtException', (error) => {
  console.error(`${colors.red}${colors.bright}❌ Uncaught Exception:${colors.reset}`, error.message);
  console.error(error.stack);
  process.exit(1);
});

// Activate the client
client.activate();

