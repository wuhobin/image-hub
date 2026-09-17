package com.aurora.imagehub.service;

import com.aurora.imagehub.model.entity.AiGeneration;
import com.aurora.imagehub.model.param.GenerationParam;
import com.aurora.imagehub.model.vo.GenerationVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

/** 单图异步创作；用户ID来自登录态，生成请求幂等，只有图片持久化成功才消耗共享额度。 */
public interface AiGenerationService extends IService<AiGeneration> {

    GenerationVO submit(long userId, GenerationParam param);

    GenerationVO task(long userId, String id);

    GenerationVO active(long userId);

    Page<GenerationVO> history(long userId, int page, int pageSize);

    /** 旧接口兼容入口；不再保留结果，校验归属后返回 409。 */
    GenerationVO retrySave(long userId, String id);

    /** 旧接口兼容入口；不再存在待放弃的暂存结果。 */
    void abandon(long userId, String id);

    /** 供有界后台执行器调用，不能从HTTP直接提交任意任务。 */
    void runTask(long userId, String id);

}
