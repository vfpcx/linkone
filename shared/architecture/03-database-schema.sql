-- =====================================================================
-- 03 · 数据库 DDL（MySQL 8.0 · v1）
-- 项目: 仓储云（通用仓储 SaaS 平台）
-- 版本: v1 · 2026-06-02
-- 编写: 架构师 Agent
-- 依赖: 99-arch-decisions.md / 01-tech-stack.md / 02-modules.md / PRD 03
-- 状态: 草案 → 待 Team Lead 复核
-- =====================================================================
--
-- 设计约束（强制）:
--   1. 字符集: utf8mb4 / 校对: utf8mb4_unicode_ci
--   2. 引擎: InnoDB
--   3. 所有业务表强制 tenant_id BIGINT NOT NULL（平台级表显式标注 PLATFORM_TABLE 豁免）
--   4. 主键: id BIGINT UNSIGNED（雪花 ID，应用层生成；ADR-011）
--   5. 软删: deleted_at DATETIME NULL（ADR-012），所有业务表统一
--   6. 时间戳: created_at / updated_at 强制；created_by / updated_by 业务表强制
--   7. 外键策略: 软外键（不加 FK 约束，由应用层 + 拦截器保证；ADR-007）
--   8. 索引规范: (tenant_id, primary_business_key) 必有；高频查询字段追加 (tenant_id, status, deleted_at)
--   9. 状态枚举: 使用 VARCHAR 存储 + 注释列出全部取值（避免 ENUM 升级困难）
--  10. 金额: DECIMAL(14,2)（保留 2 位）；单价 DECIMAL(14,4)（保留 4 位防四舍五入）
--  11. 坐标: lng/lat DECIMAL(10,7) + coordinate_system VARCHAR(8) DEFAULT 'GCJ02'（ADR-001）
--  12. 单据号: VARCHAR(64) 唯一索引；主键不暴露给前端（ADR-013）
--
-- 业务域分节:
--   §1 账号域 (account)
--   §2 租户域 (tenant)
--   §3 批发商域 (wholesaler)
--   §4 商品价格域 (product)
--   §5 库存域 (inventory)
--   §6 单据域 (document)
--   §7 计费域 (billing)
--   §8 平台运营域 (platform)
--   §9 横向支撑 (cross-cutting: address / voice / log / notification / file)
--
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =====================================================================
-- §1 账号域 (domain-account)
-- =====================================================================

