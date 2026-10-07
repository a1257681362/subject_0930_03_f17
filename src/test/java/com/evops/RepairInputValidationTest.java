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
 * 修复链路输入缺项：
 * 1) 立项/验收缺少关键字段时，错误信息逐项指出缺项；
 * 2) 输入缺项不属于并发冲突——不落 REJECTED 尝试审计、不占用请求流水号，
 *    补齐缺项后用同一流水号只重试当前步骤即可成功。
 * 独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-repair-input;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairInputValidationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void missing_fields_are_listed_and_retry_same_step_after_filling() throws Exception {
        // ---- 立项缺关键字段：错误信息逐项列出全部缺项 ----
        MvcResult rejected = postJson("/api/repair-projects", "{}");
        JsonNode root = objectMapper.readTree(
                rejected.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(false, root.get("success").asBoolean());
        String message = root.get("message").asText();
        assertTrue(message.contains("项目编号"), "应指出缺项 项目编号，实际: " + message);
        assertTrue(message.contains("文物 id"), "应指出缺项 文物 id，实际: " + message);
        assertTrue(message.contains("委托人"), "应指出缺项 委托人，实际: " + message);
        assertTrue(message.contains("修复方案"), "应指出缺项 修复方案，实际: " + message);

        // ---- 验收缺关键字段：指出缺项，补齐后同一流水号只重试当前步骤 ----
        long relicId = createRelic("IV-001", "缺项校验青铜觚");
        long projectId = repairedProject(relicId, "RP-IV-001");

        // 附条件通过但缺验收后保护等级：指出缺项 gradeAfter
        expectFail(postJson("/api/repair-projects/" + projectId + "/accept", "{"
                + "\"acceptedBy\":\"验收组\",\"result\":\"CONDITIONAL\","
                + "\"requestNo\":\"ACC-IV-1\"}"), "gradeAfter");

        // 缺项失败不占用流水号：补齐后用同一 requestNo 重试当前步骤即成功
        expectOk(postJson("/api/repair-projects/" + projectId + "/accept", "{"
                + "\"acceptedBy\":\"验收组\",\"result\":\"CONDITIONAL\",\"gradeAfter\":\"TWO\","
                + "\"requestNo\":\"ACC-IV-1\"}"));
        JsonNode detail = dataOf(getJson("/api/repair-projects/" + projectId));
        assertEquals("ACCEPTED", detail.get("project").get("status").asText());

        // 缺项未落并发拒绝审计：只有一条 EFFECTIVE 尝试，没有 REJECTED
        JsonNode attempts = dataOf(getJson("/api/repair-projects/" + projectId
                + "/operation-attempts?operation=ACCEPT"));
        assertEquals(1, attempts.size(), "缺项重试不得产生额外尝试记录");
        assertEquals("EFFECTIVE", attempts.get(0).get("decision").asText());
        assertEquals("ACC-IV-1", attempts.get(0).get("requestNo").asText());

        // ---- 验收结论非法：指出合法取值，改正后同一流水号重试成功 ----
        long relic2 = createRelic("IV-002", "缺项校验陶壶");
        long project2 = repairedProject(relic2, "RP-IV-002");
        expectFail(postJson("/api/repair-projects/" + project2 + "/accept", "{"
                + "\"acceptedBy\":\"验收组\",\"result\":\"HALF\",\"requestNo\":\"ACC-IV-2\"}"),
                "PASS/CONDITIONAL");
        expectOk(postJson("/api/repair-projects/" + project2 + "/accept", "{"
                + "\"acceptedBy\":\"验收组\",\"result\":\"PASS\",\"requestNo\":\"ACC-IV-2\"}"));
        JsonNode attempts2 = dataOf(getJson("/api/repair-projects/" + project2
                + "/operation-attempts?operation=ACCEPT"));
        assertEquals(1, attempts2.size());
        assertEquals("EFFECTIVE", attempts2.get(0).get("decision").asText());
    }

    // ------------------------------------------------------------------

    private long createRelic(String relicNo, String name) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + relicNo + "\",\"name\":\"" + name + "\",\"category\":\"青铜器\","
                + "\"material\":\"青铜\",\"era\":\"商代\",\"source\":\"考古移交\","
                + "\"preservationGrade\":\"ONE\"}")).get("id").asLong();
    }

    /** 立项 -> 会签通过 -> 开工 -> 阶段 -> 完成，项目停在 REPAIRED 待验收 */
    private long repairedProject(long relicId, String projectNo) throws Exception {
        long projectId = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"" + projectNo + "\",\"relicId\":" + relicId
                + ",\"client\":\"藏品部\",\"plan\":\"清洗矫形\"}")).get("id").asLong();
        expectOk(postJson("/api/repair-projects/" + projectId + "/plan-versions",
                "{\"planContent\":\"方案V1\",\"internalExpert\":\"馆内专家甲\","
                        + "\"externalExpert\":\"外部专家乙\",\"custodian\":\"保管员丙\","
                        + "\"submittedBy\":\"负责人\"}"));
        JsonNode versions = dataOf(getJson("/api/repair-projects/" + projectId + "/plan-versions"));
        long versionId = versions.get(0).get("version").get("id").asLong();
        sign(versionId, "EXTERNAL", "外部专家乙");
        sign(versionId, "INTERNAL", "馆内专家甲");
        sign(versionId, "CUSTODIAN", "保管员丙");
        expectOk(postJson("/api/repair-projects/" + projectId + "/start",
                "{\"requestNo\":\"ST-" + projectNo + "\",\"operatorName\":\"领班\"}"));
        expectOk(postJson("/api/repair-projects/" + projectId + "/stages",
                "{\"stageName\":\"清洗\",\"stageResult\":\"已完成清洗\",\"operator\":\"修复师\"}"));
        expectOk(postJson("/api/repair-projects/" + projectId + "/finish",
                "{\"requestNo\":\"FN-" + projectNo + "\",\"operatorName\":\"修复师\"}"));
        return projectId;
    }

    private void sign(long versionId, String role, String name) throws Exception {
        expectOk(postJson("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"" + role + "\",\"signerName\":\"" + name
                        + "\",\"decision\":\"APPROVE\"}"));
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("data");
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
