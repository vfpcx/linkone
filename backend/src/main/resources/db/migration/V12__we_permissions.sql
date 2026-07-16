-- V12: P2 入驻生态 Wave 3（WE 批发商员工：授权位 + 员工码初始授权）
-- 授权模型（决策 O-4 落地）：最小授权集两枚 PRICE_EDIT / INQUIRY_CONFIRM。
-- 存储选型：user_roles 加 permissions 列（JSON 数组文本），不建独立权限表——
--   ① 授权位只有 2 枚且与 WE 角色绑定行一一对应，独立表徒增 JOIN；
--   ② 读取场景永远伴随角色行（hasWholesalerPermission 单行查询即命中）；
--   ③ 物理类型用 VARCHAR(255) 而非 MySQL JSON：值域为固定白名单标识符，
--      无 JSON 函数检索需求，且 H2(MODE=MySQL) 测试库对 JSON 类型语义不一致。
-- 内容形如 ["PRICE_EDIT","INQUIRY_CONFIRM"]；NULL/空 = 无任何授权（默认只读）。

ALTER TABLE `user_roles` ADD COLUMN `permissions` VARCHAR(255) NULL COMMENT 'WE 授权位 JSON 数组（PRICE_EDIT/INQUIRY_CONFIRM；NULL=无授权）';

-- WE 员工码携带初始授权：注册消费时原样落到 user_roles.permissions（生码弹窗"初始授权"勾选）
ALTER TABLE `invite_codes` ADD COLUMN `permissions` VARCHAR(255) NULL COMMENT 'WE 码初始授权位 JSON 数组（仅 target_role=WE 使用）';
