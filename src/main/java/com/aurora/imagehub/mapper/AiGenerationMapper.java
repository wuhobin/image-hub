package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.AiGeneration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/** 任务状态流转均使用用户条件与认领令牌；成功图片不会再次计入预占。 */
@Mapper
public interface AiGenerationMapper extends BaseMapper<AiGeneration> {

    @Select("""
            SELECT COUNT(*) FROM hub_ai_generation g
            WHERE g.user_id = #{userId} AND g.active_user_id IS NOT NULL AND g.deleted = 0
              AND NOT EXISTS (SELECT 1 FROM hub_image i WHERE i.id = g.id AND i.user_id = g.user_id AND i.quota_charged = 1)
            """)
    long reserved(long userId);

    @Select("SELECT result_data FROM hub_ai_generation WHERE id = #{id} AND user_id = #{userId} AND deleted = 0")
    AiGeneration resultData(@Param("userId") long userId, @Param("id") String id);

    @Update("""
            UPDATE hub_ai_generation SET status = #{next}, work_token = #{token},
              work_deadline = TIMESTAMPADD(SECOND, #{seconds}, CURRENT_TIMESTAMP), update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = #{expected} AND work_token IS NULL
              AND (result_expires_at IS NULL OR result_expires_at > CURRENT_TIMESTAMP)
            """)
    int claim(@Param("userId") long userId, @Param("id") String id, @Param("expected") String expected,
              @Param("next") String next, @Param("token") String token, @Param("seconds") int seconds);

    @Update("""
            UPDATE hub_ai_generation SET status = 'SAVING', result_data = #{bytes},
              result_expires_at = TIMESTAMPADD(HOUR, 24, CURRENT_TIMESTAMP),
              api_key_ciphertext = NULL, work_token = NULL, work_deadline = NULL, error_message = NULL,
              update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'GENERATING'
              AND work_token = #{token} AND work_deadline > CURRENT_TIMESTAMP
            """)
    int storeResult(@Param("userId") long userId, @Param("id") String id,
                    @Param("token") String token, @Param("bytes") byte[] bytes);

    @Update("""
            UPDATE hub_ai_generation SET status = 'SAVE_FAILED', work_token = NULL, work_deadline = NULL,
              error_message = '图片已生成，保存失败，请在24小时内重试保存', update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'SAVING' AND work_token = #{token}
            """)
    int saveFailed(@Param("userId") long userId, @Param("id") String id, @Param("token") String token);

    @Update("""
            UPDATE hub_ai_generation SET status = 'SAVING', error_message = NULL, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'SAVE_FAILED'
              AND work_token IS NULL AND result_data IS NOT NULL AND result_expires_at > CURRENT_TIMESTAMP
            """)
    int retrySave(@Param("userId") long userId, @Param("id") String id);

    @Update("""
            UPDATE hub_ai_generation SET status = 'SUCCEEDED', active_user_id = NULL, result_data = NULL,
              api_key_ciphertext = NULL, work_token = NULL, work_deadline = NULL,
              error_message = NULL, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'SAVING'
              AND work_token = #{token} AND work_deadline > CURRENT_TIMESTAMP AND result_expires_at > CURRENT_TIMESTAMP
            """)
    int succeed(@Param("userId") long userId, @Param("id") String id, @Param("token") String token);

    @Update("""
            UPDATE hub_ai_generation SET status = #{status}, error_message = #{message}, active_user_id = NULL,
              result_data = NULL, api_key_ciphertext = NULL, work_token = NULL, work_deadline = NULL,
              update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND active_user_id IS NOT NULL
            """)
    int finish(@Param("userId") long userId, @Param("id") String id,
               @Param("status") String status, @Param("message") String message);

    @Update("""
            UPDATE hub_ai_generation SET status = 'FAILED', error_message = #{message}, active_user_id = NULL,
              result_data = NULL, api_key_ciphertext = NULL, work_token = NULL, work_deadline = NULL,
              update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'GENERATING' AND work_token = #{token}
            """)
    int generationFailed(@Param("userId") long userId, @Param("id") String id,
                         @Param("token") String token, @Param("message") String message);
}
