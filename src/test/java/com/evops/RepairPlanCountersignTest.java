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
 * 修复方案会签（museum-4）验收：
 * 会签顺序按文物风险配置；任一专家退回后旧版本只读、只能修订新版本；
 * 意见/签名时间/依据附件留痕；缺席专家不能被代签；
 * 全部会签完成才允许开工生成施工任务；
 * 版本临界（退回封存、新版本开启）前后，签名始终按同一口径归属签署时的在签版本。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-countersign;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairPlanCountersignTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void countersign_rules_hold() throws Exception {
        // ---- 一级文物：会签顺序 外部专家 -> 馆内专家 -> 保管人员 ----
        long relicId = createRelic("{"
                + "\"relicNo\":\"C-001\",\"name\":\"会签测试青铜鼎\",\"category\":\"青铜器\","
                + "\"material\":\"青铜\",\"era\":\"商代\",\"source\":\"考古移交\","
                + "\"preservationGrade\":\"ONE\"}");
        long projectId = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"CP-001\",\"relicId\":" + relicId
                + ",\"client\":\"藏品部\",\"plan\":\"清洗、矫形、补配\"}")).get("id").asLong();

        // 未提交方案 / 会签未完成：不能开工生成施工任务
        expectFail(postEmpty("/api/repair-projects/" + projectId + "/start"), "会签未完成");

        long v1 = submitVersion(projectId, "V1：乙醇清洗后矫形", "馆内专家甲", "外部专家乙", "保管员丙");
        JsonNode v1Detail = dataOf(getJson("/api/plan-versions/" + v1));
        assertEquals(1, v1Detail.get("version").get("versionNo").asInt());
        assertEquals("ONE", v1Detail.get("version").get("riskLevel").asText());
        assertEquals("SIGNING", v1Detail.get("version").get("status").asText());
        assertEquals("EXTERNAL", v1Detail.get("signOrder").get(0).asText(),
                "一级文物风险最高，外部专家先签");

        // 会签中不能再提交新版本
        expectFail(postJson("/api/repair-projects/" + projectId + "/plan-versions",
                versionJson("Vx", "馆内专家甲", "外部专家乙", "保管员丙")), "会签中");

        // 顺序校验：第一步必须是外部专家
        expectFail(sign(v1, "INTERNAL", "馆内专家甲", "APPROVE", null, null), "会签顺序");
        // 缺席代签：角色正确但实签人不是指定应签人（管理员/他人代签）一律拒绝
        expectFail(sign(v1, "EXTERNAL", "管理员", "APPROVE", null, null), "代签");
        expectFail(sign(v1, "EXTERNAL", "外部专家乙·助手", "APPROVE", null, null), "代签");

        // 外部专家乙本人签署：意见、签名时间、依据附件留痕
        expectOk(sign(v1, "EXTERNAL", "外部专家乙", "APPROVE",
                "裂隙走向已核，方案可行", "reports/c001/外部鉴定.pdf"));
        JsonNode sign1 = dataOf(getJson("/api/plan-versions/" + v1)).get("signs").get(0);
        assertEquals(1, sign1.get("stepNo").asInt());
        assertEquals("EXTERNAL", sign1.get("signerRole").asText());
        assertEquals("外部专家乙", sign1.get("signerName").asText());
        assertEquals("裂隙走向已核，方案可行", sign1.get("opinion").asText());
        assertEquals("reports/c001/外部鉴定.pdf", sign1.get("attachment").asText());
        assertTrue(sign1.get("signedAt").asText().startsWith("20"), "签名时间必须留痕");

        // 部分会签仍不能开工
        expectFail(postEmpty("/api/repair-projects/" + projectId + "/start"), "会签未完成");

        // 馆内专家退回：版本封存只读
        expectOk(sign(v1, "INTERNAL", "馆内专家甲", "RETURN",
                "矫形力度偏大，需降低并补充缓蚀步骤", "reports/c001/工艺复核.pdf"));
        JsonNode v1After = dataOf(getJson("/api/plan-versions/" + v1)).get("version");
        assertEquals("RETURNED", v1After.get("status").asText());
        assertFalse(v1After.get("closedAt").isNull(), "封存时间必须留痕");

        // ---- 临界时间：版本退回封存、新版本开启的边界上，签名归属同一口径 ----
        // 旧版本只读：任何签名（含保管人员按原顺序的第三步）都不能再归入 V1
        expectFail(sign(v1, "CUSTODIAN", "保管员丙", "APPROVE", null, null), "只读");
        expectFail(sign(v1, "EXTERNAL", "外部专家乙", "APPROVE", null, null), "只读");

        // 修订只能开新版本 V2
        long v2 = submitVersion(projectId, "V2：降低矫形力度，补充缓蚀封护",
                "馆内专家甲", "外部专家乙", "保管员丙");
        assertEquals(2, dataOf(getJson("/api/plan-versions/" + v2))
                .get("version").get("versionNo").asInt());

        // 最新版本仍在会签中，不能开工
        expectFail(postEmpty("/api/repair-projects/" + projectId + "/start"), "会签未完成");

        // V2 按同一口径重新会签：签名只归入 V2，不混入 V1
        expectOk(sign(v2, "EXTERNAL", "外部专家乙", "APPROVE", "修订后方案可行", null));
        expectOk(sign(v2, "INTERNAL", "馆内专家甲", "APPROVE", "同意修订稿", null));
        expectOk(sign(v2, "CUSTODIAN", "保管员丙", "APPROVE", "库位已落实", null));
        assertEquals("APPROVED", dataOf(getJson("/api/plan-versions/" + v2))
                .get("version").get("status").asText());

        // 归属核验：V1 仍是 2 条签名且保持 RETURNED，V2 是 3 条签名
        JsonNode v1Final = dataOf(getJson("/api/plan-versions/" + v1));
        assertEquals("RETURNED", v1Final.get("version").get("status").asText());
        assertEquals(2, v1Final.get("signs").size(), "封存版本的签名不会被新版本改写");
        assertEquals("RETURN", v1Final.get("signs").get(1).get("decision").asText());
        JsonNode v2Final = dataOf(getJson("/api/plan-versions/" + v2));
        assertEquals(3, v2Final.get("signs").size());
        assertEquals("保管员丙", v2Final.get("signs").get(2).get("signerName").asText());

        // 已通过版本同样只读
        expectFail(sign(v2, "CUSTODIAN", "保管员丙", "APPROVE", null, null), "只读");

        // 全部会签完成：允许开工生成施工任务
        expectOk(postEmpty("/api/repair-projects/" + projectId + "/start"));

        // 开工后不能再提交新方案版本
        expectFail(postJson("/api/repair-projects/" + projectId + "/plan-versions",
                versionJson("V3", "馆内专家甲", "外部专家乙", "保管员丙")), "不能再提交新方案版本");

        // ---- 三级文物：同一配置口径下顺序不同（馆内专家 -> 保管人员 -> 外部专家） ----
        long relic3 = createRelic("{"
                + "\"relicNo\":\"C-002\",\"name\":\"会签测试陶碗\",\"category\":\"陶器\","
                + "\"material\":\"陶\",\"era\":\"唐代\",\"source\":\"征集\","
                + "\"preservationGrade\":\"THREE\"}");
        long project3 = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"CP-002\",\"relicId\":" + relic3
                + ",\"client\":\"藏品部\",\"plan\":\"清洗加固\"}")).get("id").asLong();
        long v3 = submitVersion(project3, "清洗加固", "馆内专家甲", "外部专家乙", "保管员丙");
        JsonNode v3Detail = dataOf(getJson("/api/plan-versions/" + v3));
        assertEquals("INTERNAL", v3Detail.get("signOrder").get(0).asText());
        assertEquals("CUSTODIAN", v3Detail.get("signOrder").get(1).asText());
        assertEquals("EXTERNAL", v3Detail.get("signOrder").get(2).asText());
        // 三级文物外部专家最后签：第一步用外部专家即乱序
        expectFail(sign(v3, "EXTERNAL", "外部专家乙", "APPROVE", null, null), "会签顺序");
        expectOk(sign(v3, "INTERNAL", "馆内专家甲", "APPROVE", null, null));

        // 台账留痕：提交/签署/退回/通过各动作都在
        JsonNode ledger = dataOf(getJson("/api/relics/" + relicId + "/ledger"));
        assertTrue(hasAction(ledger, "PLAN_SUBMIT"), "方案提交须留痕");
        assertTrue(hasAction(ledger, "PLAN_SIGN"), "会签签署须留痕");
        assertTrue(hasAction(ledger, "PLAN_RETURN"), "方案退回须留痕");
        assertTrue(hasAction(ledger, "PLAN_APPROVE"), "会签通过须留痕");
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

    private long createRelic(String body) throws Exception {
        return dataOf(postJson("/api/relics", body)).get("id").asLong();
    }

    private String versionJson(String plan, String internal, String external, String custodian) {
        return "{\"planContent\":\"" + plan + "\",\"internalExpert\":\"" + internal + "\","
                + "\"externalExpert\":\"" + external + "\",\"custodian\":\"" + custodian + "\","
                + "\"submittedBy\":\"项目负责人\"}";
    }

    private long submitVersion(long projectId, String plan,
                               String internal, String external, String custodian) throws Exception {
        return dataOf(postJson("/api/repair-projects/" + projectId + "/plan-versions",
                versionJson(plan, internal, external, custodian))).get("version").get("id").asLong();
    }

    private MvcResult sign(long versionId, String role, String name, String decision,
                           String opinion, String attachment) throws Exception {
        StringBuilder body = new StringBuilder("{");
        body.append("\"signerRole\":\"").append(role).append("\",")
                .append("\"signerName\":\"").append(name).append("\",")
                .append("\"decision\":\"").append(decision).append("\"");
        if (opinion != null) {
            body.append(",\"opinion\":\"").append(opinion).append("\"");
        }
        if (attachment != null) {
            body.append(",\"attachment\":\"").append(attachment).append("\"");
        }
        body.append("}");
        return postJson("/api/plan-versions/" + versionId + "/sign", body.toString());
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
        assertFalse(root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
