package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.AiGeneration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/** 任务只持久化状态及云文件定位；状态更新校验用户与令牌，图片字节不入库。 */
@Mapper
public interface AiGenerationMapper extends BaseMapper<AiGeneration> {

    /** 成功图片不再占用预留次数，防止状态恢复期间重复计费。 */
    @Select("""
            SELECT COUNT(*) FROM hub_ai_generation g
            WHERE g.user_id = #{userId} AND g.active_user_id IS NOT NULL AND g.deleted = 0
              AND NOT EXISTS (SELECT 1 FROM hub_image i WHERE i.id = g.id AND i.user_id = g.user_id AND i.quota_charged = 1)
            """)
    long reserved(long userId);

    /** 用户访问时按活动用户唯一索引检查中断任务，不扫描全表，不读取图片字节。 */
    @Select("""
            SELECT id, pending_storage_info FROM hub_ai_generation
            WHERE user_id = #{userId} AND active_user_id = #{userId} AND deleted = 0
              AND status IN ('GENERATING', 'SAVING') AND work_deadline <= CURRENT_TIMESTAMP
            """)
    AiGeneration interrupted(long userId);

    /** 调用方先持有任务锁；仅释放已经超时且原工作线程不再持锁的任务。 */
    @Update("""
            UPDATE hub_ai_generation SET status = 'FAILED', active_user_id = NULL,
              error_message = '生成或保存中断，额度已释放，请重新提交创作',
              api_key_ciphertext = NULL, work_token = NULL, work_deadline = NULL, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND active_user_id = #{userId} AND deleted = 0
              AND status IN ('GENERATING', 'SAVING') AND work_deadline <= CURRENT_TIMESTAMP
            """)
    int failInterrupted(@Param("userId") long userId, @Param("id") String id);

    /** 网络上传前记录定位，失败时可即时补偿，进程中断时保留人工排查依据。 */
    @Update("""
            UPDATE hub_ai_generation SET pending_storage_info = #{storageInfo}, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'SAVING'
              AND work_token = #{token} AND work_deadline > CURRENT_TIMESTAMP AND pending_storage_info IS NULL
            """)
    int prepareUpload(@Param("userId") long userId, @Param("id") String id,
                      @Param("token") String token, @Param("storageInfo") String storageInfo);

    /** 只在已确认补偿删除完成后清空定位，不丢失删除失败记录。 */
    @Update("""
            UPDATE hub_ai_generation SET pending_storage_info = NULL, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND work_token IS NULL
            """)
    int clearPendingUpload(@Param("userId") long userId, @Param("id") String id);

    /** 只认领排队任务；生成或保存中的任务不会被另一轮轮询重新执行。 */
    @Update("""
            UPDATE hub_ai_generation SET status = 'GENERATING', work_token = #{token},
              work_deadline = TIMESTAMPADD(SECOND, #{seconds}, CURRENT_TIMESTAMP), update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'QUEUED' AND work_token IS NULL
            """)
    int claim(@Param("userId") long userId, @Param("id") String id,
              @Param("token") String token, @Param("seconds") int seconds);

    /** 同一工作线程直接进入保存阶段，仅续期保存截止时间，不暂存结果。 */
    @Update("""
            UPDATE hub_ai_generation SET status = 'SAVING', api_key_ciphertext = NULL,
              work_deadline = TIMESTAMPADD(SECOND, 120, CURRENT_TIMESTAMP), update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'GENERATING'
              AND work_token = #{token} AND work_deadline > CURRENT_TIMESTAMP
            """)
    int beginSaving(@Param("userId") long userId, @Param("id") String id, @Param("token") String token);

    /** 与图片记录在同一事务提交；提交成功后清除预留及补偿定位。 */
    @Update("""
            UPDATE hub_ai_generation SET status = 'SUCCEEDED', active_user_id = NULL,
              api_key_ciphertext = NULL, work_token = NULL, work_deadline = NULL, pending_storage_info = NULL,
              error_message = NULL, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'SAVING'
              AND work_token = #{token} AND work_deadline > CURRENT_TIMESTAMP
            """)
    int succeed(@Param("userId") long userId, @Param("id") String id, @Param("token") String token);

    /** 失败释放预留；保留未确认删除的云文件定位，绝不覆盖已成功状态。 */
    @Update("""
            UPDATE hub_ai_generation SET status = 'FAILED', error_message = #{message}, active_user_id = NULL,
              api_key_ciphertext = NULL, work_token = NULL, work_deadline = NULL, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0
              AND status IN ('GENERATING', 'SAVING') AND work_token = #{token}
            """)
    int generationFailed(@Param("userId") long userId, @Param("id") String id,
                         @Param("token") String token, @Param("message") String message);
}
