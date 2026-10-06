package org.mifos.connector.mpesa.auth;

import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnExpression("!'${mpesa-connector.redis.type:redis}'.equalsIgnoreCase('memory')")
public class RedisAccessTokenStore implements AccessTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RedisAccessTokenStore.class);

    private final StringRedisTemplate redisTemplate;
    private final String accessTokenKey;

    public RedisAccessTokenStore(StringRedisTemplate redisTemplate, RedisStoreProperties props) {
        this.redisTemplate = redisTemplate;
        this.accessTokenKey = props.getKeyPrefix() + ":access_token";
    }

    @PostConstruct
    void logStoreBackend() {
        log.info("Access token store backend: redis");
    }

    /**
     * Atomically stores the token and its expiry in a single Redis call.
     * Calling set + expire separately would leave a window where the key exists
     * with the wrong TTL, visible to other pods.
     */
    @Override
    public void saveToken(String accessToken, int expiresInSeconds) {
        redisTemplate.opsForValue().set(accessTokenKey, accessToken, expiresInSeconds, TimeUnit.SECONDS);
    }

    @Override
    public String getAccessToken() {
        return redisTemplate.opsForValue().get(accessTokenKey);
    }

    @Override
    public boolean isValid() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(accessTokenKey));
    }
}
