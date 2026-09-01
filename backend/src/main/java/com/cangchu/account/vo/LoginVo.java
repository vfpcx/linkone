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

        /** 多仓（2026-09-01）：该角色绑定租户的仓库名，供前端工作空间切换器展示；无租户角色省略 */
        private String storeName;
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
