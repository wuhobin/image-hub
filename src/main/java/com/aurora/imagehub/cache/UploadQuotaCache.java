package com.aurora.imagehub.cache;

import com.aurora.imagehub.mapper.ImageMapper;
import com.aurora.imagehub.mapper.AiGenerationMapper;
import com.aurora.imagehub.service.admin.SystemSettingsService;
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

/** Redis 保存累计消耗；配置决定总额，中断预占按成功图片恢复，删除图片不返还。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UploadQuotaCache {

    public static final int EXHAUSTED = 40301;

    private final RedissonClient redissonClient;

    private final ImageMapper imageMapper;

    private final AiGenerationMapper aiGenerationMapper;

    private final SystemSettingsService systemSettingsService;

    /** 注册已持久化后尽力初始化，失败不回滚账号；不覆盖已被使用的额度。 */
    public void initialize(long userId) {
        try {
            bucket(userId).setIfAbsent("u:0");
        } catch (RuntimeException e) {
            log.warn("Registration quota initialization deferred: userId={}", userId);
        }
    }

    /** 只读本页 Redis 余额，缺失或无效值返回空，不加锁、不触发数据库恢复。 */
    public java.util.Map<Long, Integer> readRemaining(java.util.List<Long> userIds) {
        var result = new java.util.HashMap<Long, Integer>();
        if (userIds.isEmpty()) return result;
        try {
            int total = systemSettingsService.freeUploadQuota();
            String[] keys = userIds.stream().map(id -> "image-hub:quota:{" + id + "}").toArray(String[]::new);
            java.util.Map<String, String> values = redissonClient.getBuckets(StringCodec.INSTANCE).get(keys);
            for (int i = 0; i < keys.length; i++) {
                String state = values.get(keys[i]);
                if (state == null) continue;
                Long used = readUsed(state);
                // ponytail: 管理分页最多100人，按人查询活动预占；规模扩大时改成一次分组查询。
                if (used != null) result.put(userIds.get(i), remaining(total, used + aiGenerationMapper.reserved(userIds.get(i))));
            }
            return result;
        } catch (RuntimeException e) {
            throw new BizException(503, "剩余额度读取失败，请稍后重试", e);
        }
    }

    private RBucket<String> bucket(long userId) {
        return redissonClient.getBucket("image-hub:quota:{" + userId + "}", StringCodec.INSTANCE);
    }

    private RLock lock(long userId) {
        return redissonClient.getLock("image-hub:quota:{" + userId + "}:lock");
    }

    /** 查询当前额度；缓存缺失、格式无效或上传中断时，持锁按成功图片记录恢复消耗。 */
    public UploadQuotaVO get(long userId) {
        try {
            int total = systemSettingsService.freeUploadQuota();
            RBucket<String> bucket = bucket(userId);
            String state = bucket.get();
            Long used = readUsed(state);
            if (used != null && !inFlight(state)) return response(userId, total, used);
            RLock lock = lock(userId);
            if (!lock.tryLock()) {
                // 进行中的上传已预占一次，展示可用余额；不等待网络上传完成。
                if (used != null) return response(userId, total, used);
                throw new BizException(503, "上传额度正在恢复，请稍后重试");
            }
            try { return response(userId, total, recover(userId, bucket)); }
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
        long used;
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
                used = recover(userId, bucket);
                if (used + aiGenerationMapper.reserved(userId) >= systemSettingsService.freeUploadQuota()) {
                    throw new BizException(EXHAUSTED, "共享额度已用完");
                }
                // 以预占时的配置判断准入，已准入上传可完成；配置变化不能覆盖累计消耗。
                bucket.set("u:" + (used + 1) + ":" + imageId);
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
                if (lock.isHeldByCurrentThread()) bucket.set("u:" + (used + 1));
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

    /** 调用方持有用户锁后恢复消耗，避免覆盖正在进行的上传预占。 */
    private long recover(long userId, RBucket<String> bucket) {
        String state = bucket.get();
        Long used = readUsed(state);
        if (used != null && !inFlight(state)) return used;
        return rebuild(userId, bucket);
    }

    /** 依据已计费图片重建累计消耗，统计包含已删除图片，防止删除后返还额度。 */
    private long rebuild(long userId, RBucket<String> bucket) {
        long used = imageMapper.countQuotaUploads(userId);
        bucket.set("u:" + used);
        return used;
    }

    /** 仅解析 u:累计消耗[:预占图片ID]；缺失、其他格式或非法数值返回空，由调用方决定恢复。 */
    private Long readUsed(String state) {
        if (state == null || !state.startsWith("u:")) {
            return null;
        }
        try {
            String[] parts = state.split(":", 3);
            long used = Long.parseLong(parts[1]);
            return used >= 0 ? used : null;
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
            return null;
        }
    }

    /** 在已解析有效的 u: 状态中检查预占标记，用于识别需要确认或恢复的上传。 */
    private boolean inFlight(String state) {
        return state.indexOf(':', 2) >= 0;
    }

    private int remaining(int total, long used) {
        return (int) Math.max(0L, total - used);
    }

    private UploadQuotaVO response(long userId, int total, long used) {
        long reserved = aiGenerationMapper.reserved(userId);
        return new UploadQuotaVO(total, remaining(total, used + reserved), used, reserved);
    }

    /** 只在创建持久化任务时短暂持锁，模型网络调用不占用普通上传锁。 */
    public <T> T reserveGeneration(long userId, Supplier<T> createTask) {
        RLock lock = lock(userId);
        if (!lock.tryLock()) throw new BizException(409, "当前账号正在保存图片，请稍后重试");
        try {
            long used = recover(userId, bucket(userId));
            if (used + aiGenerationMapper.reserved(userId) >= systemSettingsService.freeUploadQuota()) {
                throw new BizException(EXHAUSTED, "共享额度已用完");
            }
            return createTask.get();
        } finally {
            unlock(lock);
        }
    }

    /** 已预占的任务保存不再检查总额；提交前标记缓存待恢复，防止崩溃留下旧余额。 */
    public <T> T completeGeneration(long userId, Supplier<T> persist) {
        RLock lock = lock(userId);
        if (!lock.tryLock()) throw new BizException(409, "当前账号正在保存图片，请重试保存");
        try {
            RBucket<String> bucket = bucket(userId);
            long used = recover(userId, bucket);
            // 保存期间读者仍能看到正确的已用次数；标记用于中断后按数据库恢复。
            bucket.set("u:" + used + ":ai-save");
            T result = persist.get();
            try {
                if (lock.isHeldByCurrentThread()) rebuild(userId, bucket);
            } catch (RuntimeException e) {
                log.warn("AI quota refresh deferred: userId={}", userId);
            }
            return result;
        } finally {
            unlock(lock);
        }
    }

    private void unlock(RLock lock) {
        try { if (lock.isHeldByCurrentThread()) lock.unlock(); }
        catch (RuntimeException e) { log.warn("Quota lock release deferred to watchdog expiry", e); }
    }
}
