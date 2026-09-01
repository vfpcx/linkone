package com.cangchu.account.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cangchu.account.entity.User;
import com.cangchu.account.entity.UserRole;
import com.cangchu.account.mapper.UserMapper;
import com.cangchu.account.mapper.UserRoleMapper;
import com.cangchu.account.service.WholesalerEmployeeService;
import com.cangchu.account.vo.WholesalerEmployeeVo;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.util.WePermissions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 批发商员工(WE)管理实现（P2 入驻 Wave3）。
 *
 * <p>user_roles / users / 会话踢出均为 account 域内自有，直连 Mapper（不经 AuthService）。
 * 30 天恢复窗口口径与 Wave2 退驻 60 天一致：以 disabled_at 为起点、数据库时间比较、
 * <30 天整可恢复 / >=30 天整拒绝（互补无缝隙，WEM-S1-07 边界测试）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WholesalerEmployeeServiceImpl implements WholesalerEmployeeService {

    /** 员工禁用恢复窗口（天）。>=30 天整拒绝恢复；<30 天可恢复。 */
    private static final int DISABLE_RESTORE_WINDOW_DAYS = 30;

    /** 数据库时间窗口判定（同 Wave2 口径，不用应用时钟；H2(MODE=MySQL) 与 MySQL 均支持） */
    private static final String SQL_WITHIN_RESTORE_WINDOW =
            "disabled_at > TIMESTAMPADD(DAY, -" + DISABLE_RESTORE_WINDOW_DAYS + ", NOW())";

    private final UserRoleMapper userRoleMapper;
    private final UserMapper userMapper;
    // W8 收缩后 users.phone 明文列已 DROP，员工列表手机号从 cipher 解密取回
    private final PiiCrypto piiCrypto;

    @Override
    public List<WholesalerEmployeeVo> listEmployees(Long waUserId) {
        Long wholesalerId = requireOwnWholesalerId(waUserId);
        List<UserRole> roles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getRole, "WE")
                .eq(UserRole::getWholesalerId, wholesalerId)
                .orderByDesc(UserRole::getCreatedAt));
        if (roles.isEmpty()) {
            return List.of();
        }
        // 批量取用户信息（姓名/手机号），避免 N+1
        List<Long> userIds = roles.stream().map(UserRole::getUserId).distinct().toList();
        Map<Long, User> users = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return roles.stream().map(r -> toVo(r, users.get(r.getUserId()))).toList();
    }

    @Override
    @Transactional
    public WholesalerEmployeeVo updatePermissions(Long waUserId, Long employeeRoleId, List<String> permissions) {
        Long wholesalerId = requireOwnWholesalerId(waUserId);
        UserRole employee = requireOwnEmployee(employeeRoleId, wholesalerId);

        if (!WePermissions.allAllowed(permissions)) {
            throw new BizException(ErrorCode.EMPLOYEE_INVITE_PERMISSION_INVALID);
        }
        String encoded = WePermissions.encode(permissions);
        userRoleMapper.update(null, new LambdaUpdateWrapper<UserRole>()
                .eq(UserRole::getId, employee.getId())
                .set(UserRole::getPermissions, encoded)
                .set(UserRole::getUpdatedAt, LocalDateTime.now()));
        employee.setPermissions(encoded);
        log.info("[P2][WE 员工] WA {} 调整员工 {}(user {}) 授权为 {}",
                waUserId, employeeRoleId, employee.getUserId(), encoded);
        return toVo(employee, userMapper.selectById(employee.getUserId()));
    }

    @Override
    @Transactional
    public Map<String, Object> disableEmployee(Long waUserId, Long employeeRoleId) {
        Long wholesalerId = requireOwnWholesalerId(waUserId);
        UserRole employee = requireOwnEmployee(employeeRoleId, wholesalerId);

        // R17：仅 ACTIVE 可禁用（CAS 防并发重复禁用改写 disabled_at 而变相续期恢复窗口）
        LocalDateTime now = LocalDateTime.now();
        int affected = userRoleMapper.update(null, new LambdaUpdateWrapper<UserRole>()
                .eq(UserRole::getId, employee.getId())
                .eq(UserRole::getStatus, "ACTIVE")
                .set(UserRole::getStatus, "DISABLED")
                .set(UserRole::getDisabledAt, now)
                .set(UserRole::getUpdatedAt, now));
        if (affected == 0) {
            throw new BizException(ErrorCode.EMPLOYEE_STATE_INVALID);
        }

        // R17 禁用即踢：事务提交后踢出该用户 token（回滚不误踢；无在线会话异常吞掉）。
        // 草稿单据作废：phase-1 WE 无可持有的草稿态单据（设计文档 Wave3 章），此处无操作。
        kickoutAfterCommit(employee.getUserId(), waUserId, employeeRoleId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("employeeId", employee.getId().toString());
        result.put("status", "DISABLED");
        result.put("disabledAt", now);
        result.put("restoreWindowDays", DISABLE_RESTORE_WINDOW_DAYS);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> restoreEmployee(Long waUserId, Long employeeRoleId) {
        Long wholesalerId = requireOwnWholesalerId(waUserId);
        UserRole employee = requireOwnEmployee(employeeRoleId, wholesalerId);

        // CAS + 数据库时间窗口：DISABLED 且 <30 天整才可恢复；授权位保持禁用前设置（不动 permissions）
        int affected = userRoleMapper.update(null, new LambdaUpdateWrapper<UserRole>()
                .eq(UserRole::getId, employee.getId())
                .eq(UserRole::getStatus, "DISABLED")
                .apply(SQL_WITHIN_RESTORE_WINDOW)
                .set(UserRole::getStatus, "ACTIVE")
                .set(UserRole::getDisabledAt, null)
                .set(UserRole::getUpdatedAt, LocalDateTime.now()));
        if (affected == 0) {
            // 区分语义：仍是 DISABLED → 窗口已过（50322）；否则状态不允许（50321）
            UserRole fresh = userRoleMapper.selectById(employee.getId());
            if (fresh != null && "DISABLED".equals(fresh.getStatus())) {
                throw new BizException(ErrorCode.EMPLOYEE_RESTORE_EXPIRED);
            }
            throw new BizException(ErrorCode.EMPLOYEE_STATE_INVALID);
        }
        log.info("[P2][WE 员工] WA {} 恢复员工 {}(user {})，授权保持禁用前设置",
                waUserId, employeeRoleId, employee.getUserId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("employeeId", employee.getId().toString());
        result.put("status", "ACTIVE");
        return result;
    }

    // ==================== 私有 ====================

    /** 登录 WA 的己方商户 id（第一条 ACTIVE WA 绑定）；未入驻拒绝。 */
    private Long requireOwnWholesalerId(Long waUserId) {
        UserRole wa = userRoleMapper.selectOne(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, waUserId)
                .eq(UserRole::getRole, "WA")
                .eq(UserRole::getStatus, "ACTIVE")
                .isNotNull(UserRole::getWholesalerId)
                .last("LIMIT 1"));
        if (wa == null) {
            throw new BizException(ErrorCode.WHOLESALER_NOT_FOUND, "您没有已入驻的批发商商户");
        }
        return wa.getWholesalerId();
    }

    /** 目标员工行：必须存在、role=WE、且归属操作者商户（SEC-S4-10 跨商户按不存在处理）。 */
    private UserRole requireOwnEmployee(Long employeeRoleId, Long wholesalerId) {
        UserRole employee = userRoleMapper.selectById(employeeRoleId);
        if (employee == null || !"WE".equals(employee.getRole())
                || !wholesalerId.equals(employee.getWholesalerId())) {
            throw new BizException(ErrorCode.EMPLOYEE_NOT_FOUND);
        }
        return employee;
    }

    private void kickoutAfterCommit(Long targetUserId, Long waUserId, Long employeeRoleId) {
        Runnable kick = () -> {
            try {
                StpUtil.kickout(targetUserId);
                log.info("[P2][R17] 员工 {}(user {}) 已被 WA {} 禁用并踢出", employeeRoleId, targetUserId, waUserId);
            } catch (Exception e) {
                log.debug("[P2][R17] 用户 {} 无在线会话或踢出失败：{}", targetUserId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    kick.run();
                }
            });
        } else {
            kick.run();
        }
    }

    private WholesalerEmployeeVo toVo(UserRole role, User user) {
        return WholesalerEmployeeVo.builder()
                .id(role.getId())
                .userId(role.getUserId())
                .wholesalerId(role.getWholesalerId())
                .phone(user != null ? piiCrypto.decrypt(user.getPhoneCipher()) : null)
                .nickname(user != null ? user.getNickname() : null)
                .realName(user != null ? user.getRealName() : null)
                .permissions(WePermissions.decode(role.getPermissions()))
                .status(role.getStatus())
                .disabledAt(role.getDisabledAt())
                .createdAt(role.getCreatedAt())
                .build();
    }
}
