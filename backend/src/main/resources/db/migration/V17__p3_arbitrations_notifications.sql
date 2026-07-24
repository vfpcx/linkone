-- V17__p3_arbitrations_notifications.sql
-- P3 BE-W1（12-p3-design §4）：双仲裁统一表 arbitrations + 站内信 notifications 最小版。
-- 两表均入 TenantLine 隔离白名单（MybatisPlusConfig 代码侧同步）。
-- arbitrations 为 03 蓝图 complaints 通用客诉表的 P3 最小前身（P5 扩展而非推倒）。

-- 1) 仲裁单（多态单据引用；INBOUND_DISPUTE 由 TA 裁 / OUTBOUND_COMPLAINT 由 OPS 裁）
CREATE TABLE `arbitrations` (
    `id`                 BIGINT        NOT NULL COMMENT '雪花ID',
    `doc_no`             VARCHAR(64)   NOT NULL COMMENT '仲裁单号（统一单据号体系：INBOUND_DISPUTE→YY-、OUTBOUND_COMPLAINT→KS-）',
    `tenant_id`          BIGINT        NOT NULL COMMENT '涉事租户（OPS 查询绕 TenantLine 走无上下文先例）',
    `biz_type`           VARCHAR(24)   NOT NULL COMMENT 'INBOUND_DISPUTE / OUTBOUND_COMPLAINT（结论枚举合法性由此决定，错配 50333）',
    `ref_doc_type`       VARCHAR(16)   NOT NULL COMMENT '多态引用①：INBOUND / OUTBOUND（DocType 名对齐）',
    `ref_doc_id`         BIGINT        NOT NULL COMMENT '多态引用②：单据主键',
    `ref_doc_no`         VARCHAR(64)   NOT NULL COMMENT '冗余单据号（列表展示免 join，创建时快照）',
    `wholesaler_id`      BIGINT        NOT NULL COMMENT '涉事商户',
    `initiator_user_id`  BIGINT        NULL     COMMENT '发起人（WA 或被授权 WE）',
    `initiator_role`     VARCHAR(8)    NULL     COMMENT '发起方角色（WA/WE）',
    `reason`             VARCHAR(512)  NOT NULL COMMENT '发起理由（必填）',
    `attachments`        VARCHAR(1024) NULL     COMMENT 'JSON 数组，附件 URL ≤5 个',
    `reversed_qty`       INT           NULL     COMMENT '仅 INBOUND_DISPUTE：实际冲销件数（按在库封顶，落单后不可变）',
    `shortfall_qty`      INT           NULL     COMMENT '仅 INBOUND_DISPUTE：已售差额（定责输入，落单后不可变）',
    `status`             VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / DECIDED（唯一性闸门=单据状态，一单一裁）',
    `conclusion`         VARCHAR(24)   NULL     COMMENT '入库：APPROVED(恢复流水)/REJECTED(保留冲销)；出库：WK_LIABLE/WA_LIABLE/NEGOTIATED/NO_LIABILITY',
    `liability`          VARCHAR(16)   NULL     COMMENT '差额定责枚举（仅 INBOUND_DISPUTE∧REJECTED∧shortfall_qty>0 必填，其余必空，违规 50342）',
    `conclusion_remark`  VARCHAR(512)  NULL     COMMENT '结论备注（REJECTED 必填）',
    `arbitrator_user_id` BIGINT        NULL     COMMENT '裁决人（TA 或 OPS）',
    `decided_at`         DATETIME      NULL     COMMENT '裁决时刻',
    `created_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 单据号全局唯一（G-5.1 双保险，arb_ 前缀防 H2 索引名冲突）
    CONSTRAINT `uk_arb_doc_no` UNIQUE (`doc_no`),
    KEY `idx_arb_tenant_type_status` (`tenant_id`, `biz_type`, `status`, `created_at`),
    KEY `idx_arb_ref` (`ref_doc_type`, `ref_doc_id`),
    KEY `idx_arb_wholesaler` (`wholesaler_id`)
);

-- 2) 站内信（最小版：拉取式 + 轮询 unread-count；同事务写入，不引入 MQ）
CREATE TABLE `notifications` (
    `id`                 BIGINT        NOT NULL COMMENT '雪花ID',
    `tenant_id`          BIGINT        NULL     COMMENT '归属租户（业务侧显式落值；Job 系统态写入时从单据带入）',
    `recipient_user_id`  BIGINT        NOT NULL COMMENT '收件人',
    `type`               VARCHAR(32)   NOT NULL COMMENT 'INBOUND_PENDING_CONFIRM/INBOUND_AUTO_CONFIRMED/DISPUTE_CREATED/ARBITRATION_DECIDED/…',
    `title`              VARCHAR(128)  NOT NULL COMMENT '标题（后端拼模板，文案表在 PRD）',
    `content`            VARCHAR(512)  NULL     COMMENT '正文',
    `ref_type`           VARCHAR(16)   NULL     COMMENT '跳转引用类型（INBOUND/OUTBOUND/ARBITRATION）',
    `ref_id`             BIGINT        NULL     COMMENT '跳转引用 id',
    `read_at`            DATETIME      NULL     COMMENT '已读时刻（空=未读）',
    `created_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_ntf_recipient` (`recipient_user_id`, `read_at`, `created_at`)
);
