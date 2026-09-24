package com.aurora.imagehub.service.impl;

import com.aurora.imagehub.cache.AiModelCache;
import com.aurora.imagehub.config.aigenerate.AiEndpointPolicy;
import com.aurora.imagehub.config.aigenerate.ModelKeyCipher;
import com.aurora.imagehub.mapper.AiModelConfigMapper;
import com.aurora.imagehub.model.entity.AiModelConfig;
import com.aurora.imagehub.model.param.AiModelConfigParam;
import com.aurora.imagehub.model.vo.AiModelVO;
import com.aurora.imagehub.model.vo.admin.AiModelConfigVO;
import com.aurora.imagehub.service.AiModelConfigService;
import com.aurora.imagehub.service.admin.AdminAccountService;
import com.aurora.starter.mybatisplus.mybatis.PageUtils;
import com.aurora.starter.webmvc.exception.BizException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 前台模型列表复用二级缓存，管理和任务提交直接读库；已提交任务使用自己的快照。 */
@Service
@RequiredArgsConstructor
public class AiModelConfigServiceImpl extends ServiceImpl<AiModelConfigMapper, AiModelConfig> implements AiModelConfigService {

    private final AiModelConfigMapper aiModelConfigMapper;

    private final AiModelCache aiModelCache;

    private final AdminAccountService adminAccountService;

    private final ModelKeyCipher modelKeyCipher;

    private final AiEndpointPolicy aiEndpointPolicy;

    /** 不向普通用户返回地址和密钥；排序由管理员配置决定。 */
    @Override
    public List<AiModelVO> available() {
        return aiModelCache.get(() -> list(Wrappers.<AiModelConfig>lambdaQuery().eq(AiModelConfig::getEnabled, true)
                .orderByAsc(AiModelConfig::getSortOrder, AiModelConfig::getId)).stream().map(AiModelVO::from).toList());
    }

    /** 管理分页继续使用平台原生分页结构。 */
    @Override
    public Page<AiModelConfigVO> models(int page, int pageSize) {
        adminAccountService.currentAdmin();
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new BizException(400, "分页参数无效");
        return PageUtils.convert(page(PageUtils.buildPage(page, pageSize),
                Wrappers.<AiModelConfig>lambdaQuery().orderByAsc(AiModelConfig::getSortOrder, AiModelConfig::getId)), AiModelConfigVO::from);
    }

    /** 默认值必须属于白名单；空 Key 保留原值，启用前必须具备可解密密钥。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiModelConfigVO saveModel(Long id, AiModelConfigParam param) {
        adminAccountService.currentAdmin();
        if (!param.getSizes().contains(param.getDefaultSize()) || !param.getQualities().contains(param.getDefaultQuality())) {
            throw new BizException(400, "默认尺寸和质量必须属于允许的选项");
        }
        boolean geminiFlashImage = param.getModelCode().equals("gemini-3.1-flash-image")
                || param.getModelCode().startsWith("gemini-3.1-flash-image-");
        for (String size : param.getSizes()) {
            String[] parts = size.split("x");
            int width = Integer.parseInt(parts[0]), height = Integer.parseInt(parts[1]);
            // Gemini 的官方尺寸包含 512 档和 1:8 / 8:1 的 4K 长图，不能沿用 GPT 的边长与像素范围。
            if (geminiFlashImage) {
                if (width < 168 || height < 168 || width > 12288 || height > 12288 || (long) width * height > 18_874_368
                        || Math.max(width, height) > 8 * Math.min(width, height)) {
                    throw new BizException(400, "Gemini尺寸边长须在168至12288之间，比例不超过8:1，且总像素不能超过18874368");
                }
            } else if (width < 256 || height < 256 || width > 3840 || height > 3840 || (long) width * height > 8_294_400) {
                throw new BizException(400, "尺寸边长须在256至3840之间，且总像素不能超过8294400");
            }
            // GPT-Image-2 的自定义尺寸有额外约束，在保存配置时拦截，避免用户生成时才被供应商拒绝。
            if ((param.getModelCode().equals("gpt-image-2") || param.getModelCode().startsWith("gpt-image-2-"))
                    && (width % 16 != 0 || height % 16 != 0 || Math.max(width, height) > 3 * Math.min(width, height)
                    || (long) width * height < 655_360)) {
                throw new BizException(400, "GPT-Image-2尺寸须为16的倍数，长短边比例不超过3:1，且总像素至少655360");
            }
        }
        String url = param.getBaseUrl().trim().replaceAll("/+$", "");
        aiEndpointPolicy.requirePublicHttps(url);
        aiEndpointPolicy.validatePath(param.getImagesPath());
        String name = param.getName().trim();
        if (aiModelConfigMapper.countName(name, id) != 0) throw new BizException(409, "模型名称已被使用（包括已删除配置）");
        AiModelConfig model = id == null ? new AiModelConfig() : getById(id);
        if (model == null) throw new BizException(404, "模型配置不存在");
        model.setName(name);
        model.setModelCode(param.getModelCode());
        model.setBaseUrl(url);
        model.setImagesPath(param.getImagesPath());
        if (param.getApiKey() != null && !param.getApiKey().isBlank()) {
            model.setApiKeyCiphertext(modelKeyCipher.encrypt(param.getApiKey().trim()));
        }
        model.setSizes(String.join(",", param.getSizes().stream().distinct().toList()));
        model.setDefaultSize(param.getDefaultSize());
        model.setQualities(String.join(",", param.getQualities().stream().distinct().toList()));
        model.setDefaultQuality(param.getDefaultQuality());
        model.setPointsCost(param.getPointsCost().intValueExact());
        model.setEnabled(param.getEnabled());
        model.setSortOrder(param.getSortOrder());
        if (Boolean.TRUE.equals(model.getEnabled())) {
            if (model.getApiKeyCiphertext() == null) throw new BizException(400, "启用前请填写 API Key");
            modelKeyCipher.decrypt(model.getApiKeyCiphertext());
        }
        prepareModelUpdate();
        try {
            if (id == null) aiModelConfigMapper.insert(model);
            else if (aiModelConfigMapper.updateById(model) != 1) throw new BizException(409, "配置已变化，请刷新后重试");
        } catch (DuplicateKeyException e) {
            throw new BizException(409, "模型名称已被使用");
        }
        aiModelCache.evictAfterCommit();
        return AiModelConfigVO.from(getById(model.getId()));
    }

    /** 逻辑删除不影响已提交任务的配置快照和历史记录。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archiveModel(long id) {
        adminAccountService.currentAdmin();
        prepareModelUpdate();
        if (aiModelConfigMapper.archive(id) != 1) throw new BizException(404, "模型配置不存在");
        aiModelCache.evictAfterCommit();
    }

    /** 沿用系统配置的故障策略：缓存已知不可用时拒绝写库，避免前台长期保留旧选项。 */
    private void prepareModelUpdate() {
        try {
            aiModelCache.prepareUpdate();
        } catch (RuntimeException e) {
            throw new BizException(503, "模型缓存暂时不可用，未保存，请稍后重试", e);
        }
    }

    /** 密钥与模型状态在创建任务时检查，运行中不再依赖可变模型配置。 */
    @Override
    public AiModelConfig requireEnabled(long id) {
        AiModelConfig model = getById(id);
        if (model == null || !Boolean.TRUE.equals(model.getEnabled())) throw new BizException(400, "模型已停用或不存在");
        modelKeyCipher.decrypt(model.getApiKeyCiphertext());
        return model;
    }
}
