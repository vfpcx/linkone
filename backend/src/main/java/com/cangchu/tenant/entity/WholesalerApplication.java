package com.cangchu.tenant.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批发商入驻申请（P2 Wave1，双轨模式：申请表 + wholesalers 主体表回填，O-1）。
 *
 * <p>tenant_id 为目标租户，由业务显式设置（申请人 WA 尚无租户绑定，不能依赖
 * MetaObjectHandler 自动填充）；查询侧已纳入 TenantLine 白名单兜底隔离。
 */
@Data
@TableName("wholesaler_applications")
public class WholesalerApplication {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 目标租户（显式设置，不走自动填充） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long applicantUserId;

    private String name;

    private String contactName;

    /** W8-B1（V31）：AES-GCM(contact_phone) 密文影子列。W8-B3 起为联系人手机号唯一载体（明文列已下线）。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String contactPhoneCipher;

    /** 营业执照号/凭证（黑名单键之一） */
    private String license;

    /** PENDING / APPROVED / REJECTED */
    private String status;

    /** 部分唯一标记（F1）：PENDING=1，终态置 NULL；uk_applicant_pending 保证一账号至多一 PENDING */
    private Integer pendingFlag;

    /** SELF_APPLY / OPS_CREATED / TA_SELF_OPERATED */
    private String source;

    /** OPS 代建授权依据（TA 授权凭据文本或客诉单号） */
    private String authBasis;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long auditUserId;

    private LocalDateTime auditedAt;

    private String auditRemark;

    /** 通过后回填的批发商 id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long wholesalerId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
