-- V20__p3b_return_pallet.sql
-- P3b T3-W1（13-p3b-design §2.1/§2.4/§4.1）：退货单（RTN-）+ 托盘账补齐（D-8=A）+ D-13 防御。
-- 1) stock_movements.pallet_delta：+占用 / −释放；今后 inventories.pallet_qty ≡ Σ pallet_delta 可对账。
--    存量流水恒 0（13 §2.4-4 边界：不回填、不回溯，历史托盘虚高由上线后首次盘点校准收口）。
-- 2) return_requests：退货单（D-7 登记时扣——提交/受理零库存，登记瞬间扣件数+释放托盘）。
-- 3) D-13 存量校准：batch_enabled 全量归 0（T4-W1 前接口禁改，50360；C8 风险即刻设防）。
-- H2(MODE=MySQL) 兼容写法沿 V11/V19 先例；索引前缀 rtn_/mv_ 防 H2 索引名全局冲突。

-- 1) 托盘变化列（D-8=A）
ALTER TABLE `stock_movements` ADD COLUMN `pallet_delta` INT NOT NULL DEFAULT 0 COMMENT '托盘变化（+占用/−释放；V20 前存量恒 0 不回填，13 §2.4-4）';

-- 2) 退货单（13 §2.1）
CREATE TABLE `return_requests` (
    `id`              BIGINT       NOT NULL COMMENT '雪花ID',
    `doc_no`          VARCHAR(64)  NOT NULL COMMENT '单据号 generate(RETURN) → RTN-…（A3 零改造）',
    `tenant_id`       BIGINT       NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `wholesaler_id`   BIGINT       NOT NULL COMMENT '发起商户',
    `sku_id`          BIGINT       NOT NULL COMMENT '退货 SKU（一单一 SKU 先例）',
    `qty`             INT          NOT NULL COMMENT '退货件数（提交落值；登记可按实覆写，remark 留痕）',
    `pallet_release`  INT          NOT NULL DEFAULT 0 COMMENT '登记时实际释放托盘（默认比例建议值，WK 可覆盖，双重封顶）',
    `status`          VARCHAR(32)  NOT NULL COMMENT 'PENDING_ACCEPT/WITHDRAWN/ACCEPTED/COMPLETED（C14 已落地命名风格）',
    `withdraw_reason` VARCHAR(512) NULL     COMMENT 'R1 同构，撤回必填',
    `wa_user_id`      BIGINT       NULL     COMMENT '发起人（WA；D-9 不开放 WE）',
    `wk_user_id`      BIGINT       NULL     COMMENT '受理·登记人（WK）',
    `accepted_at`     DATETIME     NULL     COMMENT '受理时刻',
    `completed_at`    DATETIME     NULL     COMMENT '登记出货时刻（=RETURN 流水 biz_time，计费当日截止锚点）',
    `remark`          VARCHAR(512) NULL     COMMENT '备注（提交备注；登记按实覆写差异留痕）',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_rtn_doc_no` UNIQUE (`doc_no`),
    KEY `idx_rtn_ws_status` (`wholesaler_id`, `status`),
    KEY `idx_rtn_tenant_status` (`tenant_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货单(P3b T3-W1，D-7 登记时扣)';

-- 3) D-13 存量校准：batch_enabled 全量归 0（接口层同波起拒改，50360）
UPDATE `tenant_settings` SET `batch_enabled` = 0;
