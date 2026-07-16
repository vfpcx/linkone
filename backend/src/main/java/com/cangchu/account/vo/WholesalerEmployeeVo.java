package com.cangchu.account.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批发商员工(WE)视图（P2 入驻 Wave3：WA 员工管理列表项 / 授权变更返回）。
 *
 * <p>id 为 user_roles 主键（员工-商户绑定行），员工管理端点的 {id} 路径参数即此值。
 */
@Data
@Builder
public class WholesalerEmployeeVo {

    /** user_roles.id（员工绑定记录 id，disable/restore/permissions 端点的目标 id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    private String phone;
    private String nickname;
    private String realName;

    /** 授权位：PRICE_EDIT / INQUIRY_CONFIRM（空列表=只读） */
    private List<String> permissions;

    /** ACTIVE / DISABLED */
    private String status;

    /** 禁用时间（30 天恢复窗口起点；ACTIVE 时为空） */
    private LocalDateTime disabledAt;

    /** 注册加入时间 */
    private LocalDateTime createdAt;
}
