package com.cangchu.common;

import com.cangchu.CangchuApplication;
import com.cangchu.common.exception.BizException;
import com.cangchu.common.exception.ErrorCode;
import com.cangchu.common.file.FileStorageService;
import com.cangchu.common.util.SnowflakeIdUtil;
import com.cangchu.notify.entity.Notification;
import com.cangchu.notify.service.NotificationService;
import com.cangchu.notify.vo.NotificationVo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 BE-W1 基建最小验证：附件上传（12 §4.4 魔数/尺寸口径）+ 站内信三端点语义（12 §4.3）。
 */
@SpringBootTest(classes = CangchuApplication.class)
class FileAndNotificationScenarioTest {

    @Autowired
    private FileStorageService fileStorageService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private SnowflakeIdUtil snowflakeIdUtil;

    private static byte[] pngBytes() {
        byte[] b = new byte[64];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, b, 0, magic.length);
        return b;
    }

    @Test
    @DisplayName("P3-FILE-01 魔数白名单：png 通过（URL 结构 /files/yyyyMM/uuid.png）；伪装扩展名/超5MB → 50340")
    void uploadValidation() {
        String url = fileStorageService.store(pngBytes());
        assertThat(url).matches("/files/\\d{6}/[0-9a-f-]{36}\\.png");

        // 非白名单魔数（文本冒充图片）→ 50340
        BizException badMagic = Assertions.assertThrows(BizException.class,
                () -> fileStorageService.store("MZ not an image".getBytes(StandardCharsets.UTF_8)));
        assertThat(badMagic.getErrorCode()).isEqualTo(ErrorCode.FILE_UPLOAD_INVALID);

        // 超 5MB → 50340（jpg 魔数 + 5MB+1 字节）
        byte[] oversize = new byte[5 * 1024 * 1024 + 1];
        oversize[0] = (byte) 0xFF;
        oversize[1] = (byte) 0xD8;
        oversize[2] = (byte) 0xFF;
        BizException tooBig = Assertions.assertThrows(BizException.class,
                () -> fileStorageService.store(oversize));
        assertThat(tooBig.getErrorCode()).isEqualTo(ErrorCode.FILE_UPLOAD_INVALID);

        // 空文件 → 50340
        assertThat(Assertions.assertThrows(BizException.class, () -> fileStorageService.store(new byte[0]))
                .getErrorCode()).isEqualTo(ErrorCode.FILE_UPLOAD_INVALID);

        // webp 魔数通过
        byte[] webp = new byte[32];
        byte[] riff = "RIFF0000WEBP".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(riff, 0, webp, 0, riff.length);
        assertThat(fileStorageService.store(webp)).endsWith(".webp");
    }

    @Test
    @DisplayName("P3-NTF-01 站内信：未读数/列表 unreadOnly/已读幂等/非本人按不存在 50341")
    void notificationLifecycle() {
        long me = snowflakeIdUtil.nextId();
        long stranger = snowflakeIdUtil.nextId();
        long tenantId = 740_000_000_001L + (snowflakeIdUtil.nextId() & 0xFFFF);

        notificationService.send(tenantId, me, Notification.TYPE_INBOUND_PENDING_CONFIRM,
                "代建入库待确认", "入库单 WK-X 请确认", Notification.REF_INBOUND, 1L);
        notificationService.send(tenantId, me, Notification.TYPE_ARBITRATION_DECIDED,
                "仲裁已裁决", "结论：通过", Notification.REF_ARBITRATION, 2L);
        // 收件人为空 → 静默跳过（不阻断业务）
        notificationService.send(tenantId, null, Notification.TYPE_DISPUTE_CREATED, "x", "y", null, null);

        assertThat(notificationService.unreadCount(me)).isEqualTo(2);
        var page = notificationService.listMine(me, 1, 10, true);
        assertThat(page.getRecords()).hasSize(2);
        Long firstId = page.getRecords().get(0).getId();

        // 非本人已读 → 50341（按不存在，不泄露存在性）
        BizException ex = Assertions.assertThrows(BizException.class,
                () -> notificationService.markRead(firstId, stranger));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);

        // 本人已读 + 幂等
        notificationService.markRead(firstId, me);
        notificationService.markRead(firstId, me);
        assertThat(notificationService.unreadCount(me)).isEqualTo(1);
        assertThat(notificationService.listMine(me, 1, 10, true).getRecords())
                .extracting(NotificationVo::getId).doesNotContain(firstId);
        // 全量列表仍含已读（含 readAt）
        assertThat(notificationService.listMine(me, 1, 10, false).getRecords()).hasSize(2);
        assertThat(notificationService.listMine(me, 1, 10, false).getRecords().stream()
                .filter(n -> firstId.equals(n.getId())).findFirst().orElseThrow().getReadAt()).isNotNull();
    }
}
