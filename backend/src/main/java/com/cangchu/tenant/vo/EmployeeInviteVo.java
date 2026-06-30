package com.cangchu.tenant.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 员工注册码视图（TA 生码返回 / 列表项）。
 */
@Data
@Builder
public class EmployeeInviteVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    /** 注册码（明文随机串，供 TA 复制分发给员工） */
    private String code;

    /** 目标角色 WK / ST */
    private String role;

    private Integer maxUses;
    private Integer usedCount;

    /** 剩余可用次数 = maxUses - usedCount */
    private Integer remaining;

    /** 过期时间（null 表示永不过期） */
    private LocalDateTime expireAt;

    /** 状态：ACTIVE / EXHAUSTED / REVOKED */
    private String status;

    private LocalDateTime createdAt;
}
