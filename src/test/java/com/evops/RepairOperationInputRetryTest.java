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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修复输入缺项与重试口径（museum-11 加固）验收：
 * 1) 输入缺少关键字段（如 CONDITIONAL 缺 gradeAfter）时错误指明缺项字段，
 *    且不作为并发落败写 REJECTED 尝试、不消耗请求流水号；
 * 2) 调用方补齐字段后用同一流水号重试，只执行当前步骤并生效；
 * 3) 状态冲突（项目已不在待验收）仍写 REJECTED 审计，口径不回退。
 * 独立内存库。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-repair-input;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairOperationInputRetryTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static final class Outcome {
        boolean success;
        String message;
        JsonNode data;
    }

    @Test
    void missing_fields_are_named_and_retry_reruns_only_current_step() throws Exception {
        long projectId = repairedProject("IR-001", "缺项青铜爵");

        // ---- 缺关键字段：错误指明缺项（字段名 + 中文口径），不写任何尝试 ----
        Outcome missing = call(postJson("/api/repair-projects/" + projectId + "/accept",
                "{\"acceptedBy\":\"验收组\",\"result\":\"CONDITIONAL\",\"requestNo\":\"ACC-IR-1\"}"));
        assertFalse(missing.success);
        assertTrue(missing.message.contains("缺少关键字段"), "应指出缺项，实际: " + missing.message);
        assertTrue(missing.message.contains("gradeAfter"), "应点名缺项字段，实际: " + missing.message);
        assertTrue(missing.message.contains("验收后保护等级"), "应给出字段中文口径，实际: " + missing.message);
        assertEquals(0, attempts(projectId, "ACCEPT").size(),
                "输入缺项不应写入 EFFECTIVE/REJECTED 尝试（不消耗流水号）");

        // ---- 补齐后同一流水号重试：只执行当前步骤（验收），直接生效 ----
        Outcome fixed = call(postJson("/api/repair-projects/" + projectId + "/accept",
                "{\"acceptedBy\":\"验收组\",\"result\":\"CONDITIONAL\",\"gradeAfter\":\"TWO\","
                        + "\"requestNo\":\"ACC-IR-1\"}"));
        assertTrue(fixed.success, "补齐后同号重试应生效，实际: " + fixed.message);
        JsonNode acceptAttempts = attempts(projectId, "ACCEPT");
        assertEquals(1, acceptAttempts.size());
        assertEquals("EFFECTIVE", acceptAttempts.get(0).get("decision").asText());
        assertEquals("ACC-IR-1", acceptAttempts.get(0).get("requestNo").asText());
        assertEquals("ACCEPTED", projectStatus(projectId));

        // ---- 同流水号再次重提：幂等回放，不二次验收 ----
        Outcome replay = call(postJson("/api/repair-projects/" + projectId + "/accept",
                "{\"acceptedBy\":\"验收组\",\"result\":\"CONDITIONAL\",\"gradeAfter\":\"TWO\","
                        + "\"requestNo\":\"ACC-IR-1\"}"));
        assertTrue(replay.success, "同号重提应幂等回放，实际: " + replay.message);
        assertEquals(fixed.data.get("id").asLong(), replay.data.get("id").asLong());
        assertEquals(1, attempts(projectId, "ACCEPT").size(), "幂等回放不得新增尝试");

        // ---- 状态冲突（已验收项目再验收）仍写 REJECTED 审计，口径不回退 ----
        Outcome conflict = call(postJson("/api/repair-projects/" + projectId + "/accept",
                "{\"acceptedBy\":\"验收组\",\"requestNo\":\"ACC-IR-2\"}"));
        assertFalse(conflict.success);
        assertTrue(conflict.message.contains("未处于待验收"), "实际: " + conflict.message);
        JsonNode after = attempts(projectId, "ACCEPT");
        assertEquals(2, after.size());
        boolean rejectedFound = false;
        for (JsonNode a : after) {
            if ("REJECTED".equals(a.get("decision").asText())) {
                rejectedFound = true;
                assertEquals("ACC-IR-2", a.get("requestNo").asText());
                assertFalse(a.get("effectiveAttemptId").isNull(), "被拒尝试应指向生效尝试");
            }
        }
        assertTrue(rejectedFound, "状态冲突必须落 REJECTED 审计");
    }

    @Test
    void invalid_input_values_are_named_and_do_not_burn_request_no() throws Exception {
        long projectId = repairedProject("IR-101", "缺项陶俑");

        // 非法结论码值：指出合法取值
        Outcome badResult = call(postJson("/api/repair-projects/" + projectId + "/accept",
                "{\"acceptedBy\":\"验收组\",\"result\":\"MAYBE\",\"requestNo\":\"ACC-IR-X1\"}"));
        assertFalse(badResult.success);
        assertTrue(badResult.message.contains("验收结论只能为 PASS/CONDITIONAL"),
                "实际: " + badResult.message);

        // 非法等级码值：指出合法码值
        Outcome badGrade = call(postJson("/api/repair-projects/" + projectId + "/accept",
                "{\"acceptedBy\":\"验收组\",\"result\":\"CONDITIONAL\",\"gradeAfter\":\"BAD\","
                        + "\"requestNo\":\"ACC-IR-X2\"}"));
        assertFalse(badGrade.success);
        assertTrue(badGrade.message.contains("保护等级"), "实际: " + badGrade.message);

        // bean 校验缺项：验收人缺失，错误指明字段名
        Outcome noApprover = call(postJson("/api/repair-projects/" + projectId + "/accept",
                "{\"result\":\"PASS\",\"requestNo\":\"ACC-IR-X3\"}"));
        assertFalse(noApprover.success);
        assertTrue(noApprover.message.contains("acceptedBy"), "实际: " + noApprover.message);

        // 三次输入问题都不写尝试（流水号未消耗）
        assertEquals(0, attempts(projectId, "ACCEPT").size());

        // 补齐后正常验收（复用此前被拒的流水号 X1，证明未烧号）
        Outcome ok = call(postJson("/api/repair-projects/" + projectId + "/accept",
                "{\"acceptedBy\":\"验收组\",\"result\":\"PASS\",\"requestNo\":\"ACC-IR-X1\"}"));
        assertTrue(ok.success, "补齐后同号重试应生效，实际: " + ok.message);
        JsonNode acceptAttempts = attempts(projectId, "ACCEPT");
        assertEquals(1, acceptAttempts.size());
        assertEquals("EFFECTIVE", acceptAttempts.get(0).get("decision").asText());
        assertEquals("ACCEPTED", projectStatus(projectId));
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    /** 建档 -> 立项 -> 会签通过 -> 开工 -> 阶段 -> 完成，项目停在 REPAIRED */
    private long repairedProject(String projectNo, String relicName) throws Exception {
        long relicId = dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"R-" + projectNo + "\",\"name\":\"" + relicName
                + "\",\"category\":\"青铜器\",\"material\":\"青铜\",\"era\":\"商代\","
                + "\"source\":\"考古移交\",\"preservationGrade\":\"ONE\"}")).get("id").asLong();
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
                "{\"requestNo\":\"START-" + projectNo + "\"}"));
        expectOk(postJson("/api/repair-projects/" + projectId + "/stages",
                "{\"stageName\":\"清洗\",\"stageResult\":\"清洗完成\",\"operator\":\"修复师甲\","
                        + "\"requestNo\":\"STG-" + projectNo + "\"}"));
        expectOk(postJson("/api/repair-projects/" + projectId + "/finish",
                "{\"requestNo\":\"FIN-" + projectNo + "\"}"));
        return projectId;
    }

    private void sign(long versionId, String role, String name) throws Exception {
        expectOk(postJson("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"" + role + "\",\"signerName\":\"" + name
                        + "\",\"decision\":\"APPROVE\"}"));
    }

    private JsonNode attempts(long projectId, String operation) throws Exception {
        return dataOf(getJson("/api/repair-projects/" + projectId
                + "/operation-attempts?operation=" + operation));
    }

    private String projectStatus(long projectId) throws Exception {
        return dataOf(getJson("/api/repair-projects/" + projectId))
                .get("project").get("status").asText();
    }

    private Outcome call(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        Outcome o = new Outcome();
        o.success = root.get("success").asBoolean();
        o.message = root.get("message").asText();
        o.data = root.get("data");
        return o;
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
}
