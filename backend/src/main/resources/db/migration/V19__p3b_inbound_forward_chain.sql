-- V19__p3b_inbound_forward_chain.sql
-- P3b T1-BE（13-p3b-design §1/§4.1）：WA 正向入库申请链 + R3 登记纠错。
-- 状态枚举（本波启用 4 值，命名 12 §2.1 冻结）：SUBMITTED（WA 提交，零库存零计费）
--   / WITHDRAWN（R1 撤回）/ REJECTED（R2 驳回）/ ACCEPTED（WK 受理锁单）→ CONFIRMED（登记才 addStock，D-3 直落）。
-- 代建链（PENDING_WA_CONFIRM/…）一字不动；正向链 wa_confirm_deadline 恒 NULL → 72h Job 天然不命中。
-- H2(MODE=MySQL) 兼容写法沿 V11/V15 先例；索引前缀 inb_/corr_ 防 H2 索引名全局冲突。

-- 1) inbound_requests 正向链列扩展
ALTER TABLE `inbound_requests` ADD COLUMN `requested_qty` INT NULL COMMENT '申请件数（正向链提交落值后不可变；代建链 NULL）';
ALTER TABLE `inbound_requests` ADD COLUMN `reject_reason` VARCHAR(32) NULL COMMENT 'R2 驳回原因单选：QTY/QUALITY/BATCH/OTHER';
ALTER TABLE `inbound_requests` ADD COLUMN `reject_remark` VARCHAR(512) NULL COMMENT 'R2 驳回备注（驳回必填）';
ALTER TABLE `inbound_requests` ADD COLUMN `reject_attachments` VARCHAR(1024) NULL COMMENT 'R2 驳回举证附件 JSON 数组 URL ≤5（独立于登记照片）';
ALTER TABLE `inbound_requests` ADD COLUMN `withdraw_reason` VARCHAR(512) NULL COMMENT 'R1 撤回理由（撤回必填）';
ALTER TABLE `inbound_requests` ADD COLUMN `attachments` VARCHAR(1024) NULL COMMENT '登记照片 JSON 数组 URL ≤5（D-2 最小版，复用 /files）';
ALTER TABLE `inbound_requests` ADD COLUMN `printed_at` DATETIME NULL COMMENT '最近打印时刻（T1-7，非状态节点）';
ALTER TABLE `inbound_requests` ADD COLUMN `print_count` INT NOT NULL DEFAULT 0 COMMENT '打印次数（补打 count++）';
ALTER TABLE `inbound_requests` ADD COLUMN `registered_at` DATETIME NULL COMMENT '登记时刻（R3 纠错 24h 窗口锚点，SQL 内比数据库时间）';
ALTER TABLE `inbound_requests` ADD COLUMN `batch_submit_id` BIGINT NULL COMMENT '同批提交共享雪花 id（D-5 多行拆单打印聚合）';
ALTER TABLE `inbound_requests` ADD COLUMN `wa_user_id` BIGINT NULL COMMENT '提交人（WA 或被授权 WE）';
-- 备注列（13 §1.2 登记差异非 0 时 remark 必填的落点；设计 V19 清单未列、据实现补——提交行备注亦落此，登记备注覆写）
ALTER TABLE `inbound_requests` ADD COLUMN `remark` VARCHAR(512) NULL COMMENT '备注（提交行备注；登记差异备注覆写）';

-- WK 待受理队列索引
CREATE INDEX `idx_inb_tenant_status` ON `inbound_requests` (`tenant_id`, `status`, `created_at`);

-- 2) R3 登记纠错（独立小表，单级 TA 审批；不入 DocType 单号体系，以 id 引用——
--    与 wholesaler_applications 同类先例；pending_flag 部分唯一防同单双 PENDING，V13 先例）
CREATE TABLE `inbound_corrections` (
    `id`                 BIGINT       NOT NULL COMMENT '雪花ID',
    `tenant_id`          BIGINT       NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `wholesaler_id`      BIGINT       NOT NULL COMMENT '涉事商户',
    `sku_id`             BIGINT       NOT NULL COMMENT '涉事 SKU（冗余自入库单，锁键/查询免 join）',
    `inbound_request_id` BIGINT       NOT NULL COMMENT '被纠错入库单（source=WA_SUBMIT ∧ CONFIRMED）',
    `ref_doc_no`         VARCHAR(64)  NOT NULL COMMENT '冗余入库单号（列表展示免 join，创建时快照）',
    `old_qty`            INT          NOT NULL COMMENT '纠错前实登件数（发起时快照留痕）',
    `new_qty`            INT          NOT NULL COMMENT '纠错后件数（≥0，≠old_qty）',
    `reason`             VARCHAR(512) NOT NULL COMMENT '纠错理由（WK 发起必填）',
    `status`             VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    `pending_flag`       TINYINT      NULL     COMMENT 'PENDING=1/终态 NULL（uk_corr_req_pending 部分唯一，50353 兜底）',
    `applied_qty`        INT          NULL     COMMENT 'APPROVED 实际生效变动量（改小按 12 §2.4 封顶后）',
    `shortfall_qty`      INT          NULL     COMMENT '改小遇已售差额 = |delta| − applied（线下定责，禁止打负）',
    `remark`             VARCHAR(512) NULL     COMMENT '差额备注等（封顶差额自动写入）',
    `decide_remark`      VARCHAR(512) NULL     COMMENT 'TA 结论备注（REJECTED 必填）',
    `wk_user_id`         BIGINT       NOT NULL COMMENT '发起人（WK）',
    `ta_user_id`         BIGINT       NULL     COMMENT '审批人（TA）',
    `decided_at`         DATETIME     NULL     COMMENT '审批时刻',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_corr_req_pending` UNIQUE (`inbound_request_id`, `pending_flag`),
    KEY `idx_corr_tenant_status` (`tenant_id`, `status`, `created_at`),
    KEY `idx_corr_request` (`inbound_request_id`),
    KEY `idx_corr_wholesaler` (`wholesaler_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库登记纠错单(P3b T1-BE R3)';
