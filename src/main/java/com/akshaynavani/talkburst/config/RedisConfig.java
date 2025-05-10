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
    public RedisScript<String> floorAcquireScript() {
        return script("lua/floor_acquire.lua", String.class);
    }

    @Bean
    public RedisScript<Long> floorReleaseScript() {
        return script("lua/floor_release.lua", Long.class);
    }

    @Bean
    public RedisScript<Long> floorRenewScript() {
        return script("lua/floor_renew.lua", Long.class);
    }

    @Bean
    public RedisScript<List> floorPromoteScript() {
        return script("lua/floor_promote.lua", List.class);
    }

    private static <T> RedisScript<T> script(String path, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(type);
        return script;
    }
}
