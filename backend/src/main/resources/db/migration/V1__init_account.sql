-- V1__init_account.sql
-- 账号相关表

CREATE TABLE IF NOT EXISTS `users` (
    `id`              BIGINT NOT NULL,
    `phone`           VARCHAR(20)     NOT NULL,
    `phone_hash`      VARCHAR(64)     NOT NULL,
    `password_hash`   VARCHAR(120)    NULL,
    `nickname`        VARCHAR(64)     NULL,
    `avatar_url`      VARCHAR(512)    NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    `last_login_at`   DATETIME        NULL,
    `last_login_ip`   VARCHAR(64)     NULL,
    `cancel_apply_at` DATETIME        NULL,
    `register_source` VARCHAR(32)     NOT NULL DEFAULT 'SELF',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone_hash` (`phone_hash`)
);

CREATE TABLE IF NOT EXISTS `user_roles` (
    `id`              BIGINT NOT NULL,
    `user_id`         BIGINT NOT NULL,
    `role`            VARCHAR(16)     NOT NULL,
    `tenant_id`       BIGINT NULL,
    `wholesaler_id`   BIGINT NULL,
    `store_id`        BIGINT NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    `disabled_at`     DATETIME        NULL,
    `priority`        TINYINT         NOT NULL DEFAULT 50,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created_by`      BIGINT NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role_scope` (`user_id`, `role`, `tenant_id`, `wholesaler_id`)
);

CREATE TABLE IF NOT EXISTS `sms_codes` (
    `id`              BIGINT NOT NULL,
    `phone`           VARCHAR(20)     NOT NULL,
    `scene`           VARCHAR(32)     NOT NULL,
    `code`            VARCHAR(8)      NOT NULL,
    `expire_at`       DATETIME        NOT NULL,
    `verify_count`    TINYINT         NOT NULL DEFAULT 0,
    `verified_at`     DATETIME        NULL,
    `request_ip`      VARCHAR(64)     NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `login_sessions` (
    `id`              BIGINT NOT NULL,
    `user_id`         BIGINT NOT NULL,
    `role`            VARCHAR(16)     NOT NULL,
    `tenant_id`       BIGINT NULL,
    `device`          VARCHAR(16)     NOT NULL,
    `device_info`     VARCHAR(255)    NULL,
    `login_ip`        VARCHAR(64)     NULL,
    `login_method`    VARCHAR(16)     NOT NULL,
    `token_hash`      VARCHAR(64)     NULL,
    `logout_at`       DATETIME        NULL,
    `kickout_reason`  VARCHAR(64)     NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `password_history` (
    `id`              BIGINT NOT NULL,
    `user_id`         BIGINT NOT NULL,
    `password_hash`   VARCHAR(120)    NOT NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);
