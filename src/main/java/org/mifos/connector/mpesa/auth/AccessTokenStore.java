package org.mifos.connector.mpesa.auth;

import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class AccessTokenStore {

    private final StringRedisTemplate redisTemplate;
    private final String accessTokenKey;

    public AccessTokenStore(StringRedisTemplate redisTemplate, RedisStoreProperties props) {
        this.redisTemplate = redisTemplate;
        this.accessTokenKey = props.getKeyPrefix() + ":access_token";
    }

    /**
     * Atomically stores the token and its expiry in a single Redis call.
     * Calling set + expire separately would leave a window where the key exists
     * with the wrong TTL, visible to other pods.
     */
    public void saveToken(String accessToken, int expiresInSeconds) {
        redisTemplate.opsForValue().set(accessTokenKey, accessToken, expiresInSeconds, TimeUnit.SECONDS);
    }

    public String getAccessToken() {
        return redisTemplate.opsForValue().get(accessTokenKey);
    }

    public boolean isValid() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(accessTokenKey));
    }
}
