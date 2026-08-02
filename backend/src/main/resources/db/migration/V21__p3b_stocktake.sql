-- V21__p3b_stocktake.sql
-- P3b T3-W2（13-p3b-design §2.2/§4.1）：盘点单（PD-）两表。
-- 1) count_sheets：一张盘点单盘一个商户；pending_flag 部分唯一（V13 先例）——
--    同商户在途（DRAFT/PENDING_APPROVAL）至多一张，防双重盈亏（违者 50356）。
-- 2) count_sheet_items：明细一单多 SKU（盘点天然全仓性质，与单据链一单一 SKU 不同域）；
--    system_qty=提交时刻账面快照，diff=actual−system（提交后出库不改差异值——两时点语义分离），
--    applied_diff=审批通过实际生效值（盘亏 D-10 封顶后；盘盈=diff）。
--    pallet_delta 与 13 §2.2 蓝图偏差：NULL 化（NULL=盘亏默认比例建议值；非空=WK 覆盖含 0——
--    NOT NULL DEFAULT 0 无法区分「未覆盖」与「覆盖为 0」，T3-W1 palletReleaseOverride 同语义）；
--    审批通过后回写生效带符号值（盘盈 +M / 盘亏 −释放数，RTN pallet_release 回写先例）。
-- H2(MODE=MySQL) 兼容写法沿 V11/V20 先例；索引前缀 cs_/csi_ 防 H2 索引名全局冲突。

CREATE TABLE `count_sheets` (
    `id`            BIGINT        NOT NULL COMMENT '雪花ID',
    `doc_no`        VARCHAR(64)   NOT NULL COMMENT '单据号 generate(STOCKTAKE) → PD-…（A3 零改造）',
    `tenant_id`     BIGINT        NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `wholesaler_id` BIGINT        NOT NULL COMMENT '被盘商户（一张盘点单盘一个商户，R13 归属明确）',
    `status`        VARCHAR(32)   NOT NULL COMMENT 'DRAFT/PENDING_APPROVAL/REJECTED(→可改回 DRAFT 重提)/APPROVED(终态不可逆)',
    `pending_flag`  TINYINT       NULL     COMMENT 'DRAFT/PENDING_APPROVAL=1、REJECTED/APPROVED=NULL（uk_cs_ws_pending 部分唯一，50356 兜底）',
    `wk_user_id`    BIGINT        NULL     COMMENT '发起·编辑·提交人（WK，T3-9 留痕）',
    `ta_user_id`    BIGINT        NULL     COMMENT '审批人（TA）',
    `decided_at`    DATETIME      NULL     COMMENT '审批时刻（APPROVED=GAIN/LOSS 流水 biz_time 锚点）',
    `reject_remark` VARCHAR(512)  NULL     COMMENT '驳回理由（REJECTED 必填）',
    `remark`        VARCHAR(512)  NULL     COMMENT '盘点说明；审批封顶差额汇总自动追加',
    `attachments`   VARCHAR(1024) NULL     COMMENT '现场照片 ≤5（复用 /files，N2 白名单）',
    `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_cs_doc_no` UNIQUE (`doc_no`),
    CONSTRAINT `uk_cs_ws_pending` UNIQUE (`wholesaler_id`, `pending_flag`),
    KEY `idx_cs_tenant_status` (`tenant_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点单(P3b T3-W2，D-10 盘亏封顶)';

CREATE TABLE `count_sheet_items` (
    `id`           BIGINT       NOT NULL COMMENT '雪花ID',
    `sheet_id`     BIGINT       NOT NULL COMMENT '所属盘点单',
    `tenant_id`    BIGINT       NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `sku_id`       BIGINT       NOT NULL COMMENT '盘点 SKU（同 sheet 内去重，应用层 50355）',
    `system_qty`   INT          NOT NULL COMMENT '账面快照（建单预填、提交时刻定格——已扣后口径）',
    `actual_qty`   INT          NOT NULL COMMENT '实物数 ≥0',
    `diff`         INT          NOT NULL COMMENT 'actual−system（正=盘盈/负=盘亏；不做在途还原折算）',
    `applied_diff` INT          NULL     COMMENT '审批通过实际生效值（盘亏 D-10 封顶后带符号；盘盈=diff）',
    `pallet_delta` INT          NULL     COMMENT 'WK 输入：盘盈占用 +M/盘亏释放数（≥0）；NULL=盘亏默认比例；审批后回写生效带符号值',
    `remark`       VARCHAR(512) NULL     COMMENT '差异理由；盘亏封顶差额自动追加',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_csi_sheet` (`sheet_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点单明细(一单多 SKU)';
