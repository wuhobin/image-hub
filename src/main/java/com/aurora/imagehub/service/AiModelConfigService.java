package com.aurora.imagehub.service;

import com.aurora.imagehub.model.entity.AiModelConfig;
import com.aurora.imagehub.model.param.AiModelConfigParam;
import com.aurora.imagehub.model.vo.AiModelVO;
import com.aurora.imagehub.model.vo.admin.AiModelConfigVO;
import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;

/** 模型管理业务；管理方法校验管理员，用户只能读取启用模型的公开选项。 */
public interface AiModelConfigService extends IService<AiModelConfig> {

    List<AiModelVO> available();

    Page<AiModelConfigVO> models(int page, int pageSize);

    AiModelConfigVO saveModel(Long id, AiModelConfigParam param);

    void archiveModel(long id);

    /** 提交时读取并验证启用状态，返回供任务快照使用的内部实体。 */
    AiModelConfig requireEnabled(long id);
}
