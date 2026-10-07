package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.entity.EnvEvent;
import com.evops.mapper.EnvEventMapper;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物修复年度报表（museum-16）验收：
 * 1) 五项指标（立项/完成/材料净消耗/借展/异常）按绑定口径版本统计，
 *    缺验收资料的完成项目标记待补；
 * 2) 归档生成不可变快照；撤回再重提——报表编号/统计口径版本/时间三者都可辨，旧批保留；
 * 3) 报表先生成、口径后发布新版本，已出报表核对仍按原口径版本；用新口径另出新批次结果不同；
 * 4) 异常凭证只追加、不覆盖已有凭证，快照范围外异常不能塞凭证；
 * 5) Quartz（模拟）触发全程任务日志；生成失败出 FAILED 日志并追加 OPEN 告警，日志保留；
 * 6) 导出按馆区整行裁剪、按保密级别（一级）对字段打码；
 * 7) 年度边界（2024-12-31 23:59:59 / 2026-01-01 00:00:00）归属正确。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-annual;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnualReportTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EnvEventMapper envEventMapper;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    @Test
    void annual_report_full_lifecycle() throws Exception {
        // ===================== 一、准备 2025 年度业务数据 =====================
        long mainOne = createRelic("AR-R-1", "年报一级青铜鼎", "青铜", "ONE", "MAIN");
        long mainTwo = createRelic("AR-R-2", "年报二级书画卷", "书画", "TWO", "MAIN");
        long mainThree = createRelic("AR-R-3", "年报三级陶罐", "陶", "THREE", "MAIN");
        long eastOne = createRelic("AR-R-4", "东馆一级玉璧", "玉", "ONE", "EAST");

        // 展厅（环境异常按展厅在展文物归属馆区/保密级）
        long hallId = createHall("AR-H-1", "年报综合厅");
        expectOk(postJson("/api/halls/" + hallId + "/relics",
                "{\"relicId\":" + mainOne + ",\"pointCode\":\"P-1\"}"));
        expectOk(postJson("/api/halls/" + hallId + "/relics",
                "{\"relicId\":" + mainTwo + ",\"pointCode\":\"P-2\"}"));

        // P1（ONE，会签顺序 EXTERNAL→INTERNAL→CUSTODIAN）：全链路，成果报告 PASSED
        long p1 = createProject("AR-PRJ-1", mainOne, "委托人甲");
        approvePlan(p1, "ONE", "外部专家甲", "馆内专家甲", "保管员甲");
        startProject(p1);
        long equip1 = createEquipment("AR-EQ-1", "显微成像仪");
        submitTimesheet(p1, equip1, "修复师甲", "显微清垢",
                "2025-02-01 09:00:00", "2025-02-01 12:00:00");
        createMaterialAndUsage(p1, "AR-MAT-1", "AR-B-1", "石青", 100, 10, 2,
                "2025-03-01T10:00:00");
        recordStage(p1, "清洗阶段", "除垢完成", "修复师甲");

        // 设备故障须在项目修复中登记（受影响行=修复中工单的有效工时版本，与故障窗口重叠）
        JsonNode faultResp = dataOf(postJson("/api/repair-equipment/" + equip1 + "/faults",
                "{\"description\":\"镜头故障\",\"faultAt\":\"2025-02-01 10:00:00\","
                        + "\"expectedRecoveredAt\":\"2025-02-03 10:00:00\"}"));
        long faultId = faultResp.get("fault").get("id").asLong();
        assertTrue(faultResp.get("suggestCount").asInt() >= 1, "故障自动生成改期建议");

        finishProject(p1);
        submitAndConfirmReport(p1, "清洗", "专家甲", "保管甲");
        acceptProject(p1, "验收人甲");
        // 业务发生在 2025 年度：回填立项创建时间与完成日期（接口默认取当前时间）
        backfillProjectYear(p1, "2025-01-05 09:00:00", "2025-05-01");

        // P2（TWO，会签顺序 INTERNAL→EXTERNAL→CUSTODIAN）：完成但无 PASSED 成果报告 → 待补
        long p2 = createProject("AR-PRJ-2", mainTwo, "委托人乙");
        approvePlan(p2, "TWO", "外部专家乙", "馆内专家乙", "保管员乙");
        startProject(p2);
        createMaterialAndUsage(p2, "AR-MAT-2", "AR-B-2", "胶矾水", 100, 4, 0,
                "2025-04-01T10:00:00");
        recordStage(p2, "揭裱阶段", "旧裱去除", "修复师乙");
        finishProject(p2);
        backfillProjectYear(p2, "2025-01-06 09:00:00", "2025-05-02");

        // 借展：MAIN ONE / MAIN THREE / EAST ONE 各 2025 签一次
        registerAndSignAgreement("AR-LA-1", mainOne, "省博物馆", "2025-06-01T09:00:00");
        registerAndSignAgreement("AR-LA-2", mainThree, "市展览馆", "2025-09-01T09:00:00");
        registerAndSignAgreement("AR-LA-3", eastOne, "外地馆", "2025-05-01T09:00:00");

        // 环境异常：2025 一条、年度边界外各一条
        long envInId = insertEnvEvent("AR-EE-1", hallId, "TEMP", 32, "2025-08-01T10:00:00");
        insertEnvEvent("AR-EE-2", hallId, "HUMIDITY", 20, "2024-12-31T23:59:59");
        insertEnvEvent("AR-EE-3", hallId, "TEMP", 33, "2026-01-01T00:00:00");

        // ===================== 二、口径 V1（默认口径） =====================
        expectFail(postJson("/api/annual-calibers",
                "{\"name\":\"错误口径\",\"publishedBy\":\"统计员甲\","
                        + "\"spec\":{\"project\":{\"dateField\":\"HACK\",\"statuses\":[\"ACCEPTED\"]},"
                        + "\"completed\":{\"dateField\":\"FINISH_DATE\",\"statuses\":[\"ACCEPTED\"]},"
                        + "\"material\":{\"dateField\":\"USAGE_AT\",\"deductReturn\":true},"
                        + "\"loan\":{\"dateField\":\"SIGNED_AT\"},"
                        + "\"anomaly\":{\"types\":[\"ENV_EVENT\"],\"envDateField\":\"FIRST_AT\","
                        + "\"faultDateField\":\"FAULT_AT\"}}}"), "归属时间");
        long caliberV1 = publishDefaultCaliber("2025 年度默认口径", null);
        assertEquals(1, caliberV1);
        assertEquals("ACTIVE", dataOf(getJson("/api/annual-calibers/active")).get("status").asText());

        // ===================== 三、生成 2025 报表（手动） =====================
        long r1 = generate(2025, null, null, "统计员甲");
        JsonNode d1 = detail(r1);
        JsonNode h1 = d1.get("report");
        assertEquals("YR-2025-000001", h1.get("reportNo").asText());
        assertEquals(1, h1.get("batchNo").asInt());
        assertEquals(1, h1.get("caliberVersionNo").asInt());
        assertEquals("MANUAL", h1.get("triggeredBy").asText());
        assertEquals(2, h1.get("projectCount").asInt(), "立项数=2");
        assertEquals(2, h1.get("completedCount").asInt(), "完成数=2（REPAIRED/ACCEPTED 都计入）");
        assertEquals(0, new BigDecimal("12.000").compareTo(h1.get("materialConsumption").decimalValue()),
                "材料净消耗=10-2+4=12");
        assertEquals(3, h1.get("loanCount").asInt(), "借展=3（含东馆）");
        assertEquals(2, h1.get("anomalyCount").asInt(), "异常=环境1+设备1（边界外两条不计）");
        assertEquals(1, h1.get("pendingSupplementCount").asInt(), "待补=1（P2 缺成果报告）");

        JsonNode lp1 = findByProjectNo(d1.get("projectLines"), "AR-PRJ-1");
        assertEquals(1, lp1.get("countedProject").asInt());
        assertEquals(1, lp1.get("countedCompleted").asInt());
        assertEquals(1, lp1.get("hasAcceptDocs").asInt());
        assertEquals(1, lp1.get("accepted").asInt());
        assertEquals(0, lp1.get("pendingSupplement").asInt());
        JsonNode lp2 = findByProjectNo(d1.get("projectLines"), "AR-PRJ-2");
        assertEquals(1, lp2.get("countedCompleted").asInt());
        assertEquals(0, lp2.get("hasAcceptDocs").asInt());
        assertEquals(1, lp2.get("pendingSupplement").asInt(), "P2 完成但无 PASSED 报告 → 待补");
        assertEquals(1, d1.get("pendingSupplements").size());

        JsonNode mats = d1.get("materialLines");
        BigDecimal netSum = BigDecimal.ZERO;
        for (JsonNode m : mats) {
            netSum = netSum.add(m.get("netQty").decimalValue());
            assertEquals(0, m.get("issuedQty").decimalValue()
                    .subtract(m.get("returnedQty").decimalValue())
                    .compareTo(m.get("netQty").decimalValue()));
        }
        assertEquals(0, new BigDecimal("12.000").compareTo(netSum));

        JsonNode logs2025 = dataOf(getJson("/api/annual-report-job-logs?year=2025"));
        assertEquals(1, logs2025.size());
        assertEquals("SUCCESS", logs2025.get(0).get("status").asText());
        assertEquals("MANUAL", logs2025.get(0).get("triggeredBy").asText());
        assertEquals(r1, logs2025.get(0).get("reportId").asLong());
        assertNotNull(logs2025.get(0).get("finishedAt"));

        // ===================== 四、Quartz 定时触发 2024（边界归属） =====================
        JsonNode scheduled = dataOf(mockMvc.perform(post("/api/annual-reports/scheduled-fire")
                        .param("year", "2024").param("generatedBy", "系统定时"))
                .andExpect(status().isOk()).andReturn());
        long r2024 = scheduled.get("id").asLong();
        assertEquals("SCHEDULED", scheduled.get("triggeredBy").asText());
        assertEquals("YR-2024-000002", scheduled.get("reportNo").asText(), "编号全局递增");
        JsonNode d2024 = detail(r2024);
        assertEquals(0, d2024.get("report").get("projectCount").asInt());
        assertEquals(1, d2024.get("report").get("anomalyCount").asInt(),
                "2024-12-31 23:59:59 的环境异常归属 2024");
        JsonNode logs2024 = dataOf(getJson("/api/annual-report-job-logs?year=2024"));
        assertEquals(1, logs2024.size());
        assertEquals("SUCCESS", logs2024.get(0).get("status").asText());
        assertEquals("SCHEDULED", logs2024.get(0).get("triggeredBy").asText());
        assertNotNull(logs2024.get(0).get("fireKey"));

        // ===================== 五、报表先生成、口径后变更：旧报表仍按 V1 核对 =====================
        long caliberV2 = publishCaliber("2025 年度收紧口径",
                "{\"project\":{\"dateField\":\"CREATE_TIME\","
                        + "\"statuses\":[\"PLANNED\",\"REPAIRING\",\"REPAIRED\",\"ACCEPTED\"]},"
                        + "\"completed\":{\"dateField\":\"FINISH_DATE\",\"statuses\":[\"ACCEPTED\"]},"
                        + "\"material\":{\"dateField\":\"USAGE_AT\",\"deductReturn\":true},"
                        + "\"loan\":{\"dateField\":\"SIGNED_AT\"},"
                        + "\"anomaly\":{\"types\":[\"ENV_EVENT\",\"EQUIPMENT_FAULT\"],"
                        + "\"envDateField\":\"FIRST_AT\",\"faultDateField\":\"FAULT_AT\"},"
                        + "\"requireAcceptanceDocs\":true}",
                "完成数仅统计已验收项目");
        assertEquals(2, caliberV2);
        JsonNode v1Now = findCaliber(dataOf(getJson("/api/annual-calibers")), 1);
        assertEquals("SUPERSEDED", v1Now.get("status").asText(), "V1 置 SUPERSEDED 但仍可用于核对");

        JsonNode d1Again = detail(r1);
        assertEquals(1, d1Again.get("report").get("caliberVersionNo").asInt());
        assertEquals(2, d1Again.get("report").get("completedCount").asInt(), "快照不可变，仍为 V1 结果");
        assertEquals(1, d1Again.get("report").get("pendingSupplementCount").asInt());

        // 用 V2 新口径另出 2025 新批次：完成数=1（仅 P1 已验收），待补=0
        long r2 = generate(2025, caliberV2, null, "统计员甲");
        JsonNode h2 = detail(r2).get("report");
        assertEquals("YR-2025-000003", h2.get("reportNo").asText());
        assertEquals(1, h2.get("batchNo").asInt(), "V2 口径下批次重新从 1 开始");
        assertEquals(2, h2.get("caliberVersionNo").asInt());
        assertEquals(2, h2.get("projectCount").asInt());
        assertEquals(1, h2.get("completedCount").asInt(), "V2 完成数仅 ACCEPTED=1");
        assertEquals(0, h2.get("pendingSupplementCount").asInt());

        // ===================== 六、生成失败：FAILED 任务日志 + OPEN 告警 =====================
        expectFail(postJson("/api/annual-reports/generate",
                "{\"year\":2025,\"caliberVersionId\":999999,\"generatedBy\":\"统计员甲\"}"),
                "口径版本不存在");
        JsonNode failedLog = findLog(dataOf(getJson("/api/annual-report-job-logs?year=2025")), "FAILED");
        assertNotNull(failedLog, "失败必须保留 FAILED 任务日志");
        assertTrue(failedLog.get("errorMessage").asText().contains("口径版本不存在"));
        JsonNode openAlerts = dataOf(getJson("/api/annual-report-alerts?status=OPEN"));
        assertEquals(1, openAlerts.size());
        long alertId = openAlerts.get(0).get("id").asLong();
        assertTrue(openAlerts.get(0).get("alertNo").asText().startsWith("ARA-"));
        assertEquals(failedLog.get("id").asLong(), openAlerts.get(0).get("jobLogId").asLong());

        // 确认告警：只关闭告警，FAILED 日志保留不改写
        expectOk(postJson("/api/annual-report-alerts/" + alertId + "/ack",
                "{\"acknowledgedBy\":\"运维乙\",\"note\":\"口径 id 配置错误已修正\"}"));
        assertEquals(0, dataOf(getJson("/api/annual-report-alerts?status=OPEN")).size());
        assertEquals(1, dataOf(getJson("/api/annual-report-alerts?status=ACKNOWLEDGED")).size());
        assertEquals("FAILED",
                findLog(dataOf(getJson("/api/annual-report-job-logs?year=2025")), "FAILED")
                        .get("status").asText(), "失败日志不被覆盖");

        // ===================== 七、撤回 + 重提：编号/口径/时间可辨 =====================
        String r1GeneratedAt = d1.get("report").get("generatedAt").asText();
        expectOk(postJson("/api/annual-reports/" + r1 + "/revoke",
                "{\"reason\":\"源数据批次提交有误，撤回重提\",\"revokedBy\":\"统计主管\"}"));
        assertEquals("REVOKED", detail(r1).get("report").get("status").asText());
        expectFail(postJson("/api/annual-reports/" + r1 + "/revoke",
                "{\"reason\":\"x\",\"revokedBy\":\"统计主管\"}"), "已撤回");
        expectFail(postJson("/api/annual-reports/" + r2 + "/resubmit",
                "{\"generatedBy\":\"统计员甲\"}"), "仅已撤回");

        long r3 = dataOf(postJson("/api/annual-reports/" + r1 + "/resubmit",
                "{\"generatedBy\":\"统计员甲\"}")).get("id").asLong();
        JsonNode h3 = detail(r3).get("report");
        assertEquals("YR-2025-000004", h3.get("reportNo").asText(), "重提领取新编号");
        assertEquals(2, h3.get("batchNo").asInt(), "V1 同年度批次递增到 2");
        assertEquals(1, h3.get("caliberVersionNo").asInt(), "重提沿用原口径版本 V1");
        assertEquals(r1, h3.get("revokedOfId").asLong(), "撤回链指向旧批次");
        assertFalse(r1GeneratedAt.equals(h3.get("generatedAt").asText()),
                "重提生成时间必须不同于原报表");
        assertEquals(2, h3.get("completedCount").asInt(), "同一批数据结果一致");
        assertEquals(12, h3.get("materialConsumption").decimalValue().intValue());
        assertEquals("REVOKED", detail(r1).get("report").get("status").asText(), "旧批次仍可查且保留");
        assertEquals(1, detail(r1).get("report").get("caliberVersionNo").asInt());

        // ===================== 八、异常凭证只追加、不覆盖 =====================
        List<JsonNode> before = anomaliesOf(r3, "ENV_EVENT", envInId);
        assertEquals(1, before.size(), "生成时自带凭证序号 1");
        String firstDetail = before.get(0).get("detail").asText();
        expectOk(postJson("/api/annual-reports/" + r3 + "/anomalies",
                "{\"anomalyType\":\"ENV_EVENT\",\"sourceId\":" + envInId
                        + ",\"detail\":\"补充：现场复检记录已归档\",\"appendedBy\":\"保管员丙\"}"));
        List<JsonNode> after = anomaliesOf(r3, "ENV_EVENT", envInId);
        assertEquals(2, after.size());
        assertEquals(1, after.get(0).get("voucherSeq").asInt());
        assertEquals(2, after.get(1).get("voucherSeq").asInt());
        assertEquals(firstDetail, after.get(0).get("detail").asText(), "已有凭证不被覆盖");
        assertEquals("补充：现场复检记录已归档", after.get(1).get("detail").asText());
        // 快照范围外异常不能追加；已撤回报表不能追加
        expectFail(postJson("/api/annual-reports/" + r3 + "/anomalies",
                "{\"anomalyType\":\"ENV_EVENT\",\"sourceId\":999999,\"detail\":\"x\",\"appendedBy\":\"保管员丙\"}"),
                "快照范围");
        expectFail(postJson("/api/annual-reports/" + r1 + "/anomalies",
                "{\"anomalyType\":\"ENV_EVENT\",\"sourceId\":" + envInId
                        + ",\"detail\":\"x\",\"appendedBy\":\"保管员丙\"}"), "已撤回");

        // 设备故障凭证同样可追加（凭证不覆盖）
        expectOk(postJson("/api/annual-reports/" + r3 + "/anomalies",
                "{\"anomalyType\":\"EQUIPMENT_FAULT\",\"sourceId\":" + faultId
                        + ",\"detail\":\"补充：维修工单已闭环\",\"appendedBy\":\"设备员丁\"}"));
        List<JsonNode> faultVouchers = anomaliesOf(r3, "EQUIPMENT_FAULT", faultId);
        assertEquals(2, faultVouchers.size());

        // ===================== 九、导出：馆区 + 保密级别裁剪 =====================
        String mainReaderCsv = exportCsv(r3, "MAIN", "REPORT_READER");
        assertTrue(mainReaderCsv.contains("AR-PRJ-1"), "本馆区项目出库");
        assertTrue(mainReaderCsv.contains("***保密一级***"), "一级文物名称对普通查阅人打码");
        assertFalse(mainReaderCsv.contains("年报一级青铜鼎"), "一级文物名称不得出库");
        assertTrue(mainReaderCsv.contains("年报二级书画卷"), "二级文物名称可见");
        assertFalse(mainReaderCsv.contains("AR-LA-3"), "东馆借展整行剔除");
        assertFalse(mainReaderCsv.contains("东馆一级玉璧"), "东馆文物整行剔除");
        assertFalse(mainReaderCsv.contains("补充：现场复检记录已归档"), "一级异常凭证内容对普通查阅人打码");
        assertTrue(mainReaderCsv.contains("导出馆区,MAIN"));
        assertTrue(mainReaderCsv.contains("导出角色,REPORT_READER"));

        String mainAdminCsv = exportCsv(r3, "MAIN", "REPORT_ADMIN");
        assertTrue(mainAdminCsv.contains("年报一级青铜鼎"), "管理员可见一级文物明文");
        assertTrue(mainAdminCsv.contains("补充：现场复检记录已归档"), "管理员可见一级异常凭证明文");
        assertFalse(mainAdminCsv.contains("AR-LA-3"), "管理员同样受馆区整行裁剪");

        String eastCsv = exportCsv(r3, "EAST", "REPORT_ADMIN");
        assertTrue(eastCsv.contains("AR-LA-3"), "东馆只看到自己的借展");
        assertFalse(eastCsv.contains("AR-PRJ-1"), "东馆看不到主馆区项目");

        expectFail(getRaw("/api/annual-reports/" + r3 + "/export?role=REPORT_ADMIN"),
                "导出必须声明导出人馆区");
    }

    // ------------------------------------------------------------------
    // 业务数据 helper
    // ------------------------------------------------------------------

    private long createRelic(String no, String name, String material, String grade, String campus)
            throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + no + "\",\"name\":\"" + name + "\",\"category\":\"杂项\","
                + "\"material\":\"" + material + "\",\"era\":\"宋代\",\"source\":\"考古移交\","
                + "\"preservationGrade\":\"" + grade + "\",\"campusCode\":\"" + campus + "\"}"))
                .get("id").asLong();
    }

    private long createHall(String no, String name) throws Exception {
        return dataOf(postJson("/api/halls",
                "{\"hallNo\":\"" + no + "\",\"name\":\"" + name + "\",\"custodian\":\"王保管\"}"))
                .get("id").asLong();
    }

    private long createProject(String no, long relicId, String client) throws Exception {
        return dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"" + no + "\",\"relicId\":" + relicId
                + ",\"client\":\"" + client + "\",\"plan\":\"按方案修复\"}")).get("id").asLong();
    }

    /**
     * 完成方案会签。ONE：EXTERNAL→INTERNAL→CUSTODIAN；TWO/THREE：INTERNAL→EXTERNAL→CUSTODIAN
     * （顺序来自 museum.countersign.sign-order）。
     */
    private void approvePlan(long projectId, String grade, String external, String internal,
                             String custodian) throws Exception {
        long versionId = dataOf(postJson("/api/repair-projects/" + projectId + "/plan-versions", "{"
                + "\"planContent\":\"修复方案文本\",\"internalExpert\":\"" + internal
                + "\",\"externalExpert\":\"" + external + "\",\"custodian\":\"" + custodian
                + "\",\"submittedBy\":\"经办人\"}")).get("version").get("id").asLong();
        if ("ONE".equals(grade)) {
            sign(versionId, "EXTERNAL", external);
            sign(versionId, "INTERNAL", internal);
        } else {
            sign(versionId, "INTERNAL", internal);
            sign(versionId, "EXTERNAL", external);
        }
        sign(versionId, "CUSTODIAN", custodian);
    }

    private void sign(long versionId, String role, String name) throws Exception {
        expectOk(postJson("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"" + role + "\",\"signerName\":\"" + name
                        + "\",\"decision\":\"APPROVE\"}"));
    }

    private void startProject(long projectId) throws Exception {
        expectOk(postJson("/api/repair-projects/" + projectId + "/start",
                "{\"operatorName\":\"调度员\"}"));
    }

    private void recordStage(long projectId, String stageName, String result, String operator)
            throws Exception {
        expectOk(postJson("/api/repair-projects/" + projectId + "/stages", "{"
                + "\"stageName\":\"" + stageName + "\",\"stageResult\":\"" + result
                + "\",\"operator\":\"" + operator + "\"}"));
    }

    private void finishProject(long projectId) throws Exception {
        expectOk(postJson("/api/repair-projects/" + projectId + "/finish",
                "{\"operatorName\":\"修复师甲\"}"));
    }

    private void acceptProject(long projectId, String acceptedBy) throws Exception {
        expectOk(postJson("/api/repair-projects/" + projectId + "/accept",
                "{\"acceptedBy\":\"" + acceptedBy + "\",\"result\":\"PASS\"}"));
    }

    /** 回填立项创建时间（立项归属）与完成日期（完成归属）到指定年度 */
    private void backfillProjectYear(long projectId, String createTime, String finishDate) {
        jdbc().update("UPDATE t_repair_project SET create_time=?, finish_date=? WHERE id=?",
                java.sql.Timestamp.valueOf(createTime), java.sql.Date.valueOf(finishDate), projectId);
    }

    private long createEquipment(String no, String name) throws Exception {
        return dataOf(postJson("/api/repair-equipment",
                "{\"equipmentNo\":\"" + no + "\",\"name\":\"" + name + "\"}")).get("id").asLong();
    }

    private void submitTimesheet(long projectId, long equipmentId, String restorer, String workStep,
                                 String start, String end) throws Exception {
        expectOk(postJson("/api/repair-projects/" + projectId + "/timesheets", "{"
                + "\"restorer\":\"" + restorer + "\",\"submittedBy\":\"" + restorer + "\","
                + "\"lines\":[{\"workStep\":\"" + workStep + "\",\"equipmentId\":" + equipmentId
                + ",\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}]}"));
    }

    private void createMaterialAndUsage(long projectId, String materialNo, String batchNo,
                                        String name, int inboundQty, int actualQty, int returnQty,
                                        String usageAt) throws Exception {
        expectOk(postJson("/api/materials", "{"
                + "\"materialNo\":\"" + materialNo + "\",\"name\":\"" + name
                + "\",\"category\":\"PIGMENT\",\"unit\":\"g\"}"));
        expectOk(postJson("/api/materials/inbound", "{"
                + "\"materialNo\":\"" + materialNo + "\",\"batchNo\":\"" + batchNo
                + "\",\"qty\":" + inboundQty + ",\"expireDate\":\"2099-01-01\""
                + ",\"inboundBy\":\"库管员\"}"));
        long usageId = dataOf(postJson("/api/material-usages", "{"
                + "\"usageNo\":\"" + batchNo + "-U\",\"projectId\":" + projectId
                + ",\"usageAt\":\"" + usageAt + "\","
                + "\"lines\":[{\"batchNo\":\"" + batchNo + "\",\"planQty\":" + actualQty
                + ",\"actualQty\":" + actualQty + ",\"applyPart\":\"表面\",\"operator\":\"修复师甲\"}]}"))
                .get("id").asLong();
        if (returnQty > 0) {
            JsonNode detail = dataOf(getJson("/api/material-usages/" + usageId));
            long lineId = detail.get("lines").get(0).get("id").asLong();
            expectOk(postJson("/api/material-returns", "{"
                    + "\"returnNo\":\"" + batchNo + "-R\",\"usageId\":" + usageId
                    + ",\"lineId\":" + lineId + ",\"returnQty\":" + returnQty
                    + ",\"reason\":\"未用完\",\"returnedBy\":\"修复师甲\"}"));
        }
    }

    private void registerAndSignAgreement(String agreementNo, long relicId, String borrower,
                                          String signedAt) throws Exception {
        expectOk(postJson("/api/loan-agreements", "{"
                + "\"agreementNo\":\"" + agreementNo + "\",\"relicId\":" + relicId
                + ",\"borrower\":\"" + borrower + "\",\"insuranceAmount\":100000,"
                + "\"transportRoute\":\"专车押运\",\"returnDate\":\"2026-12-31\","
                + "\"items\":[{\"itemNo\":\"I1\",\"itemName\":\"文物本体\",\"qty\":1}]}"));
        long agreementId = dataOf(getJson("/api/loan-agreements?relicId=" + relicId)).get(0)
                .get("id").asLong();
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/sign",
                "{\"signedBy\":\"借出经办\",\"signedAt\":\"" + signedAt + "\"}"));
    }

    /** 提交成果报告并由馆内专家+保管员逐项确认全部 OK → PASSED */
    private void submitAndConfirmReport(long projectId, String craft, String expert, String custodian)
            throws Exception {
        long reportId = dataOf(postJson("/api/repair-projects/" + projectId + "/accept-reports", "{"
                + "\"craft\":\"" + craft + "\",\"summary\":\"修复成果汇总\","
                + "\"expertName\":\"" + expert + "\",\"custodianName\":\"" + custodian
                + "\",\"submittedBy\":\"修复师甲\"}")).get("report").get("id").asLong();
        JsonNode items = dataOf(getJson("/api/accept-reports/" + reportId)).get("items");
        confirmReport(reportId, "INTERNAL", expert, items);
        confirmReport(reportId, "CUSTODIAN", custodian, items);
        assertEquals("PASSED", dataOf(getJson("/api/accept-reports/" + reportId))
                .get("report").get("status").asText());
    }

    private void confirmReport(long reportId, String role, String confirmer, JsonNode items)
            throws Exception {
        StringBuilder results = new StringBuilder();
        for (JsonNode item : items) {
            if (results.length() > 0) {
                results.append(',');
            }
            results.append("{\"itemId\":").append(item.get("id").asLong())
                    .append(",\"result\":\"OK\"}");
        }
        expectOk(postJson("/api/accept-reports/" + reportId + "/confirm", "{"
                + "\"role\":\"" + role + "\",\"confirmer\":\"" + confirmer
                + "\",\"items\":[" + results + "]}"));
    }

    private long insertEnvEvent(String eventNo, long hallId, String metric, int peak, String at) {
        EnvEvent e = new EnvEvent();
        e.setEventNo(eventNo);
        e.setHallId(hallId);
        e.setPointCode("P-1");
        e.setMetric(metric);
        e.setMaterial("青铜");
        e.setMergeKey(eventNo);
        e.setRuleVersionId(0L);
        e.setRuleVersionNo("NONE");
        e.setThresholdLow(new BigDecimal("0"));
        e.setThresholdHigh(new BigDecimal("30"));
        BigDecimal v = new BigDecimal(peak);
        e.setFirstValue(v);
        e.setLastValue(v);
        e.setPeakValue(v);
        e.setReadingCount(1);
        e.setFirstReadingId(0L);
        e.setLastReadingId(0L);
        LocalDateTime t = LocalDateTime.parse(at);
        e.setFirstAt(t);
        e.setLastAt(t);
        e.setStatus("OPEN");
        envEventMapper.insert(e);
        return e.getId();
    }

    // ------------------------------------------------------------------
    // 年度报表 helper
    // ------------------------------------------------------------------

    private long publishDefaultCaliber(String name, String changeReason) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"publishedBy\":\"统计员甲\""
                + (changeReason == null ? "" : ",\"changeReason\":\"" + changeReason + "\"") + "}";
        return dataOf(postJson("/api/annual-calibers", body)).get("id").asLong();
    }

    private long publishCaliber(String name, String specJson, String changeReason) throws Exception {
        return dataOf(postJson("/api/annual-calibers",
                "{\"name\":\"" + name + "\",\"publishedBy\":\"统计员甲\","
                        + "\"changeReason\":\"" + changeReason + "\",\"spec\":" + specJson + "}"))
                .get("id").asLong();
    }

    private long generate(int year, Long caliberId, String campus, String by) throws Exception {
        String body = "{\"year\":" + year
                + (caliberId == null ? "" : ",\"caliberVersionId\":" + caliberId)
                + (campus == null ? "" : ",\"campusScope\":\"" + campus + "\"")
                + ",\"generatedBy\":\"" + by + "\"}";
        return dataOf(postJson("/api/annual-reports/generate", body)).get("id").asLong();
    }

    private JsonNode detail(long id) throws Exception {
        return dataOf(getJson("/api/annual-reports/" + id));
    }

    private JsonNode findByProjectNo(JsonNode arr, String projectNo) {
        for (JsonNode n : arr) {
            if (projectNo.equals(n.get("projectNo").asText())) {
                return n;
            }
        }
        throw new AssertionError("项目明细未找到: " + projectNo);
    }

    private JsonNode findCaliber(JsonNode arr, int versionNo) {
        for (JsonNode n : arr) {
            if (n.get("versionNo").asInt() == versionNo) {
                return n;
            }
        }
        throw new AssertionError("口径版本未找到: V" + versionNo);
    }

    private JsonNode findLog(JsonNode logs, String status) {
        for (JsonNode n : logs) {
            if (status.equals(n.get("status").asText())) {
                return n;
            }
        }
        return null;
    }

    private List<JsonNode> anomaliesOf(long reportId, String type, long sourceId) throws Exception {
        return java.util.stream.StreamSupport.stream(detail(reportId).get("anomalies").spliterator(), false)
                .filter(n -> type.equals(n.get("anomalyType").asText())
                        && n.get("sourceId").asLong() == sourceId)
                .collect(Collectors.toList());
    }

    private String exportCsv(long reportId, String campus, String role) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/annual-reports/" + reportId + "/export")
                        .param("viewerCampus", campus).param("role", role))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // 通用 helper
    // ------------------------------------------------------------------

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getRaw(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return getRaw(uri);
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private void expectOk(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertFalse(root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
