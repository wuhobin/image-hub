package com.aurora.imagehub.service;

import com.aurora.imagehub.model.entity.AiGeneration;
import com.aurora.imagehub.model.param.GenerationParam;
import com.aurora.imagehub.model.vo.GenerationVO;
import com.aurora.imagehub.model.vo.SharedCreationVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.multipart.MultipartFile;

/** 单图异步创作；用户ID来自登录态，生成请求幂等，只有图片持久化成功才消耗共享积分。 */
public interface AiGenerationService extends IService<AiGeneration> {

    /** 可通过 referenceImageId 复用本人未删除的图库图片，不重复上传、不单独扣额。 */
    GenerationVO submit(long userId, GenerationParam param);

    /** 参考图最多一张、10 MB，临时缓存在 Redis，不上传七牛、不单独扣额；同请求编号幂等。 */
    GenerationVO submit(long userId, GenerationParam param, MultipartFile reference);

    GenerationVO task(long userId, String id);

    GenerationVO active(long userId);

    /**
     * 删除本人终态创作及云文件、撤销分享；进行中拒绝删除，云端失败可重试，不退还已用积分。
     */
    void delete(long userId, String id);

    /**
     * 兼容旧调用，返回本人全部状态的历史记录。
     */
    Page<GenerationVO> history(long userId, int page, int pageSize);

    /**
     * 状态、关键词和排序在数据库分页前生效，返回筛选后的总数，始终限定本人且未删除。
     */
    Page<GenerationVO> history(long userId, com.aurora.imagehub.model.param.GenerationHistoryParam param);

    /**
     * 仅发布本人成功且未删除的 AI 作品；公开状态更新不能解除管理员下架。
     */
    GenerationVO share(long userId, String id, boolean promptPublic);

    /**
     * 撤销公开链接，保留原作品和已存在的云图片地址。
     */
    void revokeShare(long userId, String id);

    /**
     * 游客只读取当前公开且原图、作者均未删除的作品，提示词遵循作者开关。
     */
    Page<SharedCreationVO> publicCreations(int page, int pageSize);

    /**
     * 非公开、已删除和已下架链接均返回不存在，不透露作品状态。
     */
    SharedCreationVO publicCreation(String shareId);

    /**
     * 管理端读取公开与已下架作品；服务内再次校验有效管理员身份。
     */
    Page<SharedCreationVO> managedCreations(int page, int pageSize);

    /**
     * 下架后作者无法重新发布同一作品，不删除原文件或修改积分。
     */
    void blockShare(String shareId);

    /** 旧接口兼容入口；不再保留结果，校验归属后返回 409。 */
    GenerationVO retrySave(long userId, String id);

    /** 旧接口兼容入口；不再存在待放弃的暂存结果。 */
    void abandon(long userId, String id);

    /** 供有界后台执行器调用，不能从HTTP直接提交任意任务。 */
    void runTask(long userId, String id);

}
