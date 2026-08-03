-- V23__p3b_clearance.sql
-- P3b T4-W2（13-p3b-design §3.4/§4.1，D-6 清库并入 T4）：强制清库单（QK-）。
-- 一单一批次；状态机与盘点同构（DRAFT/PENDING_APPROVAL/REJECTED/APPROVED）；
-- pending_flag 部分唯一（V13/V21 先例）——同批次在途（DRAFT/PENDING_APPROVAL）清库单至多一张（违者 50365）。
-- pallet_release 与 13 §3.4 蓝图偏差：NULL 化（NULL=默认比例建议值；非空=WK 覆盖含 0——
-- V21 count_sheet_items.pallet_delta 同语义先例）；审批通过后回写实际释放值。
-- attachments 实物照片必填 ≥1 ≤3（50366，R19 刚性不受拍照开关影响；PRD 总纲：清库凭证 ≤3 张）。
-- H2(MODE=MySQL) 兼容写法沿 V11/V21/V22 先例；索引前缀 qk_ 防 H2 索引名全局冲突。

CREATE TABLE `clearance_requests` (
    `id`             BIGINT        NOT NULL COMMENT '雪花ID',
    `doc_no`         VARCHAR(64)   NOT NULL COMMENT '单据号 generate(CLEARANCE) → QK-…（A3 零改造）',
    `tenant_id`      BIGINT        NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `wholesaler_id`  BIGINT        NOT NULL COMMENT '归属商户（随批次推导，不取客户端）',
    `sku_id`         BIGINT        NOT NULL COMMENT '商品 SKU（随批次推导）',
    `batch_id`       BIGINT        NOT NULL COMMENT '待清理批次（一单一批次；前置 PENDING_CLEARANCE 且推算剩余>0，50365）',
    `qty`            INT           NOT NULL COMMENT '清库件数（默认=推算剩余，WK 现场核数可改；审批时按池剩余在库封顶）',
    `pallet_release` INT           NULL     COMMENT 'WK 释放托盘覆盖值（NULL=默认比例；含 0）；审批通过后回写实际释放值',
    `reason`         VARCHAR(32)   NOT NULL COMMENT '清库原因单选：EXPIRED（过期）/DAMAGED（损坏）/OTHER（其他，备注必填）',
    `reason_remark`  VARCHAR(512)  NULL     COMMENT '原因补充（reason=OTHER 必填，如客户投诉）',
    `attachments`    VARCHAR(1024) NOT NULL COMMENT '实物照片必填 ≥1 ≤3（50366，R19 刚性；复用 /files，N2 白名单）',
    `status`         VARCHAR(32)   NOT NULL COMMENT 'DRAFT/PENDING_APPROVAL/REJECTED(→可改回 DRAFT 重提)/APPROVED(终态不可逆)',
    `pending_flag`   TINYINT       NULL     COMMENT 'DRAFT/PENDING_APPROVAL=1、REJECTED/APPROVED=NULL（uk_qk_batch_pending 部分唯一，50365 兜底）',
    `wk_user_id`     BIGINT        NULL     COMMENT '发起·编辑·提交人（WK）',
    `ta_user_id`     BIGINT        NULL     COMMENT '审批人（TA）',
    `decided_at`     DATETIME      NULL     COMMENT '审批时刻（APPROVED=EXPIRY_CLEARANCE 流水 biz_time 锚点，仓储费当日截止）',
    `reject_remark`  VARCHAR(512)  NULL     COMMENT '驳回理由（REJECTED 必填）',
    `remark`         VARCHAR(512)  NULL     COMMENT '备注选填；审批封顶差额自动追加',
    `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_qk_doc_no` UNIQUE (`doc_no`),
    CONSTRAINT `uk_qk_batch_pending` UNIQUE (`batch_id`, `pending_flag`),
    KEY `idx_qk_tenant_status` (`tenant_id`, `status`, `created_at`),
    KEY `idx_qk_ws_status` (`wholesaler_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='强制清库单(P3b T4-W2，QK-；封顶口径家族第 4 处)';
