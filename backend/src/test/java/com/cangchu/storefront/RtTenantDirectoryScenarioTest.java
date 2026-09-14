package com.cangchu.storefront;

import com.cangchu.CangchuApplication;
import com.cangchu.common.pii.PiiCrypto;
import com.cangchu.common.tenant.TenantContext;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.storefront.service.StoreFrontService;
import com.cangchu.tenant.entity.Store;
import com.cangchu.tenant.entity.Tenant;
import com.cangchu.tenant.mapper.StoreMapper;
import com.cangchu.tenant.mapper.TenantMapper;
import com.cangchu.tenant.vo.RtTenantDirectoryItemVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-RT-06 · RT 首页「附近仓库」公开目录场景测试。
 *
 * <p>公开端点无登录态，调用前清空 TenantContext；直接 mapper seed 租户/店铺，
 * 经 {@link StoreFrontService#listNearbyStores} 验证返回范围、排序、距离估算。
 */
@SpringBootTest(classes = CangchuApplication.class)
class RtTenantDirectoryScenarioTest {

    /** 杭州东站附近（测试定位中心） */
    private static final BigDecimal CENTER_LAT = new BigDecimal("30.2741");
    private static final BigDecimal CENTER_LNG = new BigDecimal("120.1552");

    @Autowired
    private StoreFrontService storeFrontService;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private PiiCrypto piiCrypto;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private String seedTenant(String status, String simpleCodePrefix) {
        Tenant t = new Tenant();
        Long id = snowflakeIdUtil.nextId();
        t.setId(id);
        t.setName("附近仓库测试-" + simpleCodePrefix + "-" + id);
        t.setTenantSimpleCode(simpleCodePrefix + Math.abs(id % 1000L));
        t.setContactUserId(snowflakeIdUtil.nextId());
        t.setContactPhoneCipher(piiCrypto.encrypt("13800000000"));
        t.setStatus(status);
        tenantMapper.insert(t);
        return String.valueOf(id);
    }

    private String codeOf(String tenantId) {
        return tenantMapper.selectById(tenantId).getTenantSimpleCode();
    }

    private void seedStore(String tenantId, String name, BigDecimal lat, BigDecimal lng) {
        Store s = new Store();
        s.setId(snowflakeIdUtil.nextId());
        s.setTenantId(Long.valueOf(tenantId));
        s.setName(name);
        s.setStatus("ACTIVE");
        s.setLat(lat);
        s.setLng(lng);
        storeMapper.insert(s);
    }

    @Test
    void nearbyStores_returnsActiveOnly_andSortsByDistance() {
        // A：定位中心点，距离应为 0
        String tenantA = seedTenant("ACTIVE", "AA");
        seedStore(tenantA, "中心仓库", CENTER_LAT, CENTER_LNG);

        // B：稍远（约 11km 东北方向）
        String tenantB = seedTenant("ACTIVE", "AB");
        seedStore(tenantB, "东北仓库", new BigDecimal("30.3500"), new BigDecimal("120.2200"));

        // C：PENDING 状态，不应出现在公开目录
        String tenantC = seedTenant("PENDING", "AC");
        seedStore(tenantC, "未审核仓库", CENTER_LAT, CENTER_LNG);

        TenantContext.clear();
        List<RtTenantDirectoryItemVo> list = storeFrontService.listNearbyStores(CENTER_LAT, CENTER_LNG, 10);

        Set<String> codes = list.stream().map(RtTenantDirectoryItemVo::getTenantSimpleCode).collect(Collectors.toSet());
        assertThat(codes).contains(codeOf(tenantA));
        assertThat(codes).contains(codeOf(tenantB));
        assertThat(codes).doesNotContain(codeOf(tenantC));

        // 排序：A 距离 0 在第一位；B 距离 > 0 在第二位
        RtTenantDirectoryItemVo first = list.get(0);
        assertThat(first.getDistanceMeters()).isEqualTo(0);
        assertThat(first.getTenantSimpleCode()).isEqualTo(codeOf(tenantA));

        RtTenantDirectoryItemVo second = list.get(1);
        assertThat(second.getDistanceMeters()).isNotNull().isGreaterThan(0).isLessThan(20000);
    }

    @Test
    void nearbyStores_withoutCoordinates_returnsActiveAndNoDistance() {
        String tenantA = seedTenant("ACTIVE", "AD");
        seedStore(tenantA, "无坐标仓库 A", null, null);

        String tenantB = seedTenant("ACTIVE", "AE");
        seedStore(tenantB, "无坐标仓库 B", null, null);

        TenantContext.clear();
        List<RtTenantDirectoryItemVo> list = storeFrontService.listNearbyStores(null, null, 10);

        Set<String> codes = list.stream().map(RtTenantDirectoryItemVo::getTenantSimpleCode).collect(Collectors.toSet());
        assertThat(codes).contains(codeOf(tenantA), codeOf(tenantB));
        assertThat(list).allMatch(item -> item.getDistanceMeters() == null);
    }

    @Test
    void nearbyStores_limitIsCapped() {
        for (int i = 0; i < 5; i++) {
            String t = seedTenant("ACTIVE", "L" + i);
            seedStore(t, "批量仓库" + i, CENTER_LAT, CENTER_LNG);
        }
        TenantContext.clear();
        List<RtTenantDirectoryItemVo> list = storeFrontService.listNearbyStores(CENTER_LAT, CENTER_LNG, 100);
        assertThat(list).hasSizeLessThanOrEqualTo(50);
    }
}
