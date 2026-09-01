package com.cangchu.common.pii;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cangchu.account.entity.SmsCode;
import com.cangchu.account.entity.User;
import com.cangchu.pricing.entity.CustomerPrice;
import com.cangchu.tenant.entity.Blacklist;

/**
 * hmac 列查询的<b>唯一构造入口</b>（15-pii-hardening-v2 §4 Step 1/2）。
 *
 * <p><b>为什么单独收一个类</b>：影子期用来比对的那条 hmac 查询，与切读后真正出结果的那条，必须
 * 逐字节是同一条。否则「7 天 / 3 天 mismatch=0」证明的是 A 查询，上线跑的是 B 查询，闸门就是自欺。
 * 把谓词收在这里之后，影子比对与正式出结果共用同一构造入口，谓词无从分叉（W8 收口后
 * 影子/路由已删，本类即 hmac 读路径的唯一入口）。
 *
 * <p>每次调用新建 wrapper，调用方可继续链式追加 {@code .select(...)}；逻辑删除行两边都由
 * MyBatis-Plus 自动排除。
 */
public final class PiiHmacQueries {

    private PiiHmacQueries() {
    }

    /**
     * A1–A6 登录链：按 {@code phone_hmac} 取行（15 §4 Step 3 / 波次 PII-W6）。
     *
     * <p>W8 收口后本查询即登录链 hmac 读路径的唯一入口（V34 明文列已删，无旧列分支）。
     *
     * <p>本方法是 Step 3 才补上的（影子比对实现已随 W8 收口删除）；登录链切读后「影子期比对的
     * 谓词」与「上线出结果的谓词」必须逐字节相同，否则 Step 1 那道 7 天闸门证明的是另一条查询。
     */
    public static LambdaQueryWrapper<User> user(String phoneHmac) {
        return new LambdaQueryWrapper<User>()
                .eq(User::getPhoneHmac, phoneHmac)
                .last("LIMIT 1");
    }

    /**
     * B2 加黑查重/复活：按物理唯一键的 hmac 侧查任一状态的行（不按 status 过滤——REMOVED 行也要
     * 捞出来复活，与明文口径一致）。仅 PHONE 行有 hmac，调用方须自行把 LICENSE_NO 挡在外面。
     */
    public static LambdaQueryWrapper<Blacklist> blacklistEntry(String targetValueHmac) {
        return new LambdaQueryWrapper<Blacklist>()
                .eq(Blacklist::getTargetType, "PHONE")
                .eq(Blacklist::getTargetValueHmac, targetValueHmac)
                .last("LIMIT 1");
    }

    /** B1 入驻命中检查：命中判定是布尔（ACTIVE 行数 &gt; 0），故不取列只数行。 */
    public static LambdaQueryWrapper<Blacklist> blacklistActiveHit(String targetValueHmac) {
        return new LambdaQueryWrapper<Blacklist>()
                .eq(Blacklist::getTargetType, "PHONE")
                .eq(Blacklist::getTargetValueHmac, targetValueHmac)
                .eq(Blacklist::getStatus, "ACTIVE");
    }

    /**
     * C1/C2 定价链单行：唯一键 (wholesaler_id, rt_phone→hmac, sku_id)。
     *
     * @param status 主路若按状态过滤则传该状态（C2 传 ACTIVE），否则传 null（C1 的 upsert 探测
     *               不按 status 过滤——否则 DISABLED/EXPIRED 行会 miss→insert→撞唯一键）
     */
    public static LambdaQueryWrapper<CustomerPrice> customerPrice(Long wholesalerId, String rtPhoneHmac,
                                                                  Long skuId, String status) {
        return new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, wholesalerId)
                .eq(CustomerPrice::getRtPhoneHmac, rtPhoneHmac)
                .eq(CustomerPrice::getSkuId, skuId)
                .eq(status != null, CustomerPrice::getStatus, status)
                .last("LIMIT 1");
    }

    /** C3 批量调价按 rtPhone 圈选（多行）：skuId / rtPhoneHmac 传 null 表示本次不按该项收窄，与主路条件一致。 */
    public static LambdaQueryWrapper<CustomerPrice> customerPriceRows(Long wholesalerId, Long skuId,
                                                                      String rtPhoneHmac) {
        return new LambdaQueryWrapper<CustomerPrice>()
                .eq(CustomerPrice::getWholesalerId, wholesalerId)
                .eq(skuId != null, CustomerPrice::getSkuId, skuId)
                .eq(rtPhoneHmac != null && !rtPhoneHmac.isBlank(), CustomerPrice::getRtPhoneHmac, rtPhoneHmac);
    }

    /** SMS 验证码校验：scene / code / 未核销 / 取最新一条逐条照抄明文口径，只换手机号那一列。 */
    public static LambdaQueryWrapper<SmsCode> smsCode(String phoneHmac, String scene, String code) {
        return new LambdaQueryWrapper<SmsCode>()
                .eq(SmsCode::getPhoneHmac, phoneHmac)
                .eq(SmsCode::getScene, scene)
                .eq(SmsCode::getCode, code)
                .isNull(SmsCode::getVerifiedAt)
                .orderByDesc(SmsCode::getCreatedAt)
                .last("LIMIT 1");
    }
}
