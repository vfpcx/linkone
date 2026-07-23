-- V14: P2 Wave6 DEF-3 —— 清理 WA 注册占位角色行的存量脏数据
-- 背景：WA 注册（直申路径）先落一条无商户绑定的占位行 (role=WA, tenant_id NULL, wholesaler_id NULL)，
-- 审批通过/OPS 代建时 ensureWholesalerRole 原实现另插一条绑定 wholesaler_id 的行，
-- 导致同一账号两条 WA 角色 → 登录出现两条同名工作空间、选错进空态（DEF-3）。
-- 代码侧已改为「就地升级」占位行（AuthServiceImpl.ensureWholesalerRole）；
-- 本迁移处理历史已产生的双行：凡已有 ACTIVE 绑定行的用户，其无绑定占位行统一逻辑删除
-- （置 deleted_at，与 @TableLogic 口径一致，保留追溯不物理删）。
-- 子查询包一层派生表 t：规避 MySQL「UPDATE 目标表不可出现在 FROM 子查询」限制，H2(MODE=MySQL) 同语义。

UPDATE `user_roles` SET `deleted_at` = NOW()
WHERE `role` = 'WA'
  AND `wholesaler_id` IS NULL
  AND `status` = 'ACTIVE'
  AND `deleted_at` IS NULL
  AND `user_id` IN (
    SELECT `user_id` FROM (
      SELECT `user_id` FROM `user_roles`
      WHERE `role` = 'WA' AND `wholesaler_id` IS NOT NULL
        AND `status` = 'ACTIVE' AND `deleted_at` IS NULL
    ) t
  );
