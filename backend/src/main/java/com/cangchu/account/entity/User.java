package com.cangchu.account.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户主表
 */
@Data
@TableName("users")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String phone;

    private String phoneHash;

    private String passwordHash;

    private String nickname;

    /** 真实姓名（实名，区别于展示昵称 nickname）。D-16 注册落库。 */
    private String realName;

    private String avatarUrl;

    private String status;

    private LocalDateTime lastLoginAt;

    private String lastLoginIp;

    private LocalDateTime cancelApplyAt;

    private String registerSource;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
