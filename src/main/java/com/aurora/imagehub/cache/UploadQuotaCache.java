package com.aurora.imagehub.cache;

import com.aurora.imagehub.mapper.ImageMapper;
import com.aurora.imagehub.model.vo.UploadQuotaVO;
import com.aurora.starter.webmvc.exception.BizException;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

/** Redis 保存永久额度；中断的预占按成功图片恢复，删除图片不返还。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UploadQuotaCache {
    public static final int LIMIT = 100;
    public static final int EXHAUSTED = 40301;
    private final RedissonClient redissonClient;
    private final ImageMapper imageMapper;

    private RBucket<String> bucket(long userId) {
        return redissonClient.getBucket("image-hub:quota:v1:{" + userId + "}", StringCodec.INSTANCE);
    }

    private RLock lock(long userId) {
        return redissonClient.getLock("image-hub:quota:v1:{" + userId + "}:lock");
    }

    public UploadQuotaVO get(long userId) {
        try {
            RBucket<String> bucket = bucket(userId);
            String state = bucket.get();
            if (state != null && !state.contains(":")) return new UploadQuotaVO(LIMIT, Integer.parseInt(state));
            RLock lock = lock(userId);
            if (!lock.tryLock()) {
                // 进行中的上传已预占一次，展示可用余额；不等待网络上传完成。
                if (state != null) return new UploadQuotaVO(LIMIT, Integer.parseInt(state.split(":", 2)[0]));
                throw new BizException(503, "上传额度正在恢复，请稍后重试");
            }
            try { return new UploadQuotaVO(LIMIT, recover(userId, bucket)); }
            finally { unlock(lock); }
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(503, "上传额度暂时不可用，请稍后重试", e);
        }
    }

    /** 同账号串行预占；看门狗续租，不持有数据库事务，不影响其他账号上传。 */
    public <T> T consume(long userId, String imageId, Supplier<T> upload) {
        RLock lock;
        RBucket<String> bucket;
        int remaining;
        try {
            lock = lock(userId);
            if (!lock.tryLock()) throw new BizException(409, "当前账号有图片正在上传，请稍后重试");
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(503, "上传额度暂时不可用，请稍后重试", e);
        }
        try {
            try {
                bucket = bucket(userId);
                remaining = recover(userId, bucket);
                if (remaining <= 0) throw new BizException(EXHAUSTED, "免费上传额度已用完");
                // 单个 SET 同时记录余额与在途标记；崩溃后由下一次请求恢复，绝不直接重赠 100 次。
                bucket.set((remaining - 1) + ":" + imageId);
            } catch (BizException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new BizException(503, "上传额度暂时不可用，请稍后重试", e);
            }
            T result;
            try {
                result = upload.get();
            } catch (RuntimeException e) {
                // 包括入库成功但回读失败：以数据库成功记录为准，不能误退已消耗额度。
                try { if (lock.isHeldByCurrentThread()) rebuild(userId, bucket); }
                catch (RuntimeException recovery) { log.warn("Quota recovery deferred: userId={}", userId, recovery); }
                throw e;
            }
            try {
                if (lock.isHeldByCurrentThread()) bucket.set(Integer.toString(remaining - 1));
            } catch (RuntimeException e) {
                // 图片已入库，保留预占标记等待恢复；不能将成功响应改为失败诱发重复上传。
                log.warn("Quota confirmation deferred: userId={}", userId, e);
            }
            return result;
        } finally {
            unlock(lock);
        }
    }

    /** 云上传后再次检查锁所有权，丢失锁时禁止落库并由上传服务补偿云文件。 */
    public void checkOwnership(long userId) {
        if (!lock(userId).isHeldByCurrentThread()) throw new BizException(503, "上传额度校验已失效，请重试");
    }

    private int recover(long userId, RBucket<String> bucket) {
        String state = bucket.get();
        if (state != null && !state.contains(":")) return Integer.parseInt(state);
        return rebuild(userId, bucket);
    }

    private int rebuild(long userId, RBucket<String> bucket) {
        int remaining = (int) Math.max(0, LIMIT - imageMapper.countQuotaUploads(userId));
        bucket.set(Integer.toString(remaining));
        return remaining;
    }

    private void unlock(RLock lock) {
        try { if (lock.isHeldByCurrentThread()) lock.unlock(); }
        catch (RuntimeException e) { log.warn("Quota lock release deferred to watchdog expiry", e); }
    }
}
