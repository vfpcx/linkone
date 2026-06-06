package com.cangchu.common.tenant;

import lombok.Data;

/**
 * 租户上下文（ThreadLocal）
 */
public class TenantContext {

    private static final ThreadLocal<TenantInfo> CONTEXT = new ThreadLocal<>();

    public static void set(TenantInfo info) {
        CONTEXT.set(info);
    }

    public static TenantInfo get() {
        return CONTEXT.get();
    }

    public static Long getTenantId() {
        TenantInfo info = CONTEXT.get();
        return info != null ? info.getTenantId() : null;
    }

    public static Long getUserId() {
        TenantInfo info = CONTEXT.get();
        return info != null ? info.getUserId() : null;
    }

    public static String getRole() {
        TenantInfo info = CONTEXT.get();
        return info != null ? info.getActorRole() : null;
    }

    /**
     * OPS 跨租户查询时清除租户上下文
     */
    public static void clearForGlobalQuery() {
        CONTEXT.remove();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    @Data
    public static class TenantInfo {
        private Long tenantId;
        private Long userId;
        private Long storeId;
        private String actorRole;

        public static TenantInfo of(Long tenantId, Long userId, String actorRole) {
            TenantInfo info = new TenantInfo();
            info.tenantId = tenantId;
            info.userId = userId;
            info.actorRole = actorRole;
            return info;
        }
    }
}
