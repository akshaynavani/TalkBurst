package com.akshaynavani.talkburst.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import com.akshaynavani.talkburst.config.TalkBurstProperties;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final PttWebSocketHandler handler;
    private final PttHandshakeInterceptor interceptor;
    private final TalkBurstProperties props;

    public WebSocketConfig(PttWebSocketHandler handler, PttHandshakeInterceptor interceptor,
                           TalkBurstProperties props) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/ptt")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns(props.ws().allowedOrigins().toArray(String[]::new));
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(props.ws().maxAudioFrameBytes());
        container.setMaxTextMessageBufferSize(8 * 1024);
        container.setMaxSessionIdleTimeout(120_000L);
        return container;
    }
}