-- 1.1 用户主表（多角色共享同一 User，按手机号唯一）
DROP TABLE IF EXISTS `users`;
CREATE TABLE `users` (
    `id`              BIGINT UNSIGNED NOT NULL COMMENT '雪花 ID',
    `phone`           VARCHAR(20)     NOT NULL COMMENT '手机号（脱敏存储展示，原文存库用于登录）',
    `phone_hash`      VARCHAR(64)     NOT NULL COMMENT '手机号 SHA-256 hash（用于唯一索引，防止明文暴露）',
    `password_hash`   VARCHAR(120)    NULL     COMMENT 'BCrypt 密码 hash（cost ≥ 10）；RT 验证码登录可为 NULL',
    `nickname`        VARCHAR(64)     NULL     COMMENT '昵称（注册时可空，后续可改）',
    `avatar_url`      VARCHAR(512)    NULL     COMMENT '头像 OSS URL',
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/FROZEN/CANCELLED/PENDING_CANCEL',
    `last_login_at`   DATETIME        NULL     COMMENT '最后登录时间',
    `last_login_ip`   VARCHAR(64)     NULL     COMMENT '最后登录 IP',
    `cancel_apply_at` DATETIME        NULL     COMMENT 'RT 注销冷静期开始时间（30 天后自动注销）',
    `register_source` VARCHAR(32)     NOT NULL DEFAULT 'SELF' COMMENT '注册来源: SELF/OPS_PROXY/INVITE_TENANT/INVITE_WA/RT_CODE',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL     COMMENT '软删除时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone_hash` (`phone_hash`),
    KEY `idx_status_deleted` (`status`, `deleted_at`),
    KEY `idx_last_login_at` (`last_login_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户主表（PLATFORM_TABLE 跨租户）';

-- 1.2 用户多角色绑定（User 1:N UserRole；同 User 可在不同 tenant 下有多个角色）
DROP TABLE IF EXISTS `user_roles`;
CREATE TABLE `user_roles` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `user_id`         BIGINT UNSIGNED NOT NULL,
    `role`            VARCHAR(16)     NOT NULL COMMENT '角色: OPS/TA/WK/ST/WA/WE/RT',
    `tenant_id`       BIGINT UNSIGNED NULL     COMMENT '所属租户（OPS/RT 为 NULL）',
    `wholesaler_id`   BIGINT UNSIGNED NULL     COMMENT '所属批发商（WA/WE 必填）',
    `store_id`        BIGINT UNSIGNED NULL     COMMENT '所属店铺（WK/ST 必填）',
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/DISABLED/REMOVED',
    `disabled_at`     DATETIME        NULL     COMMENT '禁用时间（禁用后 30 天内可恢复）',
    `priority`        TINYINT         NOT NULL DEFAULT 50 COMMENT '角色优先级（TA=10,ST=20,WK=30,WA=40,WE=50,RT=60，登录路由用）',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by`      BIGINT UNSIGNED NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role_scope` (`user_id`, `role`, `tenant_id`, `wholesaler_id`),
    KEY `idx_tenant_role` (`tenant_id`, `role`, `status`),
    KEY `idx_wholesaler_role` (`wholesaler_id`, `role`, `status`),
    KEY `idx_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色绑定（PLATFORM_TABLE 多租户聚合）';

-- 1.3 短信验证码（用途 + 手机号 + 验证码 + TTL）
DROP TABLE IF EXISTS `sms_codes`;
CREATE TABLE `sms_codes` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `phone`           VARCHAR(20)     NOT NULL,
    `scene`           VARCHAR(32)     NOT NULL COMMENT '场景: REGISTER/LOGIN/RESET_PWD/CHANGE_PHONE/BIND_PHONE/RT_LOGIN',
    `code`            VARCHAR(8)      NOT NULL COMMENT '验证码（6 位数字）',
    `expire_at`       DATETIME        NOT NULL COMMENT '过期时间（默认 5 分钟）',
    `verify_count`    TINYINT         NOT NULL DEFAULT 0 COMMENT '校验失败次数（≥3 锁定）',
    `verified_at`     DATETIME        NULL     COMMENT '校验成功时间',
    `request_ip`      VARCHAR(64)     NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_phone_scene_created` (`phone`, `scene`, `created_at`),
    KEY `idx_expire` (`expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短信验证码（PLATFORM_TABLE 实际数据存 Redis，DB 仅审计）';

-- 1.4 登录会话审计（Sa-Token Token 主存 Redis；此表记录登录历史）
DROP TABLE IF EXISTS `login_sessions`;
CREATE TABLE `login_sessions` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `user_id`         BIGINT UNSIGNED NOT NULL,
    `role`            VARCHAR(16)     NOT NULL COMMENT '本次登录角色',
    `tenant_id`       BIGINT UNSIGNED NULL,
    `device`          VARCHAR(16)     NOT NULL COMMENT '设备: PC/H5/MP/APP',
    `device_info`     VARCHAR(255)    NULL     COMMENT 'UA / 浏览器 / OS',
    `login_ip`        VARCHAR(64)     NULL,
    `login_method`    VARCHAR(16)     NOT NULL COMMENT '方式: PASSWORD/SMS_CODE/RT_AUTO',
    `token_hash`      VARCHAR(64)     NULL     COMMENT 'Token SHA-256 hash（防泄漏）',
    `logout_at`       DATETIME        NULL     COMMENT '主动退出时间',
    `kickout_reason`  VARCHAR(64)     NULL     COMMENT '被踢出原因: PWD_CHANGED/PHONE_CHANGED/FROZEN/MANUAL',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_created` (`user_id`, `created_at`),
    KEY `idx_tenant_created` (`tenant_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录会话审计（PLATFORM_TABLE）';

-- 1.5 密码历史（防止改密复用最近 N 次）
DROP TABLE IF EXISTS `password_history`;
CREATE TABLE `password_history` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `user_id`         BIGINT UNSIGNED NOT NULL,
    `password_hash`   VARCHAR(120)    NOT NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='密码历史（PLATFORM_TABLE 保留近 5 条）';

-- =====================================================================
-- §2 租户域 (domain-tenant)
-- =====================================================================

-- 2.1 租户主表
DROP TABLE IF EXISTS `tenants`;
CREATE TABLE `tenants` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_simple_code` VARCHAR(8)   NOT NULL COMMENT '租户简码（4 位字母数字，用于单据号；ADR-013）',
    `name`            VARCHAR(128)    NOT NULL COMMENT '租户名称（仓储公司名）',
    `legal_name`      VARCHAR(128)    NULL     COMMENT '法人/营业执照名称',
    `license_no`      VARCHAR(64)     NULL     COMMENT '营业执照号',
    `license_url`     VARCHAR(512)    NULL     COMMENT '营业执照 OSS URL',
    `contact_user_id` BIGINT UNSIGNED NOT NULL COMMENT '联系人（创始 TA）',
    `contact_phone`   VARCHAR(20)     NOT NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/ACTIVE/FROZEN/OFFLINE',
    `audit_user_id`   BIGINT UNSIGNED NULL     COMMENT 'OPS 审核人',
    `audited_at`      DATETIME        NULL,
    `audit_remark`    VARCHAR(512)    NULL,
    `created_by_ops`  TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否 OPS 代建（PRD R2）',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by`      BIGINT UNSIGNED NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_simple_code` (`tenant_simple_code`),
    KEY `idx_status_deleted` (`status`, `deleted_at`),
    KEY `idx_contact_phone` (`contact_phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户主表（PLATFORM_TABLE）';

-- 2.2 店铺/仓库（与 Tenant 1:1，概念分离便于未来一租户多仓）
DROP TABLE IF EXISTS `stores`;
CREATE TABLE `stores` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `name`            VARCHAR(128)    NOT NULL COMMENT '店铺/仓库名称',
    `address_id`      BIGINT UNSIGNED NULL     COMMENT '关联 addresses.id',
    `lng`             DECIMAL(10,7)   NULL     COMMENT '经度（GCJ-02）',
    `lat`             DECIMAL(10,7)   NULL     COMMENT '纬度（GCJ-02）',
    `coordinate_system` VARCHAR(8)    NOT NULL DEFAULT 'GCJ02',
    `total_capacity_qty`    INT       NULL     COMMENT '件数容量上限',
    `total_capacity_pallet` INT       NULL     COMMENT '托盘容量上限',
    `capacity_visibility`   VARCHAR(16) NOT NULL DEFAULT 'PUBLIC' COMMENT '容量可见性: PUBLIC/WA_ONLY/HIDDEN',
    `capacity_precision`    VARCHAR(16) NOT NULL DEFAULT 'TIER' COMMENT '精度档: EXACT/TIER/RANGE',
    `business_hours`  VARCHAR(64)     NULL     COMMENT '营业时间（如 09:00-18:00）',
    `intro`           TEXT            NULL     COMMENT '店铺介绍（撮合页）',
    `cover_url`       VARCHAR(512)    NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/FROZEN/OFFLINE',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by`      BIGINT UNSIGNED NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_id_name` (`tenant_id`, `name`),
    KEY `idx_tenant_status` (`tenant_id`, `status`, `deleted_at`),
    KEY `idx_visibility_status` (`capacity_visibility`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='店铺/仓库';

-- 2.3 租户级开关与设置（5 个开关；与 Tenant 1:1）
DROP TABLE IF EXISTS `tenant_settings`;
CREATE TABLE `tenant_settings` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `batch_enabled`   TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '批次管理开关（影响临期/FIFO）',
    `photo_mode`      VARCHAR(16)     NOT NULL DEFAULT 'NONE' COMMENT '入库拍照: NONE/REQUIRED/OPTIONAL',
    `billing_dim`     VARCHAR(16)     NOT NULL DEFAULT 'QTY' COMMENT '计费维度: QTY/PALLET',
    `expiry_threshold_days` INT       NOT NULL DEFAULT 30 COMMENT '临期阈值（天）',
    `display_image_source`  VARCHAR(16) NOT NULL DEFAULT 'STANDARD' COMMENT '展示图源: STANDARD/INBOUND/BOTH',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `updated_by`      BIGINT UNSIGNED NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户级开关';

-- 2.4 邀请码（店铺码 + 员工注册码）
DROP TABLE IF EXISTS `invite_codes`;
CREATE TABLE `invite_codes` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NULL     COMMENT '租户邀请码（TA/WK/ST/WA 入驻）',
    `wholesaler_id`   BIGINT UNSIGNED NULL     COMMENT '批发商邀请码（WE 注册）',
    `code`            VARCHAR(32)     NOT NULL,
    `target_role`     VARCHAR(16)     NOT NULL COMMENT '目标角色: TA/WK/ST/WA/WE',
    `max_uses`        INT             NOT NULL DEFAULT 1 COMMENT '可用次数（-1 不限）',
    `used_count`      INT             NOT NULL DEFAULT 0,
    `expire_at`       DATETIME        NULL     COMMENT '过期时间（NULL 永久）',
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/DISABLED/EXPIRED',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created_by`      BIGINT UNSIGNED NOT NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_tenant_status` (`tenant_id`, `status`),
    KEY `idx_wholesaler_status` (`wholesaler_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邀请码';

-- 2.5 容量公示快照（每 10 分钟覆盖；Redis 主存，此表备份/审计）
DROP TABLE IF EXISTS `capacity_publish`;
CREATE TABLE `capacity_publish` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `store_id`        BIGINT UNSIGNED NOT NULL,
    `used_qty`        INT             NOT NULL DEFAULT 0,
    `used_pallet`     INT             NOT NULL DEFAULT 0,
    `total_qty`       INT             NULL,
    `total_pallet`    INT             NULL,
    `utilization`     DECIMAL(5,2)    NULL     COMMENT '利用率%',
    `tier`            VARCHAR(16)     NULL     COMMENT '模糊档位: FULL/HIGH/MEDIUM/LOW/EMPTY',
    `snapshot_at`     DATETIME        NOT NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_snapshot` (`tenant_id`, `snapshot_at`),
    KEY `idx_store_snapshot` (`store_id`, `snapshot_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='容量公示快照（审计）';

-- 2.6 租户入驻申请（自助注册流程）
DROP TABLE IF EXISTS `tenant_applications`;
CREATE TABLE `tenant_applications` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `applicant_user_id` BIGINT UNSIGNED NOT NULL,
    `name`            VARCHAR(128)    NOT NULL,
    `legal_name`      VARCHAR(128)    NULL,
    `license_no`      VARCHAR(64)     NULL,
    `license_url`     VARCHAR(512)    NULL,
    `contact_phone`   VARCHAR(20)     NOT NULL,
    `address_text`    VARCHAR(255)    NULL,
    `lng`             DECIMAL(10,7)   NULL,
    `lat`             DECIMAL(10,7)   NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED/CANCELLED',
    `audit_user_id`   BIGINT UNSIGNED NULL,
    `audited_at`      DATETIME        NULL,
    `audit_remark`    VARCHAR(512)    NULL,
    `tenant_id`       BIGINT UNSIGNED NULL     COMMENT '通过后生成的 tenant_id',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_status_created` (`status`, `created_at`),
    KEY `idx_applicant` (`applicant_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户入驻申请（PLATFORM_TABLE）';

-- =====================================================================
-- §3 批发商域 (domain-wholesaler)
-- =====================================================================

-- 3.1 批发商主表
DROP TABLE IF EXISTS `wholesalers`;
CREATE TABLE `wholesalers` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL COMMENT '所属租户（多租户隔离强制）',
    `name`            VARCHAR(128)    NOT NULL COMMENT '批发商商号',
    `legal_name`      VARCHAR(128)    NULL,
    `license_no`      VARCHAR(64)     NULL,
    `license_url`     VARCHAR(512)    NULL,
    `contact_user_id` BIGINT UNSIGNED NOT NULL COMMENT '主联系人（创始 WA）',
    `contact_phone`   VARCHAR(20)     NOT NULL,
    `intro`           TEXT            NULL     COMMENT '撮合介绍',
    `cover_url`       VARCHAR(512)    NULL,
    `is_self_operated` TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '是否 TA 自营（PRD R5）',
    `pinned_order`    INT             NULL     COMMENT '撮合页置顶顺序（NULL 不置顶）',
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/ACTIVE/FROZEN/WITHDRAW_APPLIED/WITHDRAWN/BLACKLISTED',
    `withdraw_apply_at` DATETIME      NULL     COMMENT '退驻申请时间（60 天可恢复）',
    `created_by_ops`  TINYINT(1)      NOT NULL DEFAULT 0 COMMENT 'OPS 代建（PRD R3）',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by`      BIGINT UNSIGNED NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_name` (`tenant_id`, `name`),
    KEY `idx_tenant_status` (`tenant_id`, `status`, `deleted_at`),
    KEY `idx_phone` (`contact_phone`),
    KEY `idx_license` (`license_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='批发商主表';

-- 3.2 批发商入驻申请
DROP TABLE IF EXISTS `wholesaler_applications`;
CREATE TABLE `wholesaler_applications` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL COMMENT '目标租户',
    `applicant_user_id` BIGINT UNSIGNED NOT NULL,
    `name`            VARCHAR(128)    NOT NULL,
    `legal_name`      VARCHAR(128)    NULL,
    `license_no`      VARCHAR(64)     NULL,
    `license_url`     VARCHAR(512)    NULL,
    `contact_phone`   VARCHAR(20)     NOT NULL,
    `intro`           TEXT            NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED/CANCELLED',
    `audit_user_id`   BIGINT UNSIGNED NULL     COMMENT 'TA 审核人',
    `audited_at`      DATETIME        NULL,
    `audit_remark`    VARCHAR(512)    NULL,
    `wholesaler_id`   BIGINT UNSIGNED NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`, `created_at`),
    KEY `idx_applicant` (`applicant_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='批发商入驻申请';

-- 3.3 黑名单（平台级共享，PRD R3）
DROP TABLE IF EXISTS `blacklist`;
CREATE TABLE `blacklist` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `target_type`     VARCHAR(16)     NOT NULL COMMENT '类型: PHONE/LICENSE_NO',
    `target_value`    VARCHAR(64)     NOT NULL,
    `reason`          VARCHAR(512)    NOT NULL,
    `evidence_urls`   TEXT            NULL     COMMENT 'JSON 数组 OSS URL',
    `operator_user_id` BIGINT UNSIGNED NOT NULL COMMENT 'OPS 操作人',
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/REMOVED',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `removed_at`      DATETIME        NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_type_value` (`target_type`, `target_value`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='黑名单（PLATFORM_TABLE）';

-- =====================================================================
-- §4 商品与价格域 (domain-product)
-- =====================================================================

-- 4.1 SPU（平台标准品，OPS 维护）
DROP TABLE IF EXISTS `spus`;
CREATE TABLE `spus` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `code`            VARCHAR(64)     NULL     COMMENT 'SPU 编码（可选）',
    `name`            VARCHAR(255)    NOT NULL,
    `brand`           VARCHAR(64)     NULL,
    `category_l1`     VARCHAR(64)     NULL,
    `category_l2`     VARCHAR(64)     NULL,
    `standard_image_url` VARCHAR(512) NULL     COMMENT 'SPU 标准图',
    `description`     TEXT            NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/MERGED/OFFLINE',
    `merged_to_spu_id` BIGINT UNSIGNED NULL    COMMENT '合并指向（merge 场景）',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by`      BIGINT UNSIGNED NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_status_name` (`status`, `name`),
    KEY `idx_brand_category` (`brand`, `category_l1`),
    FULLTEXT KEY `ft_name_brand` (`name`, `brand`) /*!50100 WITH PARSER ngram */
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SPU（PLATFORM_TABLE）';

-- 4.2 规格类型（如「规格」、「包装」）
DROP TABLE IF EXISTS `spec_types`;
CREATE TABLE `spec_types` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `name`            VARCHAR(32)     NOT NULL COMMENT '规格类型名',
    `sort`            INT             NOT NULL DEFAULT 0,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/DISABLED',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='规格类型（PLATFORM_TABLE）';

-- 4.3 规格值（如「500ml」、「12 罐/箱」）
DROP TABLE IF EXISTS `spec_values`;
CREATE TABLE `spec_values` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `spec_type_id`    BIGINT UNSIGNED NOT NULL,
    `value`           VARCHAR(64)     NOT NULL,
    `sort`            INT             NOT NULL DEFAULT 0,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_type_value` (`spec_type_id`, `value`),
    KEY `idx_type_sort` (`spec_type_id`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='规格值（PLATFORM_TABLE）';

-- 4.4 SKU（批发商商品；含公开价/起批价/起批量）
DROP TABLE IF EXISTS `skus`;
CREATE TABLE `skus` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `spu_id`          BIGINT UNSIGNED NOT NULL,
    `code`            VARCHAR(64)     NULL     COMMENT 'WA 自定义编码',
    `name`            VARCHAR(255)    NOT NULL COMMENT 'SKU 名称（默认沿用 SPU）',
    `spec_summary`    VARCHAR(255)    NULL     COMMENT '规格摘要（如「500ml·12 罐/箱」）',
    `unit`            VARCHAR(16)     NOT NULL DEFAULT '件' COMMENT '计量单位（件/箱/包/支）',
    `public_price`    DECIMAL(14,4)   NULL     COMMENT '公开单价（零售）',
    `wholesale_price` DECIMAL(14,4)   NULL     COMMENT '起批价',
    `wholesale_qty`   INT             NULL     COMMENT '起批量（≥此量适用 wholesale_price）',
    `min_order_qty`   INT             NOT NULL DEFAULT 1 COMMENT '最小购买量',
    `display_image_url`  VARCHAR(512) NULL     COMMENT '当前展示图（按 tenant_settings.display_image_source）',
    `inbound_image_url`  VARCHAR(512) NULL     COMMENT '最近入库实拍（缓存）',
    `listing_status`  VARCHAR(16)     NOT NULL DEFAULT 'LISTED' COMMENT '上架状态: LISTED/UNLISTED',
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/OFFLINE',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by`      BIGINT UNSIGNED NULL,
    `updated_by`      BIGINT UNSIGNED NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_wholesaler_status` (`tenant_id`, `wholesaler_id`, `status`, `listing_status`, `deleted_at`),
    KEY `idx_tenant_spu` (`tenant_id`, `spu_id`),
    KEY `idx_wholesaler_name` (`wholesaler_id`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SKU 主表';

-- 4.5 SKU 图片（多图）
DROP TABLE IF EXISTS `sku_images`;
CREATE TABLE `sku_images` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `sku_id`          BIGINT UNSIGNED NOT NULL,
    `image_url`       VARCHAR(512)    NOT NULL,
    `image_type`      VARCHAR(16)     NOT NULL COMMENT '类型: STANDARD/INBOUND/MANUAL',
    `source_inbound_id` BIGINT UNSIGNED NULL  COMMENT '入库实拍引用 inbound_photos.id',
    `sort`            INT             NOT NULL DEFAULT 0,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_sku_sort` (`tenant_id`, `sku_id`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SKU 图片';

-- 4.6 客户专属价（(wholesaler, sku, rt_phone) 三元唯一）
DROP TABLE IF EXISTS `customer_prices`;
CREATE TABLE `customer_prices` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `sku_id`          BIGINT UNSIGNED NOT NULL,
    `rt_phone`        VARCHAR(20)     NOT NULL COMMENT 'RT 手机号（直接用手机号，未注册也可设置）',
    `rt_user_id`      BIGINT UNSIGNED NULL     COMMENT 'RT 已注册时回填',
    `price`           DECIMAL(14,4)   NOT NULL COMMENT '专属单价',
    `min_qty`         INT             NOT NULL DEFAULT 1 COMMENT '此价生效最小数量',
    `effective_from`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `effective_to`    DATETIME        NULL     COMMENT 'NULL 永久',
    `source`          VARCHAR(16)     NOT NULL DEFAULT 'MANUAL' COMMENT '来源: MANUAL/SETTLED_FROM_INQUIRY',
    `source_inquiry_id` BIGINT UNSIGNED NULL  COMMENT '沉淀来源询价单 id',
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/EXPIRED/REVOKED',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by`      BIGINT UNSIGNED NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wholesaler_sku_rt_active` (`wholesaler_id`, `sku_id`, `rt_phone`, `status`),
    KEY `idx_tenant_rt` (`tenant_id`, `rt_phone`),
    KEY `idx_sku_rt` (`sku_id`, `rt_phone`, `status`),
    KEY `idx_effective` (`effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客户专属价';

-- 4.7 调价历史（公开价 + 专属价 + 批量调价）
DROP TABLE IF EXISTS `price_change_log`;
CREATE TABLE `price_change_log` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `sku_id`          BIGINT UNSIGNED NOT NULL,
    `price_type`      VARCHAR(16)     NOT NULL COMMENT '类型: PUBLIC/WHOLESALE/CUSTOMER',
    `customer_price_id` BIGINT UNSIGNED NULL  COMMENT '专属价时引用',
    `rt_phone`        VARCHAR(20)     NULL,
    `old_price`       DECIMAL(14,4)   NULL,
    `new_price`       DECIMAL(14,4)   NOT NULL,
    `old_qty`         INT             NULL,
    `new_qty`         INT             NULL,
    `change_reason`   VARCHAR(64)     NULL     COMMENT '原因: MANUAL/BATCH/SETTLED/EXPIRED/REVOKED',
    `batch_op_id`     BIGINT UNSIGNED NULL     COMMENT '批量调价批次 id',
    `operator_user_id` BIGINT UNSIGNED NOT NULL,
    `operator_role`   VARCHAR(16)     NOT NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_sku_created` (`tenant_id`, `sku_id`, `created_at`),
    KEY `idx_tenant_wholesaler_created` (`tenant_id`, `wholesaler_id`, `created_at`),
    KEY `idx_batch` (`batch_op_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调价历史';

-- =====================================================================
-- §5 库存域 (domain-inventory)
-- =====================================================================

-- 5.1 批次（批次开关启用时按批次管理；关闭时只用 default batch）
DROP TABLE IF EXISTS `batches`;
CREATE TABLE `batches` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `sku_id`          BIGINT UNSIGNED NOT NULL,
    `batch_no`        VARCHAR(64)     NOT NULL COMMENT '批次号（关→启时占位为 DEFAULT-{date}）',
    `prod_date`       DATE            NULL     COMMENT '生产日期',
    `expiry_date`     DATE            NULL     COMMENT '保质期截止',
    `inbound_date`    DATE            NOT NULL COMMENT '首次入库日期',
    `source`          VARCHAR(16)     NOT NULL DEFAULT 'INBOUND' COMMENT '来源: INBOUND/PROXY_INBOUND/COUNT_GAIN/SWITCH_DEFAULT',
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/EXPIRING/EXPIRED/CLEARED',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by`      BIGINT UNSIGNED NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_wholesaler_sku_batch` (`tenant_id`, `wholesaler_id`, `sku_id`, `batch_no`),
    KEY `idx_tenant_sku_fifo` (`tenant_id`, `sku_id`, `inbound_date`),
    KEY `idx_tenant_expiry` (`tenant_id`, `expiry_date`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='批次';

-- 5.2 库存（按 sku × batch 维度；批次关闭时 batch_id = 0 占位）
DROP TABLE IF EXISTS `inventory`;
CREATE TABLE `inventory` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `sku_id`          BIGINT UNSIGNED NOT NULL,
    `batch_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '批次 id（关闭批次时为 0）',
    `in_stock_qty`    INT             NOT NULL DEFAULT 0 COMMENT '在库件数',
    `locked_qty`      INT             NOT NULL DEFAULT 0 COMMENT '锁定件数（待出库）',
    `pallet_qty`      INT             NOT NULL DEFAULT 0 COMMENT '托盘数（计费用）',
    `last_movement_at` DATETIME       NULL     COMMENT '最后变动时间',
    `version`         INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_wholesaler_sku_batch` (`tenant_id`, `wholesaler_id`, `sku_id`, `batch_id`),
    KEY `idx_tenant_wholesaler_sku` (`tenant_id`, `wholesaler_id`, `sku_id`),
    KEY `idx_tenant_in_stock` (`tenant_id`, `in_stock_qty`),
    KEY `idx_last_movement` (`tenant_id`, `last_movement_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存（按 sku×batch）';

-- 5.3 库存流水（5 类）
DROP TABLE IF EXISTS `stock_movements`;
CREATE TABLE `stock_movements` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `sku_id`          BIGINT UNSIGNED NOT NULL,
    `batch_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
    `movement_type`   VARCHAR(16)     NOT NULL COMMENT '类型: INBOUND/OUTBOUND/RETURN/COUNT_GAIN/COUNT_LOSS/CLEARANCE/REVERSE_INBOUND',
    `qty`             INT             NOT NULL COMMENT '变动数量（正负号区分）',
    `pallet_delta`    INT             NOT NULL DEFAULT 0 COMMENT '托盘变动',
    `before_qty`      INT             NOT NULL,
    `after_qty`       INT             NOT NULL,
    `doc_type`        VARCHAR(16)     NULL     COMMENT '关联单据类型: IN/OUT/RT/CS/EC',
    `doc_id`          BIGINT UNSIGNED NULL,
    `doc_no`          VARCHAR(64)     NULL,
    `remark`          VARCHAR(255)    NULL,
    `operator_user_id` BIGINT UNSIGNED NOT NULL,
    `operator_role`   VARCHAR(16)     NOT NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_wholesaler_sku_created` (`tenant_id`, `wholesaler_id`, `sku_id`, `created_at`),
    KEY `idx_tenant_type_created` (`tenant_id`, `movement_type`, `created_at`),
    KEY `idx_doc` (`doc_type`, `doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存流水';

-- 5.4 入库照片（按 batch / inbound_request 归属）
DROP TABLE IF EXISTS `inbound_photos`;
CREATE TABLE `inbound_photos` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `sku_id`          BIGINT UNSIGNED NOT NULL,
    `batch_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
    `inbound_request_id` BIGINT UNSIGNED NULL,
    `image_url`       VARCHAR(512)    NOT NULL,
    `thumbnail_url`   VARCHAR(512)    NULL,
    `uploader_user_id` BIGINT UNSIGNED NOT NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_sku_created` (`tenant_id`, `sku_id`, `created_at`),
    KEY `idx_inbound_request` (`inbound_request_id`),
    KEY `idx_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='入库照片';

-- =====================================================================
-- §6 单据域 (domain-document)
-- =====================================================================

-- 6.1 入库申请单
DROP TABLE IF EXISTS `inbound_requests`;
CREATE TABLE `inbound_requests` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `doc_no`          VARCHAR(64)     NOT NULL COMMENT '单据号 IN-{simple}-{yyyyMMdd}-{6位}',
    `submit_role`     VARCHAR(16)     NOT NULL COMMENT '提交方: WA/WK',
    `submit_user_id`  BIGINT UNSIGNED NOT NULL,
    `expect_arrive_at` DATETIME       NULL     COMMENT '预计到货时间',
    `transport_mode`  VARCHAR(32)     NULL     COMMENT '运输方式',
    `transport_info`  VARCHAR(255)    NULL     COMMENT '司机/车牌/物流单号',
    `remark`          VARCHAR(512)    NULL,
    `voice_record_id` BIGINT UNSIGNED NULL,
    `status`          VARCHAR(32)     NOT NULL COMMENT '状态: SUBMITTED/REJECTED/WITHDRAWN/ACCEPTED/REGISTERED/PENDING_WA_CONFIRM/CONFIRMED/DISPUTED/ARBITRATING/CLOSED',
    `wk_accept_at`    DATETIME        NULL     COMMENT 'WK 接受时间',
    `register_at`     DATETIME        NULL     COMMENT '实际入库登记时间',
    `wa_confirm_deadline` DATETIME    NULL     COMMENT '代建 WA 确认截止（72h）',
    `wa_confirm_at`   DATETIME        NULL,
    `dispute_reason`  VARCHAR(512)    NULL,
    `arbitrate_user_id` BIGINT UNSIGNED NULL,
    `arbitrate_result` VARCHAR(16)    NULL     COMMENT '仲裁结果: KEEP/REVERSE',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by`      BIGINT UNSIGNED NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_no` (`doc_no`),
    KEY `idx_tenant_wholesaler_status` (`tenant_id`, `wholesaler_id`, `status`, `created_at`),
    KEY `idx_tenant_status_deadline` (`tenant_id`, `status`, `wa_confirm_deadline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='入库申请单';

-- 6.2 入库申请单明细
DROP TABLE IF EXISTS `inbound_request_items`;
CREATE TABLE `inbound_request_items` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `inbound_request_id` BIGINT UNSIGNED NOT NULL,
    `sku_id`          BIGINT UNSIGNED NOT NULL,
    `sku_name_snapshot` VARCHAR(255)  NOT NULL COMMENT 'SKU 名快照',
    `spec_snapshot`   VARCHAR(255)    NULL,
    `qty`             INT             NOT NULL COMMENT '申请数量',
    `actual_qty`      INT             NULL     COMMENT '实际入库数量（登记后回填）',
    `batch_no`        VARCHAR(64)     NULL,
    `prod_date`       DATE            NULL,
    `expiry_date`     DATE            NULL,
    `pallet_qty`      INT             NULL,
    `batch_id`        BIGINT UNSIGNED NULL     COMMENT '登记后回填',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_request` (`tenant_id`, `inbound_request_id`),
    KEY `idx_tenant_sku` (`tenant_id`, `sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='入库申请明细';

-- 6.3 出库申请单
DROP TABLE IF EXISTS `outbound_requests`;
CREATE TABLE `outbound_requests` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `doc_no`          VARCHAR(64)     NOT NULL,
    `submit_role`     VARCHAR(16)     NOT NULL COMMENT '提交方: WA/WK_PROXY/RT_INQUIRY',
    `submit_user_id`  BIGINT UNSIGNED NOT NULL,
    `inquiry_id`      BIGINT UNSIGNED NULL     COMMENT '关联询价单（由询价确认转换）',
    `rt_user_id`      BIGINT UNSIGNED NULL     COMMENT '收货 RT',
    `rt_phone`        VARCHAR(20)     NULL,
    `rt_name`         VARCHAR(64)     NULL,
    `delivery_address_id` BIGINT UNSIGNED NULL,
    `delivery_address_snapshot` VARCHAR(512) NULL COMMENT '地址快照',
    `delivery_lng`    DECIMAL(10,7)   NULL,
    `delivery_lat`    DECIMAL(10,7)   NULL,
    `expect_pickup_at` DATETIME       NULL,
    `total_amount`    DECIMAL(14,2)   NOT NULL DEFAULT 0 COMMENT '应收总额（含整单优惠后）',
    `full_order_discount` DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '整单优惠',
    `remark`          VARCHAR(512)    NULL,
    `voice_record_id` BIGINT UNSIGNED NULL,
    `status`          VARCHAR(32)     NOT NULL COMMENT '状态: SUBMITTED/PRINTED/SHIPPED/COMPLAINED/ARBITRATING/CLOSED/CANCELLED',
    `print_at`        DATETIME        NULL,
    `ship_at`         DATETIME        NULL,
    `ship_user_id`    BIGINT UNSIGNED NULL     COMMENT '出库操作 WK',
    `complaint_reason` VARCHAR(512)   NULL,
    `arbitrate_user_id` BIGINT UNSIGNED NULL,
    `arbitrate_result` VARCHAR(32)    NULL,
    `large_amount_confirmed` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '代建大额二次确认',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by`      BIGINT UNSIGNED NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_no` (`doc_no`),
    KEY `idx_tenant_wholesaler_status` (`tenant_id`, `wholesaler_id`, `status`, `created_at`),
    KEY `idx_tenant_rt_phone` (`tenant_id`, `rt_phone`),
    KEY `idx_inquiry` (`inquiry_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出库申请单';

-- 6.4 出库明细（关键：单价快照）
DROP TABLE IF EXISTS `outbound_request_items`;
CREATE TABLE `outbound_request_items` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `outbound_request_id` BIGINT UNSIGNED NOT NULL,
    `sku_id`          BIGINT UNSIGNED NOT NULL,
    `sku_name_snapshot` VARCHAR(255)  NOT NULL,
    `spec_snapshot`   VARCHAR(255)    NULL,
    `unit_snapshot`   VARCHAR(16)     NOT NULL,
    `qty`             INT             NOT NULL,
    `unit_price_snapshot` DECIMAL(14,4) NOT NULL COMMENT '出库时单价快照（不随后续调价变化）',
    `price_source`    VARCHAR(16)     NOT NULL COMMENT '价格来源: PUBLIC/WHOLESALE/CUSTOMER/BARGAINED',
    `item_amount`     DECIMAL(14,2)   NOT NULL COMMENT 'qty × unit_price',
    `item_discount`   DECIMAL(14,2)   NOT NULL DEFAULT 0 COMMENT '本项优惠',
    `actual_amount`   DECIMAL(14,2)   NOT NULL COMMENT '本项应收',
    `batch_pick_detail` TEXT          NULL     COMMENT 'FIFO 拣货明细 JSON: [{batch_id,qty},...]',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_request` (`tenant_id`, `outbound_request_id`),
    KEY `idx_tenant_sku` (`tenant_id`, `sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出库明细';

-- 6.5 询价单（RT 提交 / WA 代下）
DROP TABLE IF EXISTS `inquiries`;
CREATE TABLE `inquiries` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `doc_no`          VARCHAR(64)     NOT NULL,
    `rt_user_id`      BIGINT UNSIGNED NULL,
    `rt_phone`        VARCHAR(20)     NOT NULL,
    `rt_name`         VARCHAR(64)     NULL,
    `submit_role`     VARCHAR(16)     NOT NULL COMMENT '提交方: RT/WA_PROXY',
    `submit_user_id`  BIGINT UNSIGNED NOT NULL,
    `total_amount`    DECIMAL(14,2)   NOT NULL DEFAULT 0,
    `full_order_discount` DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '整单优惠（不入仓储费）',
    `sink_option`     VARCHAR(16)     NULL     COMMENT '沉淀选项: SINK_TO_CUSTOMER/NO_SINK',
    `remark`          VARCHAR(512)    NULL,
    `voice_record_id` BIGINT UNSIGNED NULL,
    `status`          VARCHAR(32)     NOT NULL COMMENT '状态: SUBMITTED/BARGAINED/CONFIRMED/REJECTED/RT_CANCELLED/WA_VOIDED/EXPIRED',
    `confirm_at`      DATETIME        NULL,
    `outbound_request_id` BIGINT UNSIGNED NULL COMMENT '确认后自动生成出库单',
    `expire_at`       DATETIME        NULL     COMMENT '询价有效期',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_no` (`doc_no`),
    KEY `idx_tenant_wholesaler_status` (`tenant_id`, `wholesaler_id`, `status`, `created_at`),
    KEY `idx_tenant_rt_phone` (`tenant_id`, `rt_phone`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='询价单';

-- 6.6 询价明细
DROP TABLE IF EXISTS `inquiry_items`;
CREATE TABLE `inquiry_items` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `inquiry_id`      BIGINT UNSIGNED NOT NULL,
    `sku_id`          BIGINT UNSIGNED NOT NULL,
    `sku_name_snapshot` VARCHAR(255)  NOT NULL,
    `qty`             INT             NOT NULL,
    `init_price`      DECIMAL(14,4)   NOT NULL COMMENT '初始单价（取价时算法）',
    `deal_price`      DECIMAL(14,4)   NULL     COMMENT '议价后成交价（WA 确认时填）',
    `price_source`    VARCHAR(16)     NOT NULL COMMENT '初价来源: PUBLIC/WHOLESALE/CUSTOMER',
    `bargained`       TINYINT(1)      NOT NULL DEFAULT 0,
    `item_amount`     DECIMAL(14,2)   NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_inquiry` (`tenant_id`, `inquiry_id`),
    KEY `idx_tenant_sku` (`tenant_id`, `sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='询价明细';

-- 6.7 退货单
DROP TABLE IF EXISTS `return_requests`;
CREATE TABLE `return_requests` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `doc_no`          VARCHAR(64)     NOT NULL,
    `original_outbound_id` BIGINT UNSIGNED NULL COMMENT '原出库单',
    `rt_user_id`      BIGINT UNSIGNED NULL,
    `rt_phone`        VARCHAR(20)     NULL,
    `reason`          VARCHAR(512)    NOT NULL,
    `total_amount`    DECIMAL(14,2)   NOT NULL DEFAULT 0,
    `submit_user_id`  BIGINT UNSIGNED NOT NULL,
    `submit_role`     VARCHAR(16)     NOT NULL,
    `status`          VARCHAR(32)     NOT NULL COMMENT '状态: SUBMITTED/APPROVED/REJECTED/RECEIVED/CLOSED',
    `wk_receive_at`   DATETIME        NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_no` (`doc_no`),
    KEY `idx_tenant_wholesaler_status` (`tenant_id`, `wholesaler_id`, `status`, `created_at`),
    KEY `idx_original_outbound` (`original_outbound_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退货单';

-- 6.8 退货明细
DROP TABLE IF EXISTS `return_request_items`;
CREATE TABLE `return_request_items` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `return_request_id` BIGINT UNSIGNED NOT NULL,
    `sku_id`          BIGINT UNSIGNED NOT NULL,
    `batch_id`        BIGINT UNSIGNED NULL,
    `qty`             INT             NOT NULL,
    `unit_price_snapshot` DECIMAL(14,4) NOT NULL,
    `item_amount`     DECIMAL(14,2)   NOT NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_request` (`tenant_id`, `return_request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退货明细';

-- 6.9 盘点单（盘盈/盘亏）
DROP TABLE IF EXISTS `count_sheets`;
CREATE TABLE `count_sheets` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `doc_no`          VARCHAR(64)     NOT NULL,
    `count_type`      VARCHAR(16)     NOT NULL COMMENT '类型: REGULAR/EXPIRY_CLEAR',
    `submit_user_id`  BIGINT UNSIGNED NOT NULL,
    `submit_role`     VARCHAR(16)     NOT NULL COMMENT '提交: WK',
    `approve_user_id` BIGINT UNSIGNED NULL     COMMENT 'TA 审批人',
    `approve_at`      DATETIME        NULL,
    `status`          VARCHAR(32)     NOT NULL COMMENT '状态: SUBMITTED/APPROVED/REJECTED/CANCELLED',
    `remark`          VARCHAR(512)    NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_no` (`doc_no`),
    KEY `idx_tenant_wholesaler_status` (`tenant_id`, `wholesaler_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='盘点单';

-- 6.10 盘点明细
DROP TABLE IF EXISTS `count_sheet_items`;
CREATE TABLE `count_sheet_items` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `count_sheet_id`  BIGINT UNSIGNED NOT NULL,
    `sku_id`          BIGINT UNSIGNED NOT NULL,
    `batch_id`        BIGINT UNSIGNED NULL,
    `system_qty`      INT             NOT NULL COMMENT '系统数量',
    `actual_qty`      INT             NOT NULL COMMENT '实际盘点数量',
    `diff_qty`        INT             NOT NULL COMMENT '差异（actual - system）',
    `reason`          VARCHAR(255)    NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_sheet` (`tenant_id`, `count_sheet_id`),
    KEY `idx_tenant_sku` (`tenant_id`, `sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='盘点明细';

-- 6.11 临期强制清库单
DROP TABLE IF EXISTS `expiry_clearances`;
CREATE TABLE `expiry_clearances` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `doc_no`          VARCHAR(64)     NOT NULL,
    `submit_user_id`  BIGINT UNSIGNED NOT NULL COMMENT '发起 WK',
    `approve_user_id` BIGINT UNSIGNED NULL     COMMENT 'TA 审批',
    `approve_at`      DATETIME        NULL,
    `clearance_reason` VARCHAR(255)   NOT NULL,
    `evidence_urls`   TEXT            NULL     COMMENT 'JSON 凭证',
    `status`          VARCHAR(32)     NOT NULL COMMENT '状态: SUBMITTED/APPROVED/EXECUTED/REJECTED',
    `executed_at`     DATETIME        NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_no` (`doc_no`),
    KEY `idx_tenant_wholesaler_status` (`tenant_id`, `wholesaler_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='临期强制清库单';

-- 6.12 临期清库明细
DROP TABLE IF EXISTS `expiry_clearance_items`;
CREATE TABLE `expiry_clearance_items` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `clearance_id`    BIGINT UNSIGNED NOT NULL,
    `sku_id`          BIGINT UNSIGNED NOT NULL,
    `batch_id`        BIGINT UNSIGNED NOT NULL,
    `qty`             INT             NOT NULL,
    `expiry_date`     DATE            NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_clearance` (`tenant_id`, `clearance_id`),
    KEY `idx_tenant_sku_batch` (`tenant_id`, `sku_id`, `batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='临期清库明细';

-- =====================================================================
-- §7 计费域 (domain-billing)
-- =====================================================================

-- 7.1 计费规则（支持版本化，R20 分段计费）
DROP TABLE IF EXISTS `billing_rules`;
CREATE TABLE `billing_rules` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NULL     COMMENT 'NULL 表示租户默认；非 NULL 为单 WA 个性规则',
    `billing_dim`     VARCHAR(16)     NOT NULL COMMENT '维度: QTY/PALLET',
    `unit_price`      DECIMAL(14,4)   NOT NULL COMMENT '件·天 或 托盘·天 单价',
    `min_charge`      DECIMAL(14,2)   NOT NULL DEFAULT 0 COMMENT '保底费',
    `expiry_threshold_days` INT       NOT NULL DEFAULT 30,
    `extra_rule_json` TEXT            NULL     COMMENT '阶梯计价、临期加价等扩展规则 JSON',
    `effective_from`  DATE            NOT NULL,
    `effective_to`    DATE            NULL     COMMENT 'NULL 表示当前生效',
    `version`         INT             NOT NULL DEFAULT 1,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by`      BIGINT UNSIGNED NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_wholesaler_effective` (`tenant_id`, `wholesaler_id`, `effective_from`, `effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计费规则（版本化）';

-- 7.2 每日库存快照（每日 0 点生成，计费基础）
DROP TABLE IF EXISTS `daily_snapshots`;
CREATE TABLE `daily_snapshots` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `snapshot_date`   DATE            NOT NULL,
    `total_qty`       INT             NOT NULL DEFAULT 0,
    `total_pallet`    INT             NOT NULL DEFAULT 0,
    `expiry_qty`      INT             NOT NULL DEFAULT 0 COMMENT '临期件数',
    `daily_fee`       DECIMAL(14,4)   NOT NULL DEFAULT 0 COMMENT '当日费用',
    `billing_rule_id` BIGINT UNSIGNED NULL     COMMENT '适用规则 id',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_wholesaler_date` (`tenant_id`, `wholesaler_id`, `snapshot_date`),
    KEY `idx_tenant_date` (`tenant_id`, `snapshot_date`),
    KEY `idx_tenant_wholesaler_yyyymm` (`tenant_id`, `wholesaler_id`, `snapshot_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每日库存快照';

-- 7.3 账单主表
DROP TABLE IF EXISTS `bills`;
CREATE TABLE `bills` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `bill_no`         VARCHAR(64)     NOT NULL COMMENT 'BL-{tenant}-{wholesaler}-{yyyyMM}',
    `billing_month`   VARCHAR(7)      NOT NULL COMMENT '账单月份 yyyy-MM',
    `period_start`    DATE            NOT NULL,
    `period_end`      DATE            NOT NULL,
    `subtotal_amount` DECIMAL(14,2)   NOT NULL DEFAULT 0 COMMENT '小计',
    `adjust_amount`   DECIMAL(14,2)   NOT NULL DEFAULT 0 COMMENT '调整额（折扣减免）',
    `total_amount`    DECIMAL(14,2)   NOT NULL DEFAULT 0 COMMENT '应收总额',
    `paid_amount`     DECIMAL(14,2)   NOT NULL DEFAULT 0 COMMENT '已收款',
    `status`          VARCHAR(32)     NOT NULL COMMENT '状态: GENERATING/DRAFT/DISPATCHED/PARTIAL_PAID/PAID/DISPUTED/CANCELLED',
    `dispatch_at`     DATETIME        NULL     COMMENT '下发时间',
    `dispatch_user_id` BIGINT UNSIGNED NULL,
    `due_date`        DATE            NULL     COMMENT '应付日期',
    `pdf_url`         VARCHAR(512)    NULL,
    `excel_url`       VARCHAR(512)    NULL,
    `idempotent_key`  VARCHAR(64)     NOT NULL COMMENT '幂等键 bill:{tenant}:{wholesaler}:{yyyyMM}',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_by`      BIGINT UNSIGNED NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bill_no` (`bill_no`),
    UNIQUE KEY `uk_idempotent` (`idempotent_key`),
    KEY `idx_tenant_wholesaler_month` (`tenant_id`, `wholesaler_id`, `billing_month`),
    KEY `idx_tenant_status` (`tenant_id`, `status`, `dispatch_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账单主表';

-- 7.4 账单明细
DROP TABLE IF EXISTS `bill_items`;
CREATE TABLE `bill_items` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `bill_id`         BIGINT UNSIGNED NOT NULL,
    `item_type`       VARCHAR(16)     NOT NULL COMMENT '类型: STORAGE/ADJUSTMENT/MIN_CHARGE/EXPIRY_SURCHARGE/REVERSE',
    `item_date`       DATE            NULL     COMMENT '关联日期（仓储费按日）',
    `sku_id`          BIGINT UNSIGNED NULL,
    `qty_or_pallet`   INT             NULL,
    `unit_price`      DECIMAL(14,4)   NULL,
    `amount`          DECIMAL(14,2)   NOT NULL,
    `description`     VARCHAR(255)    NULL,
    `reverse_of_item_id` BIGINT UNSIGNED NULL COMMENT '冲销目标',
    `operator_user_id` BIGINT UNSIGNED NULL    COMMENT 'ST 操作人（调整/冲销时）',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_bill` (`tenant_id`, `bill_id`),
    KEY `idx_tenant_type_date` (`tenant_id`, `item_type`, `item_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账单明细';

-- 7.5 已收款记录
DROP TABLE IF EXISTS `payment_records`;
CREATE TABLE `payment_records` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `bill_id`         BIGINT UNSIGNED NOT NULL,
    `payment_no`      VARCHAR(64)     NOT NULL,
    `amount`          DECIMAL(14,2)   NOT NULL,
    `pay_at`          DATETIME        NOT NULL COMMENT '实付时间',
    `pay_method`      VARCHAR(16)     NULL     COMMENT 'BANK_TRANSFER/CASH/WX/ALIPAY/OTHER',
    `evidence_urls`   TEXT            NULL,
    `remark`          VARCHAR(255)    NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'EFFECTIVE' COMMENT '状态: EFFECTIVE/REVERSED',
    `reverse_reason`  VARCHAR(255)    NULL,
    `reverse_user_id` BIGINT UNSIGNED NULL,
    `reverse_at`      DATETIME        NULL,
    `created_by`      BIGINT UNSIGNED NOT NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_payment_no` (`payment_no`),
    KEY `idx_tenant_bill` (`tenant_id`, `bill_id`),
    KEY `idx_tenant_wholesaler` (`tenant_id`, `wholesaler_id`, `pay_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='已收款记录';

-- 7.6 账单申诉
DROP TABLE IF EXISTS `bill_disputes`;
CREATE TABLE `bill_disputes` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NOT NULL,
    `wholesaler_id`   BIGINT UNSIGNED NOT NULL,
    `bill_id`         BIGINT UNSIGNED NOT NULL,
    `submit_user_id`  BIGINT UNSIGNED NOT NULL COMMENT 'WA 提交人',
    `reason`          VARCHAR(512)    NOT NULL,
    `disputed_item_ids` TEXT          NULL     COMMENT 'JSON 数组 bill_item_id',
    `attachments`     TEXT            NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RESOLVED/REJECTED',
    `resolution`      VARCHAR(512)    NULL,
    `resolver_user_id` BIGINT UNSIGNED NULL    COMMENT 'ST 处理人',
    `resolved_at`     DATETIME        NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_bill` (`tenant_id`, `bill_id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账单申诉';

-- =====================================================================
-- §8 平台运营域 (domain-platform)
-- =====================================================================

-- 8.1 平台公告
DROP TABLE IF EXISTS `announcements`;
CREATE TABLE `announcements` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `title`           VARCHAR(128)    NOT NULL,
    `content`         TEXT            NOT NULL,
    `target_roles`    VARCHAR(64)     NOT NULL COMMENT '目标角色逗号分隔: TA,WK,ST,WA,WE,RT,ALL',
    `priority`        VARCHAR(16)     NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/IMPORTANT/URGENT',
    `effective_from`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `effective_to`    DATETIME        NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'PUBLISHED' COMMENT 'DRAFT/PUBLISHED/WITHDRAWN',
    `publish_user_id` BIGINT UNSIGNED NOT NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_status_effective` (`status`, `effective_from`, `effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台公告（PLATFORM_TABLE）';

-- 8.2 客诉单
DROP TABLE IF EXISTS `complaints`;
CREATE TABLE `complaints` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `complainant_user_id` BIGINT UNSIGNED NOT NULL,
    `complainant_role` VARCHAR(16)    NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NULL     COMMENT '涉事租户',
    `wholesaler_id`   BIGINT UNSIGNED NULL,
    `target_doc_type` VARCHAR(16)     NULL,
    `target_doc_id`   BIGINT UNSIGNED NULL,
    `category`        VARCHAR(32)     NOT NULL COMMENT '类别: BILL/DOCUMENT/SERVICE/OTHER',
    `title`           VARCHAR(128)    NOT NULL,
    `content`         TEXT            NOT NULL,
    `attachments`     TEXT            NULL,
    `priority`        VARCHAR(16)     NOT NULL DEFAULT 'NORMAL',
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/PROCESSING/RESOLVED/CLOSED',
    `assignee_user_id` BIGINT UNSIGNED NULL    COMMENT 'OPS 受理人',
    `resolution`      TEXT            NULL,
    `resolved_at`     DATETIME        NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_status_priority_created` (`status`, `priority`, `created_at`),
    KEY `idx_tenant_status` (`tenant_id`, `status`),
    KEY `idx_complainant` (`complainant_user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客诉单（PLATFORM_TABLE）';

-- =====================================================================
-- §9 横向支撑 (cross-cutting)
-- =====================================================================

-- 9.1 地址簿（RT 收货地址 / 仓库地址等）
DROP TABLE IF EXISTS `addresses`;
CREATE TABLE `addresses` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NULL     COMMENT '租户地址（仓库）有；RT 地址 NULL',
    `owner_user_id`   BIGINT UNSIGNED NULL     COMMENT 'RT 地址主',
    `owner_type`      VARCHAR(16)     NOT NULL COMMENT '类型: STORE/RT/WHOLESALER',
    `contact_name`    VARCHAR(64)     NULL,
    `contact_phone`   VARCHAR(20)     NULL,
    `province`        VARCHAR(32)     NULL,
    `city`            VARCHAR(32)     NULL,
    `district`        VARCHAR(32)     NULL,
    `detail`          VARCHAR(255)    NOT NULL,
    `lng`             DECIMAL(10,7)   NULL,
    `lat`             DECIMAL(10,7)   NULL,
    `coordinate_system` VARCHAR(8)    NOT NULL DEFAULT 'GCJ02',
    `is_default`      TINYINT(1)      NOT NULL DEFAULT 0,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_owner` (`owner_type`, `owner_user_id`),
    KEY `idx_tenant_owner` (`tenant_id`, `owner_type`),
    KEY `idx_geo` (`lng`, `lat`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地址簿';

-- 9.2 语音录音
DROP TABLE IF EXISTS `voice_records`;
CREATE TABLE `voice_records` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NULL     COMMENT '租户场景；RT 询价时为 NULL',
    `user_id`         BIGINT UNSIGNED NOT NULL,
    `scene`           VARCHAR(32)     NOT NULL COMMENT '场景: INBOUND/OUTBOUND/INQUIRY',
    `audio_url`       VARCHAR(512)    NOT NULL COMMENT 'OSS URL（30 天 lifecycle 删除）',
    `duration_sec`    INT             NULL,
    `transcript`      TEXT            NULL     COMMENT '完整转写文本',
    `confidence`      DECIMAL(5,4)    NULL     COMMENT '识别置信度 0-1',
    `extracted_fields` TEXT           NULL     COMMENT 'NLU 抽取字段 JSON',
    `asr_provider`    VARCHAR(16)     NOT NULL DEFAULT 'ALIYUN_NLS',
    `expire_at`       DATETIME        NOT NULL COMMENT '30 天后清理',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_user_created` (`user_id`, `created_at`),
    KEY `idx_expire` (`expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='语音录音';

-- 9.3 操作日志（按月分区；ADR-014）
DROP TABLE IF EXISTS `operation_logs`;
CREATE TABLE `operation_logs` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NULL     COMMENT 'OPS 跨租户日志为 NULL',
    `actor_user_id`   BIGINT UNSIGNED NOT NULL,
    `actor_role`      VARCHAR(16)     NOT NULL,
    `module`          VARCHAR(32)     NOT NULL COMMENT '模块: ACCOUNT/TENANT/PRICE/INVENTORY/DOC/BILL/OPS 等',
    `action`          VARCHAR(64)     NOT NULL,
    `description`     VARCHAR(255)    NULL,
    `target_type`     VARCHAR(32)     NULL,
    `target_id`       BIGINT UNSIGNED NULL,
    `old_value`       TEXT            NULL,
    `new_value`       TEXT            NULL,
    `request_ip`      VARCHAR(64)     NULL,
    `user_agent`      VARCHAR(255)    NULL,
    `trace_id`        VARCHAR(64)     NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`, `created_at`),
    KEY `idx_tenant_module_created` (`tenant_id`, `module`, `created_at`),
    KEY `idx_actor_created` (`actor_user_id`, `created_at`),
    KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志（按月 RANGE 分区）'
PARTITION BY RANGE (TO_DAYS(created_at)) (
    PARTITION p202606 VALUES LESS THAN (TO_DAYS('2026-07-01')),
    PARTITION p202607 VALUES LESS THAN (TO_DAYS('2026-08-01')),
    PARTITION p202608 VALUES LESS THAN (TO_DAYS('2026-09-01')),
    PARTITION p202609 VALUES LESS THAN (TO_DAYS('2026-10-01')),
    PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
    PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
    PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
    PARTITION p_max   VALUES LESS THAN MAXVALUE
);

-- 9.4 通知（站内信主表，短信走 MQ 不落表）
DROP TABLE IF EXISTS `notifications`;
CREATE TABLE `notifications` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NULL,
    `recipient_user_id` BIGINT UNSIGNED NOT NULL,
    `recipient_role`  VARCHAR(16)     NOT NULL,
    `channel`         VARCHAR(16)     NOT NULL COMMENT 'INBOX/SMS/PUSH/EMAIL',
    `template_code`   VARCHAR(64)     NULL,
    `title`           VARCHAR(128)    NOT NULL,
    `content`         TEXT            NOT NULL,
    `link_url`        VARCHAR(512)    NULL     COMMENT '点击跳转链接',
    `biz_type`        VARCHAR(32)     NULL     COMMENT '业务类型: BILL_DISPATCH/INBOUND_NEW 等',
    `biz_id`          BIGINT UNSIGNED NULL,
    `event_id`        VARCHAR(64)     NULL     COMMENT '幂等键（同 event_id 24h 不重发）',
    `read_at`         DATETIME        NULL,
    `sent_at`         DATETIME        NULL,
    `send_status`     VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED',
    `failure_reason`  VARCHAR(255)    NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_recipient` (`event_id`, `recipient_user_id`),
    KEY `idx_recipient_read_created` (`recipient_user_id`, `read_at`, `created_at`),
    KEY `idx_tenant_biz` (`tenant_id`, `biz_type`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知';

-- 9.5 通用附件（与业务弱耦合，按 owner 关联）
DROP TABLE IF EXISTS `attachments`;
CREATE TABLE `attachments` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `tenant_id`       BIGINT UNSIGNED NULL,
    `owner_type`      VARCHAR(32)     NOT NULL COMMENT '类型: INBOUND/OUTBOUND/COMPLAINT/BILL_DISPUTE/WHOLESALER_LICENSE 等',
    `owner_id`        BIGINT UNSIGNED NOT NULL,
    `file_name`       VARCHAR(255)    NOT NULL,
    `file_url`        VARCHAR(512)    NOT NULL,
    `file_type`       VARCHAR(16)     NULL     COMMENT 'IMG/PDF/DOC/AUDIO/OTHER',
    `mime_type`       VARCHAR(64)     NULL,
    `size_bytes`      BIGINT          NULL,
    `bucket`          VARCHAR(64)     NULL,
    `oss_key`         VARCHAR(512)    NULL,
    `uploader_user_id` BIGINT UNSIGNED NOT NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    KEY `idx_owner` (`owner_type`, `owner_id`),
    KEY `idx_tenant_uploader` (`tenant_id`, `uploader_user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通用附件';

-- 9.6 单据序列辅助表（Redis 不可用兜底；正常走 Redis）
DROP TABLE IF EXISTS `doc_seq_fallback`;
CREATE TABLE `doc_seq_fallback` (
    `id`              BIGINT UNSIGNED NOT NULL,
    `doc_type`        VARCHAR(8)      NOT NULL COMMENT 'IN/OUT/IQ/RT/CS/EC/BL',
    `tenant_simple_code` VARCHAR(8)   NOT NULL,
    `seq_date`        DATE            NOT NULL,
    `current_seq`     INT             NOT NULL DEFAULT 0,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_type_code_date` (`doc_type`, `tenant_simple_code`, `seq_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单据号序列兜底表';

SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================================
-- 索引设计要点（速查）
-- =====================================================================
-- 1. 多租户索引：所有业务表 (tenant_id, ...) 复合索引，TenantInterceptor 注入 tenant_id 后命中
-- 2. 库存核心索引：idx_inventory_tenant_wholesaler_sku（出库 FIFO 拣货走此路径）
-- 3. 单据状态扫描：idx_tenant_status_deadline（72h 自动接受 Job 用）
-- 4. 客户专属价命中：idx_sku_rt（PriceService 价格匹配走此索引，≤200ms）
-- 5. 账单幂等：uk_idempotent 防重复生成（ADR-008）
-- 6. 操作日志分区：按月 RANGE 分区，便于按月归档至 OSS
-- 7. 软删除过滤：高频查询表追加 (..., deleted_at) 复合，避免回表
-- 8. 时间范围：created_at / snapshot_date 在范围查询表作为索引末位
--
-- 后端 Agent 落地提示:
-- - Flyway migration: V1__init_schema.sql 全量执行本文件；分区表初始化包含 2026 年 12 个月分区
-- - 业务表新增字段必须走 V{n}__add_xxx.sql；禁止线上手动改库
-- - 测试集成 TenantLeakDetector：扫描所有 Mapper SQL，缺 tenant_id 条件即失败
-- =====================================================================
