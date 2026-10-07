package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修复工时与设备排程（museum-9）验收：
 * 修复师按工单填报起止时间/工序/设备；设备同一时段不能被两个工单占用；
 * 跨天作业按自然日拆分统计；工时提交后负责人审核，退回只能补充新版本；
 * 设备故障自动生成改期建议但不悄悄改变原排程；
 * 文物置为无效后，排程的页面/导出/后台统计三端同口径。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-timesheet;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairTimesheetScheduleTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void timesheet_schedule_rules_hold() throws Exception {
        // ---- 设备建档：编号唯一 ----
        long eq1 = createEquipment("EQ-001", "激光清洗机");
        long eq2 = createEquipment("EQ-002", "显微操作台");
        expectFail(postJson("/api/repair-equipment",
                "{\"equipmentNo\":\"EQ-001\",\"name\":\"重复编号设备\"}"), "已存在");

        // ---- 两个文物、两个工单并开工 ----
        long relic1 = createRelic("TS-001", "工时测试青铜爵");
        long project1 = createStartedProject(relic1, "TP-001");
        long relic2 = createRelic("TS-002", "工时测试陶罐");
        long project2 = createStartedProject(relic2, "TP-002");

        // 未开工工单不能填报工时
        long relic9 = createRelic("TS-009", "未开工测试器");
        long project9 = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"TP-009\",\"relicId\":" + relic9
                + ",\"client\":\"藏品部\",\"plan\":\"清洗\"}")).get("id").asLong();
        expectFail(submitTimesheet(project9, "修复师甲",
                line("清洗", eq1, "2026-10-10 09:00:00", "2026-10-10 12:00:00")), "不在修复中");

        // ---- 填报：起止/工序/设备；起止校验 ----
        expectFail(submitTimesheet(project1, "修复师甲",
                line("清洗", eq1, "2026-10-10 12:00:00", "2026-10-10 09:00:00")), "早于结束时间");

        // 工单1 V1：跨天作业（10-05 20:00 -> 10-07 09:00）占用 EQ-001
        long ts1 = dataOf(submitTimesheet(project1, "修复师甲",
                line("清洗", eq1, "2026-10-05 20:00:00", "2026-10-07 09:00:00"),
                line("显微观察", eq2, "2026-10-06 10:00:00", "2026-10-06 12:00:00")))
                .get("id").asLong();
        // 同一工单重复提交待审核版本被拒绝
        expectFail(submitTimesheet(project1, "修复师甲",
                line("补配", eq2, "2026-10-08 09:00:00", "2026-10-08 10:00:00")), "待审核");

        // ---- 设备互斥：同一时段不能被两个工单占用 ----
        // 工单2 在 EQ-001 上与工单1 重叠 -> 拒绝
        expectFail(submitTimesheet(project2, "修复师乙",
                line("清洗", eq1, "2026-10-06 08:00:00", "2026-10-06 09:00:00")), "同一时段");
        // 紧邻不重叠（10-07 09:00 起）允许
        long ts2 = dataOf(submitTimesheet(project2, "修复师乙",
                line("清洗", eq1, "2026-10-07 09:00:00", "2026-10-07 18:00:00")))
                .get("id").asLong();
        // 审核通过，释放工单2 的“同时仅一个待审核版本”占用
        expectOk(review(ts2, "APPROVE", "负责人", null));

        // ---- 审核：退回封存只读，只能补充新版本 ----
        expectFail(review(ts1, "RETURN", "负责人", null), "意见");
        expectOk(review(ts1, "RETURN", "负责人", "跨天作业需注明夜间值守"));
        JsonNode ts1After = dataOf(getJson("/api/timesheets/" + ts1)).get("timesheet");
        assertEquals("RETURNED", ts1After.get("status").asText());
        assertEquals(1, ts1After.get("versionNo").asInt());
        // 封存后不能再审核
        expectFail(review(ts1, "APPROVE", "负责人", null), "只读");
        // 退回后设备占用释放：工单2 现在可以占用原冲突时段
        long ts2v2 = dataOf(submitTimesheet(project2, "修复师乙",
                line("复测", eq1, "2026-10-06 08:00:00", "2026-10-06 09:00:00")))
                .get("id").asLong();
        // 该补充版本随后被负责人退回（时段与馆方安排冲突），占用再次释放
        expectOk(review(ts2v2, "RETURN", "负责人", "时段与馆方安排冲突，请改期"));
        // 退回只能补充新版本：工单1 V2 另开，版本号递增
        long ts1v2 = dataOf(submitTimesheet(project1, "修复师甲",
                line("清洗", eq1, "2026-10-05 20:00:00", "2026-10-07 09:00:00"),
                line("显微观察", eq2, "2026-10-06 10:00:00", "2026-10-06 12:00:00")))
                .get("id").asLong();
        assertEquals(2, dataOf(getJson("/api/timesheets/" + ts1v2))
                .get("timesheet").get("versionNo").asInt());
        // 审核通过
        expectOk(review(ts1v2, "APPROVE", "负责人", null));
        assertEquals("APPROVED", dataOf(getJson("/api/timesheets/" + ts1v2))
                .get("timesheet").get("status").asText());

        // ---- 排程页面：只含有效版本行（V1 退回后不再出现） ----
        JsonNode page = dataOf(getJson("/api/repair-schedule?projectId=" + project1));
        assertEquals(2, page.size(), "退回版本的行不参与排程");
        assertEquals("2026-10-05T20:00:00", page.get(0).get("startTime").asText());

        // ---- 后台统计：跨天作业按自然日拆分 ----
        JsonNode summary = dataOf(getJson("/api/repair-schedule/summary?projectId=" + project1));
        JsonNode rows = summary.get("rows");
        long day1 = 0, day2 = 0, day3 = 0;
        for (JsonNode r : rows) {
            if ("EQ-001".equals(r.get("equipmentNo").asText())) {
                if ("2026-10-05".equals(r.get("workDate").asText())) day1 = r.get("minutes").asLong();
                if ("2026-10-06".equals(r.get("workDate").asText())) day2 = r.get("minutes").asLong();
                if ("2026-10-07".equals(r.get("workDate").asText())) day3 = r.get("minutes").asLong();
            }
        }
        assertEquals(240, day1, "10-05 20:00 起跨天，当日 4 小时");
        assertEquals(1440, day2, "10-06 全天");
        assertEquals(540, day3, "10-07 到 09:00，9 小时");
        // 总工时 = 跨天 37h + 显微 2h = 2340 分钟
        assertEquals(2340, summary.get("totalMinutes").asLong());

        // ---- 设备故障：自动生成改期建议，但不悄悄改变原排程 ----
        // 故障窗口覆盖 EQ-001 的 10-06 全天段
        JsonNode faultResult = dataOf(postJson("/api/repair-equipment/" + eq1 + "/faults",
                "{\"description\":\"激光器报警\",\"faultAt\":\"2026-10-06 00:00:00\","
                        + "\"expectedRecoveredAt\":\"2026-10-08 00:00:00\"}"));
        long faultId = faultResult.get("fault").get("id").asLong();
        assertEquals("OPEN", faultResult.get("fault").get("status").asText());
        // 受影响：工单1 V2 的 EQ-001 行（10-05 20:00~10-07 09:00 APPROVED，与故障窗口重叠）
        // 工单2 的 EQ-001 行（10-07 09:00~18:00 APPROVED）；已退回版本不受影响
        assertEquals(2, faultResult.get("suggestCount").asInt(), "故障须自动生成改期建议");
        JsonNode suggestions = faultResult.get("suggestions");
        for (JsonNode s : suggestions) {
            assertEquals("PENDING", s.get("status").asText());
            assertEquals("2026-10-08T00:00:00", s.get("suggestedStart").asText(),
                    "建议起点=预计恢复时刻");
        }
        // 设备置为故障中
        assertEquals("FAULT", dataOf(getJson("/api/repair-equipment/" + eq1))
                .get("equipment").get("status").asText());
        // 故障设备不能填报新工时
        expectFail(submitTimesheet(project2, "修复师乙",
                line("加固", eq1, "2026-10-09 09:00:00", "2026-10-09 10:00:00")), "故障");
        // 原排程不被悄悄改变：页面查询仍是原时段
        JsonNode pageAfterFault = dataOf(getJson("/api/repair-schedule?projectId=" + project1));
        assertEquals("2026-10-05T20:00:00", pageAfterFault.get(0).get("startTime").asText(),
                "故障不得改写原排程");
        assertEquals("2026-10-07T09:00:00", pageAfterFault.get(0).get("endTime").asText());

        // 建议处理：采纳/忽略只留痕，原行仍不变
        long sugId = suggestions.get(0).get("id").asLong();
        expectOk(postJson("/api/repair-suggestions/" + sugId + "/adopt",
                "{\"handledBy\":\"修复师甲\"}"));
        assertEquals(1, dataOf(getJson("/api/repair-suggestions?equipmentId=" + eq1
                + "&status=ADOPTED")).size(), "采纳只改建议状态留痕");
        expectFail(postJson("/api/repair-suggestions/" + sugId + "/dismiss",
                "{\"handledBy\":\"修复师甲\"}"), "已处理");
        JsonNode pageAfterAdopt = dataOf(getJson("/api/repair-schedule?projectId=" + project1));
        assertEquals("2026-10-05T20:00:00", pageAfterAdopt.get(0).get("startTime").asText(),
                "采纳建议也不回写原排程，改期须走工时单新版本");

        // 故障恢复：设备回到在用
        expectOk(postJson("/api/repair-equipment/" + eq1 + "/faults/" + faultId + "/close",
                "{\"handledBy\":\"设备管理员\"}"));
        assertEquals("ACTIVE", dataOf(getJson("/api/repair-equipment/" + eq1))
                .get("equipment").get("status").asText());

        // ---- 台账留痕 ----
        JsonNode ledger = dataOf(getJson("/api/relics/" + relic1 + "/ledger"));
        assertTrue(hasAction(ledger, "TS_SUBMIT"), "工时提交须留痕");
        assertTrue(hasAction(ledger, "TS_RETURN"), "审核退回须留痕");
        assertTrue(hasAction(ledger, "TS_APPROVE"), "审核通过须留痕");
        assertTrue(hasAction(ledger, "RESCHEDULE_SUGGEST"), "改期建议须留痕");

        // ---- 文物置为无效后：页面/导出/后台统计同口径 ----
        // 闭环工单1：登记阶段 -> 完成 -> 验收，然后撤销文物
        expectOk(postJson("/api/repair-projects/" + project1 + "/stages",
                "{\"stageName\":\"清洗\",\"stageResult\":\"完成\",\"operator\":\"修复师甲\"}"));
        expectOk(postEmpty("/api/repair-projects/" + project1 + "/finish"));
        expectOk(postJson("/api/repair-projects/" + project1 + "/accept",
                "{\"acceptedBy\":\"验收员\",\"result\":\"PASS\"}"));
        expectOk(postJson("/api/relics/" + relic1 + "/revoke",
                "{\"reason\":\"档案注销测试\"}"));
        assertEquals("REVOKED", dataOf(getJson("/api/relics/" + relic1))
                .get("relic").get("status").asText());

        // 默认口径：三端都不再出现已撤销文物的行
        JsonNode pageDefault = dataOf(getJson("/api/repair-schedule"));
        assertFalse(containsRelic(pageDefault, "TS-001"), "页面默认剔除无效文物行");
        assertTrue(containsRelic(pageDefault, "TS-002"), "有效文物行保留");
        JsonNode summaryDefault = dataOf(getJson("/api/repair-schedule/summary"));
        assertFalse(containsRelic(summaryDefault.get("rows"), "TS-001"), "统计默认剔除无效文物行");
        String csvDefault = getCsv("/api/repair-schedule/export");
        assertFalse(csvDefault.contains("TS-001"), "导出默认剔除无效文物行");
        assertTrue(csvDefault.contains("TS-002"), "导出保留有效文物行");

        // includeRevoked=true：三端同样保留并以 REVOKED 标明
        JsonNode pageAll = dataOf(getJson("/api/repair-schedule?includeRevoked=true"));
        assertTrue(containsRelic(pageAll, "TS-001"));
        assertEquals("REVOKED", relicStatusOf(pageAll, "TS-001"));
        JsonNode summaryAll = dataOf(getJson("/api/repair-schedule/summary?includeRevoked=true"));
        assertTrue(containsRelic(summaryAll.get("rows"), "TS-001"));
        String csvAll = getCsv("/api/repair-schedule/export?includeRevoked=true");
        assertTrue(csvAll.contains("TS-001"));
        assertTrue(csvAll.contains("REVOKED"));

        // 三端行数一致（同口径核验）：页面行数 == 导出数据行数；统计分钟数与页面一致
        assertEquals(pageAll.size(), csvDataLines(csvAll), "页面与导出行数必须一致");
        long pageMinutes = 0;
        for (JsonNode r : pageAll) pageMinutes += r.get("minutes").asLong();
        assertEquals(pageMinutes, summaryAll.get("totalMinutes").asLong(),
                "页面与后台统计的工时总量必须一致");
    }

    // ------------------------------------------------------------------

    private boolean hasAction(JsonNode ledger, String action) {
        for (JsonNode e : ledger) {
            if (action.equals(e.get("action").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsRelic(JsonNode rows, String relicNo) {
        for (JsonNode r : rows) {
            if (relicNo.equals(r.get("relicNo").asText())) {
                return true;
            }
        }
        return false;
    }

    private String relicStatusOf(JsonNode rows, String relicNo) {
        for (JsonNode r : rows) {
            if (relicNo.equals(r.get("relicNo").asText())) {
                return r.get("relicStatus").asText();
            }
        }
        return null;
    }

    private int csvDataLines(String csv) {
        int lines = csv.split("\n").length;
        return lines - 1; // 去表头
    }

    private String line(String workStep, long equipmentId, String start, String end) {
        return "{\"workStep\":\"" + workStep + "\",\"equipmentId\":" + equipmentId
                + ",\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}";
    }

    private MvcResult submitTimesheet(long projectId, String restorer, String... lines)
            throws Exception {
        return postJson("/api/repair-projects/" + projectId + "/timesheets",
                "{\"restorer\":\"" + restorer + "\",\"submittedBy\":\"" + restorer + "\","
                        + "\"lines\":[" + String.join(",", lines) + "]}");
    }

    private MvcResult review(long timesheetId, String decision, String reviewer, String opinion)
            throws Exception {
        String body = "{\"decision\":\"" + decision + "\",\"reviewedBy\":\"" + reviewer + "\""
                + (opinion == null ? "" : ",\"opinion\":\"" + opinion + "\"") + "}";
        return postJson("/api/timesheets/" + timesheetId + "/review", body);
    }

    private long createEquipment(String no, String name) throws Exception {
        return dataOf(postJson("/api/repair-equipment",
                "{\"equipmentNo\":\"" + no + "\",\"name\":\"" + name + "\"}")).get("id").asLong();
    }

    private long createRelic(String no, String name) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + no + "\",\"name\":\"" + name + "\",\"category\":\"青铜器\","
                + "\"material\":\"青铜\",\"era\":\"商代\",\"source\":\"考古移交\","
                + "\"preservationGrade\":\"THREE\"}")).get("id").asLong();
    }

    /** 立项 -> 方案会签通过 -> 开工，返回项目 id */
    private long createStartedProject(long relicId, String projectNo) throws Exception {
        long projectId = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"" + projectNo + "\",\"relicId\":" + relicId
                + ",\"client\":\"藏品部\",\"plan\":\"清洗、加固\"}")).get("id").asLong();
        long versionId = dataOf(postJson("/api/repair-projects/" + projectId + "/plan-versions",
                "{\"planContent\":\"清洗加固\",\"internalExpert\":\"馆内专家甲\","
                        + "\"externalExpert\":\"外部专家乙\",\"custodian\":\"保管员丙\","
                        + "\"submittedBy\":\"项目负责人\"}")).get("version").get("id").asLong();
        // 三级文物会签顺序：INTERNAL -> CUSTODIAN -> EXTERNAL
        expectOk(postJson("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"INTERNAL\",\"signerName\":\"馆内专家甲\",\"decision\":\"APPROVE\"}"));
        expectOk(postJson("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"CUSTODIAN\",\"signerName\":\"保管员丙\",\"decision\":\"APPROVE\"}"));
        expectOk(postJson("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"EXTERNAL\",\"signerName\":\"外部专家乙\",\"decision\":\"APPROVE\"}"));
        expectOk(postEmpty("/api/repair-projects/" + projectId + "/start"));
        return projectId;
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postEmpty(String uri) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private String getCsv(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
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
