package com.aurora.imagehub.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aurora.imagehub.model.entity.UserAccount;
import com.aurora.imagehub.model.vo.LoginVO;
import com.aurora.imagehub.model.vo.UserVO;

/** UserAccount 实体相关的账户注册、认证及会话服务。调用方依赖此接口。 */
public interface UserAccountService extends IService<UserAccount> {
    /** 向尚未注册的邮箱发送注册验证码。 */
    void sendCode(String email);

    /**
     * 校验邀请码后消费邮箱验证码；账号、邀请关系及奖励原子提交，IP 由 HTTP 入口取得，不自动登录。
     */
    void register(String username, String email, String password, String code, String inviteCode, String clientIp);

    /** 校验密码，为本次登录创建独立 Token。 */
    LoginVO login(String username, String password);

    /** 获取当前登录账户；账户已不存在时注销当前会话。 */
    UserVO currentUser();

    /**
     * 免费上传专用头像；替换后清理旧专用头像，图库原图不受影响，用户编号来自登录态。
     */
    UserVO uploadAvatar(long userId, org.springframework.web.multipart.MultipartFile file);

    /**
     * 从本人未删除的图库图片选择头像，不复制原图、不扣积分。
     */
    UserVO selectAvatar(long userId, String imageId);

    /** 仅注销当前 Token。 */
    void logout();
}
