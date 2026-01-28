package com.quiz.english.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration for scheduled tasks.
 * 
 * Enables:
 * - WebSocket heartbeat monitoring
 * - Redis health checks
 * - Stale connection cleanup
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

