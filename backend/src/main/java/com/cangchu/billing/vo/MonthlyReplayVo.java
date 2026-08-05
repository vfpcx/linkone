package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 月度回放汇总（P4 W2 → W3 契约：MonthlyBillJob 出账的 STORAGE 小计来源；
 * 亦作 ST 对账下钻「按 SKU」视角，14 §1.3/§3.2）。
 *
 * <p>零账语义（无规则 / 规则晚于月末）：periodStart/periodEnd=null、lines=[]、subtotal=0
 * （D-P4-4：首版前天数不出账、历史不补出账）。
 */
@Data
@Builder
public class MonthlyReplayVo {

    /** 账期月 yyyy-MM */
    private String month;

    /** 账单期间起 = max(月初, 首版规则 effective_from)；零账为 null */
    private LocalDate periodStart;

    /** 账单期间止 = 月末；零账为 null */
    private LocalDate periodEnd;

    /** STORAGE 行（SKU × 规则段），skuId+periodStart 升序 */
    private List<StorageLineVo> lines;

    /** Σ行金额（每行已 HALF_UP 到分后加总——Σ条目=账单小计恒等式） */
    private BigDecimal subtotal;
}
