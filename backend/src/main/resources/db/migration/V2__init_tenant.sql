-- V2__init_tenant.sql
-- 租户相关表

CREATE TABLE IF NOT EXISTS `tenants` (
    `id`              BIGINT NOT NULL,
    `tenant_simple_code` VARCHAR(8)   NOT NULL,
    `name`            VARCHAR(128)    NOT NULL,
    `legal_name`      VARCHAR(128)    NULL,
    `license_no`      VARCHAR(64)     NULL,
    `license_url`     VARCHAR(512)    NULL,
    `contact_user_id` BIGINT NOT NULL,
    `contact_phone`   VARCHAR(20)     NOT NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    `audit_user_id`   BIGINT NULL,
    `audited_at`      DATETIME        NULL,
    `audit_remark`    VARCHAR(512)    NULL,
    `created_by_ops`  TINYINT         NOT NULL DEFAULT 0,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created_by`      BIGINT NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_simple_code` (`tenant_simple_code`)
);

CREATE TABLE IF NOT EXISTS `stores` (
    `id`              BIGINT NOT NULL,
    `tenant_id`       BIGINT NOT NULL,
    `name`            VARCHAR(128)    NOT NULL,
    `address_id`      BIGINT NULL,
    `lng`             DECIMAL(10,7)   NULL,
    `lat`             DECIMAL(10,7)   NULL,
    `coordinate_system` VARCHAR(8)    NOT NULL DEFAULT 'GCJ02',
    `total_capacity_qty`    INT       NULL,
    `total_capacity_pallet` INT       NULL,
    `capacity_visibility`   VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    `capacity_precision`    VARCHAR(16) NOT NULL DEFAULT 'TIER',
    `business_hours`  VARCHAR(64)     NULL,
    `intro`           TEXT            NULL,
    `cover_url`       VARCHAR(512)    NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created_by`      BIGINT NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_id_name` (`tenant_id`, `name`)
);

CREATE TABLE IF NOT EXISTS `tenant_settings` (
    `id`              BIGINT NOT NULL,
    `tenant_id`       BIGINT NOT NULL,
    `batch_enabled`   TINYINT         NOT NULL DEFAULT 0,
    `photo_mode`      VARCHAR(16)     NOT NULL DEFAULT 'NONE',
    `billing_dim`     VARCHAR(16)     NOT NULL DEFAULT 'QTY',
    `expiry_threshold_days` INT       NOT NULL DEFAULT 30,
    `display_image_source`  VARCHAR(16) NOT NULL DEFAULT 'STANDARD',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_by`      BIGINT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant` (`tenant_id`)
);

CREATE TABLE IF NOT EXISTS `invite_codes` (
    `id`              BIGINT NOT NULL,
    `tenant_id`       BIGINT NULL,
    `wholesaler_id`   BIGINT NULL,
    `code`            VARCHAR(32)     NOT NULL,
    `target_role`     VARCHAR(16)     NOT NULL,
    `max_uses`        INT             NOT NULL DEFAULT 1,
    `used_count`      INT             NOT NULL DEFAULT 0,
    `expire_at`       DATETIME        NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created_by`      BIGINT NOT NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
);

CREATE TABLE IF NOT EXISTS `capacity_publish` (
    `id`              BIGINT NOT NULL,
    `tenant_id`       BIGINT NOT NULL,
    `store_id`        BIGINT NOT NULL,
    `used_qty`        INT             NOT NULL DEFAULT 0,
    `used_pallet`     INT             NOT NULL DEFAULT 0,
    `total_qty`       INT             NULL,
    `total_pallet`    INT             NULL,
    `utilization`     DECIMAL(5,2)    NULL,
    `tier`            VARCHAR(16)     NULL,
    `snapshot_at`     DATETIME        NOT NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `tenant_applications` (
    `id`              BIGINT NOT NULL,
    `applicant_user_id` BIGINT NOT NULL,
    `name`            VARCHAR(128)    NOT NULL,
    `legal_name`      VARCHAR(128)    NULL,
    `license_no`      VARCHAR(64)     NULL,
    `license_url`     VARCHAR(512)    NULL,
    `contact_phone`   VARCHAR(20)     NOT NULL,
    `address_text`    VARCHAR(255)    NULL,
    `lng`             DECIMAL(10,7)   NULL,
    `lat`             DECIMAL(10,7)   NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    `audit_user_id`   BIGINT NULL,
    `audited_at`      DATETIME        NULL,
    `audit_remark`    VARCHAR(512)    NULL,
    `tenant_id`       BIGINT NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`)
);
