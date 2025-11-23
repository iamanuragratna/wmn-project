package com.wmn.controller.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // STOMP endpoint for clients to connect (with SockJS fallback)
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/telemetry")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    // configure in-memory broker with /topic prefix
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");    // clients subscribe to /topic/...
        registry.setApplicationDestinationPrefixes("/app"); // for client->server messages (if any)
    }
}
