package com.aurora.imagehub.mapper;

import com.aurora.imagehub.model.entity.ImageFile;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/** 图片记录持久化；读取、统计和删除均限定 user_id，防止跨用户访问。 */
@Mapper
public interface ImageMapper extends BaseMapper<ImageFile> {
    String FILTER = """
        FROM hub_image WHERE user_id = #{userId} AND deleted = 0
        <if test="search != null and search != ''">AND LOCATE(LOWER(#{search}), LOWER(name)) > 0</if>
        <if test="type != null and type != ''">AND type = #{type}</if>
        """;


    @Select("SELECT * FROM hub_image WHERE id = #{id} AND user_id = #{userId} AND deleted = 0")
    ImageFile findOwned(@Param("userId") long userId, @Param("id") String id);

    // 固定 SQL 分支避免拼接客户端排序值；同秒上传用主键补充排序，保证分页顺序稳定。
    @Select("<script>SELECT * " + FILTER + " ORDER BY <choose>"
            + "<when test='oldestFirst'>create_time ASC, id ASC</when>"
            + "<otherwise>create_time DESC, id DESC</otherwise></choose></script>")
    Page<ImageFile> list(Page<ImageFile> page, @Param("userId") long userId,
                         @Param("search") String search, @Param("type") String type,
                         @Param("oldestFirst") boolean oldestFirst);

    @Select("<script>SELECT COALESCE(SUM(size),0) " + FILTER + "</script>")
    long sumBytes(@Param("userId") long userId, @Param("search") String search, @Param("type") String type);

    // 消耗历史必须包含已逻辑删除图片；普通列表的 deleted = 0 过滤不适用于额度恢复。
    @Select("SELECT COUNT(*) FROM hub_image WHERE user_id = #{userId} AND quota_charged = 1")
    long countQuotaUploads(long userId);

    // 逻辑删除会改变业务列，数据库 ON UPDATE 自动维护更新时间。
    @Update("UPDATE hub_image SET deleted = 1 WHERE id = #{id} AND user_id = #{userId} AND deleted = 0")
    int deleteOwned(@Param("userId") long userId, @Param("id") String id);
}
