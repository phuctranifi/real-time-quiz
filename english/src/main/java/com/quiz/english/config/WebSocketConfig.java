package com.quiz.english.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket configuration for multi-instance deployment.
 * Uses STOMP protocol over WebSocket with support for SockJS fallback.
 * 
 * For production multi-instance setup:
 * - Use external message broker (RabbitMQ/Redis) instead of simple broker
 * - Configure session affinity or use Redis-backed session store
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Configure message broker for WebSocket communication.
     * 
     * Current setup uses simple in-memory broker for development.
     * For production multi-instance deployment, replace with:
     * - RabbitMQ: registry.enableStompBrokerRelay("/topic", "/queue")
     * - Or implement Redis-based message broadcasting
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Application destination prefix for client-to-server messages
        registry.setApplicationDestinationPrefixes("/app");
        
        // Simple broker for server-to-client messages
        // Destinations: /topic for broadcasts, /queue for user-specific messages
        registry.enableSimpleBroker("/topic", "/queue");
        
        // User destination prefix for user-specific messages
        registry.setUserDestinationPrefix("/user");
    }

    /**
     * Register STOMP endpoints for WebSocket connections.
     * Includes SockJS fallback for browsers that don't support WebSocket.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Main WebSocket endpoint
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*") // Configure specific origins in production
                .withSockJS();

        // Quiz-specific endpoint
        registry.addEndpoint("/ws/quiz")
                .setAllowedOriginPatterns("*") // Configure specific origins in production
                .withSockJS();
    }
}

