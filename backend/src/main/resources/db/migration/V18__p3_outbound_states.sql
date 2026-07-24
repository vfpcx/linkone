-- V18__p3_outbound_states.sql
-- P3 BE-W2（12-p3-design §5 / §1.2 / §3）：出库单状态补拆 + R8 作废联动列。
-- 状态枚举：PENDING_ACCEPT（待受理）/ PRINTED（已打印）/ COMPLETED（已出库，存量语义不变）
--   / WITHDRAWN（已撤回，R4 直撤）/ CANCELLED（已撤销，R4 二次确认 / R8 联动）/ COMPLAINED（客诉中）。
-- 库存语义不动（拍板二 B）：确认/提交/代建瞬间已扣库存；WITHDRAWN/CANCELLED 必配 OUTBOUND_REVERSAL 回补流水。
-- status 列 V8 起即 VARCHAR(32)，无需扩宽。

ALTER TABLE `outbound_requests` ADD COLUMN `source` VARCHAR(16) NOT NULL DEFAULT 'INQUIRY_AUTO' COMMENT '来源：INQUIRY_AUTO/WA_SUBMIT/WK_CREATED';
ALTER TABLE `outbound_requests` ADD COLUMN `printed_at` DATETIME NULL COMMENT '首打时间（补打不覆盖）';
ALTER TABLE `outbound_requests` ADD COLUMN `print_count` INT NOT NULL DEFAULT 0 COMMENT '累计打印次数（补打 count++ 不迁移状态）';
ALTER TABLE `outbound_requests` ADD COLUMN `completed_at` DATETIME NULL COMMENT '实际出库登记时刻（30 天客诉窗口锚点）';
ALTER TABLE `outbound_requests` ADD COLUMN `withdraw_requested` TINYINT NOT NULL DEFAULT 0 COMMENT 'R4：已打印单的撤回申请 flag（WK 二次确认前置）';
ALTER TABLE `outbound_requests` ADD COLUMN `withdraw_requested_at` DATETIME NULL COMMENT 'R4 撤回申请时刻';
ALTER TABLE `outbound_requests` ADD COLUMN `pallet_qty` INT NOT NULL DEFAULT 0 COMMENT '出库托盘（代建/登记录入，回补按此还原）';

-- 存量回填（幂等，WHERE 限定旧值）：P1 全部 COMPLETED；来源按 inquiry_id 判定；completed_at=created_at
UPDATE `outbound_requests` SET `source` = 'WK_CREATED' WHERE `inquiry_id` IS NULL AND `source` = 'INQUIRY_AUTO';
UPDATE `outbound_requests` SET `completed_at` = `created_at` WHERE `status` = 'COMPLETED' AND `completed_at` IS NULL;

-- R8 作废联动：询价单补作废时刻（状态值 VOIDED 复用现 status 列，V8 起 VARCHAR(32)）
ALTER TABLE `inquiry_requests` ADD COLUMN `voided_at` DATETIME NULL COMMENT 'R8 作废时刻（状态 VOIDED）';

-- WK 作业列表 / WA 队列查询索引（outb_ 前缀防 H2 索引名冲突，A1/A2/B1 踩坑先例）
CREATE INDEX `idx_outb_tenant_status` ON `outbound_requests` (`tenant_id`, `status`, `created_at`);
CREATE INDEX `idx_outb_ws_status` ON `outbound_requests` (`wholesaler_id`, `status`);
