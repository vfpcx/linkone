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

    /**
     * W8-B3（V33 起）：登录身份唯一索引 = HMAC-SHA256(phone)，phone 明文列已下线。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String phoneHmac;

    /** W8-B1（V31）：AES-GCM(phone) 密文影子列（G-8.6 员工解密供给等全号消费点用）。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String phoneCipher;

    /** W8-B1（V31）：phone 尾号 4 位（列表/日志免解密打码）。JsonIgnore 同上。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String phoneLast4;

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
