package org.mifos.connector.mpesa.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class AccessTokenStore {

    static final String ACCESS_TOKEN_KEY = "mpesa:access_token";

    private final StringRedisTemplate redisTemplate;

    public AccessTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Atomically stores the token and its expiry in a single Redis call.
     * Calling set + expire separately would leave a window where the key exists
     * with the wrong TTL, visible to other pods.
     */
    public void saveToken(String accessToken, int expiresInSeconds) {
        redisTemplate.opsForValue().set(ACCESS_TOKEN_KEY, accessToken, expiresInSeconds, TimeUnit.SECONDS);
    }

    public String getAccessToken() {
        return redisTemplate.opsForValue().get(ACCESS_TOKEN_KEY);
    }

    public boolean isValid() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(ACCESS_TOKEN_KEY));
    }
}
