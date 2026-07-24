-- V16__p3_inbound_confirm_chain.sql
-- P3 BE-W1（12-p3-design §5 / §2）：代建入库 72h 确认链。
-- 状态枚举（本波子集）：PENDING_WA_CONFIRM（待WA确认，可售）/ CONFIRMED（WA接受/72h自动/仲裁通过）
--   / DISPUTED（争议中，冲销已执行）/ REVOKED（已撤销，仲裁驳回）。
-- 迁移矩阵：PENDING_WA_CONFIRM→CONFIRMED|DISPUTED；DISPUTED→CONFIRMED|REVOKED；其余不可达。
-- WA 正向申请链（SUBMITTED/ACCEPTED/…）留 R1-R3 波启用，枚举命名对齐 03 蓝图 §6.1 一并冻结。

ALTER TABLE `inbound_requests` MODIFY COLUMN `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING_WA_CONFIRM';
ALTER TABLE `inbound_requests` ADD COLUMN `source` VARCHAR(16) NOT NULL DEFAULT 'WK_CREATED' COMMENT 'WA_SUBMIT/WK_CREATED（R1-R3 波启用前者）';
ALTER TABLE `inbound_requests` ADD COLUMN `wa_confirm_deadline` DATETIME NULL COMMENT '代建 72h 确认截止（登记时=created_at+72h，显式落列，Job 用数据库时间比较）';
ALTER TABLE `inbound_requests` ADD COLUMN `wa_confirm_at` DATETIME NULL COMMENT 'WA 确认时刻（手动/自动/仲裁通过）';
ALTER TABLE `inbound_requests` ADD COLUMN `auto_accepted` TINYINT NOT NULL DEFAULT 0 COMMENT '1=72h 超时自动确认';
ALTER TABLE `inbound_requests` ADD COLUMN `disputed_at` DATETIME NULL COMMENT 'WA 异议时刻';

-- 存量回填（幂等，WHERE 限定旧值）：P1 REGISTERED 语义=登记即认 → CONFIRMED（12 §2.1）
UPDATE `inbound_requests` SET `status`='CONFIRMED', `wa_confirm_at`=`created_at` WHERE `status`='REGISTERED';

-- Job 扫描索引 + WA 待确认队列索引（inb_ 前缀防 H2 索引名冲突）
CREATE INDEX `idx_inb_status_deadline` ON `inbound_requests` (`status`, `wa_confirm_deadline`);
CREATE INDEX `idx_inb_ws_status` ON `inbound_requests` (`wholesaler_id`, `status`);
