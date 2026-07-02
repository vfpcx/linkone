package com.cangchu.tenant.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 老板名下单个仓库的概要（用于多仓列表 / 顶栏切换器）。
 * id 序列化为 String，避免前端雪花精度丢失。
 */
@Data
@Builder
public class WarehouseVo {
    private String tenantId;
    private String name;
    private String simpleCode;
    private String status;   // PENDING / ACTIVE / REJECTED
}
