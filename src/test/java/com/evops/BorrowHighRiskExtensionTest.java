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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 高风险文物（一级）馆长追加签名与归还延期重新审批（museum-10）验收：
 * 常规角色链后必须馆长签名；任一步驳回终止；延期必须重走完整链，
 * 驳回不改应还时间，通过后回写申请与贯通借出单；归还时审批中延期自动驳回。
 * 独立内存库。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-borrow-highrisk;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BorrowHighRiskExtensionTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void director_signature_and_extension_reapproval_hold() throws Exception {
        long relic = createRelic("BR-HI-001", "ONE");
        long rejectedRelic = createRelic("BR-HI-002", "ONE");

        // 高风险链：馆藏部 -> 主管 -> 馆长；越级/错角色被拒
        long appId = dataOf(apply(relic, "OUT_EXHIBITION",
                "2026-11-01T09:00:00", "2026-11-20T09:00:00")).get("id").asLong();
        expectFail(approve(appId, null, "DIRECTOR", "馆长老赵", "APPROVE", null), "审批顺序");
        expectOk(approve(appId, null, "BORROW_DEPT", "馆藏部张三", "APPROVE", null));
        expectFail(approve(appId, null, "DIRECTOR", "馆长老赵", "APPROVE", null), "审批顺序");
        expectOk(approve(appId, null, "COLLECTION_LEADER", "主管王五", "APPROVE", "同意上呈"));
        // 第三步必须馆长角色签名；以主管角色代签被拒
        expectFail(approve(appId, null, "COLLECTION_LEADER", "主管王五", "APPROVE", null), "审批顺序");
        expectOk(approve(appId, null, "DIRECTOR", "馆长老赵", "APPROVE", "同意，追加馆长签名"));

        JsonNode approved = dataOf(getApp(appId, null));
        assertEquals("APPROVED", approved.get("application").get("status").asText());
        JsonNode snapshot = approved.get("snapshot");
        assertEquals(1, snapshot.get("directorSigned").asInt());
        assertEquals("馆长老赵", snapshot.get("directorSignName").asText());
        assertTrue(snapshot.get("approvalChain").asText().contains("DIRECTOR:馆长老赵(馆长签名)"));
        assertEquals(3, approved.get("approvals").size());
        assertEquals(1, approved.get("approvals").get(2).get("directorSign").asInt());

        // 另一高风险申请：主管步驳回即终止，无快照
        long rejectedApp = dataOf(apply(rejectedRelic, "ACADEMIC_EXCHANGE",
                "2026-11-01T09:00:00", "2026-11-10T09:00:00")).get("id").asLong();
        expectOk(approve(rejectedApp, null, "BORROW_DEPT", "馆藏部张三", "APPROVE", null));
        expectOk(approve(rejectedApp, null, "COLLECTION_LEADER", "主管王五",
                "REJECT", "展期文件不全"));
        JsonNode rejectedDetail = dataOf(getApp(rejectedApp, null));
        assertEquals("REJECTED", rejectedDetail.get("application").get("status").asText());
        assertTrue(rejectedDetail.get("snapshot").isNull());
        // 驳回后不能继续签
        expectFail(approve(rejectedApp, null, "DIRECTOR", "馆长老赵", "APPROVE", null), "不在审批中");

        // 批准后文物被重新布展：出库拦截，撤展后放行（申请时点校验 + 出库时点复查）
        long hall = dataOf(postJson("/api/halls", "{"
                + "\"hallNo\":\"H-HI-1\",\"name\":\"高风险测试厅\",\"custodian\":\"保管员甲\"}"))
                .get("id").asLong();
        expectOk(postJson("/api/halls/" + hall + "/relics", "{"
                + "\"relicId\":" + relic + ",\"pointCode\":\"P-09\"}"));
        expectFail(postJson("/api/borrow-applications/" + appId + "/lend",
                "{\"lentBy\":\"交接员\"}"), "展陈计划");
        expectOk(postEmpty("/api/halls/" + hall + "/relics/" + relic + "/withdraw"));
        expectOk(postJson("/api/borrow-applications/" + appId + "/lend",
                "{\"lentBy\":\"库房交接员\"}"));
        assertEquals("BORROWED", dataOf(getApp(appId, null)).get("application").get("status").asText());

        String originalReturn = "2026-11-20T09:00:00";

        // 延期申请：不得早于当前应还；不得重复提交
        expectFail(postJson("/api/borrow-applications/" + appId + "/extensions", "{"
                + "\"requestedReturnAt\":\"2026-11-15T09:00:00\","
                + "\"reason\":\"想提前\",\"submittedBy\":\"借用人\"}"), "晚于当前应还");
        long ext1 = dataOf(postJson("/api/borrow-applications/" + appId + "/extensions", "{"
                + "\"requestedReturnAt\":\"2026-12-05T09:00:00\","
                + "\"reason\":\"对方展期延长\",\"submittedBy\":\"借用人\"}")).get("id").asLong();
        expectFail(postJson("/api/borrow-applications/" + appId + "/extensions", "{"
                + "\"requestedReturnAt\":\"2026-12-10T09:00:00\","
                + "\"reason\":\"重复申请\",\"submittedBy\":\"借用人\"}"), "重新审批中");

        // 延期重审仍走完整链（含馆长）；主管步驳回，原应还时间不变
        expectOk(approve(appId, ext1, "BORROW_DEPT", "馆藏部张三", "APPROVE", null));
        expectOk(approve(appId, ext1, "COLLECTION_LEADER", "主管王五", "REJECT", "不允许展期"));
        JsonNode ext1Node = findExtension(appId, 1);
        assertEquals("REJECTED", ext1Node.get("extension").get("status").asText());
        assertEquals(originalReturn,
                dataOf(getApp(appId, null)).get("application").get("expectedReturnAt").asText());

        // 第二次延期：完整三步（含馆长签名）通过，回写申请与贯通借出单应还时间
        long ext2 = dataOf(postJson("/api/borrow-applications/" + appId + "/extensions", "{"
                + "\"requestedReturnAt\":\"2026-12-31T09:00:00\","
                + "\"reason\":\"巡展加开两站\",\"submittedBy\":\"借用人\"}")).get("id").asLong();
        assertEquals(2, dataOf(getApp(appId, null)).get("extensions").size());
        // 延期链第一步错角色同样被拒
        expectFail(approve(appId, ext2, "COLLECTION_LEADER", "主管王五", "APPROVE", null), "审批顺序");
        expectOk(approve(appId, ext2, "BORROW_DEPT", "馆藏部张三", "APPROVE", null));
        expectOk(approve(appId, ext2, "COLLECTION_LEADER", "主管王五", "APPROVE", null));
        expectOk(approve(appId, ext2, "DIRECTOR", "馆长老赵", "APPROVE", "同意延期"));
        JsonNode ext2Node = findExtension(appId, 2);
        assertEquals("APPROVED", ext2Node.get("extension").get("status").asText());
        assertEquals(3, ext2Node.get("approvals").size());
        assertEquals(1, ext2Node.get("approvals").get(2).get("directorSign").asInt());
        assertEquals("2026-12-31T09:00:00",
                dataOf(getApp(appId, null)).get("application").get("expectedReturnAt").asText());
        JsonNode loan = dataOf(getJson("/api/relics/" + relic)).get("loans").get(0);
        assertEquals("LENT", loan.get("status").asText());
        assertEquals("2026-12-31T09:00:00", loan.get("expectedReturnAt").asText());

        // 第三次延期提交后未审结即归还：延期自动驳回，借阅闭环
        long ext3 = dataOf(postJson("/api/borrow-applications/" + appId + "/extensions", "{"
                + "\"requestedReturnAt\":\"2027-01-10T09:00:00\","
                + "\"reason\":\"再延一次\",\"submittedBy\":\"借用人\"}")).get("id").asLong();
        assertNotEquals(0L, ext3);
        expectOk(postJson("/api/borrow-applications/" + appId + "/return",
                "{\"returnedBy\":\"接收员乙\",\"returnCondition\":\"完好\"}"));
        JsonNode finalDetail = dataOf(getApp(appId, null));
        assertEquals("RETURNED", finalDetail.get("application").get("status").asText());
        assertEquals("REJECTED", findExtension(appId, 3).get("extension").get("status").asText());
        assertEquals("RETURNED", dataOf(getJson("/api/relics/" + relic))
                .get("loans").get(0).get("status").asText());

        // 非在借状态不能再申请延期
        expectFail(postJson("/api/borrow-applications/" + appId + "/extensions", "{"
                + "\"requestedReturnAt\":\"2027-02-01T09:00:00\","
                + "\"reason\":\"已归还\",\"submittedBy\":\"借用人\"}"), "借出在途");

        // 台账：申请/审批/出库/延期提交/延期驳回/延期通过/归还/延期自动驳回 全部留痕
        java.util.Set<String> actions = new java.util.HashSet<>();
        for (JsonNode entry : dataOf(getJson("/api/relics/" + relic)).get("ledger")) {
            actions.add(entry.get("action").asText());
        }
        assertTrue(actions.contains("BORROW_APPLY"));
        assertTrue(actions.contains("BORROW_APPROVE"));
        assertTrue(actions.contains("BORROW_LEND"));
        assertTrue(actions.contains("BORROW_EXTEND_SUBMIT"));
        assertTrue(actions.contains("BORROW_EXTEND_REJECT"));
        assertTrue(actions.contains("BORROW_EXTEND_APPROVE"));
        assertTrue(actions.contains("BORROW_RETURN"));
    }

    // ------------------------------------------------------------------

    private long createRelic(String no, String grade) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + no + "\",\"name\":\"" + no + "-一级书画\",\"category\":\"书画\","
                + "\"material\":\"绢本\",\"era\":\"宋\",\"source\":\"旧藏\","
                + "\"preservationGrade\":\"" + grade + "\",\"campusCode\":\"MAIN\"}"))
                .get("id").asLong();
    }

    private MvcResult apply(long relicId, String scene, String from, String to) throws Exception {
        return postJson("/api/relics/" + relicId + "/borrow-applications", "{"
                + "\"applicant\":\"馆员己\",\"borrower\":\"省外博物馆\",\"borrowScene\":\"" + scene + "\","
                + "\"planLentAt\":\"" + from + "\",\"expectedReturnAt\":\"" + to + "\","
                + "\"purpose\":\"巡展\",\"insuranceAmount\":5000000}");
    }

    private MvcResult approve(long appId, Long extId, String role, String name,
                              String decision, String opinion) throws Exception {
        String uri = extId == null
                ? "/api/borrow-applications/" + appId + "/approvals"
                : "/api/borrow-extensions/" + extId + "/approvals";
        String opinionJson = opinion == null ? "" : ",\"opinion\":\"" + opinion + "\"";
        return postJson(uri, "{"
                + "\"approverRole\":\"" + role + "\",\"approverName\":\"" + name + "\","
                + "\"decision\":\"" + decision + "\"" + opinionJson + "}");
    }

    private JsonNode findExtension(long appId, int seqNo) throws Exception {
        for (JsonNode bundle : dataOf(getApp(appId, null)).get("extensions")) {
            if (bundle.get("extension").get("seqNo").asInt() == seqNo) {
                return bundle;
            }
        }
        throw new IllegalStateException("未找到第 " + seqNo + " 次延期");
    }

    private MvcResult getApp(long id, String viewerCampus) throws Exception {
        String suffix = viewerCampus == null ? "" : "?viewerCampus=" + viewerCampus;
        return getJson("/api/borrow-applications/" + id + suffix);
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
