package com.hts.order.repository;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class SessionRepository {
    private static final Logger log = LoggerFactory.getLogger(SessionRepository.class);
    private static final String SESSION_PREFIX = "session:";

    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    @Inject
    public SessionRepository(RedisClient redisClient) {
        this.connection = redisClient.connect();
        this.commands = connection.sync();
    }

    public Long getAccountId(long sessionId) {
        try {
            String key = SESSION_PREFIX + sessionId;
            String value = commands.get(key);

            if (value == null) {
                log.warn("Session not found: sessionId={}", sessionId);
                return null;
            }

            return Long.parseLong(value);
        } catch (Exception e) {
            log.error("Failed to get accountId for sessionId={}", sessionId, e);
            return null;
        }
    }

    public void createSession(long sessionId, long accountId) {
        try {
            String key = SESSION_PREFIX + sessionId;
            commands.setex(key, 86400, String.valueOf(accountId));
            log.debug("Session created: sessionId={}, accountId={}", sessionId, accountId);
        } catch (Exception e) {
            log.error("Failed to create session: sessionId={}, accountId={}", sessionId, accountId, e);
        }
    }

    public void deleteSession(long sessionId) {
        try {
            String key = SESSION_PREFIX + sessionId;
            commands.del(key);
        } catch (Exception e) {
            log.error("Failed to delete session: sessionId={}", sessionId, e);
        }
    }

    public boolean isValidSession(long sessionId) {
        try {
            String key = SESSION_PREFIX + sessionId;
            return commands.exists(key) > 0;
        } catch (Exception e) {
            log.error("Failed to validate session: sessionId={}", sessionId, e);
            return false;
        }
    }

    public void close() {
        connection.close();
    }
}
