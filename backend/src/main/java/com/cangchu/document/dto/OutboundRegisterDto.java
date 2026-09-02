package com.cangchu.document.dto;

import lombok.Data;

/**
 * WK 登记出库入参（P3b T3-W1 改造，13 §5.2：D-8=A 出库处托盘补齐）。
 * 整体可空（旧调用不传 body 行为兼容——按默认建议值释放）；件数仍创建即扣不变，
 * 托盘在登记出库时经独立 PALLET_RELEASE 流水释放（qty=0、pallet_delta=−n）。
 */
@Data
public class OutboundRegisterDto {

    /** 释放托盘覆盖值（可空=默认建议值；0 合法=托盘未腾空；落库前对在库托盘封顶） */
    private Integer palletRelease;

    /** 拣出货位（C2，25-p5-c-c2 §4.3：货位开关启用时登记出库必填 ≤64；落 outbound_requests.location 留痕，零记账副作用） */
    @jakarta.validation.constraints.Size(max = 64, message = "货位号最长 64 字")
    private String location;
}
