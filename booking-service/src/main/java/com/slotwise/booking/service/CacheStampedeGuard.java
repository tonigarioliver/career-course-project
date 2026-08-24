package com.slotwise.booking.service;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

// Generic Cache-Aside read with cross-instance stampede protection: a hit returns
// straight from the cache; a miss takes a Redis-backed lock keyed per (cacheName, key)
// so that across every instance sharing this Redis, only one loader call runs per key at
// a time. The cache put happens *before* the lock is released — see ResourceService for
// why that ordering (not @Cacheable(sync = true)'s) is what actually closes the race.
@Component
@RequiredArgsConstructor
public class CacheStampedeGuard {

    private final CacheManager cacheManager;
    private final RedissonClient redissonClient;

    public <T> T getOrLoad(String cacheName, Object key, Class<T> type, Supplier<T> loader) {
        final Cache cache = Objects.requireNonNull(this.cacheManager.getCache(cacheName));
        final T cached = cache.get(key, type);
        if (cached != null) {
            return cached;
        }
        return this.loadUnderLock(cacheName, key, cache, type, loader);
    }

    // Package-private (not private) so tests can put several concurrent callers into this
    // method at once, simulating what several real JVMs sharing this Redis would each
    // independently do on a miss.
    <T> T loadUnderLock(String cacheName, Object key, Cache cache, Class<T> type, Supplier<T> loader) {
        final RLock lock = this.redissonClient.getLock("lock:" + cacheName + ":" + key);
        try {
            // Waiting up to 2s for the lock, held for at most 5s (auto-released if the
            // holder crashes mid-load): a caller that can't get the lock in time loads
            // directly rather than blocking indefinitely — a possible duplicate load beats
            // an unbounded wait.
            if (lock.tryLock(2, 5, TimeUnit.SECONDS)) {
                try {
                    final T cachedAfterLock = cache.get(key, type);
                    if (cachedAfterLock != null) {
                        return cachedAfterLock;
                    }
                    final T loaded = loader.get();
                    cache.put(key, loaded);
                    return loaded;
                } finally {
                    lock.unlock();
                }
            }
            return loader.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring cache lock for " + cacheName + ":" + key, e);
        }
    }
}
