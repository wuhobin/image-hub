package com.aurora.imagehub.service.impl.admin;

import com.aurora.imagehub.cache.UploadQuotaCache;
import com.aurora.imagehub.mapper.admin.AdminUserMapper;
import com.aurora.imagehub.model.entity.UserAccount;
import com.aurora.imagehub.model.param.admin.AdminUserQueryParam;
import com.aurora.imagehub.model.vo.admin.AdminUserVO;
import com.aurora.imagehub.service.admin.*;
import com.aurora.starter.mybatisplus.mybatis.PageUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 管理员跨用户查询入口，分页后仅获取本页余额，不影响用户上传。 */
@Service
@RequiredArgsConstructor
public class AdminUserAccountServiceImpl extends ServiceImpl<AdminUserMapper, UserAccount> implements AdminUserAccountService {
    private final AdminUserMapper adminUserMapper;
    private final AdminAccountService adminAccountService;
    private final UploadQuotaCache uploadQuotaCache;

    @Override
    public Page<AdminUserVO> listUsers(AdminUserQueryParam query) {
        adminAccountService.currentAdmin();
        Page<UserAccount> page = adminUserMapper.listUsers(PageUtils.buildPage(query.getPage(), query.getPageSize()),
                query.getSearch() == null ? "" : query.getSearch().trim());
        var remaining = uploadQuotaCache.readRemaining(page.getRecords().stream().map(UserAccount::getId).toList());
        return PageUtils.convert(page, user -> new AdminUserVO(user.getId().toString(), user.getUsername(),
                user.getEmail(), user.getCreateTime(), remaining.get(user.getId())));
    }
}
