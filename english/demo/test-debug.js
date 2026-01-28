const SockJS = require('sockjs-client');
const { Client } = require('@stomp/stompjs');

const client = new Client({
  webSocketFactory: () => new SockJS('http://localhost:8080/ws/quiz'),
  debug: (str) => console.log('[STOMP]', str),
  onConnect: () => {
    console.log('✅ Connected!');
    
    // Subscribe to personal messages
    client.subscribe('/user/queue/reply', (message) => {
      console.log('📨 Personal message received:', message.body);
      const data = JSON.parse(message.body);
      console.log('📨 Parsed:', JSON.stringify(data, null, 2));
    });
    
    // Subscribe to broadcast
    client.subscribe('/topic/quiz/quiz123', (message) => {
      console.log('📢 Broadcast received:', message.body);
    });
    
    // Join quiz
    setTimeout(() => {
      console.log('📝 Sending JOIN message...');
      client.publish({
        destination: '/app/quiz/join',
        body: JSON.stringify({
          type: 'JOIN',
          quizId: 'quiz123',
          userId: 'testuser'
        })
      });
    }, 1000);
    
    // Submit answer after 3 seconds
    setTimeout(() => {
      console.log('📤 Sending SUBMIT_ANSWER message...');
      client.publish({
        destination: '/app/quiz/submit',
        body: JSON.stringify({
          type: 'SUBMIT_ANSWER',
          quizId: 'quiz123',
          userId: 'testuser',
          correct: true
        })
      });
    }, 3000);
  },
  onStompError: (frame) => {
    console.error('❌ STOMP error:', frame);
  }
});

client.activate();

// Keep running
setTimeout(() => {
  console.log('⏹️  Test complete');
  client.deactivate();
  process.exit(0);
}, 10000);

