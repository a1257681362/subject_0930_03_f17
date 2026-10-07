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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 展厅温湿度与照度巡检（museum-5）验收：
 * 1) 不同材质引用不同阈值与采样频率，任务按展厅生成、节拍取材质频率最小值；
 * 2) 任务/读数/事件均固化规则版本快照，规则新版本不改写已完成巡检，报表标明规则版本；
 * 3) 连续超限合并为同一事件，恢复后再次超限另开新事件；
 * 4) 事件关闭前必须关联现场照片与处置人，并由保管员复核；复核不通过停留待处理；
 * 5) 同点位不同材质同时越限分别判定、分别成事件；
 * 6) 环境台账全动作留痕可追溯；修复审批只完成一半时停留待处理，巡检侧不受影响仍可追溯。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-env;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnvInspectionTest {

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

    private long hallId;

    @Test
    void env_inspection_rules_hold() throws Exception {
        // ========== 一、建档：纸/青铜/丝 三类材质文物，一个展厅三个点位 ==========
        // P-A 同点位陈展纸本与青铜（不同材质不同阈值，湿度安全区间取重叠 50-55）
        long paperRelicId = createRelic("E-P-1", "巡检测试纸本画", "纸", "TWO");
        long bronzeRelicId = createRelic("E-B-1", "巡检测试青铜鼎", "青铜", "ONE");
        long bronzeRelic2Id = createRelic("E-B-2", "巡检测试青铜剑", "青铜", "THREE");
        long silkRelicId = createRelic("E-S-1", "巡检测试丝织幡", "丝", "TWO");

        hallId = dataOf(postJson("/api/halls", "{"
                + "\"hallNo\":\"H1\",\"name\":\"综合厅\",\"location\":\"一层\","
                + "\"custodian\":\"王保管\"}")).get("id").asLong();
        expectOk(postJson("/api/halls/" + hallId + "/relics",
                "{\"relicId\":" + paperRelicId + ",\"pointCode\":\"P-A\"}"));
        expectOk(postJson("/api/halls/" + hallId + "/relics",
                "{\"relicId\":" + bronzeRelicId + ",\"pointCode\":\"P-A\"}"));
        expectOk(postJson("/api/halls/" + hallId + "/relics",
                "{\"relicId\":" + bronzeRelic2Id + ",\"pointCode\":\"P-B\"}"));
        expectOk(postJson("/api/halls/" + hallId + "/relics",
                "{\"relicId\":" + silkRelicId + ",\"pointCode\":\"P-C\"}"));

        // ========== 二、发布规则 v1：材质不同阈值/频率 ==========
        // 纸：18-22℃ / 50-60%RH / 0-50lux / 60 分钟
        // 青铜：15-25℃ / 35-55%RH / 0-150lux / 120 分钟
        // 丝：18-22℃ / 50-65%RH / 0-50lux / 60 分钟
        publishRule("EV-R1", "v1", 60, 120, 60,
                50, 60, 35, 55, 50, 65, 50, 150, 50);
        assertEquals("ACTIVE", dataOf(getJson("/api/env-rules/active")).get("version").get("status").asText());

        // 修复审批只走一半（会签中），最后验证不能开工、系统停留待处理
        long projectId = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"CEP-1\",\"relicId\":" + bronzeRelicId
                + ",\"client\":\"藏品部\",\"plan\":\"探伤\"}")).get("id").asLong();
        long planV1 = dataOf(postJson("/api/repair-projects/" + projectId + "/plan-versions",
                "{\"planContent\":\"探伤方案\",\"internalExpert\":\"馆内甲\","
                        + "\"externalExpert\":\"外部乙\",\"custodian\":\"王保管\","
                        + "\"submittedBy\":\"经办人\"}")).get("version").get("id").asLong();

        // ========== 三、任务按展厅生成：节拍=min(60,120,60)=60，点位 3 个 ==========
        long task1 = generateOne();
        JsonNode t1 = dataOf(getJson("/api/inspection/tasks/" + task1 + "/report"));
        assertEquals("EV-R1", t1.get("task").get("ruleVersionNo").asText());
        assertEquals(60, t1.get("task").get("intervalMin").asInt());
        assertEquals(3, t1.get("task").get("pointCount").asInt());
        assertEquals("PENDING", t1.get("task").get("status").asText());

        // 待巡检轮次未完成，手动再触发不重复生成（每展厅同时只有一个 PENDING）
        assertEquals(0, dataOf(postEmpty("/api/inspection/tasks/generate?force=true")).size());

        // R1：P-A 湿度 40% —— 纸本下限 50 越限，青铜[35,55]正常；P-B/P-C 正常
        submit(task1, "巡检员赵",
                point("P-A", 20, 40, 30), point("P-B", 20, 40, 30), point("P-C", 20, 55, 30));
        JsonNode t1After = dataOf(getJson("/api/inspection/tasks/" + task1 + "/report"));
        assertEquals("DONE", t1After.get("task").get("status").asText());
        JsonNode paReading = findReading(t1After, "P-A");
        assertEquals(1, paReading.get("abnormal").asInt());
        assertEquals("HUMIDITY", paReading.get("abnormalMetrics").asText());
        assertEquals("纸|青铜", paReading.get("matchedMaterials").asText());
        // 按材质判定明细固化各自阈值（不同材质引用不同阈值）
        JsonNode paperDetail = findDetail(t1After, "纸");
        assertEquals(50, paperDetail.get("humidityMin").asInt());
        assertEquals("HUMIDITY", paperDetail.get("abnormalMetrics").asText());
        JsonNode bronzeDetail = findDetail(t1After, "青铜");
        assertEquals(35, bronzeDetail.get("humidityMin").asInt());
        assertTrue(bronzeDetail.get("abnormalMetrics").isNull(), "青铜口径湿度 40 在 35-55 内，正常");

        // 事件：纸本湿度一个 OPEN 事件，标明首警规则版本与阈值
        JsonNode e1Node = findEvent(hallId, "OPEN", "纸", "HUMIDITY");
        assertNotNull(e1Node, "纸本湿度越限应开立事件");
        long event1 = e1Node.get("event").get("id").asLong();
        assertEquals("P-A", e1Node.get("event").get("pointCode").asText());
        assertEquals(1, e1Node.get("event").get("readingCount").asInt());
        assertEquals(50, e1Node.get("event").get("thresholdLow").asInt());
        assertEquals("EV-R1", e1Node.get("event").get("ruleVersionNo").asText());

        // 已完成巡检不可改写
        expectFail(postJson("/api/inspection/tasks/" + task1 + "/readings",
                readings("巡检员赵", point("P-A", 20, 55, 30),
                        point("P-B", 20, 40, 30), point("P-C", 20, 55, 30))), "不可改写");

        long task2 = generateOne();
        // 缺点位 / 点位重复均拒绝
        expectFail(postJson("/api/inspection/tasks/" + task2 + "/readings",
                readings("巡检员赵", point("P-A", 20, 55, 30), point("P-C", 20, 55, 30))), "缺少");
        expectFail(postJson("/api/inspection/tasks/" + task2 + "/readings",
                readings("巡检员赵", point("P-A", 20, 55, 30), point("P-A", 20, 54, 30),
                        point("P-B", 20, 40, 30), point("P-C", 20, 55, 30))), "重复");

        // ========== 四、连续超限合并为同一事件 ==========
        // R2：纸本湿度 38 仍越限 -> 并入事件1，不新开
        submit(task2, "巡检员赵",
                point("P-A", 20, 38, 30), point("P-B", 20, 40, 30), point("P-C", 20, 55, 30));
        e1Node = findEvent(hallId, "OPEN", "纸", "HUMIDITY");
        assertNotNull(e1Node);
        assertEquals(event1, e1Node.get("event").get("id").asLong(), "连续超限必须合并为同一事件");
        assertEquals(2, e1Node.get("event").get("readingCount").asInt(), "累计两次越限");
        assertEquals(38.0, e1Node.get("event").get("lastValue").asDouble(), 0.001);
        assertEquals(40.0, e1Node.get("event").get("firstValue").asDouble(), 0.001);

        // R3：纸本湿度 55 恢复 -> 事件转 RESOLVED 待保管员复核
        long task3 = generateOne();
        submit(task3, "巡检员赵",
                point("P-A", 20, 55, 30), point("P-B", 20, 40, 30), point("P-C", 20, 55, 30));
        e1Node = findEvent(hallId, "RESOLVED", "纸", "HUMIDITY");
        assertNotNull(e1Node, "指标恢复后事件转待保管员复核");
        assertEquals(event1, e1Node.get("event").get("id").asLong());

        // ========== 五、关闭前必须：处置人 + 现场照片 + 保管员复核 ==========
        expectFail(postJson("/api/env-events/" + event1 + "/review",
                "{\"reviewBy\":\"王保管\",\"approved\":true}"), "处置人");
        uploadPhoto(event1, "scene-e1.png", "王保管", "展柜加湿膜现场");
        // 只有照片仍不行：必须有处置人
        expectFail(postJson("/api/env-events/" + event1 + "/review",
                "{\"reviewBy\":\"王保管\",\"approved\":true}"), "处置人");
        expectOk(postJson("/api/env-events/" + event1 + "/handle",
                "{\"handler\":\"李修复\",\"handleRemark\":\"调整加湿器档位并更换加湿膜\"}"));
        // 已登记处置人后重复登记拒绝
        expectFail(postJson("/api/env-events/" + event1 + "/handle",
                "{\"handler\":\"李修复\",\"handleRemark\":\"再次登记\"}"), "重复登记");
        // 保管员复核通过 -> CLOSED
        expectOk(postJson("/api/env-events/" + event1 + "/review",
                "{\"reviewBy\":\"王保管\",\"approved\":true,\"closeRemark\":\"湿度已稳定\"}"));
        JsonNode e1Closed = dataOf(getJson("/api/env-events/" + event1));
        assertEquals("CLOSED", e1Closed.get("event").get("status").asText());
        assertEquals("李修复", e1Closed.get("event").get("handler").asText());
        assertEquals("王保管", e1Closed.get("event").get("reviewBy").asText());
        assertEquals(1, e1Closed.get("photos").size(), "现场照片随事件报表可查");
        // 关闭后封存：不能再补照片
        expectFail(uploadPhotoMvc(event1, "scene-e1-late.png", "王保管", "补传"), "已关闭");

        // ========== 六、恢复后再次超限另开新事件；保管员复核不通过停留待处理 ==========
        // R4：P-C 照度 80lux，丝织上限 50 -> 新事件 e2 OPEN
        long task4 = generateOne();
        submit(task4, "巡检员赵",
                point("P-A", 20, 55, 30), point("P-B", 20, 40, 30), point("P-C", 20, 55, 80));
        JsonNode e2Node = findEvent(hallId, "OPEN", "丝", "LUX");
        assertNotNull(e2Node, "不同点位/指标/材质的异常另开事件");
        long event2 = e2Node.get("event").get("id").asLong();
        assertTrue(event2 != event1);

        // R5：照度恢复 -> e2 RESOLVED；处置+照片后保管员复核不通过 -> 退回 HANDLING
        long task5 = generateOne();
        submit(task5, "巡检员赵",
                point("P-A", 20, 55, 30), point("P-B", 20, 40, 30), point("P-C", 20, 55, 30));
        assertNotNull(findEvent(hallId, "RESOLVED", "丝", "LUX"));
        expectOk(postJson("/api/env-events/" + event2 + "/handle",
                "{\"handler\":\"张工\",\"handleRemark\":\"调整遮光帘\"}"));
        uploadPhoto(event2, "scene-e2.png", "张工", "照度现场");
        expectOk(postJson("/api/env-events/" + event2 + "/review",
                "{\"reviewBy\":\"王保管\",\"approved\":false,\"reviewRemark\":\"遮光帘仍透光\"}"));
        assertEquals("HANDLING", dataOf(getJson("/api/env-events/" + event2)).get("event").get("status").asText(),
                "复核不通过系统停留待处理");
        // 非待复核状态不能关闭
        expectFail(postJson("/api/env-events/" + event2 + "/review",
                "{\"reviewBy\":\"王保管\",\"approved\":true}"), "待处理");

        // ========== 七、发布规则新版本 v2：不改写已完成巡检 ==========
        // 纸：湿度改 45-65、频率改 30 分钟；青铜/丝不变
        publishRule("EV-R2", "v2", 30, 120, 60,
                45, 65, 35, 55, 50, 65, 50, 150, 50);
        JsonNode versions = dataOf(getJson("/api/env-rules"));
        assertEquals("EV-R2", versions.get(0).get("version").get("versionNo").asText());
        assertEquals("SUPERSEDED", versions.get(1).get("version").get("status").asText(),
                "旧版本置 SUPERSEDED 只读");

        // 历史任务与读数仍是 v1 口径（R1 纸湿度下限 50，不被 v2 的 45 改写）
        JsonNode t1Report = dataOf(getJson("/api/inspection/tasks/" + task1 + "/report"));
        assertEquals("EV-R1", t1Report.get("ruleVersionNo").asText(), "报表标明历史采用规则版本");
        assertEquals(50, findDetail(t1Report, "纸").get("humidityMin").asInt(),
                "规则新版本不改写已完成巡检的判定阈值");

        // 新任务采用 v2：节拍=min(30,120,60)=30
        long task6 = generateOne();
        JsonNode t6 = dataOf(getJson("/api/inspection/tasks/" + task6 + "/report"));
        assertEquals("EV-R2", t6.get("task").get("ruleVersionNo").asText());
        assertEquals(30, t6.get("task").get("intervalMin").asInt());

        // R6：P-C 照度 80 丝织再超限——与被驳回事件 e2 中间隔了正常读数，属新异常段 -> 开事件 e3；
        // P-A 湿度 45 在纸 v2[45,65]、青铜[35,55] 内，两材质均正常
        submit(task6, "巡检员赵",
                point("P-A", 20, 45, 30), point("P-B", 20, 40, 30), point("P-C", 20, 55, 80));
        JsonNode e3Node = findEvent(hallId, "OPEN", "丝", "LUX");
        assertNotNull(e3Node);
        long event3 = e3Node.get("event").get("id").asLong();
        assertTrue(event3 != event2, "恢复后再次超限必须另开新事件");

        // R7：P-C 照度恢复 -> e3 RESOLVED；归并键级联转挂给被驳回的事件 e2
        long task7 = generateOne();
        submit(task7, "巡检员赵",
                point("P-A", 20, 45, 30), point("P-B", 20, 40, 30), point("P-C", 20, 55, 30));
        assertNotNull(findEvent(hallId, "RESOLVED", "丝", "LUX"), "e3 进入待复核");

        // R8：读数保持正常 -> 转挂取回键的驳回事件 e2 被系统自动置回 RESOLVED
        long task8 = generateOne();
        submit(task8, "巡检员赵",
                point("P-A", 20, 45, 30), point("P-B", 20, 40, 30), point("P-C", 20, 55, 30));
        assertEquals("RESOLVED", dataOf(getJson("/api/env-events/" + event2)).get("event").get("status").asText(),
                "驳回事件现场持续恢复后重新进入待复核，不留死事件");

        // e2 重新处置（驳回后允许）后保管员复核关闭；e3 补处置人与照片后关闭
        expectOk(postJson("/api/env-events/" + event2 + "/handle",
                "{\"handler\":\"张工\",\"handleRemark\":\"加装第二层遮光帘\"}"));
        expectOk(postJson("/api/env-events/" + event2 + "/review",
                "{\"reviewBy\":\"王保管\",\"approved\":true,\"closeRemark\":\"整改到位\"}"));
        assertEquals("CLOSED", dataOf(getJson("/api/env-events/" + event2)).get("event").get("status").asText());
        expectOk(postJson("/api/env-events/" + event3 + "/handle",
                "{\"handler\":\"张工\",\"handleRemark\":\"同批遮光帘问题一并整改\"}"));
        uploadPhoto(event3, "scene-e3.png", "张工", "整改后照度计读数");
        expectOk(postJson("/api/env-events/" + event3 + "/review",
                "{\"reviewBy\":\"王保管\",\"approved\":true}"));
        assertEquals("CLOSED", dataOf(getJson("/api/env-events/" + event3)).get("event").get("status").asText());

        // ========== 八、同点位不同材质同时越限分别成事件 ==========
        // R9：P-A 温度 28℃ —— 纸上限22、青铜上限25 同时越限（各自阈值不同）
        long task9 = generateOne();
        submit(task9, "巡检员赵",
                point("P-A", 28, 55, 30), point("P-B", 20, 40, 30), point("P-C", 20, 55, 30));
        JsonNode openPaperTemp = findEvent(hallId, "OPEN", "纸", "TEMP");
        JsonNode openBronzeTemp = findEvent(hallId, "OPEN", "青铜", "TEMP");
        assertNotNull(openPaperTemp, "纸本温度越限成事件");
        assertNotNull(openBronzeTemp, "青铜温度越限成事件");
        assertTrue(openPaperTemp.get("event").get("id").asLong()
                        != openBronzeTemp.get("event").get("id").asLong(),
                "不同材质阈值不同，必须分别成事件");
        assertEquals(22, openPaperTemp.get("event").get("thresholdHigh").asInt());
        assertEquals(25, openBronzeTemp.get("event").get("thresholdHigh").asInt());

        // R10：恢复，两条事件都转待复核，不留 OPEN
        long task10 = generateOne();
        submit(task10, "巡检员赵",
                point("P-A", 20, 55, 30), point("P-B", 20, 40, 30), point("P-C", 20, 55, 30));
        assertEquals(0, dataOf(getJson("/api/env-events?hallId=" + hallId + "&status=OPEN")).size(),
                "恢复后无遗留 OPEN 事件");

        // ========== 九、环境台账可追溯：全动作留痕，任务/读数/事件标明规则版本 ==========
        JsonNode hallLedger = dataOf(getJson("/api/env-ledgers?hallId=" + hallId));
        assertTrue(hasAction(hallLedger, "TASK_GENERATE"));
        assertTrue(hasAction(hallLedger, "READING_OK"));
        assertTrue(hasAction(hallLedger, "READING_ABNORMAL"));
        assertTrue(hasAction(hallLedger, "EVENT_OPEN"));
        assertTrue(hasAction(hallLedger, "EVENT_MERGE"), "连续超限合并须留痕");
        assertTrue(hasAction(hallLedger, "EVENT_RESOLVE"));
        assertTrue(hasAction(hallLedger, "EVENT_HANDLE"));
        assertTrue(hasAction(hallLedger, "EVENT_REVIEW_REJECT"));
        assertTrue(hasAction(hallLedger, "EVENT_CLOSE"));
        for (JsonNode e : hallLedger) {
            if ("TASK_GENERATE".equals(e.get("action").asText())
                    || "READING_ABNORMAL".equals(e.get("action").asText())
                    || "EVENT_OPEN".equals(e.get("action").asText())) {
                assertFalse(e.get("ruleVersionNo").isNull(),
                        "任务/读数/事件台账必须标明采用的规则版本: " + e.get("action").asText());
            }
        }
        // 规则发布动作挂在全馆台账（不属单展厅），同样可追溯并带版本号
        JsonNode allLedger = dataOf(getJson("/api/env-ledgers"));
        JsonNode publishEntry = firstAction(allLedger, "RULE_PUBLISH");
        assertNotNull(publishEntry, "规则发布须写环境台账");
        assertEquals("EV-R1", publishEntry.get("ruleVersionNo").asText());

        // ========== 十、修复审批只完成一半：停留待处理不能开工，巡检计划/指标仍可追溯 ==========
        expectOk(postJson("/api/plan-versions/" + planV1 + "/sign",
                "{\"signerRole\":\"EXTERNAL\",\"signerName\":\"外部乙\",\"decision\":\"APPROVE\"}"));
        expectFail(postEmpty("/api/repair-projects/" + projectId + "/start"), "会签未完成");
        assertEquals("SIGNING",
                dataOf(getJson("/api/plan-versions/" + planV1)).get("version").get("status").asText(),
                "审批只完成一半时停留待处理");
        // 巡检侧不受影响：十轮任务序列、读数、规则版本完整可追溯
        JsonNode allTasks = dataOf(getJson("/api/inspection/tasks?hallId=" + hallId));
        assertEquals(10, allTasks.size(), "R1~R10 十轮巡检任务全部可查");
        for (JsonNode t : allTasks) {
            assertFalse(t.get("ruleVersionNo").isNull(), "每个任务标明规则版本");
        }
        // R9 两条温度事件仍在待保管员复核的待处理清单
        assertEquals(2, dataOf(getJson(
                "/api/env-events?hallId=" + hallId + "&status=RESOLVED")).size());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 发布三材质规则版本。
     * 温度统一 纸/丝 18-22、青铜 15-25；参数只放开湿度区间、照度上限与频率以便用例切换。
     */
    private void publishRule(String versionNo, String title,
                             int paperInterval, int bronzeInterval, int silkInterval,
                             int paperHMin, int paperHMax,
                             int bronzeHMin, int bronzeHMax,
                             int silkHMin, int silkHMax,
                             int paperLuxMax, int bronzeLuxMax, int silkLuxMax) throws Exception {
        String body = "{"
                + "\"versionNo\":\"" + versionNo + "\",\"title\":\"环境规则" + title + "\","
                + "\"publishedBy\":\"环境管理员\",\"items\":["
                + "{\"material\":\"纸\",\"tempMin\":18,\"tempMax\":22,"
                + "\"humidityMin\":" + paperHMin + ",\"humidityMax\":" + paperHMax
                + ",\"luxMin\":0,\"luxMax\":" + paperLuxMax
                + ",\"sampleIntervalMin\":" + paperInterval + "},"
                + "{\"material\":\"青铜\",\"tempMin\":15,\"tempMax\":25,"
                + "\"humidityMin\":" + bronzeHMin + ",\"humidityMax\":" + bronzeHMax
                + ",\"luxMin\":0,\"luxMax\":" + bronzeLuxMax
                + ",\"sampleIntervalMin\":" + bronzeInterval + "},"
                + "{\"material\":\"丝\",\"tempMin\":18,\"tempMax\":22,"
                + "\"humidityMin\":" + silkHMin + ",\"humidityMax\":" + silkHMax
                + ",\"luxMin\":0,\"luxMax\":" + silkLuxMax
                + ",\"sampleIntervalMin\":" + silkInterval + "}"
                + "]}";
        expectOk(postJson("/api/env-rules/publish", body));
    }

    private long generateOne() throws Exception {
        JsonNode arr = dataOf(postEmpty("/api/inspection/tasks/generate?force=true"));
        assertTrue(arr.size() >= 1, "应生成一个巡检任务");
        return arr.get(0).get("id").asLong();
    }

    private void submit(long taskId, String inspector, String... points) throws Exception {
        expectOk(postJson("/api/inspection/tasks/" + taskId + "/readings",
                readings(inspector, points)));
    }

    private String readings(String inspector, String... points) {
        List<String> ps = new ArrayList<>();
        for (String p : points) {
            String[] f = p.split("\\|");
            ps.add("{\"pointCode\":\"" + f[0] + "\",\"temperature\":" + f[1]
                    + ",\"humidity\":" + f[2] + ",\"illuminance\":" + f[3] + "}");
        }
        return "{\"inspector\":\"" + inspector + "\",\"readings\":["
                + String.join(",", ps) + "]}";
    }

    private String point(String code, int temp, int humidity, int lux) {
        return code + "|" + temp + "|" + humidity + "|" + lux;
    }

    private void uploadPhoto(long eventId, String fileName, String uploadedBy, String remark)
            throws Exception {
        expectOk(uploadPhotoMvc(eventId, fileName, uploadedBy, remark));
    }

    private MvcResult uploadPhotoMvc(long eventId, String fileName, String uploadedBy, String remark)
            throws Exception {
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        MockHttpServletRequestBuilder builder = multipart("/api/env-events/" + eventId + "/photos")
                .file(new MockMultipartFile("file", fileName, MediaType.IMAGE_PNG_VALUE, bos.toByteArray()))
                .param("uploadedBy", uploadedBy)
                .param("remark", remark);
        return mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
    }

    private JsonNode findReading(JsonNode taskReport, String pointCode) {
        for (JsonNode r : taskReport.get("readings")) {
            if (pointCode.equals(r.get("pointCode").asText())) {
                return r;
            }
        }
        throw new AssertionError("读数未找到: " + pointCode);
    }

    private JsonNode findDetail(JsonNode taskReport, String material) {
        for (JsonNode d : taskReport.get("readingDetails")) {
            if (material.equals(d.get("material").asText())) {
                return d;
            }
        }
        throw new AssertionError("材质判定明细未找到: " + material);
    }

    private JsonNode findEvent(long hallId, String status, String material, String metric)
            throws Exception {
        JsonNode arr = dataOf(getJson("/api/env-events?hallId=" + hallId + "&status=" + status));
        for (JsonNode e : arr) {
            if (material.equals(e.get("material").asText())
                    && metric.equals(e.get("metric").asText())) {
                return dataOf(getJson("/api/env-events/" + e.get("id").asLong()));
            }
        }
        return null;
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

    private long createRelic(String no, String name, String material, String grade) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + no + "\",\"name\":\"" + name + "\",\"category\":\"杂项\","
                + "\"material\":\"" + material + "\",\"era\":\"宋代\",\"source\":\"考古移交\","
                + "\"preservationGrade\":\"" + grade + "\"}")).get("id").asLong();
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postEmpty(String uri) throws Exception {
        return mockMvc.perform(post(uri)).andExpect(status().isOk()).andReturn();
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
