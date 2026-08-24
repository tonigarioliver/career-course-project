package com.slotwise.booking.service;

import java.time.Duration;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

@Configuration
@EnableCaching
public class CacheConfig {

    // Boot's default RedisCacheManager serializes values with JDK serialization, which
    // needs every cached type (ResourceDto, ...) to implement Serializable. Swapping in
    // JSON avoids that everywhere and keeps cached entries human-readable via redis-cli.
    // Set here rather than spring.cache.redis.* properties because this customizer builds
    // its own RedisCacheConfiguration from scratch, which would otherwise silently drop a
    // property-configured TTL — so TTL lives here too, as the one source of truth.
    @Bean
    RedisCacheManagerBuilderCustomizer cacheConfigCustomizer() {
        return builder -> builder.cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
                // Cache-Aside self-heal: a stale entry expires within 5 minutes even if an
                // @CacheEvict call is ever missed (e.g. a direct DB write bypassing the service).
                .entryTtl(Duration.ofMinutes(5))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        // Without default typing, values round-trip as a generic
                        // LinkedHashMap instead of the original DTO type — this is the same
                        // behavior GenericJackson2JsonRedisSerializer's default constructor
                        // gave unconditionally, kept opt-in here. Fine for our own
                        // Redis-only cache data; would need a type allowlist
                        // (enableDefaultTyping(validator)) if Redis were shared/untrusted.
                        .fromSerializer(GenericJacksonJsonRedisSerializer.builder()
                                .enableUnsafeDefaultTyping()
                                .build())));
    }
}
