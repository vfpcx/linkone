package com.cangchu.common.pii;

import java.util.Set;

/**
 * PII 切读灰度的模块划分（15-pii-hardening-v2 §4 Step 2 / Step 3）。
 *
 * <p><b>为什么不是一个全局开关</b>：要切的这几块，爆炸半径完全不同——黑名单切错是放进来一个
 * 该拦的人，短信切错是全员注册/找回登录失败，定价切错是资损或漏调价，Redis 键切错只是缓存/限流
 * 窗口重来一遍，而登录切错是<b>全员登不上</b>。一刀切意味着任一块翻车都得把其余几块一起拨回，
 * 把已经观察合格的部分也赔进去。分模块后回滚粒度 = 出事的那一块，其余照常走 hmac。
 *
 * <p><b>登录链（A1–A6）从 Step 3 / 波次 PII-W6 起进入本清单</b>，但口径与另四块不同：另四块是
 * <b>硬切</b>（hmac 未命中即真未命中），登录是<b>双读兜底自愈 + 异步补写</b>——见
 * {@link PiiReadRouter#user}。Step 2 期间它<b>没有</b>模块名，故意的：那时它一行未切，继续吃全局
 * {@code cangchu.pii.read-mode}（也正是 Step 1「7 天 mismatch=0」闸门组的口径），当时给它留个
 * 模块名只会让人误以为拨一下就能切。现在双读兜底与补写都已就位，模块名才随之出现——它同时是
 * 登录链自己的回滚拨杆：拨回 {@code shadow} 即恢复 {@code phone_hash} 读路径，秒级。
 *
 * <p>配置形如：
 * <pre>
 * cangchu:
 *   pii:
 *     read-mode: shadow        # 全局默认（未登记模块）
 *     read-modes:
 *       blacklist: hmac        # 单模块切读
 *       pricing: shadow        # 单模块拨回
 *       login: hmac            # 登录链切读（双读兜底口径）
 * </pre>
 * 模块名与模式取值均在启动期校验，写错即拒绝启动（见 {@link PiiReadRouter}）。
 */
public final class PiiModule {

    /** 黑名单命中检查 / 加黑查重（B1、B2）。LICENSE_NO 行 hmac 恒 NULL，永远走明文，不受本开关影响。 */
    public static final String BLACKLIST = "blacklist";

    /** 短信验证码校验（SMS-verify）。 */
    public static final String SMS = "sms";

    /** 专属价链：upsert 唯一键匹配（C1 ×2）、价格解析（C2）、批量调价按 rtPhone 圈选（C3）。 */
    public static final String PRICING = "pricing";

    /**
     * 手机号派生的 Redis 键（C4）：专属价匹配缓存 {@code price:match:*}、短信冷却/日限
     * {@code sms:cd:* / sms:daily:*}、登录失败计数 {@code login:fail:*}。
     *
     * <p>独立成模块而不是各自挂靠业务模块，是因为它与 DB 切读的失败模式不同：键换了只会让旧键
     * 自然失效（缓存重算、限流窗口重开），不存在「查不到人」这类语义事故，可以先于 DB 切读放行、
     * 也可以单独拨回而不惊动定价/短信的读路径。
     */
    public static final String REDIS_KEY = "redis-key";

    /**
     * 登录链 A1–A6（注册查重 / 密码登录 / 找回密码 / 换绑查重 / RT 免密 / 代建开号），Step 3 新增。
     *
     * <p><b>切读口径与另四块不同</b>：另四块硬切（hmac 未命中即真未命中），登录是<b>双读兜底</b>
     * ——hmac 未命中时回落 {@code phone_hash} 再查一次，命中则照常出结果并异步补写 hmac 列
     * （{@link PiiReadRouter#user}）。理由不是「登录更重要所以更小心」，而是失败模式不可接受：
     * 硬切下一行 hmac 漏填 = 那个人登不上、找不回密码、重复注册撞唯一键，且<b>毫无征兆</b>。
     *
     * <p>兜底会让缺陷永远沉默，所以它必须被单独计数——见 {@link PiiFallbackHealer}。
     */
    public static final String LOGIN = "login";

    /** 合法模块名全集（启动期校验用，写错模块名即拒绝启动）。 */
    public static final Set<String> ALL = Set.of(BLACKLIST, SMS, PRICING, REDIS_KEY, LOGIN);

    private PiiModule() {
    }
}
