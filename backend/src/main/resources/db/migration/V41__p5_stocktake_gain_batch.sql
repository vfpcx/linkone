-- V41__p5_stocktake_gain_batch.sql
-- P5 顺延兑现（13 §5.2 注 7 · 08 §2.2 D24 · 99-open-questions D24=2026-05 选 A）：
-- 盘点批次分支——盘盈按批入库登记三字段（count_sheet_items 追加，存量行 NULL=池入旧行为不变）。
-- 语义：仅盘盈行（diff>0）可带；gain_batch_no 给了 → gain_expiry_date 必填（D24 保质期），
-- gain_production_date 可选（现场不知生产日期可不录）。审批通过时以三字段建批次登记簿行
-- （source=STOCKTAKE，initial_qty=审批 diff）并回填该行 GAIN 流水 batch_id——FIFO 推算按
-- initial_qty 吃进（pool 查询 batch_id IS NULL，带批 GAIN 不再混入「无批次在池量」）。
-- 单文件标准 SQL（沿 V40 先例，H2 MODE=MySQL 兼容）：H2 一次 ALTER 仅加一列 → 三条独立语句。

ALTER TABLE `count_sheet_items` ADD COLUMN `gain_batch_no` VARCHAR(64) NULL COMMENT '盘盈按批登记：批次号（仅盘盈行可带；uk_bat_ws_sku_no 冲突审批期 50362）';

ALTER TABLE `count_sheet_items` ADD COLUMN `gain_production_date` DATE NULL COMMENT '盘盈按批登记：生产日期（≤今天 40205；可选）';

ALTER TABLE `count_sheet_items` ADD COLUMN `gain_expiry_date` DATE NULL COMMENT '盘盈按批登记：到效期（>生产日期 40206；给批次号则必填）';
