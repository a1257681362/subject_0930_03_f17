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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物鉴定意见（museum-11）验收：
 * 1) 初鉴/复鉴分别固化专家、依据、置信说明与附件；
 * 2) 未发布可由专家本人撤回（留痕不删档），已发布只能追加更正、原文不改写；
 * 3) 两轮结论冲突进入仲裁，绝不以最后一条覆盖；仲裁采初鉴/采复鉴/综合调整定稿；
 * 4) 暂不定论不构成冲突；
 * 5) 普通馆员只见结论摘要；详细报告下载放行/拒绝都写审计流水。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-appraisal;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppraisalOpinionTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void appraisal_draft_withdraw_publish_correction() throws Exception {
        long caseId = openCase("JD-001", "青铜鼎", "ONE");

        // 初鉴未发布前不能提交复鉴
        expectFail(submit(caseId, review("专家乙", "AUTHENTIC", false)), "待复鉴");

        // 初鉴存草稿
        long draftId = opinionId(submit(caseId,
                initial("馆内专家甲", "AUTHENTIC", false, "真", "形制与商代合范工艺吻合")));
        assertEquals("DRAFT", opinionNode(caseId, "INITIAL", 0)
                .get("opinion").get("status").asText());

        // 非专家本人不能撤回/发布；撤回必须填原因
        expectFail(actOpinion(draftId, "/withdraw", "{\"operatorName\":\"别人\"}"), "本人");
        expectFail(actOpinion(draftId, "/withdraw",
                "{\"operatorName\":\"馆内专家甲\"}"), "撤回原因");
        expectOk(actOpinion(draftId, "/withdraw",
                "{\"operatorName\":\"馆内专家甲\",\"reason\":\"样本不足，重拟\"}"));
        assertEquals("WITHDRAWN", opinionNode(caseId, "INITIAL", 0)
                .get("opinion").get("status").asText());
        assertTrue(ledgerHas(caseId, "APPRAISAL_WITHDRAW"), "撤回须留痕");

        // 已撤回不能再发布；第 2 轮重新提交并直接发布
        expectFail(actOpinion(draftId, "/publish",
                "{\"operatorName\":\"馆内专家甲\"}"), "未发布草稿");
        long initialId = opinionId(submit(caseId,
                initial("馆内专家甲", "AUTHENTIC", true, "真", "合范浇铸，锈蚀分层自然")));
        assertEquals("REVIEWING", caseNode(caseId).get("status").asText());
        assertEquals("PUBLISHED", opinionNode(caseId, "INITIAL", 1)
                .get("opinion").get("status").asText());

        // 已发布不能撤回，只能追加更正；非本人不能更正
        expectFail(actOpinion(initialId, "/withdraw",
                "{\"operatorName\":\"馆内专家甲\",\"reason\":\"x\"}"), "不能撤回");
        expectFail(correct(initialId, "专家乙", "补充一点"), "本人");
        expectOk(correct(initialId, "馆内专家甲", "补充：耳部后补铸，主体仍为商代"));
        JsonNode initialRow = opinionNode(caseId, "INITIAL", 1);
        assertEquals("合范浇铸，锈蚀分层自然", initialRow.get("opinion").get("basis").asText(),
                "更正不得改写意见原文");
        assertEquals(1, initialRow.get("corrections").size());
        assertEquals("补充：耳部后补铸，主体仍为商代",
                initialRow.get("corrections").get(0).get("content").asText());
        assertTrue(ledgerHas(caseId, "APPRAISAL_CORRECT"));

        // 复鉴一致：直接定稿，不进仲裁
        expectOk(submit(caseId, review("复鉴专家丙", "AUTHENTIC", true)));
        JsonNode concluded = caseNode(caseId);
        assertEquals("CONCLUDED", concluded.get("status").asText());
        assertEquals("REVIEW", concluded.get("finalStage").asText());
        assertTrue(detail(caseId).get("arbitration").isNull(), "结论一致不应开立仲裁单");
    }

    @Test
    void conflicting_conclusions_go_to_arbitration_not_last_wins() throws Exception {
        long caseId = openCase("JD-002", "陶碗", "TWO");
        expectOk(submit(caseId, initial("初鉴专家甲", "AUTHENTIC", true, "真", "胎釉老化自然")));
        // 复鉴相反结论：进入仲裁，而不是用复鉴覆盖初鉴
        expectOk(submit(caseId, review("复鉴专家乙", "COUNTERFEIT", true, "伪", "做旧痕迹明显")));

        JsonNode arbitrating = caseNode(caseId);
        assertEquals("ARBITRATING", arbitrating.get("status").asText());
        assertTrue(arbitrating.get("finalStage").isNull(), "冲突未定稿前不得有最终结论");
        JsonNode detail = detail(caseId);
        long arbId = detail.get("arbitration").get("arbitration").get("id").asLong();
        assertTrue(detail.get("arbitration").get("arbitration").get("conflictSummary")
                .asText().contains("冲突"));
        // 初鉴与复鉴都原样保留
        assertEquals("AUTHENTIC", opinionNode(caseId, "INITIAL", 0)
                .get("opinion").get("conclusionCode").asText());
        assertEquals("COUNTERFEIT", opinionNode(caseId, "REVIEW", 0)
                .get("opinion").get("conclusionCode").asText());

        // 仲裁期间初鉴/复鉴冻结，不能再提交意见覆盖
        expectFail(submit(caseId, initial("初鉴专家甲", "COUNTERFEIT", true, "伪", "改判")),
                "仲裁");
        expectFail(submit(caseId, review("复鉴专家乙", "AUTHENTIC", true)), "仲裁");

        // 非指定仲裁人不能定稿；采初鉴 ADOPT_INITIAL
        expectFail(conclude(arbId, "路人", "ADOPT_INITIAL", null), "仲裁人");
        expectOk(conclude(arbId, "首席鉴定专家（仲裁人）", "ADOPT_INITIAL", null));

        JsonNode concludedCase = caseNode(caseId);
        assertEquals("CONCLUDED", concludedCase.get("status").asText());
        assertEquals("ARBITRATION", concludedCase.get("finalStage").asText());
        assertTrue(concludedCase.get("conclusionSummary").asText().contains("真"));
        JsonNode arbAfter = detail(caseId).get("arbitration").get("arbitration");
        assertEquals("CONCLUDED", arbAfter.get("status").asText());
        assertEquals("AUTHENTIC", arbAfter.get("finalConclusionCode").asText(),
                "采初鉴：最终结论=初鉴，复鉴原文仍保留不被覆盖");
        assertTrue(ledgerHas(caseId, "APPRAISAL_ARB_OPEN"));
        assertTrue(ledgerHas(caseId, "APPRAISAL_ARB_CONCLUDE"));
    }

    @Test
    void arbitration_adjusted_requires_full_conclusion() throws Exception {
        long caseId = openCase("JD-003", "玉佩", "ONE");
        expectOk(submit(caseId, initial("初鉴专家甲", "AUTHENTIC", true, "真", "工痕古旧")));
        expectOk(submit(caseId, review("复鉴专家乙", "PARTIAL", true, "部分真", "主体老，后加饰")));
        assertEquals("ARBITRATING", caseNode(caseId).get("status").asText());
        long arbId = detail(caseId).get("arbitration").get("arbitration").get("id").asLong();

        // 综合调整必须给出结论码/文本/依据/置信说明
        expectFail(conclude(arbId, "首席鉴定专家（仲裁人）", "ADJUSTED", null),
                "综合调整");
        String adjustedBody = "{\"arbiterName\":\"首席鉴定专家（仲裁人）\","
                + "\"finalDecision\":\"ADJUSTED\","
                + "\"finalConclusionCode\":\"PARTIAL\","
                + "\"finalConclusionText\":\"主体为古玉，纹饰部分为后加\","
                + "\"finalBasis\":\"显微观察+材质比对\","
                + "\"confidenceNote\":\"中：后加纹饰范围尚存争议\","
                + "\"attachments\":[{\"fileName\":\"仲裁显微照片.jpg\","
                + "\"filePath\":\"reports/jd003/micro.jpg\"}]}";
        expectOk(concludeRaw(arbId, adjustedBody));
        assertEquals("CONCLUDED", caseNode(caseId).get("status").asText());
        JsonNode arb = detail(caseId).get("arbitration");
        assertEquals("PARTIAL", arb.get("arbitration").get("finalConclusionCode").asText());
        assertEquals("仲裁显微照片.jpg",
                arb.get("attachments").get(0).get("fileName").asText());
    }

    @Test
    void inconclusive_does_not_conflict() throws Exception {
        long caseId = openCase("JD-004", "漆器", "THREE");
        expectOk(submit(caseId, initial("初鉴专家甲", "INCONCLUSIVE", true, "暂不定论", "样本不足")));
        // 初鉴暂不定论，复鉴给出明确结论：不构成对立冲突，按复鉴定稿
        expectOk(submit(caseId, review("复鉴专家乙", "AUTHENTIC", true, "真", "断代特征明确")));
        assertEquals("CONCLUDED", caseNode(caseId).get("status").asText());
        assertTrue(detail(caseId).get("arbitration").isNull());
    }

    @Test
    void librarian_sees_summary_only_and_downloads_are_audited() throws Exception {
        long caseId = openCase("JD-005", "金簪", "TWO");
        String withAttach = "{\"stage\":\"INITIAL\",\"expertName\":\"初鉴专家甲\","
                + "\"basis\":\"工艺比对与成分检测\",\"confidenceNote\":\"高：多重证据一致\","
                + "\"conclusionCode\":\"AUTHENTIC\",\"conclusionText\":\"明代宫廷工艺\","
                + "\"submittedBy\":\"初鉴专家甲\",\"publish\":true,"
                + "\"attachments\":[{\"fileName\":\"成分报告.pdf\",\"filePath\":\"r/jd005/r.pdf\"}]}";
        expectOk(postJson("/api/appraisal-cases/" + caseId + "/opinions", withAttach));
        expectOk(submit(caseId, review("复鉴专家丙", "AUTHENTIC", true)));

        // 普通馆员摘要：无专家姓名/依据/置信说明/附件
        JsonNode summary = dataOf(getJson("/api/appraisal-cases/" + caseId + "/summary"));
        assertEquals("CONCLUDED", summary.get("status").asText());
        assertTrue(summary.get("conclusionSummary").asText().contains("真"));
        assertFalse(summary.has("basis"));
        assertFalse(summary.has("expertName"));
        assertFalse(summary.has("attachments"));

        // 全量详情含专家、依据、置信说明、附件（专家视角）
        JsonNode detail = detail(caseId);
        JsonNode initOpinion = opinionNode(caseId, "INITIAL", 0).get("opinion");
        assertEquals("工艺比对与成分检测", initOpinion.get("basis").asText());
        assertEquals("高：多重证据一致", initOpinion.get("confidenceNote").asText());
        assertEquals("成分报告.pdf", opinionNode(caseId, "INITIAL", 0)
                .get("attachments").get(0).get("fileName").asText());

        // 普通馆员下载详细报告：拒绝且写 DENIED 审计
        MvcResult denied = postJson("/api/appraisal-cases/" + caseId
                + "/report-download?operatorName=馆员丁&role=LIBRARIAN", "");
        expectFail(denied, "仅可查看结论摘要");
        JsonNode audits1 = dataOf(getJson(
                "/api/appraisal-cases/" + caseId + "/report-audits"));
        assertEquals(1, audits1.size());
        assertEquals("DENIED", audits1.get(0).get("result").asText());
        assertEquals("LIBRARIAN", audits1.get(0).get("operatorRole").asText());

        // 鉴定专家下载：放行且写 GRANTED 审计
        expectOk(postJson("/api/appraisal-cases/" + caseId
                + "/report-download?operatorName=初鉴专家甲&role=APPRAISAL_EXPERT", ""));
        JsonNode audits2 = dataOf(getJson(
                "/api/appraisal-cases/" + caseId + "/report-audits"));
        assertEquals(2, audits2.size(), "放行与拒绝都必须留痕");
        assertEquals("GRANTED", audits2.get(1).get("result").asText());
        assertTrue(ledgerHas(caseId, "APPRAISAL_REPORT_DL"));
        assertTrue(ledgerHas(caseId, "APPRAISAL_REPORT_DENY"), "拒绝下载也要入台账");
    }

    // ------------------------------------------------------------------

    private long openCase(String caseTitle, String relicName, String grade) throws Exception {
        long relicId = dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"R-" + caseTitle + "\",\"name\":\"" + relicName
                + "\",\"category\":\"杂项\",\"material\":\"混合\",\"era\":\"古代\","
                + "\"source\":\"征集\",\"preservationGrade\":\"" + grade + "\"}")).get("id").asLong();
        return dataOf(postJson("/api/appraisal-cases", "{"
                + "\"relicId\":" + relicId + ",\"title\":\"鉴定案-" + caseTitle
                + "\",\"openedBy\":\"登记员\"}")).get("id").asLong();
    }

    private String initial(String expert, String code, boolean publish,
                           String text, String basis) {
        return opinionBody("INITIAL", expert, code, publish, text, basis);
    }

    private String review(String expert, String code, boolean publish) {
        String text;
        switch (code) {
            case "AUTHENTIC": text = "真"; break;
            case "COUNTERFEIT": text = "伪"; break;
            case "PARTIAL": text = "部分真"; break;
            default: text = "暂不定论";
        }
        return review(expert, code, publish, text, "复鉴独立检测依据");
    }

    private String review(String expert, String code, boolean publish,
                          String text, String basis) {
        return opinionBody("REVIEW", expert, code, publish, text, basis);
    }

    private String opinionBody(String stage, String expert, String code, boolean publish,
                               String text, String basis) {
        return "{\"stage\":\"" + stage + "\",\"expertName\":\"" + expert + "\","
                + "\"expertTitle\":\"高级专家\",\"basis\":\"" + basis + "\","
                + "\"confidenceNote\":\"置信说明-" + expert + "\","
                + "\"conclusionCode\":\"" + code + "\",\"conclusionText\":\"" + text + "\","
                + "\"submittedBy\":\"" + expert + "\",\"publish\":" + publish + "}";
    }

    private MvcResult submit(long caseId, String body) throws Exception {
        return postJson("/api/appraisal-cases/" + caseId + "/opinions", body);
    }

    private MvcResult actOpinion(long opinionId, String action, String body) throws Exception {
        return postJson("/api/appraisal-cases/opinions/" + opinionId + action, body);
    }

    private MvcResult correct(long opinionId, String who, String content) throws Exception {
        return postJson("/api/appraisal-cases/opinions/" + opinionId + "/corrections",
                "{\"content\":\"" + content + "\",\"correctedBy\":\"" + who + "\"}");
    }

    private MvcResult conclude(long arbId, String arbiter, String decision, String extra)
            throws Exception {
        String body = "{\"arbiterName\":\"" + arbiter + "\",\"finalDecision\":\"" + decision
                + "\"" + (extra == null ? "" : "," + extra) + "}";
        return concludeRaw(arbId, body);
    }

    private MvcResult concludeRaw(long arbId, String body) throws Exception {
        return postJson("/api/appraisal-cases/arbitrations/" + arbId + "/conclude", body);
    }

    private long opinionId(MvcResult result) throws Exception {
        return dataOf(result).get("id").asLong();
    }

    private JsonNode caseNode(long caseId) throws Exception {
        return dataOf(getJson("/api/appraisal-cases/" + caseId + "/detail")).get("case");
    }

    private JsonNode detail(long caseId) throws Exception {
        return dataOf(getJson("/api/appraisal-cases/" + caseId + "/detail"));
    }

    private JsonNode opinionNode(long caseId, String stage, int idx) throws Exception {
        String field = "INITIAL".equals(stage) ? "initialOpinions" : "reviewOpinions";
        return detail(caseId).get(field).get(idx);
    }

    private boolean ledgerHas(long caseId, String action) throws Exception {
        long relicId = caseNode(caseId).get("relicId").asLong();
        for (JsonNode e : dataOf(getJson("/api/relics/" + relicId + "/ledger"))) {
            if (action.equals(e.get("action").asText())) {
                return true;
            }
        }
        return false;
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
        assertFalse(root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
