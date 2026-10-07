package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.common.PermissionClosedException;
import com.evops.dto.ValuationApproveRequest;
import com.evops.dto.ValuationRegisterRequest;
import com.evops.dto.ValuationSubmitRequest;
import com.evops.service.InsuranceValuationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物保险估值管理（museum-15）验收：
 * 1) 估值登记来源/机构/币种/有效期；金额修订只追加新版本，双人审批（两名不同人员，
 *    且审批人不得是提交人）齐备才生效，旧版 SUPERSEDED 不改写并出具保险批单回填；
 * 2) 借展签约固化不可变快照，借展期间即使估值修订/批单/过期仍沿用签约时口径；
 * 3) 估值过期自动提醒、可人工确认，但不冻结借展归还（押运+归还验收照常完成）；
 * 4) 财务只能看金额与凭证，公众/普通馆员敏感字段入口拒绝并留痕；
 * 5) 修复用户缺权（valuation_change_open=0）时，估值变更各入口统一拒绝，
 *    换调用地址也不放行；只读不受影响，放开后恢复。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-insurance;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InsuranceValuationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private InsuranceValuationService valuationService;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // 1) 版本只追加 + 双人审批 + 保险批单
    // ------------------------------------------------------------------

    @Test
    void revision_creates_new_version_with_two_person_approval_and_endorsement() throws Exception {
        long relicId = createRelic("INS-001", "估值青铜鼎", "ONE");

        // 首次登记为草稿：来源/机构/币种/有效期齐备；金额必须为正、币种码校验
        expectFail(registerRaw(relicId, "BAD", "评估机构甲", 1000000, "CNY",
                "2026-01-01T00:00:00", "2027-01-01T00:00:00", null, false, "经办小李"), "估值来源");
        expectFail(registerRaw(relicId, "APPRAISAL", "评估机构甲", 0, "CNY",
                "2026-01-01T00:00:00", "2027-01-01T00:00:00", null, false, "经办小李"), "估值金额");
        expectFail(registerRaw(relicId, "APPRAISAL", "评估机构甲", 1000000, "XYZ",
                "2026-01-01T00:00:00", "2027-01-01T00:00:00", null, false, "经办小李"), "币种");
        expectFail(registerRaw(relicId, "APPRAISAL", "评估机构甲", 1000000, "CNY",
                "2027-01-01T00:00:00", "2026-01-01T00:00:00", null, false, "经办小李"), "有效期止");

        long v1 = valId(register(relicId, 1000000, "CNY",
                "2026-01-01T00:00:00", "2027-12-31T00:00:00", null, false, "经办小李"));
        assertEquals("DRAFT", valVersion(relicId, 1).get("status").asText());

        // 提交双人审批
        expectOk(postJson("/api/ins-valuations/" + v1 + "/submit",
                "{\"submittedBy\":\"经办小李\"}"));
        assertEquals("PENDING_APPROVAL", valVersion(relicId, 1).get("status").asText());

        // 经办与审批分离：提交人本人不能签初审/复核
        expectFail(approve(v1, "FIRST", "APPROVE", "经办小李", null), "提交人本人");
        // 初审通过
        expectOk(approve(v1, "FIRST", "APPROVE", "主管甲", "材料齐全"));
        // 不能重复初审；初审未到不能复核（此处初审已到，复核换同人仍拒绝）
        expectFail(approve(v1, "FIRST", "APPROVE", "主管乙", null), "重复初审");
        expectFail(approve(v1, "SECOND", "APPROVE", "主管甲", null), "同一人");
        // 只有一签不生效
        assertEquals("PENDING_APPROVAL", valVersion(relicId, 1).get("status").asText());
        // 复核必须第二名不同人员
        expectOk(approve(v1, "SECOND", "APPROVE", "总监乙", "同意定价"));

        JsonNode current = currentView(relicId, "估值经办", "VALUATION_OFFICER");
        assertEquals("ACTIVE", current.get("status").asText());
        assertEquals(1000000, current.get("amount").asLong());
        assertEquals("APPRAISAL", current.get("sourceType").asText());
        assertNotNull(current.get("endorsementId"), "生效版本必须回填保险批单");
        JsonNode end1 = current.get("endorsement");
        assertEquals("INITIAL", end1.get("endorsementType").asText());
        assertNull(end1.get("previousAmount").get("decimalValue"), "首次批单无上一版本金额");

        // 金额修订：必须填修订原因，且另出新版本，不覆盖 v1
        expectFail(register(relicId, 1200000, "CNY",
                "2026-06-01T00:00:00", "2027-12-31T00:00:00", null, true, "经办小李"), "修订原因");
        long v2 = valId(register(relicId, 1200000, "CNY",
                "2026-06-01T00:00:00", "2027-12-31T00:00:00", "近一年同类拍场价格上行", true, "经办小李"));
        assertEquals(2, valVersion(relicId, 2).get("versionNo").asInt());
        // 两签完成前旧版本仍 ACTIVE
        assertEquals("ACTIVE", valVersion(relicId, 1).get("status").asText());
        expectOk(approve(v2, "FIRST", "APPROVE", "主管甲", null));
        expectOk(approve(v2, "SECOND", "APPROVE", "总监丙", null));

        JsonNode versions = versionsOf(relicId, "经办", "VALUATION_OFFICER");
        assertEquals(2, versions.size());
        assertEquals("SUPERSEDED", versions.get(0).get("status").asText());
        assertEquals(1000000, versions.get(0).get("amount").asLong(), "旧版本金额原样保留不改写");
        assertEquals("ACTIVE", versions.get(1).get("status").asText());

        JsonNode end2 = currentView(relicId, "经办", "VALUATION_OFFICER").get("endorsement");
        assertEquals("ADJUST", end2.get("endorsementType").asText());
        assertEquals(1000000, end2.get("previousAmount").asLong());
        assertEquals(1200000, end2.get("newAmount").asLong());
        assertNotNull(currentView(relicId, "x", "VALUATION_OFFICER").get("endorsementId"));

        // 台账留痕：提交/审批/生效/批单
        Set<String> actions = ledgerActions(relicId);
        assertTrue(actions.contains("VALUATION_SUBMIT"));
        assertTrue(actions.contains("VALUATION_APPROVE"));
        assertTrue(actions.contains("VALUATION_ACTIVATE"));
        assertTrue(actions.contains("INSURANCE_ENDORSEMENT"));
    }

    @Test
    void rejected_revision_is_kept_and_must_be_redo_as_new_version() throws Exception {
        long relicId = createRelic("INS-010", "估值玉佩", "TWO");
        long v1 = valId(register(relicId, 60000, "CNY",
                "2026-01-01T00:00:00", "2027-01-01T00:00:00", null, true, "经办小赵"));
        // 驳回必须填原因
        expectFail(approve(v1, "FIRST", "REJECT", "主管甲", null), "驳回原因");
        expectOk(approve(v1, "FIRST", "REJECT", "主管甲", "凭证与来源不符"));
        assertEquals("REJECTED", valVersion(relicId, 1).get("status").asText());
        // 驳回版本留痕不删档，不能再审；修订须重新登记新版本
        expectFail(approve(v1, "SECOND", "APPROVE", "总监乙", null), "不在审批中");
        long v2 = valId(register(relicId, 58000, "CNY",
                "2026-02-01T00:00:00", "2027-02-01T00:00:00", "按驳回意见补充来源后重新定价",
                true, "经办小赵"));
        assertEquals(2, valVersion(relicId, 2).get("versionNo").asInt());
        expectOk(approve(v2, "FIRST", "APPROVE", "主管甲", null));
        expectOk(approve(v2, "SECOND", "APPROVE", "总监乙", null));
        assertEquals("ACTIVE", valVersion(relicId, 2).get("status").asText());
        assertEquals("REJECTED", valVersion(relicId, 1).get("status").asText(), "驳回版本永久保留");
        assertTrue(ledgerActions(relicId).contains("VALUATION_REJECT"));
    }

    // ------------------------------------------------------------------
    // 2) 借展签约快照沿用 + 3) 过期提醒不冻结归还
    // ------------------------------------------------------------------

    @Test
    void loan_uses_signed_snapshot_and_expiry_reminds_but_never_blocks_return() throws Exception {
        long relicId = createRelic("INS-002", "借展估值青瓷", "ONE");
        // 签约前估值生效：50 万，有效期在借展期间内
        long v1 = valId(register(relicId, 500000, "CNY",
                "2026-01-01T00:00:00", "2027-12-31T00:00:00", null, true, "经办钱某"));
        expectOk(approve(v1, "FIRST", "APPROVE", "主管甲", null));
        expectOk(approve(v1, "SECOND", "APPROVE", "总监乙", null));

        // 借展协议登记并签署 → 自动固化签约快照
        long agreementId = registerAgreement(relicId, "LA-INS-002", 500000, "2026-12-31");
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/sign",
                "{\"signedBy\":\"馆方代表周某\"}"));

        JsonNode snapshotView = loanView(agreementId, "经办", "VALUATION_OFFICER");
        assertTrue(snapshotView.get("loanSnapshot").asBoolean());
        assertEquals(500000, snapshotView.get("amount").asLong());
        assertEquals("APPRAISAL", snapshotView.get("sourceType").asText());
        assertTrue(snapshotView.get("note").asText().contains("借展期间一律沿用本快照"));
        assertTrue(ledgerActions(relicId).contains("VALUATION_LOAN_SNAPSHOT"));

        // 借展期间估值修订到 80 万并出具批单：签约快照口径不变
        long v2 = valId(register(relicId, 800000, "USD",
                "2026-03-01T00:00:00", "2026-09-30T00:00:00", "借展期间年度重估上调",
                true, "经办钱某"));
        expectOk(approve(v2, "FIRST", "APPROVE", "主管甲", null));
        expectOk(approve(v2, "SECOND", "APPROVE", "总监乙", null));
        assertEquals(800000, currentView(relicId, "x", "VALUATION_OFFICER")
                .get("amount").asLong(), "当前估值已是新版本");
        assertEquals(500000, loanView(agreementId, "x", "VALUATION_OFFICER")
                .get("amount").asLong(), "借展期间必须沿用签约时快照，不随后续修订变化");
        assertEquals("CNY", loanView(agreementId, "x", "VALUATION_OFFICER")
                .get("currency").asText());

        // 当前估值已过期（effective_to=2026-09-30 早于今天 2026-10-06）：扫描自动提醒
        JsonNode scan = dataOf(postJson("/api/ins-valuation-reminders/scan", ""));
        assertTrue(scan.get("created").asInt() >= 1);
        assertTrue(scan.get("note").asText().contains("不冻结借展归还"));
        JsonNode reminder = findReminder(v2);
        assertEquals("OPEN", reminder.get("status").asText());
        // 扫描幂等：不重复出提醒
        expectOk(postJson("/api/ins-valuation-reminders/scan", ""));
        assertEquals(reminder.get("id").asLong(), findReminder(v2).get("id").asLong());
        assertTrue(ledgerActions(relicId).contains("VALUATION_EXPIRE_REMIND"));

        // 估值过期 + 提醒未确认：押运四节点与归还验收照常完成（不冻结归还）
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"SEAL\",\"handler\":\"押运员甲\"}"));
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"HANDOVER\",\"handler\":\"押运员乙\"}"));
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"ARRIVAL\",\"handler\":\"接馆专员丙\"}"));
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"OPEN_BOX\",\"handler\":\"保管员丁\","
                        + "\"witnessA\":\"保管员丁\",\"witnessB\":\"借展方戊\"}"));
        long lineId = manifestLineId(agreementId);
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/return-check", "{"
                + "\"checkNo\":\"RC-INS-002\",\"checkedBy\":\"验收组\","
                + "\"items\":[{\"manifestLineId\":" + lineId + ",\"result\":\"INTACT\"}]}"));
        JsonNode agreement = dataOf(getJson("/api/loan-agreements/" + agreementId));
        assertEquals("RETURNED", agreement.get("agreement").get("status").asText(),
                "估值过期只提醒，绝不冻结归还");
        // 借展视角快照在归还后依旧是签约口径
        assertEquals(500000, loanView(agreementId, "x", "VALUATION_OFFICER")
                .get("amount").asLong());

        // 提醒人工确认：只关提醒
        long reminderId = reminder.get("id").asLong();
        expectOk(postJson("/api/ins-valuation-reminders/" + reminderId + "/ack",
                "{\"acknowledgedBy\":\"续保经办\",\"ackNote\":\"已启动下一年度核保\"}"));
        assertEquals("ACKNOWLEDGED", findReminder(v2).get("status").asText());
        expectFail(postJson("/api/ins-valuation-reminders/" + reminderId + "/ack",
                "{\"acknowledgedBy\":\"续保经办\"}"), "重复确认");
        // 确认提醒不改变估值版本状态
        assertEquals("ACTIVE", valVersion(relicId, 2).get("status").asText());
    }

    // ------------------------------------------------------------------
    // 4) 财务只见金额/凭证；公众/普通馆员拒绝
    // ------------------------------------------------------------------

    @Test
    void finance_sees_only_amount_and_voucher_while_public_and_librarian_denied() throws Exception {
        long relicId = createRelic("INS-003", "敏感估值书画", "TWO");
        long v1 = valId(registerRaw(relicId, "AUCTION", "华辰评估有限公司", 760000, "CNY",
                "2026-01-01T00:00:00", "2028-01-01T00:00:00", null, true, "经办孙某"));
        expectOk(approve(v1, "FIRST", "APPROVE", "主管甲", null));
        expectOk(approve(v1, "SECOND", "APPROVE", "总监乙", null));

        // 估值经办：全字段可见（来源/机构/有效期/凭证/审批/批单）
        JsonNode officer = currentView(relicId, "经办孙某", "VALUATION_OFFICER");
        assertEquals("AUCTION", officer.get("sourceType").asText());
        assertEquals("华辰评估有限公司", officer.get("appraisalOrg").asText());
        assertEquals("2028-01-01T00:00:00", officer.get("effectiveTo").asText());
        assertTrue(officer.has("endorsement"));

        // 财务：只能看到金额与凭证，来源/机构/有效期/批单等敏感字段一律不下发
        JsonNode finance = currentView(relicId, "财务会计", "FINANCE");
        assertEquals(760000, finance.get("amount").asLong());
        assertEquals("CNY", finance.get("currency").asText());
        assertTrue(finance.get("financeView").asBoolean());
        assertFalse(finance.has("sourceType"));
        assertFalse(finance.has("appraisalOrg"));
        assertFalse(finance.has("effectiveTo"));
        assertFalse(finance.has("changeReason"));
        assertFalse(finance.has("endorsement"));

        JsonNode financeVersions = versionsOf(relicId, "财务会计", "FINANCE");
        assertEquals(760000, financeVersions.get(0).get("amount").asLong());
        assertFalse(financeVersions.get(0).has("appraisalOrg"));

        // 公众 / 普通馆员：当前估值、版本履历入口直接拒绝（不返回脱敏空壳）
        expectFail(getJson("/api/relics/" + relicId
                + "/insurance-valuation?operatorName=游客&role=PUBLIC"), "无权访问");
        expectFail(getJson("/api/relics/" + relicId
                + "/insurance-valuation?operatorName=馆员&role=LIBRARIAN"), "无权访问");
        expectFail(getJson("/api/relics/" + relicId
                + "/ins-valuation-versions?operatorName=馆员&role=LIBRARIAN"), "无权访问");
        // 拒绝同样写台账留痕
        assertTrue(ledgerActions(relicId).contains("VALUATION_ACCESS_DENY"));

        // 借展快照视角同样收口：财务只见金额/凭证，公众拒绝
        long agreementId = registerAgreement(relicId, "LA-INS-003", 760000, "2027-12-31");
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/sign",
                "{\"signedBy\":\"馆方代表李某\"}"));
        JsonNode financeLoan = loanView(agreementId, "财务会计", "FINANCE");
        assertEquals(760000, financeLoan.get("amount").asLong());
        assertFalse(financeLoan.has("appraisalOrg"));
        expectFail(getJson("/api/loan-agreements/" + agreementId
                + "/valuation-view?operatorName=游客&role=PUBLIC"), "无权访问");
        expectFail(getJson("/api/loan-agreements/" + agreementId
                + "/valuation-snapshots?operatorName=馆员&role=LIBRARIAN"), "无权访问");
    }

    // ------------------------------------------------------------------
    // 5) 缺权时估值变更留痕入口统一拒绝，换调用地址不放行；放开恢复
    // ------------------------------------------------------------------

    @Test
    void closed_permission_gate_rejects_all_change_entry_points_even_via_other_urls() throws Exception {
        long relicId = createRelic("INS-004", "闸门测试佛像", "ONE");
        // 开放时先准备：一个待审批版本 + 一个草稿版本
        long pendingId = valId(register(relicId, 210000, "CNY",
                "2026-01-01T00:00:00", "2027-01-01T00:00:00", null, true, "经办冯某"));
        long draftId = valId(register(relicId, 220000, "CNY",
                "2026-02-01T00:00:00", "2028-02-01T00:00:00", "备用修订", false, "经办冯某"));

        // 收紧「文物保险估值变更留痕」
        String tighten = "{\"open\":false,\"operator\":\"系统管理员\",\"reason\":\"文物修复用户缺权整改\"}";
        JsonNode policy = dataOf(putJson("/api/policy/valuation-change", tighten));
        assertFalse(policy.get("valuationChangeOpen").asBoolean());
        assertTrue(globalLedgerHas("VAL_PERMIT_TIGHTEN"));

        // 修复用户（RESTORER）走 HTTP 各变更入口：登记 / 提交 / 审批全部在入口拒绝
        expectFail(postJson("/api/ins-valuations", registerBody(relicId, 999, "CNY",
                "2026-01-01T00:00:00", "2027-01-01T00:00:00", null, true, "修复用户陈某")),
                "入口已收紧");
        expectFail(postJson("/api/ins-valuations/" + draftId + "/submit",
                "{\"submittedBy\":\"修复用户陈某\"}"), "入口已收紧");
        expectFail(approve(pendingId, "FIRST", "APPROVE", "修复用户陈某", null), "入口已收紧");

        // 换调用地址也不放行：只读地址没有任何写映射，POST 不可能产生写入（响应失败、无副作用）
        expectBlockedWrite("/api/relics/" + relicId + "/insurance-valuation");
        expectBlockedWrite("/api/loan-agreements/" + relicId + "/valuation-view");

        // 服务层直调（不经过任何 URL）同样被拒绝——证明闸门锁的是变更动作本身而非某个地址：
        // 即使修复用户以后拿到别的控制器/内部调用路径，也无法绕过
        ValuationRegisterRequest registerReq = new ValuationRegisterRequest();
        registerReq.setRelicId(relicId);
        registerReq.setSourceType("APPRAISAL");
        registerReq.setAppraisalOrg("评估机构");
        registerReq.setAmount(new BigDecimal("230000"));
        registerReq.setCurrency("CNY");
        registerReq.setEffectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0));
        registerReq.setEffectiveTo(LocalDateTime.of(2027, 1, 1, 0, 0));
        registerReq.setChangeReason("内部直调修订");
        registerReq.setSubmittedBy("修复用户陈某");
        registerReq.setSubmitNow(true);
        expectPermissionClosed(() -> valuationService.register(registerReq));
        ValuationSubmitRequest submitReq = new ValuationSubmitRequest();
        submitReq.setSubmittedBy("修复用户陈某");
        expectPermissionClosed(() -> valuationService.submit(draftId, submitReq));
        ValuationApproveRequest approveReq = new ValuationApproveRequest();
        approveReq.setStep("FIRST");
        approveReq.setDecision("APPROVE");
        approveReq.setApprover("修复用户陈某");
        expectPermissionClosed(() -> valuationService.approve(pendingId, approveReq));

        // 收紧期间数据未被推进：待审批仍待审批、草稿仍草稿、无新生效版本
        assertEquals("PENDING_APPROVAL", statusOf(pendingId));
        assertEquals("DRAFT", statusOf(draftId));

        // 只读查询不受影响（估值经办仍可查；财务金额视图仍在）
        JsonNode policyGet = dataOf(getJson("/api/policy/valuation-change"));
        assertTrue(policyGet.get("repairPhotoUploadOpen").asBoolean(),
                "影像上传开关与估值开关相互独立");
        assertFalse(policyGet.get("valuationChangeOpen").asBoolean());
        expectFail(getJson("/api/relics/" + relicId
                + "/insurance-valuation?operatorName=游客&role=PUBLIC"), "无权访问");

        // 放开后：审批链路继续推进，版本正常生效并出批单
        String relax = "{\"open\":true,\"operator\":\"系统管理员\",\"reason\":\"权限恢复\"}";
        assertTrue(dataOf(putJson("/api/policy/valuation-change", relax))
                .get("valuationChangeOpen").asBoolean());
        assertTrue(globalLedgerHas("VAL_PERMIT_RELAX"));
        expectOk(approve(pendingId, "FIRST", "APPROVE", "主管甲", null));
        expectOk(approve(pendingId, "SECOND", "APPROVE", "总监乙", null));
        assertEquals("ACTIVE", statusOf(pendingId));
        assertNotNull(endorsementIdOf(pendingId), "新版本生效必须回填保险批单");
    }

    private void expectPermissionClosed(RunnableThrowing call) {
        try {
            call.run();
            throw new AssertionError("收紧后变更必须在入口拒绝");
        } catch (PermissionClosedException expected) {
            assertTrue(expected.getMessage().contains("入口已收紧"),
                    "拒绝信息应明确缺权与入口收紧：" + expected.getMessage());
        } catch (Exception e) {
            throw new AssertionError("期望 PermissionClosedException，实际 " + e, e);
        }
    }

    private interface RunnableThrowing {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private long createRelic(String no, String name, String grade) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"R-" + no + "\",\"name\":\"" + name + "\",\"category\":\"杂项\","
                + "\"material\":\"混合\",\"era\":\"古代\",\"source\":\"征集\","
                + "\"preservationGrade\":\"" + grade + "\"}")).get("id").asLong();
    }

    private long registerAgreement(long relicId, String agreementNo, long amount, String returnDate)
            throws Exception {
        return dataOf(postJson("/api/loan-agreements", "{"
                + "\"agreementNo\":\"" + agreementNo + "\",\"relicId\":" + relicId
                + ",\"borrower\":\"外地市博物馆\",\"insuranceAmount\":" + amount
                + ",\"transportRoute\":\"馆库-专线-借展方展厅\",\"returnDate\":\"" + returnDate + "\","
                + "\"purpose\":\"巡展\",\"items\":[{\"itemNo\":\"I1\",\"itemName\":\"文物本体\","
                + "\"qty\":1,\"outCondition\":\"完好\"}]}")).get("id").asLong();
    }

    private long manifestLineId(long agreementId) throws Exception {
        JsonNode detail = dataOf(getJson("/api/loan-agreements/" + agreementId));
        for (JsonNode bundle : detail.get("versions")) {
            JsonNode items = bundle.get("items");
            if (items != null && items.size() > 0) {
                JsonNode seal = null;
                for (JsonNode node : detail.get("escortNodes")) {
                    if ("SEAL".equals(node.get("nodeType").asText())) {
                        seal = node;
                    }
                }
                long outboundVersionId = seal == null
                        ? detail.get("agreement").get("currentVersionId").asLong()
                        : seal.get("agreementVersionId").asLong();
                if (bundle.get("version").get("id").asLong() == outboundVersionId) {
                    return items.get(0).get("id").asLong();
                }
            }
        }
        throw new IllegalStateException("未找到出库清单行");
    }

    private MvcResult register(long relicId, long amount, String currency,
                               String from, String to, String reason,
                               boolean submitNow, String by) throws Exception {
        return postJson("/api/ins-valuations",
                registerBody(relicId, amount, currency, from, to, reason, submitNow, by));
    }

    private MvcResult registerRaw(long relicId, String source, String org, long amount,
                                  String currency, String from, String to, String reason,
                                  boolean submitNow, String by) throws Exception {
        return postJson("/api/ins-valuations", "{"
                + "\"relicId\":" + relicId + ",\"sourceType\":\"" + source + "\","
                + "\"appraisalOrg\":\"" + org + "\",\"amount\":" + amount
                + ",\"currency\":\"" + currency + "\",\"effectiveFrom\":\"" + from
                + "\",\"effectiveTo\":\"" + to + "\",\"voucherNo\":\"VCH-" + relicId + "\","
                + "\"voucherName\":\"核保凭证.pdf\""
                + (reason == null ? "" : ",\"changeReason\":\"" + reason + "\"")
                + ",\"submittedBy\":\"" + by + "\",\"submitNow\":" + submitNow + "}");
    }

    private String registerBody(long relicId, long amount, String currency,
                                String from, String to, String reason,
                                boolean submitNow, String by) {
        return "{"
                + "\"relicId\":" + relicId + ",\"sourceType\":\"APPRAISAL\","
                + "\"appraisalOrg\":\"馆方评估中心\",\"amount\":" + amount
                + ",\"currency\":\"" + currency + "\",\"effectiveFrom\":\"" + from
                + "\",\"effectiveTo\":\"" + to + "\",\"voucherNo\":\"VCH-" + relicId + "\","
                + "\"voucherName\":\"核保凭证.pdf\""
                + (reason == null ? "" : ",\"changeReason\":\"" + reason + "\"")
                + ",\"submittedBy\":\"" + by + "\",\"submitNow\":" + submitNow + "}";
    }

    private MvcResult approve(long versionId, String step, String decision,
                              String approver, String rejectReason) throws Exception {
        String body = "{\"step\":\"" + step + "\",\"decision\":\"" + decision
                + "\",\"approver\":\"" + approver + "\"";
        if (decision.equals("REJECT")) {
            body += rejectReason == null ? "}"
                    : ",\"rejectReason\":\"" + rejectReason + "\"}";
        } else {
            body += ",\"opinion\":\"同意\"}";
        }
        return postJson("/api/ins-valuations/" + versionId + "/approvals", body);
    }

    private long valId(MvcResult result) throws Exception {
        return dataOf(result).get("id").asLong();
    }

    private JsonNode valVersion(long relicId, int versionNo) throws Exception {
        for (JsonNode v : versionsOf(relicId, "经办", "VALUATION_OFFICER")) {
            if (v.get("versionNo").asInt() == versionNo) {
                return v;
            }
        }
        throw new IllegalStateException("未找到 V" + versionNo);
    }

    private String statusOf(long versionId) {
        return jdbc().queryForObject(
                "SELECT status FROM t_ins_valuation_version WHERE id = ?",
                String.class, versionId);
    }

    /** 对只读地址发 POST：必须失败（405 或业务失败），且不得产生任何写入副作用 */
    private void expectBlockedWrite(String uri) throws Exception {
        mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(r -> assertTrue(
                        r.getResponse().getStatus() == 405
                                || !objectMapper.readTree(
                                        r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                                .get("success").asBoolean(),
                        "只读地址不允许写入: " + uri));
    }

    private Long endorsementIdOf(long versionId) {
        return jdbc().queryForObject(
                "SELECT endorsement_id FROM t_ins_valuation_version WHERE id = ?",
                Long.class, versionId);
    }

    private boolean globalLedgerHas(String action) {
        Integer cnt = jdbc().queryForObject(
                "SELECT COUNT(1) FROM t_relic_ledger WHERE relic_id = 0 AND action = ?",
                Integer.class, action);
        return cnt != null && cnt > 0;
    }

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    private JsonNode currentView(long relicId, String who, String role) throws Exception {
        return dataOf(getJson("/api/relics/" + relicId
                + "/insurance-valuation?operatorName=" + who + "&role=" + role));
    }

    private JsonNode versionsOf(long relicId, String who, String role) throws Exception {
        return dataOf(getJson("/api/relics/" + relicId
                + "/ins-valuation-versions?operatorName=" + who + "&role=" + role));
    }

    private JsonNode loanView(long agreementId, String who, String role) throws Exception {
        return dataOf(getJson("/api/loan-agreements/" + agreementId
                + "/valuation-view?operatorName=" + who + "&role=" + role));
    }

    private JsonNode findReminder(long versionId) throws Exception {
        JsonNode list = dataOf(getJson("/api/ins-valuation-reminders"));
        for (JsonNode r : list) {
            if (r.get("valuationVersionId").asLong() == versionId) {
                return r;
            }
        }
        throw new IllegalStateException("未找到估值版本 " + versionId + " 的过期提醒");
    }

    private Set<String> ledgerActions(long relicId) throws Exception {
        Set<String> actions = new HashSet<>();
        for (JsonNode e : dataOf(getJson("/api/relics/" + relicId + "/ledger"))) {
            actions.add(e.get("action").asText());
        }
        return actions;
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private MvcResult putJson(String uri, String body) throws Exception {
        return mockMvc.perform(put(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
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
