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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 展览借品清点盘点（museum-12）验收：
 * 1) 按展厅生成清点任务（一展厅同时在途任务唯一，重复生成幂等）；
 * 2) 工作人员扫描箱号与展签，系统列出缺失、错位和状态异常；
 * 3) 差异单引用现场照片与见证人，差异必须能解释；复核完成前不能关闭展览；
 * 4) 清点期间发生借还变更（借出/归还）与展陈变更时提示版本冲突；
 * 5) 重基线用旧数据回放后提交新版本，旧版本扫描可溯不改写；
 * 6) 已关闭任务只能新增补充记录，不能重写原结果；
 * 7) 清点台账全程留痕。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-inv;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExhibitionInventoryTest {

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
    void exhibition_inventory_rules_hold() throws Exception {
        // ========== 一、按展厅生成清点任务，扫描列出错位与状态异常 ==========
        long hall1 = dataOf(postJson("/api/halls",
                "{\"hallNo\":\"IH1\",\"name\":\"陶瓷厅\",\"location\":\"一层\",\"custodian\":\"王保管\"}"))
                .get("id").asLong();
        long r1 = createRelic("IW-0001", "青瓷瓶");
        long r2 = createRelic("IW-0002", "白玉佩");
        long r3 = createRelic("IW-0003", "铜香炉");
        display(hall1, r1, "P-A", "L-01", "BX-01");
        display(hall1, r2, "P-A", "L-02", "BX-02");
        display(hall1, r3, "P-B", "L-03", "BX-03");

        // 生成任务：基线 v1 应清点 3 件；重复生成幂等命中同一任务
        JsonNode task1 = dataOf(postJson("/api/inv-tasks",
                "{\"hallId\":" + hall1 + ",\"operatorName\":\"清点甲\"}"));
        long task1Id = task1.get("id").asLong();
        assertEquals("INV-000001", task1.get("taskNo").asText());
        assertEquals(1, task1.get("roundNo").asInt());
        assertEquals(1, task1.get("baselineVersion").asInt());
        assertEquals(3, task1.get("expectedCount").asInt());
        assertEquals(task1Id, dataOf(postJson("/api/inv-tasks",
                "{\"hallId\":" + hall1 + ",\"operatorName\":\"清点乙\"}")).get("id").asLong(),
                "重复生成命中同一在途任务，不产生第二条");

        // 清点中的展厅不能关闭（复核完成前不能关闭展览）
        expectFail(postJson("/api/halls/" + hall1 + "/close", "{\"closedBy\":\"馆长\"}"),
                "复核完成前不能关闭展览");

        // 扫描：相符 / 错位（箱号不符）/ 重复拒绝 / 未在基线
        JsonNode scan1 = dataOf(postJson("/api/inv-tasks/" + task1Id + "/scans",
                "{\"boxNo\":\"BX-01\",\"displayNo\":\"L-01\",\"pointCode\":\"P-A\",\"scannedBy\":\"清点甲\"}"));
        assertEquals("MATCHED", scan1.get("matchResult").asText());
        JsonNode scan2 = dataOf(postJson("/api/inv-tasks/" + task1Id + "/scans",
                "{\"boxNo\":\"BX-99\",\"displayNo\":\"L-02\",\"scannedBy\":\"清点甲\"}"));
        assertEquals("MISPLACED", scan2.get("matchResult").asText(), "箱号与基线不符判错位");
        assertTrue(scan2.get("matchDetail").asText().contains("BX-02"), "错位说明给出期望箱号");
        expectFail(postJson("/api/inv-tasks/" + task1Id + "/scans",
                "{\"boxNo\":\"BX-02\",\"displayNo\":\"L-02\",\"scannedBy\":\"清点甲\"}"), "重复扫描");
        JsonNode scanX = dataOf(postJson("/api/inv-tasks/" + task1Id + "/scans",
                "{\"boxNo\":\"BX-88\",\"displayNo\":\"L-88\",\"scannedBy\":\"清点甲\"}"));
        assertEquals("UNEXPECTED", scanX.get("matchResult").asText(), "未在基线的展签判状态异常");

        // 文物撤销后扫描在场 → 状态异常
        expectOk(postJson("/api/relics/" + r3 + "/revoke", "{\"reason\":\"标本调拨\"}"));
        JsonNode scan3 = dataOf(postJson("/api/inv-tasks/" + task1Id + "/scans",
                "{\"boxNo\":\"BX-03\",\"displayNo\":\"L-03\",\"scannedBy\":\"清点乙\"}"));
        assertEquals("ABNORMAL", scan3.get("matchResult").asText());
        assertTrue(scan3.get("matchDetail").asText().contains("已撤销"));

        // 差异清单：缺失 0、错位 1、状态异常 2
        JsonNode report = dataOf(getJson("/api/inv-tasks/" + task1Id + "/diff-report"));
        assertEquals(0, report.get("missing").size());
        assertEquals(1, report.get("misplaced").size());
        assertEquals(2, report.get("abnormal").size());

        // 差异未开立差异单不能关闭任务
        expectFail(postJson("/api/inv-tasks/" + task1Id + "/close",
                "{\"closedBy\":\"王保管\"}"), "差异单");

        // ========== 二、差异单：说明+见证人必填，现场照片复核前至少一张 ==========
        expectFail(postJson("/api/inv-tasks/" + task1Id + "/diff-orders",
                "{\"explanation\":\"\",\"witness1\":\"见证甲\"}"), "差异说明");
        expectFail(postJson("/api/inv-tasks/" + task1Id + "/diff-orders",
                "{\"explanation\":\"箱号贴错\",\"witness1\":\"\"}"), "见证人");
        expectFail(postJson("/api/inv-tasks/" + task1Id + "/diff-orders",
                "{\"explanation\":\"箱号贴错\",\"witness1\":\"见证甲\",\"witness2\":\"见证甲\"}"),
                "同一人");
        JsonNode diff1 = dataOf(postJson("/api/inv-tasks/" + task1Id + "/diff-orders",
                "{\"explanation\":\"L-02 箱号贴错为 BX-99；L-03 文物已撤销待撤展；L-88 为临展未登记展品\","
                        + "\"witness1\":\"见证甲\",\"witness2\":\"见证乙\",\"createdBy\":\"清点甲\"}"));
        long diff1Id = diff1.get("id").asLong();
        assertEquals("DIF-000001", diff1.get("diffNo").asText());
        assertEquals(0, diff1.get("missingCount").asInt());
        assertEquals(1, diff1.get("misplacedCount").asInt());
        assertEquals(2, diff1.get("abnormalCount").asInt());
        assertEquals("OPEN", diff1.get("status").asText());
        JsonNode diffDetail = dataOf(getJson("/api/inv-diff-orders/" + diff1Id));
        assertEquals(3, diffDetail.get("lines").size(), "差异逐行固化可解释");
        assertEquals("MISPLACED", diffDetail.get("lines").get(0).get("diffType").asText());

        // 差异单开立后本版本扫描封存
        expectFail(postJson("/api/inv-tasks/" + task1Id + "/scans",
                "{\"boxNo\":\"BX-77\",\"displayNo\":\"L-77\",\"scannedBy\":\"清点甲\"}"), "封存");

        // 复核前必须关联现场照片；同内容照片去重
        expectFail(postJson("/api/inv-diff-orders/" + diff1Id + "/review",
                "{\"reviewedBy\":\"王保管\",\"approved\":true}"), "现场照片");
        expectOk(uploadPhotoMvc(diff1Id, "site-1.png", "清点甲"));
        expectFail(uploadPhotoMvc(diff1Id, "site-1-dup.png", "清点甲"), "内容一致");

        // 复核退回须填意见；退回后仍待复核，展览不能关闭
        expectFail(postJson("/api/inv-diff-orders/" + diff1Id + "/review",
                "{\"reviewedBy\":\"王保管\",\"approved\":false}"), "意见");
        expectOk(postJson("/api/inv-diff-orders/" + diff1Id + "/review",
                "{\"reviewedBy\":\"王保管\",\"approved\":false,\"note\":\"补充 L-88 临展协议编号\"}"));
        assertEquals("OPEN", dataOf(getJson("/api/inv-diff-orders/" + diff1Id))
                .get("order").get("status").asText());
        expectFail(postJson("/api/halls/" + hall1 + "/close", "{\"closedBy\":\"馆长\"}"),
                "复核完成前不能关闭展览");

        // 复核通过 → 任务可关闭，结果汇总固化
        expectOk(postJson("/api/inv-diff-orders/" + diff1Id + "/review",
                "{\"reviewedBy\":\"王保管\",\"approved\":true}"));
        JsonNode closed1 = dataOf(postJson("/api/inv-tasks/" + task1Id + "/close",
                "{\"closedBy\":\"王保管\"}"));
        assertEquals("CLOSED", closed1.get("status").asText());
        assertEquals(1, closed1.get("matchedCount").asInt());
        assertEquals(0, closed1.get("missingCount").asInt());
        assertEquals(1, closed1.get("misplacedCount").asInt());
        assertEquals(2, closed1.get("abnormalCount").asInt());

        // ========== 三、已关闭任务只能新增补充记录，不能重写原结果 ==========
        expectFail(postJson("/api/inv-tasks/" + task1Id + "/scans",
                "{\"boxNo\":\"BX-01\",\"displayNo\":\"L-01\",\"scannedBy\":\"清点甲\"}"),
                "只能新增补充记录");
        expectFail(postJson("/api/inv-tasks/" + task1Id + "/rebaseline",
                "{\"operatorName\":\"清点甲\"}"), "只能新增补充记录");
        expectFail(postJson("/api/inv-tasks/" + task1Id + "/diff-orders",
                "{\"explanation\":\"x\",\"witness1\":\"见证甲\"}"), "只能新增补充记录");
        expectOk(postJson("/api/inv-tasks/" + task1Id + "/supplements",
                "{\"content\":\"L-88 已补办临展登记\",\"operatorName\":\"王保管\"}"));
        JsonNode detail1 = dataOf(getJson("/api/inv-tasks/" + task1Id));
        assertEquals(1, detail1.get("supplements").size());
        assertEquals(1, detail1.get("task").get("matchedCount").asInt(), "原结果不被补充记录改写");
        assertEquals(2, dataOf(getJson("/api/inv-tasks/" + task1Id + "/diff-report"))
                .get("abnormal").size(), "关闭后差异清单口径不变");

        // 差异全部复核完成，展览可以关闭；关闭后不能再生成清点任务
        expectOk(postJson("/api/halls/" + hall1 + "/close", "{\"closedBy\":\"馆长\"}"));
        assertEquals("CLOSED", dataOf(getJson("/api/halls/" + hall1)).get("hall").get("status").asText());
        expectFail(postJson("/api/inv-tasks", "{\"hallId\":" + hall1 + "}"), "已关闭");

        // ========== 四、清点期间借还变更提示版本冲突，重基线回放旧数据 ==========
        long hall2 = dataOf(postJson("/api/halls",
                "{\"hallNo\":\"IH2\",\"name\":\"书画厅\",\"location\":\"二层\",\"custodian\":\"李保管\"}"))
                .get("id").asLong();
        long r4 = createRelic("IW-0004", "山水画");
        long r5 = createRelic("IW-0005", "书法卷");
        display(hall2, r4, "P-C", "L-04", "BX-04");
        display(hall2, r5, "P-C", "L-05", "BX-05");
        long task2 = dataOf(postJson("/api/inv-tasks",
                "{\"hallId\":" + hall2 + ",\"operatorName\":\"清点丙\"}")).get("id").asLong();
        expectOk(postJson("/api/inv-tasks/" + task2 + "/scans",
                "{\"boxNo\":\"BX-04\",\"displayNo\":\"L-04\",\"scannedBy\":\"清点丙\"}"));

        // 借出变更 → 扫描/差异单/关闭全部提示版本冲突
        long loan5 = dataOf(postJson("/api/loans",
                "{\"loanNo\":\"LO-2026-9001\",\"relicId\":" + r5
                        + ",\"borrower\":\"邻市博物馆\",\"purpose\":\"外展\"}")).get("id").asLong();
        expectFail(postJson("/api/inv-tasks/" + task2 + "/scans",
                "{\"boxNo\":\"BX-05\",\"displayNo\":\"L-05\",\"scannedBy\":\"清点丙\"}"), "版本冲突");
        MvcResult conflict = postJson("/api/inv-tasks/" + task2 + "/scans",
                "{\"boxNo\":\"BX-05\",\"displayNo\":\"L-05\",\"scannedBy\":\"清点丙\"}");
        JsonNode conflictRoot = objectMapper.readTree(
                conflict.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(conflictRoot.get("message").asText().contains("借还变更"), "提示借还变更");
        expectFail(postJson("/api/inv-tasks/" + task2 + "/diff-orders",
                "{\"explanation\":\"x\",\"witness1\":\"见证甲\"}"), "版本冲突");
        expectFail(postJson("/api/inv-tasks/" + task2 + "/close",
                "{\"closedBy\":\"李保管\"}"), "版本冲突");

        // 重基线：旧数据回放后提交新版本 v2，旧扫描可溯
        JsonNode task2v2 = dataOf(postJson("/api/inv-tasks/" + task2 + "/rebaseline",
                "{\"operatorName\":\"清点丙\"}"));
        assertEquals(2, task2v2.get("baselineVersion").asInt());
        assertEquals(2, task2v2.get("expectedCount").asInt());
        JsonNode v2Scans = dataOf(getJson("/api/inv-tasks/" + task2)).get("scans");
        assertEquals(1, v2Scans.size(), "v1 扫描回放至 v2");
        assertNotNull(v2Scans.get(0).get("replayedFromId").asLong());
        assertEquals("L-04", v2Scans.get(0).get("displayNo").asText());

        // v2 基线已含在借单：扫描在借文物在场 → 状态异常（差异必须能解释）
        JsonNode scan5 = dataOf(postJson("/api/inv-tasks/" + task2 + "/scans",
                "{\"boxNo\":\"BX-05\",\"displayNo\":\"L-05\",\"scannedBy\":\"清点丙\"}"));
        assertEquals("ABNORMAL", scan5.get("matchResult").asText());
        assertTrue(scan5.get("matchDetail").asText().contains("在借未还"));

        // 归还变更同样触发版本冲突
        expectOk(postJson("/api/loans/" + loan5 + "/return",
                "{\"returnCondition\":\"完好\",\"receiver\":\"李保管\"}"));
        expectFail(postJson("/api/inv-tasks/" + task2 + "/diff-orders",
                "{\"explanation\":\"x\",\"witness1\":\"见证甲\"}"), "版本冲突");

        // 再次重基线 → v3：归还后回放重算，L-05 恢复相符，无差异直接关闭
        JsonNode task2v3 = dataOf(postJson("/api/inv-tasks/" + task2 + "/rebaseline",
                "{\"operatorName\":\"清点丙\"}"));
        assertEquals(3, task2v3.get("baselineVersion").asInt());
        JsonNode v3Scans = dataOf(getJson("/api/inv-tasks/" + task2)).get("scans");
        assertEquals(2, v3Scans.size(), "v2 两条扫描全部回放至 v3");
        for (JsonNode s : v3Scans) {
            assertEquals("MATCHED", s.get("matchResult").asText(), "归还后回放重算为相符");
            assertNotNull(s.get("replayedFromId"), "回放行可溯源");
        }
        JsonNode report3 = dataOf(getJson("/api/inv-tasks/" + task2 + "/diff-report"));
        assertEquals(0, report3.get("missing").size());
        assertEquals(0, report3.get("misplaced").size());
        assertEquals(0, report3.get("abnormal").size());
        JsonNode closed2 = dataOf(postJson("/api/inv-tasks/" + task2 + "/close",
                "{\"closedBy\":\"李保管\"}"));
        assertEquals(2, closed2.get("matchedCount").asInt());

        // ========== 五、撤展变更触发版本冲突；缺失差异闭环 ==========
        long hall3 = dataOf(postJson("/api/halls",
                "{\"hallNo\":\"IH3\",\"name\":\"玉器厅\",\"location\":\"三层\",\"custodian\":\"赵保管\"}"))
                .get("id").asLong();
        long r6 = createRelic("IW-0006", "玉璧");
        long r7 = createRelic("IW-0007", "玉琮");
        display(hall3, r6, "P-D", "L-06", "BX-06");
        display(hall3, r7, "P-D", "L-07", "BX-07");
        long task3 = dataOf(postJson("/api/inv-tasks",
                "{\"hallId\":" + hall3 + ",\"operatorName\":\"清点丁\"}")).get("id").asLong();
        // 撤展变更 → 版本冲突
        expectOk(postEmpty("/api/halls/" + hall3 + "/relics/" + r7 + "/withdraw"));
        expectFail(postJson("/api/inv-tasks/" + task3 + "/scans",
                "{\"boxNo\":\"BX-06\",\"displayNo\":\"L-06\",\"scannedBy\":\"清点丁\"}"), "版本冲突");
        // 重基线后基线只剩 L-06；扫描相符，无差异直接关闭
        expectOk(postJson("/api/inv-tasks/" + task3 + "/rebaseline", "{\"operatorName\":\"清点丁\"}"));
        assertEquals(1, dataOf(getJson("/api/inv-tasks/" + task3)).get("task")
                .get("expectedCount").asInt());
        expectOk(postJson("/api/inv-tasks/" + task3 + "/scans",
                "{\"boxNo\":\"BX-06\",\"displayNo\":\"L-06\",\"scannedBy\":\"清点丁\"}"));
        expectOk(postJson("/api/inv-tasks/" + task3 + "/close", "{\"closedBy\":\"赵保管\"}"));

        // 缺失：基线在册未扫描 → 差异单 MISSING 行，复核后关闭
        long hall4 = dataOf(postJson("/api/halls",
                "{\"hallNo\":\"IH4\",\"name\":\"漆器厅\",\"location\":\"三层\",\"custodian\":\"钱保管\"}"))
                .get("id").asLong();
        long r8 = createRelic("IW-0008", "漆盒");
        long r9 = createRelic("IW-0009", "漆盘");
        display(hall4, r8, "P-E", "L-08", "BX-08");
        display(hall4, r9, "P-E", "L-09", "BX-09");
        long task4 = dataOf(postJson("/api/inv-tasks",
                "{\"hallId\":" + hall4 + ",\"operatorName\":\"清点戊\"}")).get("id").asLong();
        expectOk(postJson("/api/inv-tasks/" + task4 + "/scans",
                "{\"boxNo\":\"BX-08\",\"displayNo\":\"L-08\",\"scannedBy\":\"清点戊\"}"));
        JsonNode report4 = dataOf(getJson("/api/inv-tasks/" + task4 + "/diff-report"));
        assertEquals(1, report4.get("missing").size(), "基线在册未扫描判缺失");
        assertEquals("L-09", report4.get("missing").get(0).get("displayNo").asText());
        JsonNode diff4 = dataOf(postJson("/api/inv-tasks/" + task4 + "/diff-orders",
                "{\"explanation\":\"L-09 漆盘送修复室急修，未来及撤展登记\","
                        + "\"witness1\":\"见证丙\",\"createdBy\":\"清点戊\"}"));
        assertEquals(1, diff4.get("missingCount").asInt());
        long diff4Id = diff4.get("id").asLong();
        expectOk(uploadPhotoMvc(diff4Id, "site-4.png", "清点戊"));
        expectOk(postJson("/api/inv-diff-orders/" + diff4Id + "/review",
                "{\"reviewedBy\":\"钱保管\",\"approved\":true}"));
        JsonNode closed4 = dataOf(postJson("/api/inv-tasks/" + task4 + "/close",
                "{\"closedBy\":\"钱保管\"}"));
        assertEquals(1, closed4.get("matchedCount").asInt());
        assertEquals(1, closed4.get("missingCount").asInt());
        // 已关闭任务补充记录正常，原缺失结果不重写
        expectOk(postJson("/api/inv-tasks/" + task4 + "/supplements",
                "{\"content\":\"L-09 已补办撤展登记\",\"operatorName\":\"钱保管\"}"));
        assertEquals(1, dataOf(getJson("/api/inv-tasks/" + task4)).get("task")
                .get("missingCount").asInt());

        // ========== 六、清点台账全程留痕 ==========
        JsonNode ledger1 = dataOf(getJson("/api/inv-ledgers?hallId=" + hall1));
        assertTrue(hasAction(ledger1, "INV_TASK_GENERATE"));
        assertTrue(hasAction(ledger1, "INV_SCAN"));
        assertTrue(hasAction(ledger1, "INV_DIFF_CREATE"));
        assertTrue(hasAction(ledger1, "INV_DIFF_REVIEW_REJECT"));
        assertTrue(hasAction(ledger1, "INV_DIFF_REVIEW"));
        assertTrue(hasAction(ledger1, "INV_TASK_CLOSE"));
        assertTrue(hasAction(ledger1, "INV_SUPPLEMENT"));
        assertTrue(hasAction(ledger1, "INV_HALL_CLOSE"));
        JsonNode ledger2 = dataOf(getJson("/api/inv-ledgers?hallId=" + hall2));
        assertTrue(hasAction(ledger2, "INV_REBASELINE"), "重基线回放留痕");
        assertNull(firstAction(ledger1, "INV_REBASELINE"), "台账按展厅隔离");
    }

    // ================================================================
    // 辅助
    // ================================================================

    private long createRelic(String no, String name) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + no + "\",\"name\":\"" + name + "\",\"category\":\"杂项\","
                + "\"material\":\"陶瓷\",\"era\":\"宋代\",\"source\":\"考古移交\","
                + "\"preservationGrade\":\"THREE\"}")).get("id").asLong();
    }

    private void display(long hallId, long relicId, String point, String displayNo, String boxNo)
            throws Exception {
        expectOk(postJson("/api/halls/" + hallId + "/relics",
                "{\"relicId\":" + relicId + ",\"pointCode\":\"" + point
                        + "\",\"displayNo\":\"" + displayNo + "\",\"boxNo\":\"" + boxNo + "\"}"));
    }

    private MvcResult uploadPhotoMvc(long diffId, String fileName, String uploadedBy)
            throws Exception {
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return mockMvc.perform(multipart("/api/inv-diff-orders/" + diffId + "/photos")
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
