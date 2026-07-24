-- V15__p3_stock_movement_ext.sql
-- P3 BE-W1（12-p3-design §5 / §1.5）：库存流水扩展——全链地基。
--   type 扩宽 16→32（DISPUTE_REVERSAL 等新类型超原宽度）；
--   biz_time    计费语义时间锚点（7.6 口径）：默认=created_at；DISPUTE_RESTORE=原入库时间戳(G10)；
--   reversal_of_id 反向流水指向的原流水 id（OUTBOUND_REVERSAL 必填，P4 配对抵消；
--                  DISPUTE_RESTORE 亦回指其配对 DISPUTE_REVERSAL，双向可回溯）；
--   remark      流水备注（冲销托盘数等审计信息）。
-- 类型枚举（qty 恒正，方向由 type 表达）：INBOUND/OUTBOUND(现状)/OUTBOUND_REVERSAL(+)/
--   DISPUTE_REVERSAL(-)/DISPUTE_RESTORE(+)；RETURN/GAIN/LOSS/EXPIRY_CLEARANCE 预留 T3/T4。
-- H2(MODE=MySQL) 兼容：MODIFY COLUMN 在 MySQL 兼容模式受支持；索引单独 CREATE INDEX（两库兼容）。

ALTER TABLE `stock_movements` MODIFY COLUMN `type` VARCHAR(32) NOT NULL;
ALTER TABLE `stock_movements` ADD COLUMN `biz_time` DATETIME NULL COMMENT '计费语义时间锚点(7.6)：默认=created_at；DISPUTE_RESTORE=原入库时间戳(G10)';
ALTER TABLE `stock_movements` ADD COLUMN `reversal_of_id` BIGINT NULL COMMENT '反向流水指向的原流水id（OUTBOUND_REVERSAL 必填，P4 配对抵消）';
ALTER TABLE `stock_movements` ADD COLUMN `remark` VARCHAR(255) NULL COMMENT '流水备注（冲销托盘数等审计信息）';

-- 存量回填：biz_time = created_at（入库次日起算/出库当日截止的既有口径不变）
UPDATE `stock_movements` SET `biz_time` = `created_at` WHERE `biz_time` IS NULL;

-- 对账/仲裁详情查询索引（mv_ 前缀：H2 索引名全局唯一，A1/A2/B1 踩坑先例）
CREATE INDEX `idx_mv_wholesaler_sku_type` ON `stock_movements` (`wholesaler_id`, `sku_id`, `type`);
