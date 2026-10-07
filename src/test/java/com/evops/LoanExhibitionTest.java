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

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物借展协议与押运闭环（museum-6）业务规则验收：
 * 协议登记/签署/版本变更、四节点顺序与异常暂停、开箱双人见证、
 * 归还逐项验收保留差异、贯通借出占用、修复侧反查与台账留痕。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-loan;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoanExhibitionTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void full_loan_exhibition_flow_holds() throws Exception {
        // ---- 建档 ----
        long relicId = createRelic("{"
                + "\"relicNo\":\"LX-001\",\"name\":\"借展测试青铜簋\",\"category\":\"青铜器\","
                + "\"material\":\"青铜\",\"era\":\"西周\",\"source\":\"考古发掘\","
                + "\"preservationGrade\":\"ONE\"}");

        // ---- 协议登记：必填项校验 ----
        expectFail(postJson("/api/loan-agreements", "{"
                + "\"agreementNo\":\"LA-BAD\",\"relicId\":" + relicId
                + ",\"insuranceAmount\":1000,\"transportRoute\":\"路线\","
                + "\"returnDate\":\"2026-12-31\",\"items\":[{\"itemNo\":\"I1\",\"itemName\":\"x\",\"qty\":1}]}"),
                "借展方");
        expectFail(postJson("/api/loan-agreements", "{"
                + "\"agreementNo\":\"LA-BAD\",\"relicId\":" + relicId
                + ",\"borrower\":\"市博物馆\",\"transportRoute\":\"路线\","
                + "\"returnDate\":\"2026-12-31\",\"items\":[{\"itemNo\":\"I1\",\"itemName\":\"x\",\"qty\":1}]}"),
                "保险金额");
        expectFail(postJson("/api/loan-agreements", "{"
                + "\"agreementNo\":\"LA-BAD\",\"relicId\":" + relicId
                + ",\"borrower\":\"市博物馆\",\"insuranceAmount\":1000,"
                + "\"returnDate\":\"2026-12-31\",\"items\":[]}"),
                "借展清单");
        expectFail(postJson("/api/loan-agreements", "{"
                + "\"agreementNo\":\"LA-BAD\",\"relicId\":" + relicId
                + ",\"borrower\":\"市博物馆\",\"insuranceAmount\":-1,"
                + "\"transportRoute\":\"路线\",\"returnDate\":\"2026-12-31\","
                + "\"items\":[{\"itemNo\":\"I1\",\"itemName\":\"x\",\"qty\":1}]}"),
                "保险金额");

        // ---- 协议登记成功：3 项清单 ----
        JsonNode agreementData = dataOf(postJson("/api/loan-agreements", "{"
                + "\"agreementNo\":\"LA-2026-0001\",\"relicId\":" + relicId
                + ",\"borrower\":\"市博物馆\",\"insuranceAmount\":500000,"
                + "\"transportRoute\":\"省博库房-专线-市博物馆展厅\","
                + "\"returnDate\":\"2026-12-31\",\"purpose\":\"『青铜文明』巡展\","
                + "\"terms\":\"恒温恒湿展柜\",\"items\":["
                + "{\"itemNo\":\"ITEM-1\",\"itemName\":\"青铜簋\",\"qty\":1,\"outCondition\":\"口沿微残\"},"
                + "{\"itemNo\":\"ITEM-2\",\"itemName\":\"原配锦盒\",\"qty\":1,\"outCondition\":\"边角磨损\"},"
                + "{\"itemNo\":\"ITEM-3\",\"itemName\":\"囊匣\",\"qty\":2,\"outCondition\":\"完好\"}]}"));
        long agreementId = agreementData.get("id").asLong();
        assertEquals("DRAFT", agreementData.get("status").asText());

        // 同一文物不能重复登记未闭环协议
        expectFail(postJson("/api/loan-agreements", "{"
                + "\"agreementNo\":\"LA-2026-0002\",\"relicId\":" + relicId
                + ",\"borrower\":\"另一单位\",\"insuranceAmount\":1,"
                + "\"transportRoute\":\"路线二\",\"returnDate\":\"2026-12-31\","
                + "\"items\":[{\"itemNo\":\"I1\",\"itemName\":\"x\",\"qty\":1}]}"),
                "未闭环借展协议");
        // 协议单号唯一
        expectFail(postJson("/api/loan-agreements", "{"
                + "\"agreementNo\":\"LA-2026-0001\",\"relicId\":" + relicId
                + ",\"borrower\":\"市博物馆\",\"insuranceAmount\":1,"
                + "\"transportRoute\":\"路线\",\"returnDate\":\"2026-12-31\","
                + "\"items\":[{\"itemNo\":\"I1\",\"itemName\":\"x\",\"qty\":1}]}"),
                "协议单号已存在");

        // ---- 未签协议不能押运 ----
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"SEAL\",\"handler\":\"押运员甲\"}"), "未签署");

        // ---- 签署 v1 ----
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/sign",
                "{\"signedBy\":\"馆方代表张三\"}"));
        // 已签版本不能重复签署（不能覆盖）
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/sign",
                "{\"signedBy\":\"馆方代表张三\"}"), "不能覆盖");
        // 已有未闭环协议，旧借出通道不能重复占用
        expectFail(postJson("/api/loans", "{"
                + "\"loanNo\":\"LO-OLD-1\",\"relicId\":" + relicId
                + ",\"borrower\":\"旧通道单位\"}"), "未闭环借展协议");

        // ---- 协议变更：出 v2（提高保险金额），签署前押运暂停 ----
        long v2Id = dataOf(postJson("/api/loan-agreements/" + agreementId + "/amend", "{"
                + "\"changeReason\":\"借展方追加保险\","
                + "\"insuranceAmount\":800000}")).get("id").asLong();
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"SEAL\",\"handler\":\"押运员甲\"}"), "尚未签署");
        // 存在未签新版本时不能再次变更
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/amend",
                "{\"changeReason\":\"再改一次\"}"), "尚未签署");

        // 签署 v2：旧 v1 置 SUPERSEDED 且内容不改写
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/sign",
                "{\"signedBy\":\"馆方代表张三\"}"));
        JsonNode detail = dataOf(getJson("/api/loan-agreements/" + agreementId));
        JsonNode versions = detail.get("versions");
        assertEquals(2, versions.size());
        assertEquals("SUPERSEDED", versions.get(0).get("version").get("status").asText());
        assertEquals("市博物馆", versions.get(0).get("version").get("borrower").asText());
        assertEquals(500000, versions.get(0).get("version").get("insuranceAmount").asLong());
        assertEquals("SIGNED", versions.get(1).get("version").get("status").asText());
        assertEquals(800000, versions.get(1).get("version").get("insuranceAmount").asLong());
        // 变更未传清单：v2 清单复制自 v1（新行、3 项）
        assertEquals(3, versions.get(1).get("items").size());

        // ---- 押运节点：跳序拒绝 ----
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"HANDOVER\",\"handler\":\"押运员乙\"}"), "跳序");

        // ---- 封箱：协议进入押运中，贯通写 t_relic_loan(LENT) ----
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"SEAL\",\"handler\":\"押运员甲\",\"remark\":\"囊匣外加木质运输箱\"}"));
        JsonNode inTransit = dataOf(getJson("/api/loan-agreements/" + agreementId));
        assertEquals("IN_TRANSIT", inTransit.get("agreement").get("status").asText());

        // 同节点不能重复登记
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"SEAL\",\"handler\":\"押运员甲\"}"), "重复登记");

        // 在途期间跨业务拦截：修复不能开工、文物不能撤销（封箱贯通借出记录已在途）
        long projectId = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-LX-001\",\"relicId\":" + relicId
                + ",\"client\":\"藏品处\",\"plan\":\"现状评估\"}")).get("id").asLong();
        expectFail(postEmpty("/api/repair-projects/" + projectId + "/start"), "尚在外借");
        expectFail(postJson("/api/relics/" + relicId + "/revoke",
                "{\"reason\":\"业务测试撤销\"}"), "不能撤销");

        // ---- 交接节点异常：不填异常原因拒绝 ----
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"HANDOVER\",\"handler\":\"押运员乙\",\"abnormal\":true}"), "异常原因");
        // 登记异常：后续节点暂停
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes", "{"
                + "\"nodeType\":\"HANDOVER\",\"handler\":\"押运员乙\","
                + "\"abnormal\":true,\"anomalyReason\":\"运输途中急刹，箱内有移位声响\"}"));
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"ARRIVAL\",\"handler\":\"押运员丙\"}"), "未解除异常");

        // 解除异常：后续节点恢复
        long handoverNodeId = findNode(agreementId, "HANDOVER");
        expectFail(postJson("/api/escort-nodes/" + handoverNodeId + "/resolve",
                "{\"resolvedBy\":\"\",\"remark\":\"\"}"), "解除处理人");
        expectOk(postJson("/api/escort-nodes/" + handoverNodeId + "/resolve",
                "{\"resolvedBy\":\"押运员乙\",\"remark\":\"开箱检查固定带松动，重新加固，文物未见损伤\"}"));
        // 已解除不能重复解除
        expectFail(postJson("/api/escort-nodes/" + handoverNodeId + "/resolve",
                "{\"resolvedBy\":\"押运员乙\",\"remark\":\"再次确认\"}"), "已解除");

        // ---- 到馆 ----
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"ARRIVAL\",\"handler\":\"接馆专员丙\",\"remark\":\"文物运抵市博物馆\"}"));

        // ---- 开箱：双人见证 ----
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"OPEN_BOX\",\"handler\":\"保管员丁\"}"), "两名见证人");
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"OPEN_BOX\",\"handler\":\"保管员丁\","
                        + "\"witnessA\":\"保管员丁\",\"witnessB\":\"保管员丁\"}"), "同一人");
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes", "{"
                + "\"nodeType\":\"OPEN_BOX\",\"handler\":\"保管员丁\","
                + "\"witnessA\":\"保管员丁\",\"witnessB\":\"借展方代表戊\","
                + "\"remark\":\"双人在场开箱清点\"}"));

        // ---- 归还验收：取封箱出库版本（v2）的原清单行 ----
        JsonNode afterNodes = dataOf(getJson("/api/loan-agreements/" + agreementId));
        long outboundVersionId = findSealVersionId(afterNodes);
        JsonNode outboundItems = findVersionBundle(afterNodes, outboundVersionId).get("items");
        assertEquals(3, outboundItems.size());
        long line1 = outboundItems.get(0).get("id").asLong();
        long line2 = outboundItems.get(1).get("id").asLong();
        long line3 = outboundItems.get(2).get("id").asLong();

        // 漏项（只交 2 行）拒绝；重复行拒绝；差异行缺说明拒绝
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/return-check", "{"
                + "\"checkNo\":\"RC-LX-001\",\"checkedBy\":\"验收组\","
                + "\"items\":[{\"manifestLineId\":" + line1 + ",\"result\":\"INTACT\"},"
                + "{\"manifestLineId\":" + line2 + ",\"result\":\"INTACT\"}]}"), "逐项比对");
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/return-check", "{"
                + "\"checkNo\":\"RC-LX-001\",\"checkedBy\":\"验收组\","
                + "\"items\":[{\"manifestLineId\":" + line1 + ",\"result\":\"INTACT\"},"
                + "{\"manifestLineId\":" + line1 + ",\"result\":\"INTACT\"},"
                + "{\"manifestLineId\":" + line2 + ",\"result\":\"INTACT\"},"
                + "{\"manifestLineId\":" + line3 + ",\"result\":\"INTACT\"}]}"), "重复同一清单行");
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/return-check", "{"
                + "\"checkNo\":\"RC-LX-001\",\"checkedBy\":\"验收组\","
                + "\"items\":[{\"manifestLineId\":" + line1 + ",\"result\":\"INTACT\"},"
                + "{\"manifestLineId\":" + line2 + ",\"result\":\"DAMAGED\"},"
                + "{\"manifestLineId\":" + line3 + ",\"result\":\"INTACT\"}]}"), "必须填写差异说明");

        // 正式验收：青铜簋相符、锦盒损伤、囊匣缺失 → WITH_DIFF，差异逐行保留
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/return-check", "{"
                + "\"checkNo\":\"RC-LX-001\",\"checkedBy\":\"藏品验收组\","
                + "\"summary\":\"归还现场逐项清点\",\"items\":["
                + "{\"manifestLineId\":" + line1 + ",\"result\":\"INTACT\"},"
                + "{\"manifestLineId\":" + line2 + ",\"result\":\"DAMAGED\",\"diffNote\":\"锦盒边角新增磕裂约 3cm\"},"
                + "{\"manifestLineId\":" + line3 + ",\"result\":\"MISSING\",\"diffNote\":\"囊匣 2 件仅归还 1 件，缺 1 件\"}]}"));

        JsonNode finished = dataOf(getJson("/api/loan-agreements/" + agreementId));
        assertEquals("RETURNED", finished.get("agreement").get("status").asText());
        JsonNode checkBundle = finished.get("returnCheck");
        assertEquals("WITH_DIFF", checkBundle.get("check").get("conclusion").asText());
        JsonNode checkLines = checkBundle.get("lines");
        assertEquals(3, checkLines.size());
        // 差异事实保留在对应行
        String diffNotes = checkLines.toString();
        assertTrue(diffNotes.contains("锦盒边角新增磕裂约 3cm"));
        assertTrue(diffNotes.contains("缺 1 件"));
        // 不能重复验收
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/return-check", "{"
                + "\"checkNo\":\"RC-LX-002\",\"checkedBy\":\"验收组\","
                + "\"items\":[{\"manifestLineId\":" + line1 + ",\"result\":\"INTACT\"},"
                + "{\"manifestLineId\":" + line2 + ",\"result\":\"INTACT\"},"
                + "{\"manifestLineId\":" + line3 + ",\"result\":\"INTACT\"}]}"), "已完成归还验收");
        // 贯通借出记录同步归还
        JsonNode dossier = dataOf(getJson("/api/relics/" + relicId));
        assertEquals("RETURNED", dossier.get("loans").get(0).get("status").asText());
        assertEquals("LA-2026-0001", dossier.get("loans").get(0).get("loanNo").asText());

        // 归还后修复开工不再被外借拦截（仍会被会签未完成拦截，证明占用已释放）
        expectFail(postEmpty("/api/repair-projects/" + projectId + "/start"), "会签");
        // 协议已闭环；立项项目作废后文物可撤销
        expectOk(postJson("/api/repair-projects/" + projectId + "/cancel",
                "{\"reason\":\"借展归还后测试结项作废\"}"));
        expectOk(postJson("/api/relics/" + relicId + "/revoke", "{\"reason\":\"测试结束撤销\"}"));

        // ---- 修复侧反查：原单、处理人、异常原因、差异 ----
        JsonNode trace = dataOf(getJson("/api/loan-agreements/trace?projectId=" + projectId));
        assertEquals("LX-001", trace.get("relicNo").asText());
        JsonNode tracedAgr = trace.get("agreements").get(0);
        assertEquals("LA-2026-0001", tracedAgr.get("agreementNo").asText());
        assertEquals(2, tracedAgr.get("currentVersionNo").asInt());
        assertEquals(0, tracedAgr.get("insuranceAmount").decimalValue()
                .compareTo(new java.math.BigDecimal("800000")));
        assertTrue(tracedAgr.get("transportRoute").asText().contains("市博物馆"));
        JsonNode tracedNodes = tracedAgr.get("escortNodes");
        assertEquals(4, tracedNodes.size());
        JsonNode handover = null;
        for (JsonNode node : tracedNodes) {
            if ("HANDOVER".equals(node.get("nodeType").asText())) {
                handover = node;
            }
            assertNotNull(node.get("handler").asText());
        }
        assertNotNull(handover);
        assertEquals(1, handover.get("abnormal").asInt());
        assertTrue(handover.get("anomalyReason").asText().contains("移位声响"));
        assertEquals("押运员乙", handover.get("handler").asText());
        assertEquals("押运员乙", handover.get("resolvedBy").asText());
        assertTrue(handover.get("resolveRemark").asText().contains("重新加固"));
        // 验收差异（反查只回非相符行）
        assertEquals("WITH_DIFF", tracedAgr.get("returnCheck").get("conclusion").asText());
        assertEquals(2, tracedAgr.get("returnCheck").get("lines").size());

        // ---- 台账动作链完整留痕（作废项目、撤销后重新拉取完整档案） ----
        dossier = dataOf(getJson("/api/relics/" + relicId));
        Set<String> actions = new HashSet<>();
        for (JsonNode entry : dossier.get("ledger")) {
            actions.add(entry.get("action").asText());
        }
        assertTrue(actions.contains("LOAN_AGR_REGISTER"));
        assertTrue(actions.contains("LOAN_AGR_SIGN"));
        assertTrue(actions.contains("LOAN_AGR_AMEND"));
        assertTrue(actions.contains("ESCORT_NODE"));
        assertTrue(actions.contains("ESCORT_ANOMALY"));
        assertTrue(actions.contains("ESCORT_RESUME"));
        assertTrue(actions.contains("LOAN_RETURN_CHECK"));
        // 撤销不抹除：协议关联台账仍在（登记/2 次签署/变更/4 节点/异常/解除/验收/撤销共 12 条）
        assertTrue(dossier.get("ledger").size() >= 12);
    }

    // ------------------------------------------------------------------

    private long findNode(long agreementId, String nodeType) throws Exception {
        JsonNode detail = dataOf(getJson("/api/loan-agreements/" + agreementId));
        for (JsonNode node : detail.get("escortNodes")) {
            if (nodeType.equals(node.get("nodeType").asText())) {
                return node.get("id").asLong();
            }
        }
        throw new IllegalStateException("未找到节点 " + nodeType);
    }

    private long findSealVersionId(JsonNode detail) {
        for (JsonNode node : detail.get("escortNodes")) {
            if ("SEAL".equals(node.get("nodeType").asText())) {
                return node.get("agreementVersionId").asLong();
            }
        }
        throw new IllegalStateException("未找到封箱节点");
    }

    private JsonNode findVersionBundle(JsonNode detail, long versionId) {
        for (JsonNode bundle : detail.get("versions")) {
            if (bundle.get("version").get("id").asLong() == versionId) {
                return bundle;
            }
        }
        throw new IllegalStateException("未找到出库版本 " + versionId);
    }

    private long createRelic(String body) throws Exception {
        return dataOf(postJson("/api/relics", body)).get("id").asLong();
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
