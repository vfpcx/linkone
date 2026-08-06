-- V25__p4_daily_snapshots.sql
-- P4 W2（14-p4-design §1.2/§4，D-P4-2=A 快照作缓存、流水回放为准）：
-- 每日快照表，SKU 粒度（蓝图 ws 粒度不够 D-P4-1 下钻，偏差已在 14 §4 注明）。
-- 三用途：① ST 账单详情按日下钻；② 对账留痕；③ 缓存——非记账真源，出账以流水回放为准。
-- 只存物理量（qty/pallet_qty），不存金额（蓝图 daily_fee/expiry_qty/billing_rule_id 三列裁掉，
-- 金额出账时按规则段现算，避免规则变更后快照金额失真）。双 0 不落行，缺行即 0。
-- 幂等语义：uk_snap_ws_sku_date 一 (ws,sku,日) 一行；重跑/recalc 以回放结果删插覆写。
-- H2(MODE=MySQL) 兼容写法沿 V11/V21/V23/V24 先例。

CREATE TABLE `daily_snapshots` (
    `id`            BIGINT   NOT NULL COMMENT '雪花ID',
    `tenant_id`     BIGINT   NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `wholesaler_id` BIGINT   NOT NULL COMMENT '批发商商户',
    `sku_id`        BIGINT   NOT NULL COMMENT 'SKU（D-P4-1=A SKU 粒度下钻）',
    `snapshot_date` DATE     NOT NULL COMMENT '快照日（=回放公式的 D；值为 bizDate ≤ D−1 的净值）',
    `qty`           INT      NOT NULL DEFAULT 0 COMMENT '计费在库件数（14 §1.1 billableQty(D)，max(Σsigned,0)）',
    `pallet_qty`    INT      NOT NULL DEFAULT 0 COMMENT '计费占用托盘（Σpallet_delta，V20 前存量基线 0，D-P4-5=A）',
    `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_snap_ws_sku_date` UNIQUE (`wholesaler_id`, `sku_id`, `snapshot_date`),
    KEY `idx_snap_tenant_date` (`tenant_id`, `snapshot_date`),
    KEY `idx_snap_ws_date` (`wholesaler_id`, `snapshot_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日计费快照(P4 W2，14 §1.2/§4；缓存/下钻/对账三用途，非记账真源)';
