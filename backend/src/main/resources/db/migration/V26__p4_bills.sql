-- V26__p4_bills.sql
-- P4 W3（14-p4-design §3/§4，账单生命周期）：bills / bill_items / payment_records / bill_disputes 四表。
-- 蓝图偏差（14 §4 已注明）：bills 去 GENERATING/CANCELLED 两态、due_date、pdf_url/excel_url（D-P4-8
-- 同步流式不落存储）、deleted_at；payment_records 去 payment_no（内部凭证以 id 引用，inbound_corrections
-- 先例）、deleted_at；bill_items 的 MIN_CHARGE/EXPIRY_SURCHARGE 类型随 P5 留位不启用。
-- 幂等：uk_bill_idempotent（bill:{t}:{ws}:{yyyyMM}）+ uk_bill_no 双层兜底（G-5.1 同构），
-- 月度生成先查后写、并发双跑恰一单。bill_disputes.pending_flag 部分唯一（V13 先例，50382 兜底）。
-- H2(MODE=MySQL) 兼容写法沿 V11/V21/V23/V24/V25 先例。

CREATE TABLE `bills` (
    `id`               BIGINT        NOT NULL COMMENT '雪花ID',
    `bill_no`          VARCHAR(64)   NOT NULL COMMENT '账单号 BL-{租户简码}-W{wholesalerId}-{yyyyMM}（14 §3.4，月粒度无日序列）',
    `tenant_id`        BIGINT        NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `wholesaler_id`    BIGINT        NOT NULL COMMENT '批发商商户',
    `billing_month`    VARCHAR(7)    NOT NULL COMMENT '账期月 yyyy-MM',
    `period_start`     DATE          NOT NULL COMMENT '账单期间起 = max(月初, 首版规则 effective_from)（14 §1.3 起点截断）',
    `period_end`       DATE          NOT NULL COMMENT '账单期间止 = 月末',
    `subtotal_amount`  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '仓储费小计（ΣSTORAGE + Σ其 REVERSAL）',
    `adjust_amount`    DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '调整合计（ΣADJUSTMENT + Σ其 REVERSAL，可负）',
    `total_amount`     DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '应收 = subtotal + adjust',
    `paid_amount`      DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '累计已收（回款登记累加/冲销回减）',
    `status`           VARCHAR(32)   NOT NULL COMMENT 'DRAFT/DISPATCHED/PENDING_PAYMENT/PARTIAL_PAID/PAID/DISPUTED（14 §3.1 六态）',
    `dispatch_at`      DATETIME      NULL     COMMENT '下发时刻（R11 判据 + 满 1 日自动确认锚点 + 申诉 7 天窗口起点）',
    `dispatch_user_id` BIGINT        NULL     COMMENT '下发操作 ST',
    `confirmed_at`     DATETIME      NULL     COMMENT 'WA 确认对账时刻（或 00:50 Job 自动确认落值；R11 判据）',
    `idempotent_key`   VARCHAR(64)   NOT NULL COMMENT '幂等键 bill:{t}:{ws}:{yyyyMM}（月度生成先查后写+uk 兜底）',
    `created_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_bill_no` UNIQUE (`bill_no`),
    CONSTRAINT `uk_bill_idempotent` UNIQUE (`idempotent_key`),
    KEY `idx_bill_tenant_status` (`tenant_id`, `status`, `billing_month`),
    KEY `idx_bill_ws_month` (`wholesaler_id`, `billing_month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='月度账单(P4 W3，14 §3/§4；6 态生命周期，月度 Job 幂等生成)';

CREATE TABLE `bill_items` (
    `id`                 BIGINT        NOT NULL COMMENT '雪花ID',
    `tenant_id`          BIGINT        NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `bill_id`            BIGINT        NOT NULL COMMENT '归属账单',
    `item_type`          VARCHAR(32)   NOT NULL COMMENT 'STORAGE/ADJUSTMENT/REVERSAL/STOCKTAKE_IMPACT（MIN_CHARGE/EXPIRY_SURCHARGE 留位 P5）',
    `sku_id`             BIGINT        NULL     COMMENT 'STORAGE/STOCKTAKE_IMPACT 落值',
    `period_start`       DATE          NULL     COMMENT 'STORAGE 规则段起（R20 分段一段一行）',
    `period_end`         DATE          NULL     COMMENT 'STORAGE 规则段止',
    `qty_days`           INT           NULL     COMMENT '件·天（未启用维 NULL）',
    `pallet_days`        INT           NULL     COMMENT '托盘·天（未启用维 NULL）',
    `unit_price_qty`     DECIMAL(14,4) NULL     COMMENT '段内件·天单价快照',
    `unit_price_pallet`  DECIMAL(14,4) NULL     COMMENT '段内托盘·天单价快照',
    `amount`             DECIMAL(14,2) NOT NULL COMMENT '条目金额（ADJUSTMENT 存负值；REVERSAL=−原值；STOCKTAKE_IMPACT 恒 0）',
    `description`        VARCHAR(255)  NULL     COMMENT '说明（调整原因/跨月差额注明/盘盈亏展示文案）',
    `reverse_of_item_id` BIGINT        NULL     COMMENT 'REVERSAL 回指原条目（R10 冲销链，不删原条目）',
    `operator_user_id`   BIGINT        NULL     COMMENT 'ST 调整/冲销留痕',
    `created_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_bi_bill` (`bill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单明细行(P4 W3，14 §4；SKU×规则段 STORAGE + 调整/冲销/盘点影响)';

CREATE TABLE `payment_records` (
    `id`              BIGINT        NOT NULL COMMENT '雪花ID',
    `tenant_id`       BIGINT        NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `wholesaler_id`   BIGINT        NOT NULL COMMENT '批发商商户',
    `bill_id`         BIGINT        NOT NULL COMMENT '归属账单',
    `amount`          DECIMAL(14,2) NOT NULL COMMENT '本次收款金额（>0 且 ≤剩余应收，50373 不允许超收）',
    `pay_at`          DATETIME      NOT NULL COMMENT '实付时间（ST 手填，可过去日期）',
    `pay_method`      VARCHAR(16)   NOT NULL COMMENT 'BANK_TRANSFER/CASH/WX/ALIPAY/OTHER',
    `evidence_urls`   VARCHAR(1024) NULL     COMMENT '转账凭证 ≤5 张（/files 白名单 50340，JSON 数组）',
    `remark`          VARCHAR(255)  NULL     COMMENT '备注',
    `status`          VARCHAR(16)   NOT NULL DEFAULT 'EFFECTIVE' COMMENT 'EFFECTIVE/REVERSED（R12 冲销留痕不删）',
    `reverse_reason`  VARCHAR(255)  NULL     COMMENT 'R12 冲销理由（必填留痕）',
    `reverse_user_id` BIGINT        NULL     COMMENT 'R12 冲销操作 ST',
    `reverse_at`      DATETIME      NULL     COMMENT 'R12 冲销时刻',
    `created_by`      BIGINT        NULL     COMMENT '登记 ST',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_pay_bill` (`bill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回款记录(P4 W3，14 §4；线下回款 ST 手工登记，多次部分回款一次一条)';

CREATE TABLE `bill_disputes` (
    `id`                BIGINT        NOT NULL COMMENT '雪花ID',
    `tenant_id`         BIGINT        NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `wholesaler_id`     BIGINT        NOT NULL COMMENT '批发商商户',
    `bill_id`           BIGINT        NOT NULL COMMENT '归属账单',
    `submit_user_id`    BIGINT        NOT NULL COMMENT 'WA 提交人',
    `reason`            VARCHAR(512)  NOT NULL COMMENT '申诉理由（必填）',
    `disputed_item_ids` VARCHAR(1024) NULL     COMMENT '争议条目 id JSON 数组（字符串 id 防 JS 精度）',
    `attachments`       VARCHAR(1024) NULL     COMMENT '附图 ≤5（/files 白名单 50340，JSON 数组）',
    `status`            VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RESOLVED/REJECTED',
    `pending_flag`      TINYINT       NULL     COMMENT 'PENDING=1/终态 NULL（uk_bd_bill_pending 部分唯一，50382 兜底，V13 先例）',
    `resolution`        VARCHAR(512)  NULL     COMMENT 'ST 处理说明（必填留痕）',
    `resolver_user_id`  BIGINT        NULL     COMMENT '处理 ST',
    `resolved_at`       DATETIME      NULL     COMMENT '处理时刻',
    `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_bd_bill_pending` UNIQUE (`bill_id`, `pending_flag`),
    KEY `idx_bd_tenant_status` (`tenant_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单申诉(P4 W3，14 §3.3/§4；7 天窗口，同账单至多一张待处理，不冻结账单)';
