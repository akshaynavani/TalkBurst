package com.akshaynavani.talkburst.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }

    @Bean
    public RedisScript<Long> joinSessionScript() {
        return script("lua/join_session.lua", Long.class);
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> floorAcquireScript() {
        return script("lua/floor_acquire.lua", List.class);
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> floorReleaseScript() {
        return script("lua/floor_release.lua", List.class);
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> floorRenewScript() {
        return script("lua/floor_renew.lua", List.class);
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> floorSweepScript() {
        return script("lua/floor_sweep.lua", List.class);
    }

    private static <T> RedisScript<T> script(String path, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(type);
        return script;
    }
}
