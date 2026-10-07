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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务规则验收：编号唯一、撤销不抹除、借/还/修/验状态链路不可跳步。
 * 使用独立内存库，不调用起步接口，编号互不干扰。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-rules;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MuseumLedgerRuleTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void all_rules_hold() throws Exception {
        // ---- 建档：材质/年代/来源/保存等级 ----
        long relicId = createRelic("{"
                + "\"relicNo\":\"T-001\",\"name\":\"测试陶罐\",\"category\":\"陶器\","
                + "\"material\":\"夹砂陶\",\"era\":\"汉代\",\"source\":\"社会捐赠\","
                + "\"preservationGrade\":\"TWO\",\"description\":\"规则测试文物\"}");

        // 文物编号必须唯一：再次用 T-001 建档应失败
        expectFail(postJson("/api/relics", "{"
                + "\"relicNo\":\"T-001\",\"name\":\"重复编号\","
                + "\"material\":\"陶\",\"era\":\"汉\",\"source\":\"捐赠\","
                + "\"preservationGrade\":\"TWO\"}"), "文物编号已存在");

        // 保存等级非法
        expectFail(postJson("/api/relics", "{"
                + "\"relicNo\":\"T-002\",\"name\":\"等级非法\","
                + "\"material\":\"陶\",\"era\":\"汉\",\"source\":\"捐赠\","
                + "\"preservationGrade\":\"ZERO\"}"), "保存等级");

        // ---- 入藏：照片/尺寸/库位 ----
        expectOk(postJson("/api/relics/" + relicId + "/intake", "{"
                + "\"intakeNo\":\"I-001\",\"heightMm\":120.5,\"widthMm\":80.0,"
                + "\"location\":\"二号库·陶器区·B-01\",\"receiver\":\"库管员\","
                + "\"photos\":[{\"photoUrl\":\"photos/t001/a.jpg\",\"viewType\":\"正面\"}]}"));

        // 一件文物只能入藏一次
        expectFail(postJson("/api/relics/" + relicId + "/intake", "{"
                + "\"intakeNo\":\"I-002\",\"location\":\"二号库·B-02\","
                + "\"photos\":[{\"photoUrl\":\"photos/t001/b.jpg\"}]}"), "不能重复入藏");

        // 入藏至少一张照片（Bean 校验：photos 为空）
        expectFail(postJson("/api/relics/" + relicId + "/intake", "{"
                + "\"intakeNo\":\"I-003\",\"location\":\"二号库·B-03\",\"photos\":[]}"), "照片");

        // ---- 借出 / 归还 ----
        JsonNode loanData = dataOf(postJson("/api/loans", "{"
                + "\"loanNo\":\"L-001\",\"relicId\":" + relicId
                + ",\"borrower\":\"市博物馆\",\"purpose\":\"巡展\"}"));
        long loanId = loanData.get("id").asLong();

        // 借出未还时不能重复借出
        expectFail(postJson("/api/loans", "{"
                + "\"loanNo\":\"L-002\",\"relicId\":" + relicId
                + ",\"borrower\":\"某单位\"}"), "不能重复借出");

        // 借出未还、修复不能开工（先立项再验证）
        JsonNode projectData = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"P-001\",\"relicId\":" + relicId
                + ",\"client\":\"藏品处\",\"plan\":\"清洗加固\"}"));
        long projectId = projectData.get("id").asLong();

        expectFail(postEmpty("/api/repair-projects/" + projectId + "/start"), "尚在外借");

        // 项目编号必须唯一
        expectFail(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"P-001\",\"relicId\":" + relicId
                + ",\"client\":\"委托人\",\"plan\":\"方案\"}"), "项目编号已存在");

        // 归还
        expectOk(postJson("/api/loans/" + loanId + "/return",
                "{\"returnCondition\":\"完好\",\"receiver\":\"库管员\"}"));
        // 不能重复归还
        expectFail(postJson("/api/loans/" + loanId + "/return",
                "{\"receiver\":\"库管员\"}"), "不能归还");

        // ---- 修复链路不可跳步 ----
        // 未开工不能登记阶段结果
        expectFail(postJson("/api/repair-projects/" + projectId + "/stages", "{"
                + "\"stageName\":\"清洗\",\"stageResult\":\"已清洗\"}"), "不能登记阶段结果");

        // 开工后，未登记阶段结果不能完成修复
        // （museum-4：开工前须先完成方案会签，否则不能生成施工任务）
        expectFail(postEmpty("/api/repair-projects/" + projectId + "/start"), "会签");
        countersign(projectId);
        expectOk(postEmpty("/api/repair-projects/" + projectId + "/start"));
        expectFail(postEmpty("/api/repair-projects/" + projectId + "/finish"), "阶段结果");

        expectOk(postJson("/api/repair-projects/" + projectId + "/stages", "{"
                + "\"stageName\":\"清洗\",\"stageResult\":\"表面附着物清除\","
                + "\"operator\":\"修复师乙\"}"));
        expectOk(postEmpty("/api/repair-projects/" + projectId + "/finish"));

        // 已完成不能重复完成；待验收状态不能作废
        expectFail(postEmpty("/api/repair-projects/" + projectId + "/finish"), "不能完成");
        expectFail(postJson("/api/repair-projects/" + projectId + "/cancel",
                "{\"reason\":\"想作废\"}"), "不允许作废");

        // 验收
        expectOk(postJson("/api/repair-projects/" + projectId + "/accept", "{"
                + "\"acceptedBy\":\"验收组\",\"result\":\"PASS\",\"opinion\":\"合格\"}"));
        // 已验收不能再验收
        expectFail(postJson("/api/repair-projects/" + projectId + "/accept",
                "{\"acceptedBy\":\"验收组\"}"), "不能验收");

        // ---- 撤销：只改状态，记录仍可查 ----
        expectOk(postJson("/api/relics/" + relicId + "/revoke",
                "{\"reason\":\"建档信息核实有误，待重新建档\"}"));
        JsonNode dossier = dataOf(getJson("/api/relics/" + relicId));
        assertEquals("REVOKED", dossier.get("relic").get("status").asText());
        assertEquals("建档信息核实有误，待重新建档",
                dossier.get("relic").get("revokeReason").asText());
        // 撤销不抹除：入藏照片、借还、项目、台账都还在
        assertEquals(1, dossier.get("intake").get("photos").size());
        assertEquals(1, dossier.get("loans").size());
        assertEquals(1, dossier.get("repairProjects").size());
        assertTrue(dossier.get("ledger").size() >= 8);
        // 已撤销文物不能再操作
        expectFail(postJson("/api/loans", "{"
                + "\"loanNo\":\"L-003\",\"relicId\":" + relicId
                + ",\"borrower\":\"某单位\"}"), "已撤销");

        // 列表仍能查到已撤销记录（撤销不是删除）
        JsonNode list = dataOf(getJson("/api/relics?keyword=T-001"));
        assertTrue(list.isArray() && list.size() == 1);
    }

    // ------------------------------------------------------------------

    private long createRelic(String body) throws Exception {
        return dataOf(postJson("/api/relics", body)).get("id").asLong();
    }

    /** 二级文物会签顺序：馆内专家 -> 外部专家 -> 保管人员，全部同意方可开工 */
    private void countersign(long projectId) throws Exception {
        long versionId = dataOf(postJson("/api/repair-projects/" + projectId + "/plan-versions", "{"
                + "\"planContent\":\"清洗加固\",\"internalExpert\":\"馆内专家甲\","
                + "\"externalExpert\":\"外部专家乙\",\"custodian\":\"保管员丙\","
                + "\"submittedBy\":\"项目负责人\"}")).get("version").get("id").asLong();
        signPlan(versionId, "INTERNAL", "馆内专家甲");
        signPlan(versionId, "EXTERNAL", "外部专家乙");
        signPlan(versionId, "CUSTODIAN", "保管员丙");
    }

    private void signPlan(long versionId, String role, String name) throws Exception {
        expectOk(postJson("/api/plan-versions/" + versionId + "/sign", "{"
                + "\"signerRole\":\"" + role + "\",\"signerName\":\"" + name + "\","
                + "\"decision\":\"APPROVE\"}"));
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postEmpty(String uri) throws Exception {
        MockHttpServletRequestBuilder request = post(uri).contentType(MediaType.APPLICATION_JSON);
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
    }

    private void expectOk(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(false, root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
