package com.quiz.english.config;

import com.quiz.english.redis.QuizEventSubscriber;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Redis Pub/Sub configuration for quiz events.
 * 
 * Multi-Instance Broadcasting Architecture:
 * =========================================
 * 
 * Channel Pattern: quiz:{quizId}:events
 * Example: quiz:quiz123:events, quiz:quiz456:events
 * 
 * How it works:
 * 1. Each backend instance subscribes to pattern: quiz:*:events
 * 2. When Instance A publishes to quiz:quiz123:events, ALL instances receive it
 * 3. Each instance then broadcasts to its local WebSocket clients
 * 4. Result: All users see updates regardless of which instance they're connected to
 * 
 * Benefits:
 * - No sticky sessions required
 * - Horizontal scalability (add more instances anytime)
 * - Real-time sync across all instances
 * - Single source of truth in Redis
 * 
 * Example Flow:
 * User on Instance A submits answer
 *   → Instance A updates Redis leaderboard
 *   → Instance A publishes SCORE_UPDATED to quiz:quiz123:events
 *   → Instances A, B, C all receive the event
 *   → Each instance broadcasts leaderboard to its WebSocket clients
 *   → All users (on A, B, C) see the update in real-time
 */
@Configuration
@RequiredArgsConstructor
public class RedisPubSubConfig {
    
    private final QuizEventSubscriber quizEventSubscriber;
    
    /**
     * Configure message listener adapter for quiz events.
     * Delegates incoming Redis messages to QuizEventSubscriber.
     */
    @Bean
    public MessageListenerAdapter quizEventListenerAdapter() {
        return new MessageListenerAdapter(quizEventSubscriber, "onMessage");
    }
    
    /**
     * Configure Redis message listener container with quiz event subscription.
     * 
     * Pattern: quiz:*:events
     * - Matches: quiz:quiz123:events, quiz:quiz456:events, etc.
     * - Each instance subscribes to ALL quiz events
     * - Subscriber filters and processes relevant events
     */
    @Bean
    public RedisMessageListenerContainer quizEventListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter quizEventListenerAdapter) {
        
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        
        // Subscribe to all quiz event channels using pattern matching
        // Pattern: quiz:*:events matches quiz:quiz123:events, quiz:quiz456:events, etc.
        container.addMessageListener(quizEventListenerAdapter, new PatternTopic("quiz:*:events"));
        
        return container;
    }
}

