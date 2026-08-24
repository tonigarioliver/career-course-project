package com.slotwise.booking.service;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    // Reads DataRedisConnectionDetails rather than spring.data.redis.host/port directly:
    // @ServiceConnection (Testcontainers) wires a random port into that bean, not into the
    // properties, so a plain @Value("${spring.data.redis.port}") would connect to the
    // application.yml default (6379) instead of the test container's actual port.
    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(DataRedisConnectionDetails connectionDetails) {
        final DataRedisConnectionDetails.Standalone standalone = connectionDetails.getStandalone();
        final Config config = new Config();
        config.useSingleServer().setAddress("redis://" + standalone.getHost() + ":" + standalone.getPort());
        return Redisson.create(config);
    }
}
