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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 归还验收状态转换（加固后）验收：
 * 1) 重复回执：同一验收单号重提命中原验收幂等回放，不二次闭环；
 *    不同单号拒绝重复验收；协议与贯通借出单保持 RETURNED；
 * 2) 并发验收：8 并发不同单号恰 1 生效，其余业务口径拒绝，闭环恰好一次；
 * 3) 并发重复回执：8 并发同一单号全部回放同一验收；
 * 4) 补录差异：只追加差异并把结论更正为 WITH_DIFF，绝不重新打开已完成归还；
 * 5) 外借清单：查询与导出读取同一状态源，归还验收后文物同步退出在借清单。
 * 独立内存库。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-loan-return;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoanReturnCheckConcurrencyTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static final class Outcome {
        boolean success;
        String message;
        JsonNode data;
    }

    // ------------------------------------------------------------------
    // 1) 重复回执幂等回放；外借清单查询/导出同步退出
    // ------------------------------------------------------------------

    @Test
    void duplicate_receipt_replays_and_return_stays_closed() throws Exception {
        long[] ctx = readyForCheck("RT-001", "LA-RT-001");
        long relicId = ctx[0];
        long agreementId = ctx[1];
        long[] lines = manifestLineIds(agreementId);
        String body = checkBody("RC-RT-001", lines, true);

        JsonNode check = dataOf(postJson("/api/loan-agreements/" + agreementId + "/return-check", body));
        long checkId = check.get("id").asLong();
        assertEquals("WITH_DIFF", check.get("conclusion").asText());

        // 重复回执：同一验收单号重提 → 幂等回放同一验收，不二次闭环
        JsonNode replay = dataOf(postJson("/api/loan-agreements/" + agreementId + "/return-check", body));
        assertEquals(checkId, replay.get("id").asLong());
        assertEquals("RC-RT-001", replay.get("checkNo").asText());

        // 不同单号 → 拒绝重复验收
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/return-check",
                checkBody("RC-RT-002", lines, false)), "不能重复验收");

        // 协议与贯通借出单保持闭环；在借清单（查询与导出）都不再出现该文物
        JsonNode detail = dataOf(getJson("/api/loan-agreements/" + agreementId));
        assertEquals("RETURNED", detail.get("agreement").get("status").asText());
        JsonNode dossier = dataOf(getJson("/api/relics/" + relicId));
        assertEquals("RETURNED", dossier.get("loans").get(0).get("status").asText());
        assertEquals("LA-RT-001", dossier.get("loans").get(0).get("loanNo").asText());
        assertEquals(0, dataOf(getJson("/api/loans?status=LENT&relicId=" + relicId)).size());
        String lentCsv = getCsv("/api/loans/export?status=LENT&relicId=" + relicId);
        assertFalse(lentCsv.contains("LA-RT-001"), "在借导出不应含已归还验收的贯通单");
        // 同一状态源：不过滤时查询与导出都含该归还记录
        JsonNode allRows = dataOf(getJson("/api/loans?relicId=" + relicId));
        assertEquals(1, allRows.size());
        assertEquals("RETURNED", allRows.get(0).get("status").asText());
        String allCsv = getCsv("/api/loans/export?relicId=" + relicId);
        assertTrue(allCsv.contains("LA-RT-001"));
        assertTrue(allCsv.contains("RETURNED"));
    }

    // ------------------------------------------------------------------
    // 2) 并发验收：恰 1 生效
    // ------------------------------------------------------------------

    @Test
    void concurrent_return_checks_exactly_one_effective() throws Exception {
        long[] ctx = readyForCheck("RT-101", "LA-RT-101");
        long relicId = ctx[0];
        long agreementId = ctx[1];
        long[] lines = manifestLineIds(agreementId);

        List<Outcome> outcomes = parallelPost(8,
                "/api/loan-agreements/" + agreementId + "/return-check",
                i -> checkBody("RC-C-" + i, lines, false));
        int ok = 0;
        Set<String> rejectMessages = new HashSet<>();
        for (Outcome o : outcomes) {
            if (o.success) {
                ok++;
            } else {
                rejectMessages.add(o.message);
                assertTrue(o.message.contains("不能重复验收"),
                        "落败请求应为业务口径拒绝，实际: " + o.message);
            }
        }
        assertEquals(1, ok, "同一协议并发验收只允许一个生效");
        assertEquals(1, rejectMessages.size(), "落败请求拒绝口径应一致");

        // 闭环恰好一次：协议 RETURNED、贯通借出单 RETURNED、在借清单为空
        JsonNode detail = dataOf(getJson("/api/loan-agreements/" + agreementId));
        assertEquals("RETURNED", detail.get("agreement").get("status").asText());
        assertTrue(detail.get("returnCheck").get("check").get("checkNo").asText().startsWith("RC-C-"));
        JsonNode dossier = dataOf(getJson("/api/relics/" + relicId));
        assertEquals(1, dossier.get("loans").size(), "贯通借出单只能有一条");
        assertEquals("RETURNED", dossier.get("loans").get(0).get("status").asText());
        assertEquals(0, dataOf(getJson("/api/loans?status=LENT&relicId=" + relicId)).size());
    }

    // ------------------------------------------------------------------
    // 3) 并发重复回执：全部回放同一验收
    // ------------------------------------------------------------------

    @Test
    void concurrent_duplicate_receipts_all_replay_same_check() throws Exception {
        long[] ctx = readyForCheck("RT-102", "LA-RT-102");
        long agreementId = ctx[1];
        long[] lines = manifestLineIds(agreementId);

        List<Outcome> outcomes = parallelPost(8,
                "/api/loan-agreements/" + agreementId + "/return-check",
                i -> checkBody("RC-SAME", lines, false));
        Set<Long> checkIds = new HashSet<>();
        for (Outcome o : outcomes) {
            assertTrue(o.success, "同一验收单号的并发回执都应回放成功，实际: " + o.message);
            checkIds.add(o.data.get("id").asLong());
            assertEquals("RC-SAME", o.data.get("checkNo").asText());
        }
        assertEquals(1, checkIds.size(), "并发重复回执只能形成同一条验收记录");
        JsonNode detail = dataOf(getJson("/api/loan-agreements/" + agreementId));
        assertEquals("RETURNED", detail.get("agreement").get("status").asText());
        assertEquals("ALL_MATCH", detail.get("returnCheck").get("check").get("conclusion").asText());
    }

    // ------------------------------------------------------------------
    // 4) 补录差异：只追加，不重新打开已完成归还
    // ------------------------------------------------------------------

    @Test
    void supplement_diff_appends_without_reopening_return() throws Exception {
        long[] ctx = readyForCheck("RT-201", "LA-RT-201");
        long relicId = ctx[0];
        long agreementId = ctx[1];
        long[] lines = manifestLineIds(agreementId);

        // 未验收协议不能补录（该协议保持押运中，补录被拦截）
        long[] pending = readyForCheck("RT-202", "LA-RT-202");
        long[] pendingLines = manifestLineIds(pending[1]);
        expectFail(postJson("/api/loan-agreements/" + pending[1] + "/return-check/supplements",
                suppBody(pendingLines[0], "DAMAGED", "复查发现磕痕", "保管员己")), "不能补录差异");

        // 全部相符完成验收（结论 ALL_MATCH）
        JsonNode check = dataOf(postJson("/api/loan-agreements/" + agreementId + "/return-check",
                checkBody("RC-RT-201", lines, false)));
        assertEquals("ALL_MATCH", check.get("conclusion").asText());

        // 补录差异：结论更正为 WITH_DIFF，差异只追加
        JsonNode supp = dataOf(postJson("/api/loan-agreements/" + agreementId
                        + "/return-check/supplements",
                suppBody(lines[0], "DAMAGED", "复查发现口沿新增冲线约2cm", "保管员己")));
        assertEquals("DAMAGED", supp.get("result").asText());
        assertEquals("I-1", supp.get("itemNo").asText());

        JsonNode detail = dataOf(getJson("/api/loan-agreements/" + agreementId));
        assertEquals("WITH_DIFF", detail.get("returnCheck").get("check").get("conclusion").asText());
        assertEquals(1, detail.get("returnCheck").get("supplements").size());
        // 原比对行不改写（仍全部 INTACT）
        JsonNode checkLines = detail.get("returnCheck").get("lines");
        assertEquals(2, checkLines.size());
        for (JsonNode line : checkLines) {
            assertEquals("INTACT", line.get("result").asText());
        }

        // 不重新打开已完成归还：协议保持 RETURNED、贯通借出单保持 RETURNED、在借清单仍为空
        assertEquals("RETURNED", detail.get("agreement").get("status").asText());
        JsonNode dossier = dataOf(getJson("/api/relics/" + relicId));
        assertEquals("RETURNED", dossier.get("loans").get(0).get("status").asText());
        assertEquals(0, dataOf(getJson("/api/loans?status=LENT&relicId=" + relicId)).size());
        assertFalse(getCsv("/api/loans/export?status=LENT&relicId=" + relicId).contains("LA-RT-201"));

        // 完全相同补录拒绝；INTACT 拒绝；未知清单行拒绝
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/return-check/supplements",
                suppBody(lines[0], "DAMAGED", "复查发现口沿新增冲线约2cm", "保管员己")), "不能重复补录");
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/return-check/supplements",
                suppBody(lines[0], "INTACT", "误操作", "保管员己")), "DAMAGED/MISSING");
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/return-check/supplements",
                suppBody(999999L, "MISSING", "不存在的行", "保管员己")), "不属于原验收比对清单");

        // 再补一条不同差异（另一行、另一类型）允许，台账留痕
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/return-check/supplements",
                suppBody(lines[1], "MISSING", "囊匣复查短少 1 件", "保管员己")));
        detail = dataOf(getJson("/api/loan-agreements/" + agreementId));
        assertEquals(2, detail.get("returnCheck").get("supplements").size());
        assertEquals("RETURNED", detail.get("agreement").get("status").asText());

        // 修复侧反查可见补录差异
        long projectId = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-RT-201\",\"relicId\":" + relicId
                + ",\"client\":\"藏品处\",\"plan\":\"归还后复查\"}")).get("id").asLong();
        JsonNode trace = dataOf(getJson("/api/loan-agreements/trace?projectId=" + projectId));
        JsonNode tracedCheck = trace.get("agreements").get(0).get("returnCheck");
        assertEquals("WITH_DIFF", tracedCheck.get("conclusion").asText());
        assertEquals(2, tracedCheck.get("supplements").size());

        // 台账含补录动作
        Set<String> actions = new HashSet<>();
        for (JsonNode entry : dataOf(getJson("/api/relics/" + relicId + "/ledger"))) {
            actions.add(entry.get("action").asText());
        }
        assertTrue(actions.contains("LOAN_RETURN_CHECK"));
        assertTrue(actions.contains("LOAN_RETURN_CHECK_SUPP"));
    }

    // ------------------------------------------------------------------
    // 4b) 已完成归还不能被重新打开：闭环后全部变更入口拒绝，唯补录可追加
    // ------------------------------------------------------------------

    @Test
    void closed_return_rejects_all_mutations_except_supplement() throws Exception {
        long[] ctx = readyForCheck("RT-211", "LA-RT-211");
        long relicId = ctx[0];
        long agreementId = ctx[1];
        long[] lines = manifestLineIds(agreementId);
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/return-check",
                checkBody("RC-RT-211", lines, false)));

        // 签署/变更/押运节点/重复验收一律拒绝（协议已闭环）
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/sign",
                "{\"signedBy\":\"馆方代表\"}"), "已闭环");
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/amend",
                "{\"changeReason\":\"闭环后试图变更\"}"), "已闭环");
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"SEAL\",\"handler\":\"押运甲\"}"), "已闭环");
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/return-check",
                checkBody("RC-RT-211-B", lines, false)), "不能重复验收");

        // 补录差异是闭环后唯一允许的追加，且协议/贯通借出单保持 RETURNED
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/return-check/supplements",
                suppBody(lines[0], "DAMAGED", "复查发现耳部裂纹", "保管员己")));
        JsonNode detail = dataOf(getJson("/api/loan-agreements/" + agreementId));
        assertEquals("RETURNED", detail.get("agreement").get("status").asText());
        assertEquals("WITH_DIFF", detail.get("returnCheck").get("check").get("conclusion").asText());
        JsonNode dossier = dataOf(getJson("/api/relics/" + relicId));
        assertEquals("RETURNED", dossier.get("loans").get(0).get("status").asText());
        assertEquals(0, dataOf(getJson("/api/loans?status=LENT&relicId=" + relicId)).size());
    }

    // ------------------------------------------------------------------
    // 5) 外借清单：查询与导出同一状态源
    // ------------------------------------------------------------------

    @Test
    void loan_list_and_export_read_same_state_source() throws Exception {
        // relicA：旧通道借出在途（在借清单成员）
        long relicA = dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"RT-301\",\"name\":\"在借测试陶罐\",\"category\":\"陶器\","
                + "\"material\":\"陶\",\"era\":\"汉\",\"source\":\"征集\","
                + "\"preservationGrade\":\"THREE\"}")).get("id").asLong();
        expectOk(postJson("/api/loans", "{"
                + "\"loanNo\":\"LO-RT-301\",\"relicId\":" + relicA
                + ",\"borrower\":\"巡展单位\",\"purpose\":\"外展\"}"));
        // relicB：借展协议已归还验收（不在在借清单）
        long[] ctxB = readyForCheck("RT-302", "LA-RT-302");
        long[] linesB = manifestLineIds(ctxB[1]);
        expectOk(postJson("/api/loan-agreements/" + ctxB[1] + "/return-check",
                checkBody("RC-RT-302", linesB, false)));

        // 在借清单：查询与导出口径一致——含在借单、不含已归还贯通单
        Set<String> lentNos = new HashSet<>();
        for (JsonNode row : dataOf(getJson("/api/loans?status=LENT"))) {
            assertEquals("LENT", row.get("status").asText());
            lentNos.add(row.get("loanNo").asText());
        }
        assertTrue(lentNos.contains("LO-RT-301"));
        assertFalse(lentNos.contains("LA-RT-302"));
        String lentCsv = getCsv("/api/loans/export?status=LENT");
        assertTrue(lentCsv.contains("LO-RT-301"));
        assertFalse(lentCsv.contains("LA-RT-302"));

        // 全量口径：查询与导出都同时含在借与已归还记录
        Set<String> allNos = new HashSet<>();
        for (JsonNode row : dataOf(getJson("/api/loans"))) {
            allNos.add(row.get("loanNo").asText());
        }
        assertTrue(allNos.contains("LO-RT-301"));
        assertTrue(allNos.contains("LA-RT-302"));
        String allCsv = getCsv("/api/loans/export");
        assertTrue(allCsv.contains("LO-RT-301"));
        assertTrue(allCsv.contains("LA-RT-302"));

        // 按文物过滤：relicB 清单为已归还，查询与导出一致
        JsonNode rowsB = dataOf(getJson("/api/loans?relicId=" + ctxB[0]));
        assertEquals(1, rowsB.size());
        assertEquals("RETURNED", rowsB.get(0).get("status").asText());
        String csvB = getCsv("/api/loans/export?relicId=" + ctxB[0]);
        assertTrue(csvB.contains("LA-RT-302"));
        assertFalse(csvB.contains("LO-RT-301"));
        // 在借过滤下 relicB 查询与导出同为空
        assertEquals(0, dataOf(getJson("/api/loans?status=LENT&relicId=" + ctxB[0])).size());
        assertFalse(getCsv("/api/loans/export?status=LENT&relicId=" + ctxB[0]).contains("LA-RT-302"));
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    /** 建档 -> 协议登记 -> 签署 -> 四节点齐备，待归还验收 */
    private long[] readyForCheck(String relicNo, String agreementNo) throws Exception {
        long relicId = dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + relicNo + "\",\"name\":\"" + relicNo + "-借展器\","
                + "\"category\":\"青铜器\",\"material\":\"青铜\",\"era\":\"西周\","
                + "\"source\":\"考古发掘\",\"preservationGrade\":\"TWO\"}")).get("id").asLong();
        long agreementId = dataOf(postJson("/api/loan-agreements", "{"
                + "\"agreementNo\":\"" + agreementNo + "\",\"relicId\":" + relicId
                + ",\"borrower\":\"市博物馆\",\"insuranceAmount\":300000,"
                + "\"transportRoute\":\"库房-专线-展厅\",\"returnDate\":\"2026-12-31\","
                + "\"items\":["
                + "{\"itemNo\":\"I-1\",\"itemName\":\"本体\",\"qty\":1,\"outCondition\":\"完好\"},"
                + "{\"itemNo\":\"I-2\",\"itemName\":\"囊匣\",\"qty\":1,\"outCondition\":\"完好\"}]}"))
                .get("id").asLong();
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/sign",
                "{\"signedBy\":\"馆方代表\"}"));
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"SEAL\",\"handler\":\"押运甲\"}"));
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"HANDOVER\",\"handler\":\"押运乙\"}"));
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"ARRIVAL\",\"handler\":\"接馆丙\"}"));
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"OPEN_BOX\",\"handler\":\"保管丁\","
                        + "\"witnessA\":\"保管丁\",\"witnessB\":\"借展方戊\"}"));
        return new long[]{relicId, agreementId};
    }

    /** 封箱出库版本的原借展清单行 id（按清单顺序） */
    private long[] manifestLineIds(long agreementId) throws Exception {
        JsonNode detail = dataOf(getJson("/api/loan-agreements/" + agreementId));
        long sealVersionId = -1;
        for (JsonNode node : detail.get("escortNodes")) {
            if ("SEAL".equals(node.get("nodeType").asText())) {
                sealVersionId = node.get("agreementVersionId").asLong();
            }
        }
        assertNotEquals(-1, sealVersionId, "应存在封箱节点");
        for (JsonNode bundle : detail.get("versions")) {
            if (bundle.get("version").get("id").asLong() == sealVersionId) {
                JsonNode items = bundle.get("items");
                long[] ids = new long[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    ids[i] = items.get(i).get("id").asLong();
                }
                return ids;
            }
        }
        throw new IllegalStateException("未找到封箱版本清单");
    }

    /** 验收请求体：withDiff 时最后一行判 DAMAGED 并带差异说明 */
    private String checkBody(String checkNo, long[] lineIds, boolean withDiff) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"checkNo\":\"").append(checkNo)
                .append("\",\"checkedBy\":\"藏品验收组\",\"items\":[");
        for (int i = 0; i < lineIds.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            boolean damaged = withDiff && i == lineIds.length - 1;
            sb.append("{\"manifestLineId\":").append(lineIds[i])
                    .append(",\"result\":\"").append(damaged ? "DAMAGED" : "INTACT").append("\"");
            if (damaged) {
                sb.append(",\"diffNote\":\"囊匣边角新增磕痕\"");
            }
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private String suppBody(long manifestLineId, String result, String diffNote, String by) {
        return "{\"manifestLineId\":" + manifestLineId + ",\"result\":\"" + result
                + "\",\"diffNote\":\"" + diffNote + "\",\"supplementedBy\":\"" + by + "\"}";
    }

    /** 通用并发 POST：所有线程同一 URI，各自生成 body，同时放行 */
    private List<Outcome> parallelPost(int threads, String uri,
                                       Function<Integer, String> bodyBuilder) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return toOutcome(mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyBuilder.apply(idx)))
                        .andExpect(status().isOk()).andReturn());
            }));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private Outcome toOutcome(MvcResult result) throws Exception {
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

    private String getCsv(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
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
