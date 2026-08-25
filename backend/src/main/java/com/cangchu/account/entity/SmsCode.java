package com.cangchu.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短信验证码
 */
@Data
@TableName("sms_codes")
public class SmsCode {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String phone;

    /**
     * HMAC-SHA256(phone) 盲索引（V30，PII 阶段 0）。
     * 唯一产生点 {@code PiiCrypto.phoneHmac}；write-mode=dual 时随 phone 同写，读路径不用。
     * JsonIgnore 保证任何实体直出的响应形状零变化（同 users.phone_hmac 口径）。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String phoneHmac;

    private String scene;

    private String code;

    private LocalDateTime expireAt;

    private Integer verifyCount;

    private LocalDateTime verifiedAt;

    private String requestIp;

    private LocalDateTime createdAt;
}
