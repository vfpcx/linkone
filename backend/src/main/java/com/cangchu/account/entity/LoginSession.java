package com.cangchu.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录会话审计
 */
@Data
@TableName("login_sessions")
public class LoginSession {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String role;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private String device;

    private String deviceInfo;

    private String loginIp;

    private String loginMethod;

    private String tokenHash;

    private LocalDateTime logoutAt;

    private String kickoutReason;

    private LocalDateTime createdAt;
}
