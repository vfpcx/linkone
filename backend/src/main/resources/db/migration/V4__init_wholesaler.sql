-- V4__init_wholesaler.sql
-- phase-1 A1：批发商商户基座（TA 自营创建）。仅 wholesalers 表；不含 product/inventory/document。
-- 模型沿用 02-modules.md §2.3 domain-wholesaler 的 phase-1 子集：status 直接 ACTIVE、source=SELF_OPERATED。

CREATE TABLE IF NOT EXISTS `wholesalers` (
    `id`              BIGINT NOT NULL,
    `tenant_id`       BIGINT NOT NULL,
    `name`            VARCHAR(128)    NOT NULL,
    `owner_user_id`   BIGINT NOT NULL,
    `license`         VARCHAR(512)    NULL,
    `intro`           TEXT            NULL,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    `source`          VARCHAR(16)     NOT NULL DEFAULT 'SELF_OPERATED',
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created_by`      BIGINT NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    -- 命名加 wholesaler_ 前缀：H2(测试库) 约束名全局唯一，避免与 stores.uk_tenant_id_name 冲突
    UNIQUE KEY `uk_wholesaler_tenant_id_name` (`tenant_id`, `name`)
);
