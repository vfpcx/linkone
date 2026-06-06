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

    private String scene;

    private String code;

    private LocalDateTime expireAt;

    private Integer verifyCount;

    private LocalDateTime verifiedAt;

    private String requestIp;

    private LocalDateTime createdAt;
}
