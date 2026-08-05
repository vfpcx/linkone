-- V24__p4_billing_rules.sql
-- P4 W1（14-p4-design §2.1/§4，D-P4-3=A 最小规则模型 + D-P4-4 契约修复不补历史）：
-- 租户级计费规则版本链。双维四列（qty/pallet 各自开关+单价）替代蓝图单行 billing_dim+unit_price
-- （并存语义装不下，14 §2.1 蓝图偏差）；wholesaler_id/min_charge/extra_rule_json 留位不启用（P4 恒
-- NULL/0，P5 per-WA 个性价/保底/阶梯）。版本链只追加不删（无 deleted_at）；同日多次变更覆写当日行
-- （uk_rule_tenant_from 一日一版）；关旧行 effective_to=新版 from−1。
-- tenant_settings.billing_dim 自本版起转「只读镜像」（规则保存事务同步写 QTY/PALLET/BOTH），
-- 为行为约定非 DDL——tenant_settings 零改动。H2(MODE=MySQL) 兼容写法沿 V11/V21/V23 先例。

CREATE TABLE `billing_rules` (
    `id`                   BIGINT        NOT NULL COMMENT '雪花ID',
    `tenant_id`            BIGINT        NOT NULL COMMENT '归属租户（TenantLine 白名单）',
    `wholesaler_id`        BIGINT        NULL     COMMENT '留位恒 NULL（P5 per-WA 个性价，D-P4-3）',
    `qty_enabled`          TINYINT       NOT NULL DEFAULT 0 COMMENT '件·天维度启用（与托盘·天可并存；至少其一=1，应用层 50379）',
    `pallet_enabled`       TINYINT       NOT NULL DEFAULT 0 COMMENT '托盘·天维度启用',
    `price_per_qty_day`    DECIMAL(14,4) NULL     COMMENT '件·天单价（qty_enabled=1 时必填 ≥0；停用维置 NULL）',
    `price_per_pallet_day` DECIMAL(14,4) NULL     COMMENT '托盘·天单价（pallet_enabled=1 时必填 ≥0；停用维置 NULL）',
    `min_charge`           DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '留位不启用（P5 保底费，P4 恒 0）',
    `extra_rule_json`      TEXT          NULL     COMMENT '留位不启用（P5 阶梯/临期加价，P4 恒 NULL）',
    `effective_from`       DATE          NOT NULL COMMENT '生效日（首版=首次保存日 D-P4-4；变更日按新规则 05 §1.5）',
    `effective_to`         DATE          NULL     COMMENT 'NULL=当前生效；关旧=新版 effective_from−1',
    `version`              INT           NOT NULL COMMENT '版本号自 1 递增（同日覆写不递增）',
    `created_by`           BIGINT        NULL     COMMENT '保存人（仓库管理员）',
    `created_at`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_rule_tenant_from` UNIQUE (`tenant_id`, `effective_from`),
    KEY `idx_rule_tenant_to` (`tenant_id`, `effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计费规则版本链(P4 W1，14 §2.1/§4；effective_from 版本化，历史留痕不删)';
