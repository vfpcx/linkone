-- V39__p5d_c3_customer_followups.sql
-- C3 客户跟进（US-WE-04，architecture/24-p5-c-c3 §3）：客户跟进档案 + 跟进提醒两表。
-- 客户 = 当前商户（wholesaler）按 rt_phone_hmac 归并的询价买家（inquiry_requests 唯一源头，
-- 本表仅存档案：备注/冗余密文/提醒时点，不重新定义客户）。
-- 行同存 tenant_id，纳入 TenantLine 白名单；业务层按 wholesaler 收敛（tenant + wholesaler 双层）。
-- H2(MODE=MySQL) 兼容：标准 SQL 单文件（沿 V36 先例）。
-- 索引名加 cf_/fr_ 前缀防 H2 全局索引重名（沿 V22 bat_ 先例）。

CREATE TABLE `customer_followups` (
    `id`              BIGINT       NOT NULL COMMENT '雪花ID',
    `tenant_id`       BIGINT       NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `wholesaler_id`   BIGINT       NOT NULL COMMENT '归属商户（客户=该商户询价买家，业务层收敛）',
    `rt_phone_hmac`   VARCHAR(64)  NOT NULL COMMENT 'RT 手机号 HMAC-SHA256 盲索引（PiiCrypto.phoneHmac 唯一产生点）',
    `rt_phone_cipher` VARCHAR(255) NULL COMMENT 'RT 手机号密文冗余（Job 站内信打尾号用；建档/更新时复制自最新询价单）',
    `remark`          VARCHAR(200) NULL COMMENT '跟进备注（覆盖式，空串=清除备注）',
    `created_by`      BIGINT       NOT NULL COMMENT '建档操作人（WE/WA）',
    `updated_by`      BIGINT       NULL COMMENT '最后更新人',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_cf_wholesaler_hmac` UNIQUE (`wholesaler_id`, `rt_phone_hmac`),
    KEY `idx_cf_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户跟进档案(C3 US-WE-04，24-p5-c-c3 §3.1；归属 document 域)';

CREATE TABLE `followup_reminders` (
    `id`                   BIGINT       NOT NULL COMMENT '雪花ID',
    `tenant_id`            BIGINT       NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `wholesaler_id`        BIGINT       NOT NULL COMMENT '归属商户（冗余：收敛校验 / Job 直取）',
    `customer_followup_id` BIGINT       NOT NULL COMMENT '客户档案 id（无级联，删提醒单独处理）',
    `content`              VARCHAR(200) NOT NULL COMMENT '提醒内容（≤200，必填）',
    `remind_at`            DATETIME     NOT NULL COMMENT '提醒时点（须晚于 now）',
    `reminded_at`          DATETIME     NULL COMMENT '站内信触发时刻（空=未触发；Job CAS 防重位）',
    `created_by`           BIGINT       NOT NULL COMMENT '创建人（=站内信收件人）',
    `created_at`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_fr_due` (`remind_at`, `reminded_at`),
    KEY `idx_fr_followup` (`customer_followup_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户跟进提醒(C3 US-WE-04，24-p5-c-c3 §3.2；归属 document 域)';
