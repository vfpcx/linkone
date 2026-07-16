package com.cangchu.account.service;

import com.cangchu.account.vo.WholesalerEmployeeVo;

import java.util.List;
import java.util.Map;

/**
 * 批发商员工(WE)管理（P2 入驻 Wave3）。
 *
 * <p>置于 account 域：员工管理的本体是 user_roles 行（授权位/status/disabled_at）
 * 与登录会话（R17 禁用即踢），两者均归 account 域；商品/单据域不感知授权位存储。
 * 操作者必须是员工所属商户的 WA（登录态推导，不信任客户端传商户）。
 */
public interface WholesalerEmployeeService {

    /** 本商户 WE 员工列表（含已禁用；含授权位/状态/禁用时间，WA 端员工管理页）。 */
    List<WholesalerEmployeeVo> listEmployees(Long waUserId);

    /**
     * 调整员工授权位（整体替换）：permissions ⊆ [PRICE_EDIT, INQUIRY_CONFIRM]，
     * 越界 50319；员工不属本商户/不存在 50320。已禁用员工也可调（恢复后生效，产品 §6.1 只读展示由前端约束）。
     */
    WholesalerEmployeeVo updatePermissions(Long waUserId, Long employeeRoleId, List<String> permissions);

    /**
     * R17 禁用员工：ACTIVE→DISABLED（CAS）+ disabled_at=now + 事务提交后踢出 token。
     * phase-1 无 WE 可持有的草稿态单据（询价由 RT 创建、出库生成即 COMPLETED、入库归 WK），
     * 草稿作废为空操作——见 10-onboarding-design.md Wave3 章说明。
     */
    Map<String, Object> disableEmployee(Long waUserId, Long employeeRoleId);

    /**
     * 撤销禁用（30 天内）：DISABLED→ACTIVE，授权位保持禁用前设置；
     * 窗口判定用数据库时间（同 Wave2 归档口径），逾期 50322。
     */
    Map<String, Object> restoreEmployee(Long waUserId, Long employeeRoleId);
}
