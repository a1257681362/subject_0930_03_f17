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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 归还验收状态转换回归（bug：文物完成归还验收后仍出现在外借清单）：
 * 1) 贯通借出单不能走旧通道归还，只能由归还验收闭环；
 * 2) 验收闭环后外借清单（LENT）不再列出该文物，清单与 CSV 导出读同一状态源；
 * 3) 重复回执（同一验收单号重发）幂等回放同一验收单，不重复执行；
 * 4) 并发验收只有一单生效，其余业务拒绝（不是数据库约束报错）；
 * 5) 差异补录只追加差异行，协议与贯通单保持 RETURNED，不重新打开已完成归还。
 * 独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-loan-return;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoanReturnCheckStateTest {

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
    // 1. 主 bug：贯通单旧通道归还被拦截；验收后外借清单不再列出；查询/导出同源
    // ------------------------------------------------------------------

    @Test
    void linking_loan_closed_only_by_return_check_and_list_reads_same_source() throws Exception {
        long relicId = createRelic("LS-001", "状态源青铜爵");
        long agreementId = registerAgreement("LA-ST-001", relicId, 2);
        sign(agreementId);
        seal(agreementId);

        // 封箱后：贯通单在借，外借清单（LENT）列出该文物
        JsonNode lentRows = dataOf(getJson("/api/loans?relicId=" + relicId + "&status=LENT"));
        assertEquals(1, lentRows.size());
        assertEquals("LA-ST-001", lentRows.get(0).get("loanNo").asText());
        long linkingLoanId = lentRows.get(0).get("loanId").asLong();

        // 旧通道归还贯通单 → 拒绝（必须走归还验收闭环）
        expectFail(postJson("/api/loans/" + linkingLoanId + "/return",
                "{\"returnCondition\":\"完好\",\"receiver\":\"库管员\"}"), "贯通单");
        // 拦截后贯通单仍在借
        assertEquals(1, dataOf(getJson("/api/loans?relicId=" + relicId + "&status=LENT")).size());

        // 旧通道自建单不受影响（回归）：另一文物旧通道借还正常
        long relic2 = createRelic("LS-002", "旧通道陶罐");
        long oldLoan = dataOf(postJson("/api/loans",
                "{\"loanNo\":\"LO-OLD-9\",\"relicId\":" + relic2 + ",\"borrower\":\"邻馆\"}"))
                .get("id").asLong();
        expectOk(postJson("/api/loans/" + oldLoan + "/return",
                "{\"returnCondition\":\"完好\",\"receiver\":\"库管员\"}"));

        // 完成押运四节点并归还验收（全部相符）
        handoverArrivalOpenBox(agreementId);
        JsonNode detail = dataOf(getJson("/api/loan-agreements/" + agreementId));
        long[] lineIds = manifestLineIds(detail);
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/return-check", "{"
                + "\"checkNo\":\"RC-ST-001\",\"checkedBy\":\"验收组\","
                + "\"items\":[{\"manifestLineId\":" + lineIds[0] + ",\"result\":\"INTACT\"},"
                + "{\"manifestLineId\":" + lineIds[1] + ",\"result\":\"INTACT\"}]}"));

        // 验收闭环后：外借清单（LENT）不再列出该文物
        assertEquals(0, dataOf(getJson("/api/loans?relicId=" + relicId + "&status=LENT")).size());
        JsonNode returnedRows = dataOf(getJson("/api/loans?relicId=" + relicId + "&status=RETURNED"));
        assertEquals(1, returnedRows.size());
        assertEquals("LA-ST-001", returnedRows.get(0).get("loanNo").asText());
        assertEquals("归还验收全部相符", returnedRows.get(0).get("returnCondition").asText());

        // 查询与导出同一状态源：行集一致、顺序一致
        String csv = getCsv("/api/loans/export?relicId=" + relicId);
        String[] csvLines = csv.split("\n");
        assertEquals(2, csvLines.length, "导出应只有表头 + 1 行数据");
        assertTrue(csvLines[0].contains("借出单号"));
        assertTrue(csvLines[1].contains("LA-ST-001"));
        assertTrue(csvLines[1].contains("RETURNED"));
        JsonNode allRows = dataOf(getJson("/api/loans?relicId=" + relicId));
        assertEquals(allRows.size(), csvLines.length - 1, "查询与导出行集必须一致");
        for (int i = 0; i < allRows.size(); i++) {
            assertTrue(csvLines[i + 1].contains(allRows.get(i).get("loanNo").asText()),
                    "导出行与查询行同一状态源同一顺序");
        }
        // 未知状态过滤给出明确错误
        expectFail(getJson("/api/loans?status=UNKNOWN"), "未知借出状态");
    }

    // ------------------------------------------------------------------
    // 2. 重复回执：同一验收单号重发幂等回放
    // ------------------------------------------------------------------

    @Test
    void duplicate_receipt_replays_same_check_without_reexecution() throws Exception {
        long relicId = createRelic("LS-101", "重复回执玉璧");
        long agreementId = registerAgreement("LA-DUP-001", relicId, 2);
        sign(agreementId);
        seal(agreementId);
        handoverArrivalOpenBox(agreementId);
        long[] lineIds = manifestLineIds(dataOf(getJson("/api/loan-agreements/" + agreementId)));

        String body = "{"
                + "\"checkNo\":\"RC-DUP-1\",\"checkedBy\":\"验收组\","
                + "\"items\":[{\"manifestLineId\":" + lineIds[0] + ",\"result\":\"INTACT\"},"
                + "{\"manifestLineId\":" + lineIds[1] + ",\"result\":\"DAMAGED\","
                + "\"diffNote\":\"盒体磕伤\"}]}";
        JsonNode first = dataOf(postJson("/api/loan-agreements/" + agreementId + "/return-check", body));
        // 同一验收单号重发（重复回执）：回放同一验收单，不重复执行
        JsonNode replay = dataOf(postJson("/api/loan-agreements/" + agreementId + "/return-check", body));
        assertEquals(first.get("id").asLong(), replay.get("id").asLong());
        assertEquals("WITH_DIFF", replay.get("conclusion").asText());

        // 不新增验收行、不重复写台账
        JsonNode detail = dataOf(getJson("/api/loan-agreements/" + agreementId));
        assertEquals(2, detail.get("returnCheck").get("lines").size());
        int checkLedger = 0;
        for (JsonNode e : dataOf(getJson("/api/relics/" + relicId + "/ledger"))) {
            if ("LOAN_RETURN_CHECK".equals(e.get("action").asText())) {
                checkLedger++;
            }
        }
        assertEquals(1, checkLedger, "重复回执不得重复写验收台账");

        // 不同单号才是真正的重复验收
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/return-check", "{"
                + "\"checkNo\":\"RC-DUP-2\",\"checkedBy\":\"验收组\","
                + "\"items\":[{\"manifestLineId\":" + lineIds[0] + ",\"result\":\"INTACT\"},"
                + "{\"manifestLineId\":" + lineIds[1] + ",\"result\":\"INTACT\"}]}"),
                "已完成归还验收");
    }

    // ------------------------------------------------------------------
    // 3. 并发验收：不同单号只有一单生效；同一单号全部幂等回放
    // ------------------------------------------------------------------

    @Test
    void concurrent_return_checks_single_effective_others_clean_reject() throws Exception {
        // ---- 8 并发不同验收单号：恰 1 单生效，其余业务拒绝 ----
        long relicId = createRelic("LS-201", "并发验收铜镜");
        long agreementId = registerAgreement("LA-CC-001", relicId, 2);
        sign(agreementId);
        seal(agreementId);
        handoverArrivalOpenBox(agreementId);
        long[] lineIds = manifestLineIds(dataOf(getJson("/api/loan-agreements/" + agreementId)));

        List<Outcome> outcomes = parallelPost(8,
                "/api/loan-agreements/" + agreementId + "/return-check",
                i -> "{\"checkNo\":\"RC-CC-" + i + "\",\"checkedBy\":\"验收组" + i + "\","
                        + "\"items\":[{\"manifestLineId\":" + lineIds[0] + ",\"result\":\"INTACT\"},"
                        + "{\"manifestLineId\":" + lineIds[1] + ",\"result\":\"INTACT\"}]}");
        int ok = 0;
        for (Outcome o : outcomes) {
            if (o.success) {
                ok++;
            } else {
                assertTrue(o.message.contains("已完成归还验收"),
                        "落败验收应为业务拒绝而非约束报错，实际: " + o.message);
            }
        }
        assertEquals(1, ok, "并发验收只允许一单生效");
        JsonNode detail = dataOf(getJson("/api/loan-agreements/" + agreementId));
        assertEquals("RETURNED", detail.get("agreement").get("status").asText());
        assertEquals(2, detail.get("returnCheck").get("lines").size(), "只应有一单的比对行");
        // 贯通单同步归还，外借清单清空
        assertEquals(0, dataOf(getJson("/api/loans?relicId=" + relicId + "&status=LENT")).size());

        // ---- 8 并发同一验收单号：全部幂等回放同一验收单 ----
        long relic2 = createRelic("LS-202", "并发回执陶俑");
        long agreement2 = registerAgreement("LA-CC-002", relic2, 2);
        sign(agreement2);
        seal(agreement2);
        handoverArrivalOpenBox(agreement2);
        long[] lineIds2 = manifestLineIds(dataOf(getJson("/api/loan-agreements/" + agreement2)));

        List<Outcome> sameNo = parallelPost(8,
                "/api/loan-agreements/" + agreement2 + "/return-check",
                i -> "{\"checkNo\":\"RC-CC-SAME\",\"checkedBy\":\"验收组\","
                        + "\"items\":[{\"manifestLineId\":" + lineIds2[0] + ",\"result\":\"INTACT\"},"
                        + "{\"manifestLineId\":" + lineIds2[1] + ",\"result\":\"INTACT\"}]}");
        Set<Long> checkIds = new HashSet<>();
        for (Outcome o : sameNo) {
            assertTrue(o.success, "同一单号并发应全部幂等回放，实际: " + o.message);
            checkIds.add(o.data.get("id").asLong());
        }
        assertEquals(1, checkIds.size(), "同一单号只能形成一条验收单");
        JsonNode detail2 = dataOf(getJson("/api/loan-agreements/" + agreement2));
        assertEquals(2, detail2.get("returnCheck").get("lines").size(), "回放不得重复写比对行");
    }

    // ------------------------------------------------------------------
    // 4. 差异补录：只追加，不重新打开已完成归还
    // ------------------------------------------------------------------

    @Test
    void diff_supplement_appends_without_reopening_completed_return() throws Exception {
        long relicId = createRelic("LS-301", "补录差异青花盘");
        long agreementId = registerAgreement("LA-SP-001", relicId, 2);
        sign(agreementId);
        seal(agreementId);
        handoverArrivalOpenBox(agreementId);
        long[] lineIds = manifestLineIds(dataOf(getJson("/api/loan-agreements/" + agreementId)));

        // 未验收的协议不能补录（另起一协议验证）
        long relic9 = createRelic("LS-309", "未验收补录瓶");
        long pendingAgreement = registerAgreement("LA-SP-009", relic9, 1);
        expectFail(postJson("/api/loan-agreements/" + pendingAgreement
                        + "/return-check/diff-supplements", "{"
                        + "\"supplementNo\":\"SP-X\",\"supplementedBy\":\"保管员\","
                        + "\"manifestLineId\":1,\"result\":\"DAMAGED\",\"diffNote\":\"x\"}"),
                "尚未完成归还验收");

        // 全部相符验收闭环
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/return-check", "{"
                + "\"checkNo\":\"RC-SP-001\",\"checkedBy\":\"验收组\","
                + "\"items\":[{\"manifestLineId\":" + lineIds[0] + ",\"result\":\"INTACT\"},"
                + "{\"manifestLineId\":" + lineIds[1] + ",\"result\":\"INTACT\"}]}"));
        JsonNode closed = dataOf(getJson("/api/loan-agreements/" + agreementId));
        assertEquals("ALL_MATCH", closed.get("returnCheck").get("check").get("conclusion").asText());

        // 补录参数校验：结果只能 DAMAGED/MISSING、清单行必须属于原借展清单
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/return-check/diff-supplements",
                "{\"supplementNo\":\"SP-1\",\"supplementedBy\":\"保管员丁\","
                        + "\"manifestLineId\":" + lineIds[0] + ",\"result\":\"INTACT\",\"diffNote\":\"x\"}"),
                "DAMAGED/MISSING");
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/return-check/diff-supplements",
                "{\"supplementNo\":\"SP-1\",\"supplementedBy\":\"保管员丁\","
                        + "\"manifestLineId\":999999,\"result\":\"DAMAGED\",\"diffNote\":\"x\"}"),
                "不属于原借展清单");

        // 正式补录：锦盒归还后发现新增损伤
        JsonNode bundle = dataOf(postJson("/api/loan-agreements/" + agreementId
                + "/return-check/diff-supplements", "{"
                + "\"supplementNo\":\"SP-1\",\"supplementedBy\":\"保管员丁\","
                + "\"manifestLineId\":" + lineIds[1] + ",\"result\":\"DAMAGED\","
                + "\"diffNote\":\"入库复检发现锦盒底部新增裂纹 2cm\"}"));
        assertEquals("WITH_DIFF", bundle.get("check").get("conclusion").asText());
        assertEquals(1, bundle.get("supplementLine").get("lineSeq").asInt());
        assertEquals("SP-1", bundle.get("supplementLine").get("supplementNo").asText());

        // 不重新打开已完成归还：协议/贯通单保持 RETURNED，外借清单仍为空
        JsonNode after = dataOf(getJson("/api/loan-agreements/" + agreementId));
        assertEquals("RETURNED", after.get("agreement").get("status").asText());
        assertEquals("WITH_DIFF", after.get("returnCheck").get("check").get("conclusion").asText());
        assertEquals(3, after.get("returnCheck").get("lines").size(), "补录只追加一行");
        assertEquals(0, dataOf(getJson("/api/loans?relicId=" + relicId + "&status=LENT")).size());
        JsonNode returnedRows = dataOf(getJson("/api/loans?relicId=" + relicId + "&status=RETURNED"));
        assertEquals(1, returnedRows.size());
        assertEquals("归还验收存在差异", returnedRows.get(0).get("returnCondition").asText(),
                "补录后贯通单品相与验收结论同一状态源");

        // 补录单号唯一
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/return-check/diff-supplements",
                "{\"supplementNo\":\"SP-1\",\"supplementedBy\":\"保管员丁\","
                        + "\"manifestLineId\":" + lineIds[0] + ",\"result\":\"MISSING\",\"diffNote\":\"y\"}"),
                "补录单号已存在");

        // 同一清单行可再次补录（只追加，行序递增）
        JsonNode bundle2 = dataOf(postJson("/api/loan-agreements/" + agreementId
                + "/return-check/diff-supplements", "{"
                + "\"supplementNo\":\"SP-2\",\"supplementedBy\":\"保管员丁\","
                + "\"manifestLineId\":" + lineIds[1] + ",\"result\":\"DAMAGED\","
                + "\"diffNote\":\"复检又发现边角脱落\"}"));
        assertEquals(2, bundle2.get("supplementLine").get("lineSeq").asInt());

        // 补录后已完成归仍不能被新验收打开；原单号重发仍回放
        expectFail(postJson("/api/loan-agreements/" + agreementId + "/return-check", "{"
                + "\"checkNo\":\"RC-SP-NEW\",\"checkedBy\":\"验收组\","
                + "\"items\":[{\"manifestLineId\":" + lineIds[0] + ",\"result\":\"INTACT\"},"
                + "{\"manifestLineId\":" + lineIds[1] + ",\"result\":\"INTACT\"}]}"),
                "已完成归还验收");
        JsonNode replay = dataOf(postJson("/api/loan-agreements/" + agreementId + "/return-check", "{"
                + "\"checkNo\":\"RC-SP-001\",\"checkedBy\":\"验收组\","
                + "\"items\":[{\"manifestLineId\":" + lineIds[0] + ",\"result\":\"INTACT\"},"
                + "{\"manifestLineId\":" + lineIds[1] + ",\"result\":\"INTACT\"}]}"));
        assertEquals("WITH_DIFF", replay.get("conclusion").asText(), "回放应读到补录后的最新结论");

        // 台账留痕：验收一次 + 补录两次
        int checkLedger = 0;
        int supplementLedger = 0;
        for (JsonNode e : dataOf(getJson("/api/relics/" + relicId + "/ledger"))) {
            if ("LOAN_RETURN_CHECK".equals(e.get("action").asText())) {
                checkLedger++;
            }
            if ("LOAN_RETURN_SUPPLEMENT".equals(e.get("action").asText())) {
                supplementLedger++;
            }
        }
        assertEquals(1, checkLedger);
        assertEquals(2, supplementLedger);

        // 修复侧反查同一状态源：差异行含补录事实
        long projectId = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"RP-LS-301\",\"relicId\":" + relicId
                + ",\"client\":\"藏品处\",\"plan\":\"裂纹修复\"}")).get("id").asLong();
        JsonNode trace = dataOf(getJson("/api/loan-agreements/trace?projectId=" + projectId));
        JsonNode tracedCheck = trace.get("agreements").get(0).get("returnCheck");
        assertEquals("WITH_DIFF", tracedCheck.get("conclusion").asText());
        assertEquals(2, tracedCheck.get("lines").size(), "反查差异行应含两条补录");
    }

    // ------------------------------------------------------------------
    // 5. 借阅审批贯通单同样不能走旧通道归还
    // ------------------------------------------------------------------

    @Test
    void borrow_linking_loan_cannot_be_returned_via_old_channel() throws Exception {
        long relicId = createRelic("LS-401", "借阅贯通陶碗");
        long appId = dataOf(postJson("/api/relics/" + relicId + "/borrow-applications", "{"
                + "\"applicant\":\"宣教部\","
                + "\"borrower\":\"社区学校\",\"borrowScene\":\"PUBLIC_EDUCATION\","
                + "\"planLentAt\":\"2026-11-01T09:00:00\",\"expectedReturnAt\":\"2026-11-20T09:00:00\","
                + "\"purpose\":\"课堂展示\",\"insuranceAmount\":5000}")).get("id").asLong();
        expectOk(postJson("/api/borrow-applications/" + appId + "/approvals",
                "{\"approverRole\":\"BORROW_DEPT\",\"approverName\":\"馆藏部张三\","
                        + "\"decision\":\"APPROVE\"}"));
        expectOk(postJson("/api/borrow-applications/" + appId + "/lend",
                "{\"lentBy\":\"库房交接员\"}"));

        JsonNode lentRows = dataOf(getJson("/api/loans?relicId=" + relicId + "&status=LENT"));
        assertEquals(1, lentRows.size());
        long linkingLoanId = lentRows.get(0).get("loanId").asLong();
        // 旧通道归还借阅贯通单 → 拒绝
        expectFail(postJson("/api/loans/" + linkingLoanId + "/return",
                "{\"returnCondition\":\"完好\",\"receiver\":\"库管员\"}"), "贯通单");
        // 借阅归还闭环后，外借清单不再列出
        expectOk(postJson("/api/borrow-applications/" + appId + "/return",
                "{\"returnedBy\":\"库管员\",\"returnCondition\":\"完好\"}"));
        assertEquals(0, dataOf(getJson("/api/loans?relicId=" + relicId + "&status=LENT")).size());
    }

    // ------------------------------------------------------------------
    // 流程辅助
    // ------------------------------------------------------------------

    private long createRelic(String relicNo, String name) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + relicNo + "\",\"name\":\"" + name + "\",\"category\":\"青铜器\","
                + "\"material\":\"青铜\",\"era\":\"西周\",\"source\":\"考古发掘\","
                + "\"preservationGrade\":\"THREE\"}")).get("id").asLong();
    }

    private long registerAgreement(String agreementNo, long relicId, int itemCount) throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 1; i <= itemCount; i++) {
            if (i > 1) {
                items.append(',');
            }
            items.append("{\"itemNo\":\"ITEM-").append(i).append("\",\"itemName\":\"条目").append(i)
                    .append("\",\"qty\":1,\"outCondition\":\"完好\"}");
        }
        return dataOf(postJson("/api/loan-agreements", "{"
                + "\"agreementNo\":\"" + agreementNo + "\",\"relicId\":" + relicId
                + ",\"borrower\":\"市博物馆\",\"insuranceAmount\":100000,"
                + "\"transportRoute\":\"库房-专线-展厅\",\"returnDate\":\"2026-12-31\","
                + "\"items\":[" + items + "]}")).get("id").asLong();
    }

    private void sign(long agreementId) throws Exception {
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/sign",
                "{\"signedBy\":\"馆方代表\"}"));
    }

    private void seal(long agreementId) throws Exception {
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"SEAL\",\"handler\":\"押运员甲\"}"));
    }

    private void handoverArrivalOpenBox(long agreementId) throws Exception {
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"HANDOVER\",\"handler\":\"押运员乙\"}"));
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"ARRIVAL\",\"handler\":\"接馆专员丙\"}"));
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"OPEN_BOX\",\"handler\":\"保管员丁\","
                        + "\"witnessA\":\"保管员丁\",\"witnessB\":\"借展方代表戊\"}"));
    }

    /** 封箱出库版本（本测试均未变更协议，即 v1）的清单行 id */
    private long[] manifestLineIds(JsonNode detail) {
        JsonNode items = detail.get("versions").get(0).get("items");
        long[] ids = new long[items.size()];
        for (int i = 0; i < items.size(); i++) {
            ids[i] = items.get(i).get("id").asLong();
        }
        return ids;
    }

    private List<Outcome> parallelPost(int threads, String uri,
                                       Function<Integer, String> bodyBuilder) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                MvcResult result = mockMvc.perform(post(uri)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bodyBuilder.apply(idx)))
                        .andExpect(status().isOk()).andReturn();
                JsonNode root = objectMapper.readTree(result.getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                Outcome o = new Outcome();
                o.success = root.get("success").asBoolean();
                o.message = root.get("message").asText();
                o.data = root.get("data");
                return o;
            }));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
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
        return mockMvc.perform(get(uri)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
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
