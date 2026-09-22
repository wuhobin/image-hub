package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.AiGeneration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;

/** 任务只持久化状态及云文件定位；状态更新校验用户与令牌，图片字节不入库。 */
@Mapper
public interface AiGenerationMapper extends BaseMapper<AiGeneration> {

    /**
     * 公共列表和详情共用可见性条件；在 SQL 中隐藏提示词，避免误带入公开响应。
     */
    @Select("""
            <script>
            SELECT g.id, g.user_id, g.model_id, g.model_name, g.image_size, g.quality,
                   g.share_id, g.share_status, g.prompt_public, g.published_time,
                   CASE WHEN g.prompt_public = 1 THEN g.prompt ELSE NULL END AS prompt
            FROM hub_ai_generation g
            JOIN hub_image i ON i.id = g.id AND i.user_id = g.user_id AND i.deleted = 0 AND i.source_type = 'AI'
            JOIN hub_user u ON u.id = g.user_id AND u.deleted = 0
            WHERE g.deleted = 0 AND g.status = 'SUCCEEDED'
            <choose>
              <when test="includeBlocked">AND g.share_status IN ('PUBLIC', 'BLOCKED')</when>
              <otherwise>AND g.share_status = 'PUBLIC'</otherwise>
            </choose>
            <if test="shareId != null">AND g.share_id = #{shareId}</if>
            ORDER BY g.published_time DESC, g.share_id DESC
            </script>
            """)
    Page<AiGeneration> shared(Page<AiGeneration> page, @Param("shareId") String shareId,
                              @Param("includeBlocked") boolean includeBlocked);

    /**
     * 单条条件更新抵御发布与下架的竞争；重复保存保持链接和发布时间。
     */
    @Update("""
            UPDATE hub_ai_generation SET
              share_id = COALESCE(share_id, #{shareId}),
              published_time = CASE WHEN share_status = 'PUBLIC' THEN published_time ELSE CURRENT_TIMESTAMP END,
              prompt_public = #{promptPublic}, share_status = 'PUBLIC', update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0
              AND status = 'SUCCEEDED' AND share_status != 'BLOCKED'
              AND EXISTS (SELECT 1 FROM hub_image i WHERE i.id = #{id} AND i.user_id = #{userId}
                          AND i.deleted = 0 AND i.source_type = 'AI')
            """)
    int share(@Param("userId") long userId, @Param("id") String id,
              @Param("shareId") String shareId, @Param("promptPublic") boolean promptPublic);

    /**
     * 清除分享标识令旧链接永久失效；不覆盖管理员下架状态。
     */
    @Update("""
            UPDATE hub_ai_generation SET share_status = 'PRIVATE', share_id = NULL,
              published_time = NULL, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND share_status = 'PUBLIC'
            """)
    int revokeShare(@Param("userId") long userId, @Param("id") String id);

    /**
     * 仅管理业务可调用；下架标记保留，防止作者重新公开。
     */
    @Update("""
            UPDATE hub_ai_generation SET share_status = 'BLOCKED', update_time = CURRENT_TIMESTAMP
            WHERE share_id = #{shareId} AND deleted = 0 AND share_status IN ('PUBLIC', 'BLOCKED')
            """)
    int blockShare(String shareId);

    /** 按任务快照累计预留积分；成功图片不再预留，防止状态恢复期间重复计费。 */
    @Select("""
            SELECT COALESCE(SUM(g.points_cost), 0) FROM hub_ai_generation g
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
              error_message = '生成或保存中断，积分已释放，请重新提交创作',
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
              error_message = NULL, duration_millis = #{durationMillis}, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'SAVING'
              AND work_token = #{token} AND work_deadline > CURRENT_TIMESTAMP
            """)
    int succeed(@Param("userId") long userId, @Param("id") String id,
                @Param("token") String token, @Param("durationMillis") long durationMillis);

    /** 失败释放预留；保留未确认删除的云文件定位，绝不覆盖已成功状态。 */
    @Update("""
            UPDATE hub_ai_generation SET status = 'FAILED', error_message = #{message}, active_user_id = NULL,
              duration_millis = #{durationMillis},
              api_key_ciphertext = NULL, work_token = NULL, work_deadline = NULL, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0
              AND status IN ('GENERATING', 'SAVING') AND work_token = #{token}
            """)
    int generationFailed(@Param("userId") long userId, @Param("id") String id,
                         @Param("token") String token, @Param("message") String message,
                         @Param("durationMillis") long durationMillis);
}
