package com.aurora.imagehub.controller.admin;

import com.aurora.imagehub.model.param.admin.AdminUserQueryParam;
import com.aurora.imagehub.model.vo.admin.AdminUserVO;
import com.aurora.imagehub.service.admin.AdminUserAccountService;
import com.aurora.starter.webmvc.domain.response.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 管理员用户列表，只提供分页搜索，不提供用户修改或删除接口。 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
    private final AdminUserAccountService adminUserAccountService;
    @GetMapping
    public Result<Page<AdminUserVO>> list(@Valid @ModelAttribute AdminUserQueryParam query) {
        return Result.data(adminUserAccountService.listUsers(query));
    }
}
