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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物借阅审批（museum-10）规则验收：
 * 按等级配置场景/最高时长/审批角色；申请校验当前展陈计划与修复状态；
 * 二三级常规角色链审批；批准生成借阅快照；出库贯通借出；
 * 保险资料跨馆区脱敏；策略调整只影响新申请。
 * 独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-borrow;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BorrowApprovalTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void grade_policy_scene_duration_and_display_repair_checks() throws Exception {
        // 默认策略三行，一级 30 天必须馆长签名，二级 60 天双签，三级 90 天单签
        JsonNode policies = dataOf(getJson("/api/borrow-policies"));
        assertEquals(3, policies.size());

        long three = createRelic("BR-THREE", "THREE", "MAIN");
        long two = createRelic("BR-TWO", "TWO", "MAIN");
        long one = createRelic("BR-ONE", "ONE", "MAIN");

        // 一级不允许科研观摩场景（默认策略仅 OUT_EXHIBITION/ACADEMIC_EXCHANGE）
        expectFail(apply(one, "RESEARCH", "2026-11-01T09:00:00", "2026-11-20T09:00:00"),
                "不允许该借阅场景");
        // 二级借阅 61 天超过 60 天上限
        expectFail(apply(two, "RESEARCH", "2026-11-01T09:00:00", "2027-01-01T09:00:00"),
                "最高允许时长");
        // 归还时间早于出库时间
        expectFail(apply(three, "RESEARCH", "2026-11-20T09:00:00", "2026-11-01T09:00:00"),
                "预计归还时间");

        // 当前展陈计划：展出中文物不能申请，撤展后可以
        long hall = dataOf(postJson("/api/halls", "{"
                + "\"hallNo\":\"H-BR-1\",\"name\":\"借阅测试厅\",\"location\":\"东楼\",\"custodian\":\"保管员甲\"}"))
                .get("id").asLong();
        expectOk(postJson("/api/halls/" + hall + "/relics", "{"
                + "\"relicId\":" + two + ",\"pointCode\":\"P-01\",\"displayNo\":\"DP-01\"}"));
        expectFail(apply(two, "RESEARCH", "2026-11-01T09:00:00", "2026-11-30T09:00:00"),
                "展陈计划");
        expectOk(postEmpty("/api/halls/" + hall + "/relics/" + two + "/withdraw"));

        // 修复状态：立项（PLANNED）即不能申请，无需开工
        long blockingProject = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-BR-BLOCK\",\"relicId\":" + two
                + ",\"client\":\"藏品处\",\"plan\":\"待修方案\"}")).get("id").asLong();
        expectFail(apply(two, "RESEARCH", "2026-11-01T09:00:00", "2026-11-30T09:00:00"),
                "立项/修复中");
        expectOk(postJson("/api/repair-projects/" + blockingProject + "/cancel",
                "{\"reason\":\"取消占用\"}"));

        // 三级单签：错误角色拒签、驳回必填意见
        long rejectedApp = dataOf(apply(three, "RESEARCH",
                "2026-11-01T09:00:00", "2027-01-20T09:00:00")).get("id").asLong();
        expectFail(approve(rejectedApp, "COLLECTION_LEADER", "张三", "APPROVE", null), "审批顺序");
        expectFail(approve(rejectedApp, "BORROW_DEPT", "张三", "REJECT", "  "), "驳回必须填写");
        expectOk(approve(rejectedApp, "BORROW_DEPT", "馆藏部张三", "REJECT", "用途材料不齐"));
        assertEquals("REJECTED", dataOf(getApp(rejectedApp, "MAIN")).get("application").get("status").asText());

        // 重新申请：三级一步即通过，生成快照
        long appId = dataOf(apply(three, "PUBLIC_EDUCATION",
                "2026-11-01T09:00:00", "2027-01-20T09:00:00")).get("id").asLong();
        expectOk(approve(appId, "BORROW_DEPT", "馆藏部张三", "APPROVE", "同意宣教借阅"));
        JsonNode approved = dataOf(getApp(appId, "MAIN"));
        assertEquals("APPROVED", approved.get("application").get("status").asText());
        assertEquals(1, approved.get("approvals").size());
        JsonNode snapshot = approved.get("snapshot");
        assertEquals(80, snapshot.get("durationDays").asInt());
        assertEquals("BR-THREE", snapshot.get("relicNo").asText());
        assertEquals(0, snapshot.get("directorSigned").asInt());
        // 已批准不能再签
        expectFail(approve(appId, "BORROW_DEPT", "馆藏部张三", "APPROVE", null), "不在审批中");

        // 保险资料按馆区隔离
        JsonNode sameCampus = dataOf(getApp(appId, "MAIN"));
        assertEquals(0, sameCampus.get("application").get("insuranceAmount").decimalValue()
                .compareTo(new java.math.BigDecimal("120000")));
        assertTrue(sameCampus.get("application").get("insuranceViewable").asBoolean());
        JsonNode otherCampus = dataOf(getApp(appId, "WEST"));
        assertTrue(otherCampus.get("application").get("insuranceAmount").isNull());
        assertEquals(false, otherCampus.get("application").get("insuranceViewable").asBoolean());
        assertTrue(otherCampus.get("snapshot").get("insuranceAmount").isNull());
        // 未声明馆区同样不可见
        assertTrue(dataOf(getApp(appId, null)).get("application").get("insuranceAmount").isNull());

        // 出库贯通写 t_relic_loan，归还闭环
        expectOk(postJson("/api/borrow-applications/" + appId + "/lend",
                "{\"lentBy\":\"库房交接员\"}"));
        JsonNode dossier = dataOf(getJson("/api/relics/" + three));
        assertEquals("LENT", dossier.get("loans").get(0).get("status").asText());
        expectOk(postJson("/api/borrow-applications/" + appId + "/return",
                "{\"returnedBy\":\"接收员乙\",\"returnCondition\":\"完好\"}"));
        assertEquals("RETURNED",
                dataOf(getApp(appId, "MAIN")).get("application").get("status").asText());
        assertEquals("RETURNED", dataOf(getJson("/api/relics/" + three))
                .get("loans").get(0).get("status").asText());

        // 策略调整只影响新申请：放开三级 120 天上限后，旧申请仍为历史、新申请按新策略校验
        expectOk(putJson("/api/borrow-policies/THREE", "{"
                + "\"allowedScenes\":[\"OUT_EXHIBITION\",\"ACADEMIC_EXCHANGE\",\"RESEARCH\",\"PUBLIC_EDUCATION\"],"
                + "\"maxDurationDays\":120,\"approvalRoles\":[\"BORROW_DEPT\",\"COLLECTION_LEADER\"],"
                + "\"directorSignRequired\":false,\"updatedBy\":\"馆办\"}"));
        long newApp = dataOf(apply(three, "RESEARCH",
                "2026-11-01T09:00:00", "2027-02-25T09:00:00")).get("id").asLong(); // 116 天
        JsonNode fresh = dataOf(getApp(newApp, "MAIN")).get("application");
        assertEquals(116, fresh.get("durationDays").asInt());
        assertEquals("BORROW_DEPT,COLLECTION_LEADER", fresh.get("approvalRoles").asText());
        // 新策略两步链：先馆藏部后主管；顺序错乱被拒，按序两步通过
        expectFail(approve(newApp, "COLLECTION_LEADER", "主管王五", "APPROVE", null), "审批顺序");
        expectOk(approve(newApp, "BORROW_DEPT", "馆藏部张三", "APPROVE", null));
        expectOk(approve(newApp, "COLLECTION_LEADER", "馆藏主管王五", "APPROVE", "同意"));
        assertEquals("APPROVED", dataOf(getApp(newApp, "MAIN")).get("application").get("status").asText());
    }

    // ------------------------------------------------------------------

    private long createRelic(String no, String grade, String campus) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + no + "\",\"name\":\"" + no + "-青铜鼎\",\"category\":\"青铜器\","
                + "\"material\":\"青铜\",\"era\":\"西周\",\"source\":\"考古发掘\","
                + "\"preservationGrade\":\"" + grade + "\",\"campusCode\":\"" + campus + "\"}"))
                .get("id").asLong();
    }

    private MvcResult apply(long relicId, String scene, String from, String to) throws Exception {
        return postJson("/api/relics/" + relicId + "/borrow-applications", "{"
                + "\"applicant\":\"馆员己\",\"borrower\":\"交流单位甲\",\"borrowScene\":\"" + scene + "\","
                + "\"planLentAt\":\"" + from + "\",\"expectedReturnAt\":\"" + to + "\","
                + "\"purpose\":\"业务测试\",\"insuranceAmount\":120000}");
    }

    private MvcResult approve(long appId, String role, String name, String decision, String opinion)
            throws Exception {
        String opinionJson = opinion == null ? "" : ",\"opinion\":\"" + opinion + "\"";
        return postJson("/api/borrow-applications/" + appId + "/approvals", "{"
                + "\"approverRole\":\"" + role + "\",\"approverName\":\"" + name + "\","
                + "\"decision\":\"" + decision + "\"" + opinionJson + "}");
    }

    private MvcResult getApp(long id, String viewerCampus) throws Exception {
        String suffix = viewerCampus == null ? "" : "?viewerCampus=" + viewerCampus;
        return getJson("/api/borrow-applications/" + id + suffix);
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult putJson(String uri, String body) throws Exception {
        return mockMvc.perform(put(uri).contentType(MediaType.APPLICATION_JSON).content(body))
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
