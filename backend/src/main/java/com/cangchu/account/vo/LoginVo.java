package com.cangchu.account.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录响应
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoginVo {

    private String token;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String primaryRole;

    private List<RoleInfo> roleList;

    private String primaryRouter;

    private LocalDateTime expireAt;

    private TenantInfo tenantInfo;

    private Boolean isNew;

    @Data
    @Builder
    public static class RoleInfo {
        private String role;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long tenantId;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long wholesalerId;

        private Integer priority;
    }

    @Data
    @Builder
    public static class TenantInfo {

        @JsonSerialize(using = ToStringSerializer.class)
        private Long tenantId;

        private String tenantName;

        private String tenantSimpleCode;
    }
}
