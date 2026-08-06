package com.cangchu.inventory.dto;

/**
 * 存在流水的 (tenant, wholesaler) 组合（P4 W2 快照 Job 枚举范围，14 §1.2
 * 「对每个存在流水的 (wholesaler, sku)」——sku 维度由回放引擎在组合内部展开）。
 */
public record BillingPairView(Long tenantId, Long wholesalerId) {
}
