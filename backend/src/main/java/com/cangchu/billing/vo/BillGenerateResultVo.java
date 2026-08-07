package com.cangchu.billing.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 账单生成结果 VO（P4 W3；Job 汇总 / 手动补跑返回）。幂等语义：existing=本次未新建（已有单）。
 */
@Data
@Builder
public class BillGenerateResultVo {

    /** 账期月 yyyy-MM */
    private String month;

    /** 本次新生成张数 */
    private int generated;

    /** 已存在跳过张数（幂等） */
    private int existing;

    /** 无出账对象/不满足条件跳过的商户数 */
    private int skipped;

    /** 新生成账单 id（字符串防精度） */
    private List<String> billIds;
}
