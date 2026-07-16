-- V11: P2 入驻生态 Wave 2（R13 退驻 + R14 强制下架）
-- 蓝图依据 04-core-flows §1.8 状态机：正常→已退驻(TA 审批)→60 天恢复/归档；正常→已下架(TA 单方即时)。
-- 复用 Wave1 双轨审批先例：退驻申请表 + wholesalers 主体状态翻转。

-- 1) 批发商退驻申请（R13：WA 发起 → TA 审批，TenantLine 隔离）
CREATE TABLE `wholesaler_withdraw_applications` (
    `id`                BIGINT       NOT NULL PRIMARY KEY COMMENT '雪花ID',
    `tenant_id`         BIGINT       NOT NULL COMMENT '所属租户（TA 审批侧隔离维度）',
    `wholesaler_id`     BIGINT       NOT NULL COMMENT '退驻的批发商主体',
    `applicant_user_id` BIGINT       NOT NULL COMMENT '申请人（该商户 WA 账号）',
    `reason`            VARCHAR(512) NULL     COMMENT '退驻原因（选填）',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED/CANCELLED(WA撤回)',
    `audit_user_id`     BIGINT       NULL     COMMENT '审核人（TA）',
    `audited_at`        DATETIME     NULL     COMMENT '审核时间（通过时刻=60 天恢复/归档窗口起点，BND-S3-01 统一口径）',
    `audit_remark`      VARCHAR(512) NULL     COMMENT '审核意见（驳回必填）',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted_at`        DATETIME     NULL     COMMENT '软删时间(空=未删)',
    INDEX `idx_wswd_tenant_status` (`tenant_id`, `status`, `created_at`),
    INDEX `idx_wswd_wholesaler` (`wholesaler_id`),
    INDEX `idx_wswd_applicant` (`applicant_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批发商退驻申请(P2 Wave2 R13)';

-- 2) wholesalers 状态扩展列（withdraw_apply_at V10 已建）
-- withdrawn_at：退驻审批通过时刻（=申请单 audited_at 快照），60 天恢复窗口与归档任务的唯一时间基准，
--               恢复/归档判断一律用数据库时间与该列比较（不用应用时钟，防时区/口径漂移）。
ALTER TABLE `wholesalers` ADD COLUMN `withdrawn_at` DATETIME NULL COMMENT '退驻生效时间（审批通过时刻，60 天窗口起点）';
ALTER TABLE `wholesalers` ADD COLUMN `offline_at` DATETIME NULL COMMENT '强制下架时间（R14）';
ALTER TABLE `wholesalers` ADD COLUMN `offline_reason` VARCHAR(512) NULL COMMENT '强制下架原因（R14 必填留痕）';
ALTER TABLE `wholesalers` ADD COLUMN `archived_at` DATETIME NULL COMMENT '归档时间（退驻超 60 天）';
