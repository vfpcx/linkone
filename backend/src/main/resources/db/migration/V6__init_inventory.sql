-- V6__init_inventory.sql
-- phase-1 B1：库存（入/出库）。批次关闭模式，按单 sku 维度；不做 FIFO/批次/临期/盘点/退货/快照。
-- 模型沿用 06-phase1-wholesaler-selling-plan.md §3：
--   inventories      —— 单 sku 维度库存（qty/pallet_qty），唯一索引 (wholesaler_id, sku_id)。
--   stock_movements  —— 出入库流水（INBOUND/OUTBOUND）。
-- 关联：sku_id 指向 skus.id（雪花 Long）；inventory 行同存 wholesaler_id + tenant_id。
-- tenant_id 纳入 TenantLine 隔离白名单。
-- 命名加 inv_/mv_ 前缀：H2(测试库) 约束/索引名全局唯一，避免与既有表冲突（A1/A2 踩坑）。

CREATE TABLE IF NOT EXISTS `inventories` (
    `id`              BIGINT          NOT NULL,
    `wholesaler_id`   BIGINT          NOT NULL,
    `tenant_id`       BIGINT          NOT NULL,
    `sku_id`          BIGINT          NOT NULL,
    `qty`             INT             NOT NULL DEFAULT 0,
    `pallet_qty`      INT             NOT NULL DEFAULT 0,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 单 sku 维度唯一：同一商户下一个 sku 只有一行库存（应用层 upsert 双保险，G-5.1）
    CONSTRAINT `uk_inv_wholesaler_sku` UNIQUE (`wholesaler_id`, `sku_id`)
);

CREATE TABLE IF NOT EXISTS `stock_movements` (
    `id`                BIGINT        NOT NULL,
    `sku_id`            BIGINT        NOT NULL,
    `wholesaler_id`     BIGINT        NOT NULL,
    `tenant_id`         BIGINT        NOT NULL,
    `type`             VARCHAR(16)    NOT NULL,
    `qty`               INT           NOT NULL,
    `ref_doc_no`       VARCHAR(64)    NULL,
    `operator_user_id`  BIGINT        NULL,
    `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_mv_sku` (`sku_id`)
);
