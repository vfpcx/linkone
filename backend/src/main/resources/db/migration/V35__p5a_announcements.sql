-- V35__p5a_announcements.sql
-- P5-A（18-p5-design §2.2）：平台公告表（归属 notify 域，平台级）。
-- 平台级表（无 tenant_id 列）：不在 TenantLine 白名单，天然不做租户隔离，OPS 管辖。
-- H2(MODE=MySQL) 兼容：标准 SQL 单文件（沿 V17/V24 先例，无方言差异无需 database-specific 拆分）。

CREATE TABLE `announcements` (
    `id`           BIGINT       NOT NULL COMMENT '雪花ID',
    `title`        VARCHAR(128) NOT NULL COMMENT '公告标题（≤128）',
    `content`      VARCHAR(512) NOT NULL COMMENT '公告正文（≤512）',
    `target_roles` VARCHAR(255) NOT NULL COMMENT '角色组 KEY 逗号分隔：ALL/OPS/TA/WK_ST/WA_WE（发布时展开为具体角色）',
    `status`       VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/INACTIVE（状态机，重复发布 50502）',
    `published_at` DATETIME     NULL COMMENT '发布时刻（DRAFT 为空）',
    `published_by` BIGINT       NULL COMMENT '发布人（OPS）',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_ann_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台公告(P5-A，18 §2.2；平台级表，OPS 管辖)';
