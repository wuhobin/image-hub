package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.ImageFile;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/** 图片记录持久化；读取、统计和删除均限定 user_id，防止跨用户访问。 */
@Mapper
public interface ImageMapper extends BaseMapper<ImageFile> {
    String FILTER = """
            FROM hub_image WHERE user_id = #{userId} AND deleted = 0 AND source_type IN ('UPLOAD', 'AI')
        <if test="search != null and search != ''">AND LOCATE(LOWER(#{search}), LOWER(name)) > 0</if>
        <if test="type != null and type != ''">AND type = #{type}</if>
        <if test="sourceType != null and sourceType != ''">AND source_type = #{sourceType}</if>
        """;


    @Select("SELECT * FROM hub_image WHERE id = #{id} AND user_id = #{userId} AND deleted = 0")
    ImageFile findOwned(@Param("userId") long userId, @Param("id") String id);

    /**
     * 从数据库读取当前引用，避免按旧用户快照误清理另一请求刚保存的头像。
     */
    @Select("""
            SELECT i.* FROM hub_image i WHERE i.user_id = #{userId} AND i.deleted = 0 AND i.source_type = 'AVATAR'
              AND NOT EXISTS (SELECT 1 FROM hub_user u WHERE u.id = #{userId} AND u.avatar_image_id = i.id AND u.deleted = 0)
            """)
    java.util.List<ImageFile> unusedAvatars(long userId);

    // 固定 SQL 分支避免拼接客户端排序值；同秒上传用主键补充排序，保证分页顺序稳定。
    @Select("<script>SELECT * " + FILTER + " ORDER BY <choose>"
            + "<when test='oldestFirst'>create_time ASC, id ASC</when>"
            + "<otherwise>create_time DESC, id DESC</otherwise></choose></script>")
    Page<ImageFile> list(Page<ImageFile> page, @Param("userId") long userId,
                         @Param("search") String search, @Param("type") String type, @Param("sourceType") String sourceType,
                         @Param("oldestFirst") boolean oldestFirst);

    @Select("<script>SELECT COALESCE(SUM(size),0) " + FILTER + "</script>")
    long sumBytes(@Param("userId") long userId, @Param("search") String search, @Param("type") String type,
                  @Param("sourceType") String sourceType);

    // 消耗历史必须包含已逻辑删除图片；普通列表的 deleted = 0 过滤不适用于积分恢复。
    @Select("SELECT COALESCE(SUM(points_cost), 0) FROM hub_image WHERE user_id = #{userId} AND quota_charged = 1")
    long sumConsumedPoints(long userId);

    // 逻辑删除显式使用数据库当前时间，保持审计时间由数据库维护。
    @Update("UPDATE hub_image SET deleted = 1, update_time = CURRENT_TIMESTAMP WHERE id = #{id} AND user_id = #{userId} AND deleted = 0")
    int deleteOwned(@Param("userId") long userId, @Param("id") String id);
}
