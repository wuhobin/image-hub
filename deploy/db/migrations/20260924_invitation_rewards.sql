-- 已有库执行一次；新库直接使用 schema.sql。老用户邀请码在访问个人中心时生成。
ALTER TABLE hub_user
    ADD COLUMN invite_code VARCHAR(32) DEFAULT NULL COMMENT '稳定的公开邀请码，按需生成',
    ADD UNIQUE KEY uk_hub_user_invite_code (invite_code);

CREATE TABLE IF NOT EXISTS hub_user_invitation
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    PRIMARY
    KEY,
    inviter_id
    BIGINT
    NOT
    NULL
    COMMENT
    '直接邀请人',
    invitee_id
    BIGINT
    NOT
    NULL
    COMMENT
    '受邀新用户，一生只能绑定一次',
    register_ip
    VARCHAR
(
    45
) NOT NULL COMMENT '服务端取得的注册来源，仅供审核',
    inviter_points INT NOT NULL COMMENT '注册时邀请人奖励快照',
    invitee_points INT NOT NULL COMMENT '注册时新用户奖励快照',
    status VARCHAR
(
    12
) NOT NULL COMMENT 'PAID已到账，PENDING待审核，REJECTED已拒绝，DISABLED活动暂停',
    risk_reason VARCHAR
(
    255
) DEFAULT NULL,
    reviewer_id BIGINT DEFAULT NULL,
    review_note VARCHAR
(
    255
) DEFAULT NULL,
    create_time datetime DEFAULT CURRENT_TIMESTAMP,
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_hub_invitation_invitee
(
    invitee_id
),
    KEY idx_hub_invitation_recent
(
    inviter_id,
    register_ip,
    deleted,
    create_time
),
    KEY idx_hub_invitation_admin
(
    status,
    deleted,
    create_time,
    id
),
    CONSTRAINT chk_hub_invitation_participants CHECK
(
    inviter_id
    <>
    invitee_id
),
    CONSTRAINT chk_hub_invitation_points CHECK
(
    inviter_points >
    0
    AND
    invitee_points >
    0
),
    CONSTRAINT chk_hub_invitation_status CHECK
(
    status
    IN
(
    'PAID',
    'PENDING',
    'REJECTED',
    'DISABLED'
))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_unicode_ci;

INSERT INTO hub_settings (config_key, config_value, description)
SELECT 'invitation.enabled',
       'true',
       '是否对新的邀请注册发放奖励' WHERE NOT EXISTS (SELECT 1 FROM hub_settings WHERE config_key = 'invitation.enabled');

INSERT INTO hub_settings (config_key, config_value, description)
SELECT 'invitation.inviter-points',
       '20',
       '邀请人每次成功邀请奖励' WHERE NOT EXISTS (SELECT 1 FROM hub_settings WHERE config_key = 'invitation.inviter-points');

INSERT INTO hub_settings (config_key, config_value, description)
SELECT 'invitation.invitee-points',
       '20',
       '新用户通过邀请注册的额外奖励' WHERE NOT EXISTS (SELECT 1 FROM hub_settings WHERE config_key = 'invitation.invitee-points');
