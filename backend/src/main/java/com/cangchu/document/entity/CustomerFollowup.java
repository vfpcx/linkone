package com.cangchu.document.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户跟进档案（C3 · US-WE-04，24-p5-c-c3 §3.1，document 域）。
 *
 * <p>客户 = 本商户（wholesaler）询价买家（按 rt_phone_hmac 归并，inquiry_requests 为唯一事实源）；
 * 本表仅存档案：单行/商户/客户，remark 覆盖式（D-C-6/8）。行同存 tenant_id 纳入 TenantLine 白名单，
 * 业务层另按 wholesaler 收敛（K-7：不在登录人 scope 一律假装不存在）。
 */
@Data
@TableName("customer_followups")
public class CustomerFollowup {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonIgnore
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    /** RT 手机号 HMAC-SHA256 盲索引（PiiCrypto.phoneHmac 唯一产生点；明文列不存在） */
    @JsonIgnore
    private String rtPhoneHmac;

    /** RT 手机号密文冗余（Job 站内信打尾号用；建档/更新时复制自最新询价单） */
    @JsonIgnore
    private String rtPhoneCipher;

    /** 跟进备注（≤200；空串=清除备注，K-3） */
    private String remark;

    @JsonIgnore
    private Long createdBy;

    @JsonIgnore
    private Long updatedBy;

    @JsonIgnore
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @JsonIgnore
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime updatedAt;
}
