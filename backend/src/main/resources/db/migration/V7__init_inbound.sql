-- V7__init_inbound.sql
-- phase-1 C1：入库单（WK 代建登记）。沿用 06-phase1-wholesaler-selling-plan.md §3：
--   inbound_requests —— WK 直接登记入库，登记即 REGISTERED；单事务内调 inventory.addStock。
-- phase-1 子集：不做 72h 异议/仲裁/退货/盘点/清库；status 仅 REGISTERED。
-- 关联：wholesaler_id→wholesalers.id、sku_id→skus.id（雪花 Long），行同存 tenant_id。
-- tenant_id 纳入 TenantLine 隔离白名单（MybatisPlusConfig）。
-- 命名加 inb_ 前缀：H2(测试库) 约束/索引名全局唯一，避免与既有表冲突（A1/A2/B1 踩坑）。

CREATE TABLE IF NOT EXISTS `inbound_requests` (
    `id`              BIGINT          NOT NULL,
    `doc_no`          VARCHAR(64)     NOT NULL,
    `wholesaler_id`   BIGINT          NOT NULL,
    `tenant_id`       BIGINT          NOT NULL,
    `sku_id`          BIGINT          NOT NULL,
    `qty`             INT             NOT NULL,
    `pallet_qty`      INT             NOT NULL DEFAULT 0,
    `status`          VARCHAR(16)     NOT NULL DEFAULT 'REGISTERED',
    `wk_user_id`      BIGINT          NULL,
    `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 单据号全局唯一（DocumentNumberService Redis INCR 生成，双保险 G-5.1）
    CONSTRAINT `uk_inb_doc_no` UNIQUE (`doc_no`)
);

CREATE INDEX IF NOT EXISTS `idx_inb_wholesaler` ON `inbound_requests` (`wholesaler_id`);
CREATE INDEX IF NOT EXISTS `idx_inb_tenant` ON `inbound_requests` (`tenant_id`);
