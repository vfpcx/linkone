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

    /**
     * HMAC-SHA256(phone) 盲索引（V30；W8-B3 起为发码/校验身份键，phone 明文列已下线）。
     * 唯一产生点 {@code PiiCrypto.phoneHmac}。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String phoneHmac;

    /** W8-B1（V31）：phone 尾号 4 位（发码排障日志免解密打码）。JsonIgnore 同上。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String phoneLast4;

    private String scene;

    private String code;

    private LocalDateTime expireAt;

    private Integer verifyCount;

    private LocalDateTime verifiedAt;

    private String requestIp;

    private LocalDateTime createdAt;
}
