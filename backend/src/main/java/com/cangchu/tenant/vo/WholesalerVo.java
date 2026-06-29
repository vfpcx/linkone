package com.cangchu.tenant.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批发商商户视图对象。
 */
@Data
@Builder
public class WholesalerVo {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private String name;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerUserId;

    private String license;

    private String intro;

    private String status;

    private String source;

    /** WA 账号对应的 user_roles.id（开通账号时返回，否则 null） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long waUserId;

    private LocalDateTime createdAt;
}
