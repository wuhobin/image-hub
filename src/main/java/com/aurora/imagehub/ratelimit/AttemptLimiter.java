package com.aurora.imagehub.ratelimit;

import com.aurora.starter.webmvc.exception.BizException;
import cn.hutool.crypto.digest.DigestUtil;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 基于 Redis 固定时间窗口的尝试次数限制，供 IP 和账户维度共同复用。 */
@Component
@RequiredArgsConstructor
public class AttemptLimiter {
    private final StringRedisTemplate stringRedisTemplate;
    // Lua 原子执行计数和首次过期设置，避免并发请求留下永不过期的计数器。
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
        local n = redis.call('INCR', KEYS[1])
        if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
        return n
        """, Long.class);

    /** 记录一次尝试；超限抛出 429，subject 哈希后写入 Redis key。 */
    public void check(String scene, String subject, int limit, int seconds) {
        Long count = stringRedisTemplate.execute(INCREMENT,
                List.of("image-hub:attempt:" + scene + ":" + DigestUtil.sha256Hex(subject)),
                Integer.toString(seconds));
        if (count == null) throw new BizException(503, "服务暂不可用，请稍后重试");
        if (count > limit) throw new BizException(429, "操作过于频繁，请稍后再试");
    }
}
