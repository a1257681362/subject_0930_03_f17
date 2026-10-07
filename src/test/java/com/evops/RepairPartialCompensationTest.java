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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修复部分成功（验收附条件通过 CONDITIONAL）补偿联动（museum-10）验收：
 * 保护等级立即调整并开立等级补偿；在途/最近借阅成对开立借阅补偿并互持 id；
 * 提前收回补偿先闭环、等级补偿才能确认；已归还借阅走差异补充确认；
 * 审批中借阅按新等级口径驳回；借阅补偿保险金额跨馆区脱敏。
 * 独立内存库。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-comp;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairPartialCompensationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void conditional_accept_opens_corresponding_compensations() throws Exception {
        // ---- A. CONDITIONAL 必须登记验收后等级；无借阅时等级补偿可直接闭环 ----
        long relicA = createRelic("CP-A", "TWO");
        long projectA = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-CP-A\",\"relicId\":" + relicA
                + ",\"client\":\"藏品处\",\"plan\":\"修复方案A\"}")).get("id").asLong();
        prepareForAccept(projectA);
        // 缺 gradeAfter / 非法码值 被拒（项目仍待验收，可再次验收）
        expectFail(acceptConditional(projectA, null), "验收后保护等级");
        expectFail(acceptConditional(projectA, "BAD"), "保护等级");
        expectOk(acceptConditional(projectA, "ONE")); // 附条件通过 TWO -> ONE
        assertEquals("ONE", dataOf(getJson("/api/relics/" + relicA))
                .get("relic").get("preservationGrade").asText());

        JsonNode gradeA = dataOf(getJson("/api/repair-grade-compensations?relicId=" + relicA)).get(0);
        assertEquals("OPEN", gradeA.get("status").asText());
        assertEquals("TWO", gradeA.get("gradeBefore").asText());
        assertEquals("ONE", gradeA.get("gradeAfter").asText());
        assertTrue(gradeA.get("borrowCompensationId").isNull());
        long gradeAId = gradeA.get("id").asLong();
        // 无成对借阅补偿：可直接闭环，最终等级确认 ONE
        expectOk(postJson("/api/repair-grade-compensations/" + gradeAId + "/resolve", "{"
                + "\"resolvedBy\":\"馆领导\",\"note\":\"等级调整复核无误\"}"));
        assertEquals("RESOLVED", dataOf(getJson("/api/repair-grade-compensations/" + gradeAId))
                .get("status").asText());
        assertEquals("ONE", dataOf(getJson("/api/relics/" + relicA))
                .get("relic").get("preservationGrade").asText());

        // ---- B. 已批准待出库借阅在途：成对开立 RECALL，阻断出库、须先收回 ----
        long relicB = createRelic("CP-B", "TWO");
        long appB = dataOf(apply(relicB, "RESEARCH",
                "2026-11-01T09:00:00", "2026-11-25T09:00:00", 300000)).get("id").asLong();
        expectOk(approve(appB, "BORROW_DEPT", "馆藏部张三", "APPROVE", null));
        expectOk(approve(appB, "COLLECTION_LEADER", "主管王五", "APPROVE", null));
        assertEquals("APPROVED", dataOf(getApp(appB)).get("application").get("status").asText());

        long projectB = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-CP-B\",\"relicId\":" + relicB
                + ",\"client\":\"藏品处\",\"plan\":\"修复方案B\"}")).get("id").asLong();
        // 待出库借阅不占 t_relic_loan，修复可开工
        prepareForAccept(projectB);
        expectOk(acceptConditional(projectB, "ONE"));

        JsonNode gradeB = dataOf(getJson("/api/repair-grade-compensations?relicId=" + relicB)).get(0);
        long gradeBId = gradeB.get("id").asLong();
        long borrowBId = gradeB.get("borrowCompensationId").asLong();
        JsonNode borrowB = dataOf(getJson("/api/borrow-compensations/" + borrowBId
                + "?viewerCampus=MAIN"));
        assertEquals("RECALL", borrowB.get("actionType").asText());
        assertEquals("OPEN", borrowB.get("status").asText());
        assertEquals(gradeBId, borrowB.get("gradeCompensationId").asLong());
        assertEquals("TWO", borrowB.get("gradeBefore").asText());
        assertEquals("ONE", borrowB.get("gradeAfter").asText());

        // 馆区保险隔离：同馆区可见金额，跨馆区/不声明馆区一律脱敏
        assertEquals(0, borrowB.get("insuranceBefore").decimalValue()
                .compareTo(new java.math.BigDecimal("300000")));
        assertTrue(dataOf(getJson("/api/borrow-compensations/" + borrowBId + "?viewerCampus=WEST"))
                .get("insuranceBefore").isNull());
        assertTrue(dataOf(getJson("/api/borrow-compensations/" + borrowBId))
                .get("insuranceBefore").isNull());

        // 未闭环提前收回：禁止出库
        expectFail(postJson("/api/borrow-applications/" + appB + "/lend",
                "{\"lentBy\":\"交接员\"}"), "提前收回补偿");
        // 等级补偿不能先于借阅补偿闭环
        expectFail(postJson("/api/repair-grade-compensations/" + gradeBId + "/resolve", "{"
                + "\"resolvedBy\":\"馆领导\",\"note\":\"想先闭环\"}"), "尚未闭环");
        // RECALL 未收回（借阅仍 APPROVED）不能闭环
        expectFail(postJson("/api/borrow-compensations/" + borrowBId + "/resolve", "{"
                + "\"resolvedBy\":\"保管员\",\"note\":\"还没收回\"}"), "已收回");
        // 撤销借阅（APPROVED + 在途补偿允许撤）后补偿可闭环
        expectOk(postJson("/api/borrow-applications/" + appB + "/cancel",
                "{\"operator\":\"藏品处\",\"reason\":\"等级调整，不出库\"}"));
        expectOk(postJson("/api/borrow-compensations/" + borrowBId + "/resolve", "{"
                + "\"resolvedBy\":\"保管员\",\"note\":\"文物未出库，提前收回要求落实\"}"));
        expectOk(postJson("/api/repair-grade-compensations/" + gradeBId + "/resolve", "{"
                + "\"resolvedBy\":\"馆领导\",\"resolvedGrade\":\"ONE\",\"note\":\"等级维持调整后 ONE\"}"));
        assertEquals("RESOLVED",
                dataOf(getJson("/api/repair-grade-compensations/" + gradeBId)).get("status").asText());
        assertEquals("ONE", dataOf(getJson("/api/relics/" + relicB))
                .get("relic").get("preservationGrade").asText());

        // ---- C. 已归还借阅：成对开立 SUPPLEMENT_CONFIRM，补充确认后等级补偿闭环 ----
        long relicC = createRelic("CP-C", "TWO");
        long appC = dataOf(apply(relicC, "OUT_EXHIBITION",
                "2026-11-01T09:00:00", "2026-11-20T09:00:00", 200000)).get("id").asLong();
        expectOk(approve(appC, "BORROW_DEPT", "馆藏部张三", "APPROVE", null));
        expectOk(approve(appC, "COLLECTION_LEADER", "主管王五", "APPROVE", null));
        expectOk(postJson("/api/borrow-applications/" + appC + "/lend", "{\"lentBy\":\"交接员\"}"));
        expectOk(postJson("/api/borrow-applications/" + appC + "/return",
                "{\"returnedBy\":\"接收员\",\"returnCondition\":\"完好\"}"));

        long projectC = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-CP-C\",\"relicId\":" + relicC
                + ",\"client\":\"藏品处\",\"plan\":\"修复方案C\"}")).get("id").asLong();
        prepareForAccept(projectC);
        expectOk(acceptConditional(projectC, "THREE")); // TWO -> THREE

        JsonNode gradeC = dataOf(getJson("/api/repair-grade-compensations?relicId=" + relicC)).get(0);
        long gradeCId = gradeC.get("id").asLong();
        long borrowCId = gradeC.get("borrowCompensationId").asLong();
        JsonNode borrowC = dataOf(getJson("/api/borrow-compensations/" + borrowCId
                + "?viewerCampus=MAIN"));
        assertEquals("SUPPLEMENT_CONFIRM", borrowC.get("actionType").asText());
        assertEquals(appC, borrowC.get("applicationId").asLong());
        // 补充确认（含更正后保险金额）后等级补偿才能闭环
        expectFail(postJson("/api/repair-grade-compensations/" + gradeCId + "/resolve", "{"
                + "\"resolvedBy\":\"馆领导\",\"note\":\"未确认差异\"}"), "尚未闭环");
        expectOk(postJson("/api/borrow-compensations/" + borrowCId + "/resolve", "{"
                + "\"resolvedBy\":\"藏品处\",\"note\":\"等级降低后保险差额已补充确认\","
                + "\"insuranceAfter\":150000}"));
        expectOk(postJson("/api/repair-grade-compensations/" + gradeCId + "/resolve", "{"
                + "\"resolvedBy\":\"馆领导\",\"note\":\"借阅侧差异确认完毕，等级补偿闭环\"}"));
        assertEquals("THREE", dataOf(getJson("/api/relics/" + relicC))
                .get("relic").get("preservationGrade").asText());

        // ---- D. 审批中的借阅申请：等级调整后按新口径驳回，须重新申请 ----
        long relicD = createRelic("CP-D", "THREE");
        long appD = dataOf(apply(relicD, "RESEARCH",
                "2026-11-01T09:00:00", "2027-01-20T09:00:00", 80000)).get("id").asLong();
        assertEquals("PENDING", dataOf(getApp(appD)).get("application").get("status").asText());
        long projectD = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-CP-D\",\"relicId\":" + relicD
                + ",\"client\":\"藏品处\",\"plan\":\"修复方案D\"}")).get("id").asLong();
        prepareForAccept(projectD);
        expectOk(acceptConditional(projectD, "TWO")); // THREE -> TWO

        assertEquals("REJECTED", dataOf(getApp(appD)).get("application").get("status").asText());
        assertTrue(dataOf(getApp(appD)).get("application").get("rejectReason").asText()
                .contains("保护等级"));
        // 等级调整 THREE -> TWO 后无借阅补偿（无在途/已归还借阅，PENDING 只驳回）
        JsonNode gradeD = dataOf(getJson("/api/repair-grade-compensations?relicId=" + relicD)).get(0);
        assertTrue(gradeD.get("borrowCompensationId").isNull());
        long gradeDId = gradeD.get("id").asLong();
        expectOk(postJson("/api/repair-grade-compensations/" + gradeDId + "/resolve", "{"
                + "\"resolvedBy\":\"馆领导\",\"note\":\"等级升为 TWO\"}"));
        // 重新申请按新等级 TWO 口径：科研仍允许，但须双签（一步不够）
        long appD2 = dataOf(apply(relicD, "RESEARCH",
                "2026-11-01T09:00:00", "2026-11-30T09:00:00", 80000)).get("id").asLong();
        expectOk(approve(appD2, "BORROW_DEPT", "馆藏部张三", "APPROVE", null));
        assertEquals("PENDING", dataOf(getApp(appD2)).get("application").get("status").asText());
        expectOk(approve(appD2, "COLLECTION_LEADER", "主管王五", "APPROVE", null));
        assertEquals("APPROVED", dataOf(getApp(appD2)).get("application").get("status").asText());

        // ---- 台账：补偿开立/闭环、借阅驳回全部留痕 ----
        java.util.Set<String> actions = new java.util.HashSet<>();
        for (JsonNode entry : dataOf(getJson("/api/relics/" + relicB)).get("ledger")) {
            actions.add(entry.get("action").asText());
        }
        assertTrue(actions.contains("GRADE_COMP_OPEN"));
        assertTrue(actions.contains("BORROW_COMP_OPEN"));
        assertTrue(actions.contains("BORROW_COMP_RESOLVE"));
        assertTrue(actions.contains("GRADE_COMP_RESOLVE"));
        assertTrue(actions.contains("BORROW_CANCEL"));
    }

    // ------------------------------------------------------------------

    /** 建方案版本 -> 按版本返回的会签顺序三签 -> 开工 -> 阶段 -> 完成（待验收） */
    private void prepareForAccept(long projectId) throws Exception {
        expectOk(postJson("/api/repair-projects/" + projectId + "/plan-versions", "{"
                + "\"planContent\":\"现状评估与修复步骤\",\"internalExpert\":\"专家甲\","
                + "\"externalExpert\":\"外聘乙\",\"custodian\":\"保管员丙\","
                + "\"submittedBy\":\"修复师丁\"}"));
        JsonNode versionBundle = dataOf(getJson("/api/repair-projects/" + projectId + "/plan-versions")).get(0);
        long versionId = versionBundle.get("version").get("id").asLong();
        for (JsonNode roleNode : versionBundle.get("signOrder")) {
            String role = roleNode.asText();
            String name;
            switch (role) {
                case "INTERNAL": name = "专家甲"; break;
                case "EXTERNAL": name = "外聘乙"; break;
                default: name = "保管员丙"; break;
            }
            expectOk(planSign(versionId, role, name));
        }
        expectOk(postEmpty("/api/repair-projects/" + projectId + "/start"));
        expectOk(postJson("/api/repair-projects/" + projectId + "/stages", "{"
                + "\"stageName\":\"加固\",\"stageResult\":\"本体加固完成\",\"operator\":\"修复师丁\"}"));
        expectOk(postEmpty("/api/repair-projects/" + projectId + "/finish"));
    }

    /** 附条件通过验收：必须带合法的验收后保护等级 */
    private MvcResult acceptConditional(long projectId, String gradeAfter) throws Exception {
        String gradeJson = gradeAfter == null ? "" : ",\"gradeAfter\":\"" + gradeAfter + "\"";
        return postJson("/api/repair-projects/" + projectId + "/accept", "{"
                + "\"acceptedBy\":\"验收组\",\"result\":\"CONDITIONAL\",\"opinion\":\"部分成功：附条件通过\""
                + gradeJson + "}");
    }

    private MvcResult planSign(long versionId, String role, String name) throws Exception {
        return postJson("/api/plan-versions/" + versionId + "/sign", "{"
                + "\"signerRole\":\"" + role + "\",\"signerName\":\"" + name + "\","
                + "\"decision\":\"APPROVE\"}");
    }

    private long createRelic(String no, String grade) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + no + "\",\"name\":\"" + no + "-补偿测试器\",\"category\":\"青铜器\","
                + "\"material\":\"青铜\",\"era\":\"商\",\"source\":\"考古发掘\","
                + "\"preservationGrade\":\"" + grade + "\",\"campusCode\":\"MAIN\"}"))
                .get("id").asLong();
    }

    private MvcResult apply(long relicId, String scene, String from, String to, long insurance)
            throws Exception {
        return postJson("/api/relics/" + relicId + "/borrow-applications", "{"
                + "\"applicant\":\"馆员己\",\"borrower\":\"交流单位\",\"borrowScene\":\"" + scene + "\","
                + "\"planLentAt\":\"" + from + "\",\"expectedReturnAt\":\"" + to + "\","
                + "\"purpose\":\"补偿联动测试\",\"insuranceAmount\":" + insurance + "}");
    }

    private MvcResult approve(long appId, String role, String name, String decision, String opinion)
            throws Exception {
        String opinionJson = opinion == null ? "" : ",\"opinion\":\"" + opinion + "\"";
        return postJson("/api/borrow-applications/" + appId + "/approvals", "{"
                + "\"approverRole\":\"" + role + "\",\"approverName\":\"" + name + "\","
                + "\"decision\":\"" + decision + "\"" + opinionJson + "}");
    }

    private MvcResult getApp(long id) throws Exception {
        return getJson("/api/borrow-applications/" + id);
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postEmpty(String uri) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
    }

    private void expectOk(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(false, root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
