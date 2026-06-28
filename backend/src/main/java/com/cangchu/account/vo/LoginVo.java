package com.cangchu.account.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
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

    private List<RoleInfo> roles;

    private String primaryRouter;

    /** D-13：带时区偏移的过期时间（OffsetDateTime → ISO-8601 含 +08:00），与契约对齐 */
    private OffsetDateTime expireAt;

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
