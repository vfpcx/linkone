-- V10: P2 入驻生态 Wave 1（入驻主链：申请 → 审批 → 黑名单 → OPS 代建）
-- 蓝图依据 03-database-schema.sql §3.2/§3.3 + tenant_applications 双轨审批先例（O-1）。
-- 与 V4 现状对齐：wholesalers 实际列为 name/owner_user_id/license/intro/status/source，
-- 申请表 license 字段与主表同名（VARCHAR 512，蓝图 license_no/license_url 合并占位）。

-- 1) 批发商入驻申请（双轨：申请表 + 主体表回填 wholesaler_id）
CREATE TABLE `wholesaler_applications` (
    `id`                BIGINT       NOT NULL PRIMARY KEY COMMENT '雪花ID',
    `tenant_id`         BIGINT       NOT NULL COMMENT '目标租户（TenantLine 隔离）',
    `applicant_user_id` BIGINT       NOT NULL COMMENT '申请人用户ID（WA 账号）',
    `name`              VARCHAR(128) NOT NULL COMMENT '商户名称',
    `contact_name`      VARCHAR(64)  NULL     COMMENT '联系人姓名',
    `contact_phone`     VARCHAR(20)  NULL     COMMENT '联系电话（黑名单键之一）',
    `license`           VARCHAR(512) NULL     COMMENT '营业执照号/凭证（黑名单键之一）',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED',
    `source`            VARCHAR(24)  NOT NULL DEFAULT 'SELF_APPLY' COMMENT '来源: SELF_APPLY/OPS_CREATED/TA_SELF_OPERATED',
    `auth_basis`        VARCHAR(512) NULL     COMMENT 'OPS 代建授权依据（TA 授权凭据文本或客诉单号，OPS_CREATED 必填）',
    `audit_user_id`     BIGINT       NULL     COMMENT '审核人（TA）',
    `audited_at`        DATETIME     NULL     COMMENT '审核时间',
    `audit_remark`      VARCHAR(512) NULL     COMMENT '审核意见（驳回必填）',
    `wholesaler_id`     BIGINT       NULL     COMMENT '通过后回填的批发商ID',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted_at`        DATETIME     NULL     COMMENT '软删时间(空=未删)',
    INDEX `idx_wsapp_tenant_status` (`tenant_id`, `status`, `created_at`),
    INDEX `idx_wsapp_applicant` (`applicant_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批发商入驻申请(P2 Wave1)';

-- 2) 平台黑名单（PLATFORM_TABLE，跨租户共享；不进 TenantLine 白名单——决策 O-6）
CREATE TABLE `blacklist` (
    `id`               BIGINT       NOT NULL PRIMARY KEY COMMENT '雪花ID',
    `target_type`      VARCHAR(16)  NOT NULL COMMENT '类型: PHONE/LICENSE_NO（手机号/执照号双键）',
    `target_value`     VARCHAR(64)  NOT NULL COMMENT '被拉黑的值',
    `reason`           VARCHAR(512) NOT NULL COMMENT '加黑原因',
    `operator_user_id` BIGINT       NOT NULL COMMENT '操作人（OPS，即 created_by 语义）',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/REMOVED',
    `removed_at`       DATETIME     NULL     COMMENT '解除时间',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted_at`       DATETIME     NULL     COMMENT '软删时间(空=未删)',
    UNIQUE KEY `uk_blacklist_type_value` (`target_type`, `target_value`),
    INDEX `idx_blacklist_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台黑名单(PLATFORM_TABLE, P2 Wave1)';

-- 3) wholesalers 补列：退驻申请时间（R13 逻辑 Wave2 落地，本波只建列）
ALTER TABLE `wholesalers` ADD COLUMN `withdraw_apply_at` DATETIME NULL COMMENT '退驻申请时间（60 天可恢复，R13）';
