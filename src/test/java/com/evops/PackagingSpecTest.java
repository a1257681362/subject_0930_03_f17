package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.gateway.MuseumRepairClient;
import com.evops.gateway.MuseumRepairUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物运输包装规格（museum-13）验收：
 * 1) 规格按材质/尺寸/缓冲等级/气候区版本化发布，旧版 SUPERSEDED；
 * 2) 装箱校验文物重量、外形、防震要求；
 * 3) 规格变更（v2）不影响已签押运关联装箱单（快照仍为 v1）；
 * 4) 临时替代包装必须审批、记录逐项差异，驳回装箱单 REJECTED，批准才可发运；
 * 5) 装箱照片与箱号绑定，发运前/拆箱后两阶段只追加，拆箱回写不覆盖发运前证据；
 * 6) 外部修复依赖无响应时不假装成功（停留 NOTIFY_PENDING），恢复后恰好通知一次。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-pack;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PackagingSpecTest.ControllableRepairClientConfig.class)
class PackagingSpecTest {

    @TempDir
    static Path imageDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("museum.image.storage-root", () -> imageDir.toString());
    }

    /** 可控外部修复依赖：默认无响应，恢复后置 available；统计实际投递次数 */
    static class ControllableRepairClient implements MuseumRepairClient {
        final AtomicBoolean available = new AtomicBoolean(false);
        final AtomicInteger sentCount = new AtomicInteger(0);

        @Override
        public String notifyPackConfirmed(PackConfirmNotice notice) {
            if (!available.get()) {
                throw new MuseumRepairUnavailableException("外部修复依赖无响应（测试桩）");
            }
            sentCount.incrementAndGet();
            return "ACK-" + notice.getNotifyKey();
        }
    }

    @TestConfiguration
    static class ControllableRepairClientConfig {
        static final ControllableRepairClient CLIENT = new ControllableRepairClient();

        @Bean
        @Primary
        public MuseumRepairClient controllableRepairClient() {
            return CLIENT;
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private com.evops.service.PackNotifyRetryScheduler retryScheduler;

    private ControllableRepairClient client() {
        return ControllableRepairClientConfig.CLIENT;
    }

    @Test
    void packaging_rules_hold() throws Exception {
        // ================================================================
        // 一、建档 + 入藏（重量/外形）+ 已签押运单；发布包装规格 v1
        // ================================================================
        long relicA = createRelic("PK-A-001", "一级青铜鼎", "青铜", "ONE");
        doIntake(relicA, 6000, 280, 180, 140);
        long agreementA = createSignedAgreement("LA-PK-A", relicA);

        long relicB = createRelic("PK-B-001", "二级青铜壶", "青铜", "TWO");
        doIntake(relicB, 4000, 260, 170, 130);
        long agreementB = createSignedAgreement("LA-PK-B", relicB);

        // 未签押运单不能装箱：用独立文物的 DRAFT 协议，避免占用 relicB
        long relicC = createRelic("PK-C-001", "青铜卣", "青铜", "TWO");
        doIntake(relicC, 3000, 200, 150, 120);
        long draftAgr = createAgreement("LA-PK-D", relicC, false);
        expectFail(postJson("/api/pack-orders",
                packJson(null, relicC, draftAgr, "BOX-D", "TEMPERATE", null,
                        3000, 200, 150, 120, "装箱工乙")), "未签署");

        // 规格 v1：青铜 S 码 L1（温带）、M 码 L2（温带）、S 码 L2（湿热）
        expectOk(postJson("/api/pack-specs/publish",
                "{\"versionNo\":\"PACK-R1\",\"title\":\"运输包装规格v1\",\"publishedBy\":\"文保部\","
                        + "\"items\":["
                        + specItem("青铜", "S", 100, 500, 100, 400, 100, 300, 20000, "L1", 30, "TEMPERATE")
                        + "," + specItem("青铜", "M", 400, 900, 300, 700, 200, 600, 40000, "L2", 50, "TEMPERATE")
                        + "," + specItem("青铜", "S", 100, 500, 100, 400, 100, 300, 20000, "L2", 50, "HOT_HUMID")
                        + "]}"));
        assertEquals("ACTIVE", dataOf(getJson("/api/pack-specs/active")).get("status").asText());

        // ================================================================
        // 二、装箱校验：重量 / 外形 / 防震 不满足一律拒绝
        // ================================================================
        // 重量超限（S 码承重 20000g）
        expectFail(postJson("/api/pack-orders",
                packJson(null, relicA, agreementA, "BOX-W", "TEMPERATE", null,
                        25000, 300, 200, 150, "装箱工甲")), "重量");
        // 外形超出 S 码区间（长 600 > 500；且不落入 M 码因为宽 200 < M 下限 300）
        expectFail(postJson("/api/pack-orders",
                packJson(null, relicA, agreementA, "BOX-S", "TEMPERATE", null,
                        8000, 600, 200, 150, "装箱工甲")), "外形");
        // 防震不满足：湿热区 S 码只有 L2，一级文物要求 L1
        expectFail(postJson("/api/pack-orders",
                packJson(null, relicA, agreementA, "BOX-C", "HOT_HUMID", null,
                        8000, 300, 200, 150, "装箱工甲")), "防震");

        // ================================================================
        // 三、标准装箱成功：外部依赖无响应 -> NOTIFY_PENDING，不假装成功
        // ================================================================
        assertFalse(client().available.get(), "初始外部修复依赖无响应");
        MvcResult packRes = postJson("/api/pack-orders",
                packJson("REQ-A-1", relicA, agreementA, "BOX-A-001", "TEMPERATE", null,
                        8000, 300, 200, 150, "装箱工甲"));
        JsonNode packData = dataOf(packRes);
        long orderA = packData.get("order").get("id").asLong();
        assertEquals("NOTIFY_PENDING", packData.get("order").get("status").asText(),
                "外部无响应时装箱单停留待确认，不得假装成功");
        assertFalse(packData.get("notified").asBoolean());
        assertTrue(packData.get("notifyError").asText().contains("无响应"),
                "无响应原因应回传，实际：" + packData.get("notifyError").asText());
        assertEquals("PACK-R1", packData.get("order").get("specVersionNo").asText(), "固化规格版本快照");

        // 同一流水号重试：命中同一装箱单，不产生第二条
        JsonNode replay = dataOf(postJson("/api/pack-orders",
                packJson("REQ-A-1", relicA, agreementA, "BOX-A-001", "TEMPERATE", null,
                        8000, 300, 200, 150, "装箱工甲")));
        assertEquals(orderA, replay.get("order").get("id").asLong(), "补传/重试复用流水号只形成一条");
        assertEquals(1, dataOf(getJson("/api/pack-orders?agreementId=" + agreementA)).size());

        // 外部仍无响应，手动重试不推进：通知表保持一条 PENDING
        expectFail(postJson("/api/pack-orders/" + orderA + "/notify-retry", "{}"), "无响应");
        JsonNode detailPending = dataOf(getJson("/api/pack-orders/" + orderA));
        assertEquals("NOTIFY_PENDING", detailPending.get("order").get("status").asText());
        assertEquals("PENDING", detailPending.get("notify").get("status").asText());
        assertTrue(detailPending.get("notify").get("attempts").asInt() >= 1, "失败尝试应累加");
        assertEquals(0, client().sentCount.get(), "无响应期间一次都不能算通知成功");

        // 发运前照片可传，拆箱后照片此时不能传
        uploadPhoto(orderA, "BEFORE_SHIP", "before-a.png", "押运拍照员");
        expectFail(uploadPhotoMvc(orderA, "AFTER_UNPACK", "after-a.png", "押运拍照员"), "拆箱");

        // ================================================================
        // 四、规格变更出 v2：已装箱单快照仍为 v1（规格变更不影响已签押运单）
        // ================================================================
        expectOk(postJson("/api/pack-specs/publish",
                "{\"versionNo\":\"PACK-R2\",\"title\":\"运输包装规格v2\",\"publishedBy\":\"文保部\","
                        + "\"items\":["
                        + specItem("青铜", "S", 100, 500, 100, 400, 100, 300, 18000, "L1", 25, "TEMPERATE")
                        + "]}"));
        assertEquals("PACK-R2", dataOf(getJson("/api/pack-specs/active")).get("versionNo").asText());
        JsonNode detailAfterV2 = dataOf(getJson("/api/pack-orders/" + orderA));
        assertEquals("PACK-R1", detailAfterV2.get("order").get("specVersionNo").asText(),
                "规格 v2 发布不影响已签押运关联装箱单");
        assertEquals(0, detailAfterV2.get("order").get("specMaxWeightG").decimalValue()
                .compareTo(new BigDecimal("20000")), "承重快照保持 v1");

        // ================================================================
        // 五、外部依赖恢复：补偿投递恰好一次，装箱单 PACKED；之后不再重复通知
        // ================================================================
        client().available.set(true);
        int sentByScheduler = retryScheduler.retryOnce();
        assertEquals(1, sentByScheduler, "恢复后补偿投递一条");
        JsonNode detailPacked = dataOf(getJson("/api/pack-orders/" + orderA));
        assertEquals("PACKED", detailPacked.get("order").get("status").asText());
        assertEquals("SENT", detailPacked.get("notify").get("status").asText());
        int sentOnce = client().sentCount.get();
        assertEquals(1, sentOnce, "恢复后只通知一次");
        // 再跑定时补偿 + 手动重试：SENT 永不重投
        assertEquals(0, retryScheduler.retryOnce(), "已送达不再补偿");
        expectOk(postJson("/api/pack-orders/" + orderA + "/notify-retry", "{}"));
        assertEquals(sentOnce, client().sentCount.get(), "恢复后重复触发不产生第二次通知");

        // ================================================================
        // 六、拆箱回写：只写拆箱字段，发运前证据（快照+BEFORE_SHIP 照片）不覆盖
        // ================================================================
        expectFail(postJson("/api/pack-orders/" + orderA + "/unpack",
                "{\"unpackedBy\":\"接保员\",\"condition\":\"DAMAGED\"}"), "说明");
        expectOk(postJson("/api/pack-orders/" + orderA + "/unpack",
                "{\"unpackedBy\":\"接保员\",\"condition\":\"INTACT\",\"note\":\"包装完好\"}"));
        JsonNode detailUnpacked = dataOf(getJson("/api/pack-orders/" + orderA));
        assertEquals("UNPACKED", detailUnpacked.get("order").get("status").asText());
        assertEquals("INTACT", detailUnpacked.get("order").get("unpackCondition").asText());
        assertEquals("PACK-R1", detailUnpacked.get("order").get("specVersionNo").asText(),
                "拆箱回写不改变发运前规格快照");
        // 重复拆箱拒绝
        expectFail(postJson("/api/pack-orders/" + orderA + "/unpack",
                "{\"unpackedBy\":\"接保员\",\"condition\":\"INTACT\"}"), "重复拆箱");
        // 拆箱后照片只追加，不覆盖发运前照片
        uploadPhoto(orderA, "AFTER_UNPACK", "after-a.png", "接保员");
        JsonNode finalDetail = dataOf(getJson("/api/pack-orders/" + orderA));
        assertEquals(1, finalDetail.get("beforeShipPhotos").size(), "发运前照片原样保留");
        assertEquals(1, finalDetail.get("afterUnpackPhotos").size(), "拆箱后照片另存一行");
        assertEquals("BOX-A-001",
                finalDetail.get("beforeShipPhotos").get(0).get("boxNo").asText(), "照片与箱号绑定");
        assertEquals("BOX-A-001",
                finalDetail.get("afterUnpackPhotos").get(0).get("boxNo").asText());

        // ================================================================
        // 七、临时替代包装：必须审批、记录差异；驳回 REJECTED；批准才可发运
        // ================================================================
        // 替代能力弱于防震要求（二级要求 L2，替代给 L3）直接拒绝
        expectFail(postJson("/api/pack-orders/substitute",
                substituteJson("REQ-B-BAD", relicB, agreementB, "BOX-B-BAD",
                        5000, 260, 170, 130, "L3", 80, 15000)), "防震");
        // 替代承重不足拒绝
        expectFail(postJson("/api/pack-orders/substitute",
                substituteJson("REQ-B-BAD2", relicB, agreementB, "BOX-B-BAD2",
                        5000, 260, 170, 130, "L2", 50, 3000)), "重量");

        // 正常替代申请：同 L2、承重 15000、冲击 60（与标准 M/S 均有差异）
        MvcResult subRes = postJson("/api/pack-orders/substitute",
                substituteJson("REQ-B-1", relicB, agreementB, "BOX-B-001",
                        5000, 260, 170, 130, "L2", 60, 15000));
        long orderB = dataOf(subRes).get("id").asLong();
        JsonNode bPending = dataOf(getJson("/api/pack-orders/" + orderB));
        assertEquals("PENDING_APPROVAL", bPending.get("order").get("status").asText());
        long subId = bPending.get("substitute").get("id").asLong();
        String diffDetail = bPending.get("substitute").get("diffDetail").asText();
        assertTrue(diffDetail.contains("缓冲等级") && diffDetail.contains("承重")
                        && diffDetail.contains("防震") && diffDetail.contains("外形") && diffDetail.contains("气候"),
                "替代差异须逐项记录，实际：" + diffDetail);
        // 待审批不能拆箱/发运
        expectFail(postJson("/api/pack-orders/" + orderB + "/unpack",
                "{\"unpackedBy\":\"x\",\"condition\":\"INTACT\"}"), "确认");

        // 驳回缺意见拒绝；带意见驳回 -> 装箱单终态 REJECTED
        expectFail(postJson("/api/pack-substitutes/" + subId + "/approve",
                "{\"approver\":\"包装主管\",\"approved\":false}"), "意见");
        expectOk(postJson("/api/pack-substitutes/" + subId + "/approve",
                "{\"approver\":\"包装主管\",\"approved\":false,\"opinion\":\"替代包装来源不明，驳回\"}"));
        assertEquals("REJECTED", dataOf(getJson("/api/pack-orders/" + orderB))
                .get("order").get("status").asText());
        assertEquals("REJECTED", dataOf(getJson("/api/pack-orders/" + orderB))
                .get("substitute").get("status").asText());

        // 被拒后允许就同一押运单重新申请替代；这次批准（外部已恢复）-> PACKED
        JsonNode sub2Data = dataOf(postJson("/api/pack-orders/substitute",
                substituteJson("REQ-B-2", relicB, agreementB, "BOX-B-002",
                        5000, 260, 170, 130, "L2", 60, 15000)));
        long orderB2 = sub2Data.get("id").asLong();
        long sub2Id = dataOf(getJson("/api/pack-orders/" + orderB2)).get("substitute").get("id").asLong();
        JsonNode approved = dataOf(postJson("/api/pack-substitutes/" + sub2Id + "/approve",
                "{\"approver\":\"包装主管\",\"approved\":true,\"opinion\":\"限期归还，同意\"}"));
        assertEquals("PACKED", approved.get("order").get("status").asText(), "批准并经外部确认后可发运");
        // 发运前照片 + 受损拆箱必须填说明
        uploadPhoto(orderB2, "BEFORE_SHIP", "before-b2.png", "押运拍照员");
        expectFail(postJson("/api/pack-orders/" + orderB2 + "/unpack",
                "{\"unpackedBy\":\"接保员\",\"condition\":\"DAMAGED\"}"), "说明");
        expectOk(postJson("/api/pack-orders/" + orderB2 + "/unpack",
                "{\"unpackedBy\":\"接保员\",\"condition\":\"DAMAGED\",\"note\":\"角部缓冲压痕\"}"));
        assertEquals("DAMAGED", dataOf(getJson("/api/pack-orders/" + orderB2))
                .get("order").get("unpackCondition").asText());

        // ================================================================
        // 八、包装台账全动作留痕（规格发布为全馆动作，relicId 为空）
        // ================================================================
        JsonNode allLedger = dataOf(getJson("/api/pack-ledgers"));
        assertTrue(hasAction(allLedger, "PACK_SPEC_PUBLISH"));
        JsonNode ledgerA = dataOf(getJson("/api/pack-ledgers?relicId=" + relicA));
        assertTrue(hasAction(ledgerA, "PACK_STANDARD"));
        assertTrue(hasAction(ledgerA, "PACK_NOTIFY_SENT"), "恢复后送达须留痕");
        assertTrue(hasAction(ledgerA, "PACK_UNPACK"));
        JsonNode ledgerB = dataOf(getJson("/api/pack-ledgers?relicId=" + relicB));
        assertTrue(hasAction(ledgerB, "PACK_SUBSTITUTE_APPLY"));
        assertTrue(hasAction(ledgerB, "PACK_SUBSTITUTE_REJECT"));
        assertTrue(hasAction(ledgerB, "PACK_SUBSTITUTE_APPROVE"));
    }

    // ================================================================
    // 辅助
    // ================================================================

    private long createRelic(String no, String name, String material, String grade) throws Exception {
        return dataOf(postJson("/api/relics",
                "{\"relicNo\":\"" + no + "\",\"name\":\"" + name + "\",\"category\":\"青铜器\","
                        + "\"material\":\"" + material + "\",\"era\":\"西周\",\"source\":\"考古发掘\","
                        + "\"preservationGrade\":\"" + grade + "\"}")).get("id").asLong();
    }

    private void doIntake(long relicId, int weightG, int h, int w, int d) throws Exception {
        expectOk(postJson("/api/relics/" + relicId + "/intake",
                "{\"intakeNo\":\"RU-" + relicId + "\",\"heightMm\":" + h + ",\"widthMm\":" + w
                        + ",\"depthMm\":" + d + ",\"weightG\":" + weightG
                        + ",\"location\":\"库房A\",\"receiver\":\"库管甲\","
                        + "\"photos\":[{\"photoUrl\":\"intake-" + relicId + ".jpg\"}]}"));
    }

    private long createAgreement(String no, long relicId, boolean sign) throws Exception {
        long id = dataOf(postJson("/api/loan-agreements",
                "{\"agreementNo\":\"" + no + "\",\"relicId\":" + relicId
                        + ",\"borrower\":\"市博物馆\",\"insuranceAmount\":500000,"
                        + "\"transportRoute\":\"省博-专线-市博\",\"returnDate\":\"2026-12-31\","
                        + "\"items\":[{\"itemNo\":\"I1\",\"itemName\":\"文物\",\"qty\":1}]}"))
                .get("id").asLong();
        if (sign) {
            expectOk(postJson("/api/loan-agreements/" + id + "/sign", "{\"signedBy\":\"馆方代表\"}"));
        }
        return id;
    }

    private long createSignedAgreement(String no, long relicId) throws Exception {
        return createAgreement(no, relicId, true);
    }

    private String specItem(String material, String size, int lmin, int lmax, int wmin, int wmax,
                            int dmin, int dmax, int maxWeight, String cushion, int shock, String climate) {
        return "{\"material\":\"" + material + "\",\"sizeCode\":\"" + size + "\","
                + "\"fitLengthMinMm\":" + lmin + ",\"fitLengthMaxMm\":" + lmax
                + ",\"fitWidthMinMm\":" + wmin + ",\"fitWidthMaxMm\":" + wmax
                + ",\"fitDepthMinMm\":" + dmin + ",\"fitDepthMaxMm\":" + dmax
                + ",\"maxWeightG\":" + maxWeight + ",\"cushionLevel\":\"" + cushion + "\""
                + ",\"shockLimitG\":" + shock + ",\"climateZone\":\"" + climate + "\"}";
    }

    private String packJson(String requestNo, long relicId, long agreementId, String boxNo,
                            String climate, String requiredCushion,
                            int weight, int len, int wid, int dep, String packedBy) {
        return "{\"requestNo\":" + (requestNo == null ? "null" : "\"" + requestNo + "\"")
                + ",\"relicId\":" + relicId + ",\"agreementId\":" + agreementId
                + ",\"boxNo\":\"" + boxNo + "\",\"climateZone\":\"" + climate + "\""
                + (requiredCushion == null ? "" : ",\"requiredCushionLevel\":\"" + requiredCushion + "\"")
                + ",\"actualWeightG\":" + weight + ",\"actualLengthMm\":" + len
                + ",\"actualWidthMm\":" + wid + ",\"actualDepthMm\":" + dep
                + ",\"packedBy\":\"" + packedBy + "\"}";
    }

    /** 替代包装申请：S 码温带、给定制震/承重；外形区间固定覆盖 100~500/100~400/100~300 */
    private String substituteJson(String requestNo, long relicId, long agreementId, String boxNo,
                                  int weight, int len, int wid, int dep,
                                  String cushion, int shock, int maxWeight) {
        return "{\"requestNo\":\"" + requestNo + "\",\"relicId\":" + relicId
                + ",\"agreementId\":" + agreementId + ",\"boxNo\":\"" + boxNo + "\""
                + ",\"climateZone\":\"TEMPERATE\",\"actualWeightG\":" + weight
                + ",\"actualLengthMm\":" + len + ",\"actualWidthMm\":" + wid + ",\"actualDepthMm\":" + dep
                + ",\"packedBy\":\"装箱工乙\",\"reason\":\"标准箱临时占用，改用周转代木箱\",\"applicant\":\"保管员乙\""
                + ",\"subSizeCode\":\"SUB-S\",\"subCushionLevel\":\"" + cushion + "\""
                + ",\"subClimateZone\":\"TEMPERATE\",\"subMaxWeightG\":" + maxWeight
                + ",\"subShockLimitG\":" + shock
                + ",\"subFitLengthMinMm\":100,\"subFitLengthMaxMm\":500"
                + ",\"subFitWidthMinMm\":100,\"subFitWidthMaxMm\":400"
                + ",\"subFitDepthMinMm\":100,\"subFitDepthMaxMm\":300}";
    }

    private void uploadPhoto(long orderId, String stage, String fileName, String uploadedBy) throws Exception {
        expectOk(uploadPhotoMvc(orderId, stage, fileName, uploadedBy));
    }

    private MvcResult uploadPhotoMvc(long orderId, String stage, String fileName, String uploadedBy)
            throws Exception {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return mockMvc.perform(multipart("/api/pack-orders/" + orderId + "/photos")
                        .file(new MockMultipartFile("file", fileName,
                                MediaType.IMAGE_PNG_VALUE, bos.toByteArray()))
                        .param("stage", stage).param("uploadedBy", uploadedBy))
                .andExpect(status().isOk()).andReturn();
    }

    private boolean hasAction(JsonNode ledger, String action) {
        for (JsonNode e : ledger) {
            if (action.equals(e.get("action").asText())) {
                return true;
            }
        }
        return false;
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        MockHttpServletRequestBuilder builder = post(uri)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            builder.content(body);
        }
        return mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private void expectOk(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertFalse(root.get("success").asBoolean());
        assertNotNull(root.get("message"));
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
