package com.ash.springai.interview_platform.redis;

import org.springframework.stereotype.Service;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.RAtomicLong;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.redisson.api.options.KeysScanOptions;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.TimeUnit;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisService {
    
    private final RedissonClient redissonClient;

    public <T> void set(String key, T value) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        bucket.set(value);
    }

    public <T> void set(String key, T value, Duration ttl) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        bucket.set(value, ttl);
    }

    public <T> T get(String key) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        return bucket.get();
    }

    public <T> T getOrLoad(String key, Duration ttl, Function<String, T> loader) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        T value = bucket.get();
        if (value == null) {
            value = loader.apply(key);
            if (value != null) {
                bucket.set(value, ttl);
            }
        }
        return value;
    }

    public boolean delete(String key) {
        return redissonClient.getBucket(key).delete();
    }

    public boolean exists(String key) {
        return redissonClient.getBucket(key).isExists();
    }

    public boolean expire(String key, Duration ttl) {
        return redissonClient.getBucket(key).expire(ttl);
    }

    public long getTimeToLive(String key) {
        return redissonClient.getBucket(key).remainTimeToLive();
    }

    public <K, V> void hSet(String key, K field, V value) {
        RMap<K, V> map = redissonClient.getMap(key);
        map.put(field, value);
    }

    public <K, V> V hGet(String key, K field) {
        RMap<K, V> map = redissonClient.getMap(key);
        return map.get(field);
    }

    public <K, V> Map<K, V> hGetAll(String key) {
        RMap<K, V> map = redissonClient.getMap(key);
        return map.readAllMap();
    }

    public <K, V> boolean hDelete(String key, K field) {
        RMap<K, V> map = redissonClient.getMap(key);
        return map.remove(field) != null;
    }

    public <K> boolean hExists(String key, K field) {
        RMap<K, Object> map = redissonClient.getMap(key);
        return map.containsKey(field);
    }

    public RLock getLock(String lockKey) {
        return redissonClient.getLock(lockKey);
    }

    public boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            return lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void unlock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime,
                                    TimeUnit unit, LockedOperation<T> operation) {
            RLock lock = redissonClient.getLock(lockKey);
            try {
                if (lock.tryLock(waitTime, leaseTime, unit)) {
                    try {
                        return operation.execute();
                    } finally {
                        lock.unlock();
                    }
                }
                throw new RuntimeException("获取锁失败: " + lockKey);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("获取锁被中断: " + lockKey, e);
            }
    }

    @FunctionalInterface
    public interface LockedOperation<T> {
        T execute();
    }

    @FunctionalInterface
    public interface StreamMessageProcessor {
        void process(StreamMessageId messageId, Map<String, String> data);
    }

    public boolean streamConsumeMessages(
        String streamKey,
        String groupName,
        String consumerName,
        int count,
        long blockTimeoutMs,
        StreamMessageProcessor processor) {

        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);

        // 使用阻塞读取，让 Redis 服务端等待消息
        Map<StreamMessageId, Map<String, String>> messages = stream.readGroup(
            groupName,
            consumerName,
            StreamReadGroupArgs.neverDelivered()
                .count(count)
                .timeout(Duration.ofMillis(blockTimeoutMs))
        );

        if (messages == null || messages.isEmpty()) {
            return false;
        }

        for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
            processor.process(entry.getKey(), entry.getValue());
        }

        return true;
    }

    public void createStreamGroup(String streamKey, String groupName) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        try {
            stream.createGroup(StreamCreateGroupArgs.name(groupName).makeStream());
            log.info("创建 Stream 消费者组: stream={}, group={}", streamKey, groupName);
        } catch (Exception e) {
            // 组已存在，忽略
            if (!e.getMessage().contains("BUSYGROUP")) {
                log.warn("创建消费者组失败: {}", e.getMessage());
            }
        }
    }

    public String streamAdd(String streamKey, Map<String, String> message) {
        return streamAdd(streamKey, message, 0);
    }

    public String streamAdd(String streamKey, Map<String, String> message, int maxLen) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        StreamAddArgs<String, String> args = StreamAddArgs.entries(message);
        if (maxLen > 0) {
            args.trimNonStrict().maxLen(maxLen);
        }
        StreamMessageId messageId = stream.add(args);
        log.debug("发送 Stream 消息: stream={}, messageId={}, maxLen={}", streamKey, messageId, maxLen);
        return messageId.toString();
    }

    public Map<StreamMessageId, Map<String, String>> streamReadGroup(
            String streamKey, String groupName, String consumerName, int count) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        return stream.readGroup(groupName, consumerName,
            StreamReadGroupArgs.neverDelivered().count(count));
    }

    public void streamAck(String streamKey, String groupName, StreamMessageId... ids) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        stream.ack(groupName, ids);
    }

    public long streamLen(String streamKey) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        return stream.size();
    }

    public RAtomicLong getAtomicLong(String key) {
        return redissonClient.getAtomicLong(key);
    }

    public long increment(String key) {
        return redissonClient.getAtomicLong(key).incrementAndGet();
    }

    public long decrement(String key) {
        return redissonClient.getAtomicLong(key).decrementAndGet();
    }

    public <T> void listRightPush(String key, T value) {
        RList<T> list = redissonClient.getList(key);
        list.add(value);
    }

    public <T> List<T> listGetAll(String key) {
        RList<T> list = redissonClient.getList(key);
        return list.readAll();
    }

    public RedissonClient getClient() {
        return redissonClient;
    }

    public long deleteByPattern(String pattern) {
        RKeys keys = redissonClient.getKeys();
        return keys.deleteByPattern(pattern);
    }

    public Iterable<String> findKeysByPattern(String pattern) {
        RKeys keys = redissonClient.getKeys();
        return keys.getKeys(KeysScanOptions.defaults().pattern(pattern));
    }
}
