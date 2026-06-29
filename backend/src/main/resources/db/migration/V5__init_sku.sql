-- V5__init_sku.sql
-- phase-1 A2：商品 SKU + 公开价（单价/起批价/起批量）。仅 skus 表；不含 SPU 平台化/专属价/议价/批量调价。
-- 模型沿用 06-phase1-wholesaler-selling-plan.md §3：公开价三件套 + listed 上下架；spu_id 可空（phase-1 不强制平台 SPU）。
-- 关联：wholesaler_id 指向 wholesalers.id（雪花 Long）；tenant_id 纳入 TenantLine 隔离白名单。

CREATE TABLE IF NOT EXISTS `skus` (
    `id`              BIGINT          NOT NULL,
    `wholesaler_id`   BIGINT          NOT NULL,
    `tenant_id`       BIGINT          NOT NULL,
    `spu_id`          BIGINT          NULL,
    `name`            VARCHAR(128)    NOT NULL,
    `spec`            VARCHAR(256)    NULL,
    `unit_price`      DECIMAL(12, 2)  NOT NULL,
    `moq_price`       DECIMAL(12, 2)  NOT NULL DEFAULT 0,
    `moq_qty`         INT             NOT NULL DEFAULT 1,
    `listed`          TINYINT(1)      NOT NULL DEFAULT 1,
    `main_image`      VARCHAR(512)    NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created_by`      BIGINT          NULL,
    `deleted_at`      DATETIME        NULL,
    PRIMARY KEY (`id`),
    -- 命名加 sku_ 前缀：H2(测试库) 约束/索引名全局唯一，避免与既有表冲突（A1 踩坑）
    KEY `idx_sku_wholesaler_id` (`wholesaler_id`),
    KEY `idx_sku_tenant_id` (`tenant_id`)
);
