package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 虫害监测（museum-8）验收：
 * 1) 展厅与库房设置诱捕点位：编号唯一、布放日期、坐标、责任区域；
 * 2) 监测记录登记虫种数量与照片，按提交时生效规则版本判定并固化结论；
 * 3) 超阈值生成处置任务并关联责任区域；
 * 4) 更换诱捕片不删除旧记录（旧片 REPLACED、旧监测记录仍指向旧片）；
 * 5) 点位迁移保留前后坐标；
 * 6) 监测结果按月份冻结：冻结后该月记录与照片不再接受补录；
 * 7) 外包人员只能提交现场数据，不能关闭任务；
 * 8) 规则调整只生成新版本，不改写监测记录旧结论；
 * 9) 虫害台账全程留痕。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-pest;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PestMonitorTest {

    @TempDir
    static Path imageDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("museum.image.storage-root", () -> imageDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void pest_monitor_rules_hold() throws Exception {
        // ========== 一、建档：展厅 + 库房，发布规则 v1（衣鱼阈值 5、书虱阈值 3） ==========
        long hallId = dataOf(postJson("/api/halls",
                "{\"hallNo\":\"PH1\",\"name\":\"书画厅\",\"location\":\"二层\",\"custodian\":\"王保管\"}"))
                .get("id").asLong();
        long areaId = dataOf(postJson("/api/storage/areas",
                "{\"areaNo\":\"PA1\",\"name\":\"东区库房\",\"tempZone\":\"CONST_TEMP\","
                        + "\"fireRating\":\"F1\",\"loadCapacityKg\":5000,\"manager\":\"赵库管\"}"))
                .get("id").asLong();

        expectOk(postJson("/api/pest-rules/publish",
                "{\"versionNo\":\"PR-R1\",\"title\":\"虫害阈值v1\",\"publishedBy\":\"文保部\","
                        + "\"items\":[{\"pestSpecies\":\"衣鱼\",\"thresholdCount\":5},"
                        + "{\"pestSpecies\":\"书虱\",\"thresholdCount\":3}]}"));
        assertEquals("ACTIVE", dataOf(getJson("/api/pest-rules/active"))
                .get("version").get("status").asText());

        // 未布放诱捕片的点位不能提交监测记录（先建点位后布片）
        // 展厅点位：编号、布放日期、坐标、责任区域
        long hallPointId = dataOf(postJson("/api/pest-points",
                "{\"pointNo\":\"TP-H-01\",\"zoneType\":\"HALL\",\"zoneId\":" + hallId
                        + ",\"responsibleArea\":\"二层书画厅责任区\",\"coordX\":10.5,\"coordY\":20.25,"
                        + "\"deployedAt\":\"2026-09-01T09:00:00\",\"deployedBy\":\"文保甲\"}"))
                .get("id").asLong();
        // 库房点位
        long storagePointId = dataOf(postJson("/api/pest-points",
                "{\"pointNo\":\"TP-S-01\",\"zoneType\":\"STORAGE\",\"zoneId\":" + areaId
                        + ",\"responsibleArea\":\"东区库房责任区\",\"coordX\":3,\"coordY\":7,"
                        + "\"deployedAt\":\"2026-09-01T10:00:00\",\"deployedBy\":\"文保乙\"}"))
                .get("id").asLong();
        JsonNode hallPoint = dataOf(getJson("/api/pest-points/" + hallPointId)).get("point");
        assertEquals("书画厅", hallPoint.get("zoneName").asText(), "区域名称快照自展厅带入");
        assertEquals("二层书画厅责任区", hallPoint.get("responsibleArea").asText());
        assertEquals("2026-09-01T09:00:00", hallPoint.get("deployedAt").asText(), "布放日期登记");

        // 编号重复 / 非法区域类型拒绝
        expectFail(postJson("/api/pest-points",
                "{\"pointNo\":\"TP-H-01\",\"zoneType\":\"HALL\",\"zoneId\":" + hallId
                        + ",\"responsibleArea\":\"x\",\"coordX\":1,\"coordY\":1,"
                        + "\"deployedAt\":\"2026-09-01T09:00:00\",\"deployedBy\":\"文保甲\"}"), "已存在");
        expectFail(postJson("/api/pest-points",
                "{\"pointNo\":\"TP-X-01\",\"zoneType\":\"YARD\",\"zoneId\":" + hallId
                        + ",\"responsibleArea\":\"x\",\"coordX\":1,\"coordY\":1,"
                        + "\"deployedAt\":\"2026-09-01T09:00:00\",\"deployedBy\":\"文保甲\"}"), "区域类型");

        // ========== 二、布放诱捕片；外包人员提交现场数据（正常） ==========
        expectFail(postJson("/api/pest-records",
                recordJson(hallPointId, "2026-09", "外包小张", "OUTSOURCE",
                        species("衣鱼", 1))), "诱捕片");
        long card1 = dataOf(postJson("/api/pest-points/" + hallPointId + "/cards",
                "{\"cardNo\":\"CARD-001\",\"deployedAt\":\"2026-09-01T09:30:00\","
                        + "\"deployedBy\":\"文保甲\"}")).get("id").asLong();

        // 外包人员提交：衣鱼 3（阈值 5 内）、书虱 2（阈值 3 内）→ 正常，不生成任务
        long record1 = dataOf(postJson("/api/pest-records",
                recordJson(hallPointId, "2026-09", "外包小张", "OUTSOURCE",
                        species("衣鱼", 3), species("书虱", 2)))).get("id").asLong();
        JsonNode r1 = dataOf(getJson("/api/pest-records/" + record1));
        assertEquals("NORMAL", r1.get("record").get("conclusion").asText());
        assertEquals("OUTSOURCE", r1.get("record").get("submitterRole").asText(), "外包人员可提交现场数据");
        assertEquals("PR-R1", r1.get("record").get("ruleVersionNo").asText(), "记录固化规则版本快照");
        assertEquals(card1, r1.get("record").get("cardId").asLong(), "记录关联提交时在布诱捕片");
        assertEquals(5, findDetail(r1, "衣鱼").get("thresholdCount").asInt(), "明细固化阈值快照");
        assertEquals(0, findDetail(r1, "衣鱼").get("overThreshold").asInt());
        assertEquals(0, dataOf(getJson("/api/pest-tasks?pointId=" + hallPointId)).size(), "未超阈值不生成任务");

        // 同一记录内虫种重复拒绝
        expectFail(postJson("/api/pest-records",
                "{\"pointId\":" + hallPointId + ",\"monitorMonth\":\"2026-09\","
                        + "\"submittedBy\":\"外包小张\",\"submitterRole\":\"OUTSOURCE\","
                        + "\"details\":[{\"pestSpecies\":\"衣鱼\",\"pestCount\":1},"
                        + "{\"pestSpecies\":\"衣鱼\",\"pestCount\":2}]}"), "重复");

        // 监测照片：内容寻址，同照片重复关联拒绝
        uploadPhoto(record1, "trap-1.png", "外包小张");
        expectFail(uploadPhotoMvc(record1, "trap-1.png", "外包小张"), "已关联");

        // ========== 三、更换诱捕片不删除旧记录 ==========
        long card2 = dataOf(postJson("/api/pest-points/" + hallPointId + "/cards",
                "{\"cardNo\":\"CARD-002\",\"deployedAt\":\"2026-09-15T09:00:00\","
                        + "\"deployedBy\":\"文保甲\"}")).get("id").asLong();
        JsonNode cards = dataOf(getJson("/api/pest-points/" + hallPointId + "/cards"));
        assertEquals(2, cards.size(), "更换后旧片保留不删除");
        assertEquals("REPLACED", cards.get(0).get("status").asText(), "旧片置 REPLACED");
        assertEquals("ACTIVE", cards.get(1).get("status").asText(), "新片在布");
        // 旧监测记录仍指向旧片（历史不丢）
        assertEquals(card1, dataOf(getJson("/api/pest-records/" + record1))
                .get("record").get("cardId").asLong(), "更换诱捕片不改写旧监测记录");

        // ========== 四、点位迁移保留前后坐标 ==========
        expectOk(postJson("/api/pest-points/" + hallPointId + "/move",
                "{\"toX\":12.75,\"toY\":22.5,\"reason\":\"展柜调整\",\"movedBy\":\"文保甲\"}"));
        JsonNode pointDetail = dataOf(getJson("/api/pest-points/" + hallPointId));
        assertEquals(12.75, pointDetail.get("point").get("coordX").asDouble(), 0.0001,
                "点位行更新为当前坐标");
        JsonNode moves = pointDetail.get("moves");
        assertEquals(1, moves.size());
        assertEquals(10.5, moves.get(0).get("fromX").asDouble(), 0.0001, "迁移前坐标保留");
        assertEquals(20.25, moves.get(0).get("fromY").asDouble(), 0.0001);
        assertEquals(12.75, moves.get(0).get("toX").asDouble(), 0.0001, "迁移后坐标保留");
        assertEquals(22.5, moves.get(0).get("toY").asDouble(), 0.0001);

        // ========== 五、超阈值生成处置任务并关联责任区域 ==========
        // 库房点位布片后提交：书虱 4 > 阈值 3 → 超阈值
        dataOf(postJson("/api/pest-points/" + storagePointId + "/cards",
                "{\"cardNo\":\"CARD-101\",\"deployedAt\":\"2026-09-01T10:30:00\","
                        + "\"deployedBy\":\"文保乙\"}"));
        long record2 = dataOf(postJson("/api/pest-records",
                recordJson(storagePointId, "2026-09", "外包小李", "OUTSOURCE",
                        species("衣鱼", 2), species("书虱", 4), species("蛾类", 9))))
                .get("id").asLong();
        JsonNode r2 = dataOf(getJson("/api/pest-records/" + record2));
        assertEquals("OVER_THRESHOLD", r2.get("record").get("conclusion").asText());
        assertEquals("书虱", r2.get("record").get("overSpecies").asText());
        assertEquals(1, findDetail(r2, "书虱").get("overThreshold").asInt());
        assertTrue(findDetail(r2, "蛾类").get("thresholdCount").isNull(),
                "规则无条目的虫种不猜测阈值");
        assertEquals(0, findDetail(r2, "蛾类").get("overThreshold").asInt(),
                "无阈值条目不参与超阈值判定");

        JsonNode tasks = dataOf(getJson("/api/pest-tasks?pointId=" + storagePointId));
        assertEquals(1, tasks.size(), "超阈值生成处置任务");
        long task1 = tasks.get(0).get("id").asLong();
        assertEquals("OPEN", tasks.get(0).get("status").asText());
        assertEquals("东区库房责任区", tasks.get(0).get("responsibleArea").asText(),
                "处置任务关联责任区域");
        assertEquals("书虱", tasks.get(0).get("overSpecies").asText());
        assertEquals(record2, tasks.get(0).get("recordId").asLong());
        assertEquals("PR-R1", tasks.get(0).get("ruleVersionNo").asText());

        // ========== 六、外包人员不能关闭任务，馆内人员可关闭 ==========
        expectFail(postJson("/api/pest-tasks/" + task1 + "/close",
                "{\"closedBy\":\"外包小李\",\"closerRole\":\"OUTSOURCE\"}"), "外包人员");
        expectOk(postJson("/api/pest-tasks/" + task1 + "/close",
                "{\"closedBy\":\"赵库管\",\"closerRole\":\"INTERNAL\","
                        + "\"closeNote\":\"已投放药剂并复查\"}"));
        JsonNode closedTask = dataOf(getJson("/api/pest-tasks?pointId=" + storagePointId)).get(0);
        assertEquals("CLOSED", closedTask.get("status").asText());
        assertEquals("赵库管", closedTask.get("closedBy").asText());
        expectFail(postJson("/api/pest-tasks/" + task1 + "/close",
                "{\"closedBy\":\"赵库管\",\"closerRole\":\"INTERNAL\"}"), "已关闭");

        // ========== 七、监测结果按月份冻结 ==========
        expectOk(postJson("/api/pest-monitor/freeze",
                "{\"monitorMonth\":\"2026-09\",\"frozenBy\":\"文保部\"}"));
        // 重复冻结幂等：仍只有一条冻结记录
        expectOk(postJson("/api/pest-monitor/freeze",
                "{\"monitorMonth\":\"2026-09\",\"frozenBy\":\"文保部\"}"));
        assertEquals(1, dataOf(getJson("/api/pest-monitor/freezes")).size());
        // 冻结后该月监测记录拒绝补录
        expectFail(postJson("/api/pest-records",
                recordJson(hallPointId, "2026-09", "文保甲", "INTERNAL",
                        species("衣鱼", 1))), "已冻结");
        // 冻结后该月记录的照片拒绝补传
        expectFail(uploadPhotoMvc(record1, "trap-1-late.png", "文保甲"), "已冻结");
        // 已冻结事实保留可查
        assertEquals("NORMAL", dataOf(getJson("/api/pest-records/" + record1))
                .get("record").get("conclusion").asText());
        assertEquals(1, dataOf(getJson("/api/pest-records/" + record1)).get("photos").size());

        // ========== 八、规则调整只生成新版本，不改写监测记录旧结论 ==========
        // v2：衣鱼阈值收紧为 2（v1 为 5）
        expectOk(postJson("/api/pest-rules/publish",
                "{\"versionNo\":\"PR-R2\",\"title\":\"虫害阈值v2\",\"publishedBy\":\"文保部\","
                        + "\"items\":[{\"pestSpecies\":\"衣鱼\",\"thresholdCount\":2},"
                        + "{\"pestSpecies\":\"书虱\",\"thresholdCount\":3}]}"));
        JsonNode versions = dataOf(getJson("/api/pest-rules"));
        assertEquals("PR-R2", versions.get(0).get("version").get("versionNo").asText());
        assertEquals("SUPERSEDED", versions.get(1).get("version").get("status").asText(),
                "旧版本置 SUPERSEDED 只读");

        // 旧记录结论不改写：record1 衣鱼 3 在 v1 阈值 5 下为 NORMAL，不随 v2 阈值 2 改写
        JsonNode r1AfterV2 = dataOf(getJson("/api/pest-records/" + record1));
        assertEquals("NORMAL", r1AfterV2.get("record").get("conclusion").asText(),
                "规则新版本不改写监测记录旧结论");
        assertEquals("PR-R1", r1AfterV2.get("record").get("ruleVersionNo").asText());
        assertEquals(5, findDetail(r1AfterV2, "衣鱼").get("thresholdCount").asInt(),
                "历史明细阈值快照不随后续版本改写");

        // 新提交按 v2 判定：2026-10 衣鱼 3 > v2 阈值 2 → 超阈值，生成新任务
        long record3 = dataOf(postJson("/api/pest-records",
                recordJson(hallPointId, "2026-10", "文保甲", "INTERNAL",
                        species("衣鱼", 3)))).get("id").asLong();
        JsonNode r3 = dataOf(getJson("/api/pest-records/" + record3));
        assertEquals("OVER_THRESHOLD", r3.get("record").get("conclusion").asText());
        assertEquals("PR-R2", r3.get("record").get("ruleVersionNo").asText(), "新记录按新版本判定");
        assertEquals(2, findDetail(r3, "衣鱼").get("thresholdCount").asInt());
        JsonNode hallTasks = dataOf(getJson("/api/pest-tasks?pointId=" + hallPointId
                + "&status=OPEN"));
        assertEquals(1, hallTasks.size());
        assertEquals("二层书画厅责任区", hallTasks.get(0).get("responsibleArea").asText());
        // 新记录关联更换后的在布诱捕片 CARD-002
        assertEquals(card2, r3.get("record").get("cardId").asLong());

        // ========== 九、台账全程留痕 ==========
        JsonNode ledger = dataOf(getJson("/api/pest-ledgers"));
        assertTrue(hasAction(ledger, "RULE_PUBLISH"), "规则发布留痕");
        assertTrue(hasAction(ledger, "POINT_CREATE"), "点位建档留痕");
        assertTrue(hasAction(ledger, "POINT_MOVE"), "点位迁移留痕");
        assertTrue(hasAction(ledger, "CARD_DEPLOY"), "诱捕片布放留痕");
        assertTrue(hasAction(ledger, "CARD_REPLACE"), "诱捕片更换留痕");
        assertTrue(hasAction(ledger, "RECORD_SUBMIT"), "监测提交留痕");
        assertTrue(hasAction(ledger, "TASK_GENERATE"), "任务生成留痕");
        assertTrue(hasAction(ledger, "TASK_CLOSE"), "任务关闭留痕");
        assertTrue(hasAction(ledger, "MONTH_FREEZE"), "月份冻结留痕");
        JsonNode moveEntry = firstAction(ledger, "POINT_MOVE");
        assertTrue(moveEntry.get("summary").asText().contains("10.5")
                        && moveEntry.get("summary").asText().contains("12.75"),
                "迁移台账含前后坐标");
        JsonNode generateEntry = firstAction(ledger, "TASK_GENERATE");
        assertTrue(generateEntry.get("summary").asText().contains("东区库房责任区"),
                "任务生成台账关联责任区域");
    }

    // ================================================================
    // 辅助
    // ================================================================

    private String recordJson(long pointId, String month, String submittedBy,
                              String role, String... speciesCounts) {
        return "{\"pointId\":" + pointId + ",\"monitorMonth\":\"" + month
                + "\",\"submittedBy\":\"" + submittedBy + "\",\"submitterRole\":\"" + role
                + "\",\"details\":[" + String.join(",", speciesCounts) + "]}";
    }

    private String species(String name, int count) {
        return "{\"pestSpecies\":\"" + name + "\",\"pestCount\":" + count + "}";
    }

    private JsonNode findDetail(JsonNode recordReport, String species) {
        for (JsonNode d : recordReport.get("details")) {
            if (species.equals(d.get("pestSpecies").asText())) {
                return d;
            }
        }
        throw new AssertionError("虫种明细未找到: " + species);
    }

    private void uploadPhoto(long recordId, String fileName, String uploadedBy) throws Exception {
        expectOk(uploadPhotoMvc(recordId, fileName, uploadedBy));
    }

    private MvcResult uploadPhotoMvc(long recordId, String fileName, String uploadedBy)
            throws Exception {
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return mockMvc.perform(multipart("/api/pest-records/" + recordId + "/photos")
                        .file(new MockMultipartFile("file", fileName,
                                MediaType.IMAGE_PNG_VALUE, bos.toByteArray()))
                        .param("uploadedBy", uploadedBy))
                .andExpect(status().isOk()).andReturn();
    }

    private boolean hasAction(JsonNode ledger, String action) {
        return firstAction(ledger, action) != null;
    }

    private JsonNode firstAction(JsonNode ledger, String action) {
        for (JsonNode e : ledger) {
            if (action.equals(e.get("action").asText())) {
                return e;
            }
        }
        return null;
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
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
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
