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

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物库房库位与密集架预约（museum-7）业务规则验收：
 * 1) 库位按温区/防火等级/承重维护，停用/超重/非法枚举拦截；
 * 2) 搬入前预约目标格位，同格位在途唯一、箱号落库唯一；
 * 3) 移动单记录原位/目的位/箱号/操作人员，完成后库存清原格立目的格；
 * 4) 预约超时自动释放，但已开工（MOVING）的格位不释放；
 * 5) 互斥设备（密集架）同一时段只允许一个操作，错峰/不同架放行；
 * 6) 库存查询按馆员权限隐藏敏感位置（管理员全见/保管员脱敏/访客过滤）；
 * 7) 请求流水号补传/补录/重试只形成一条可用结果；台账动作链完整。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-storage;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StorageReservationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void storage_reservation_full_flow_holds() throws Exception {
        // ---- 库区建档：温区/防火等级/承重 ----
        expectFail(postJson("/api/storage/areas", "{"
                + "\"areaNo\":\"WH-1\",\"name\":\"一号库\",\"tempZone\":\"BAD\","
                + "\"fireRating\":\"F1\",\"loadCapacityKg\":1000}"), "温区");
        expectFail(postJson("/api/storage/areas", "{"
                + "\"areaNo\":\"WH-1\",\"name\":\"一号库\",\"tempZone\":\"CONST_TEMP\","
                + "\"fireRating\":\"F9\",\"loadCapacityKg\":1000}"), "防火等级");
        expectFail(postJson("/api/storage/areas", "{"
                + "\"areaNo\":\"WH-1\",\"name\":\"一号库\",\"tempZone\":\"CONST_TEMP\","
                + "\"fireRating\":\"F1\",\"loadCapacityKg\":-1}"), "承重");
        JsonNode area = dataOf(postJson("/api/storage/areas", "{"
                + "\"areaNo\":\"WH-1\",\"name\":\"一号恒温库\",\"tempZone\":\"CONST_TEMP\","
                + "\"fireRating\":\"F1\",\"loadCapacityKg\":1000,"
                + "\"location\":\"地下一层\",\"manager\":\"库管主任\"}"));
        long areaId = area.get("id").asLong();
        expectFail(postJson("/api/storage/areas", "{"
                + "\"areaNo\":\"WH-1\",\"name\":\"重复库区\",\"tempZone\":\"ORDINARY\","
                + "\"fireRating\":\"F2\",\"loadCapacityKg\":100}"), "库区编号已存在");

        // ---- 密集架（互斥设备） ----
        long rack1 = dataOf(postJson("/api/storage/racks", "{"
                + "\"rackNo\":\"RK-1\",\"areaId\":" + areaId
                + ",\"equipmentNo\":\"EQ-01\",\"name\":\"甲区一排密集架\",\"aisle\":\"A 通道\"}"))
                .get("id").asLong();
        expectFail(postJson("/api/storage/racks", "{"
                + "\"rackNo\":\"RK-1X\",\"areaId\":" + areaId
                + ",\"equipmentNo\":\"EQ-01\",\"name\":\"重号设备\"}"), "互斥设备编号已存在");
        long rack2 = dataOf(postJson("/api/storage/racks", "{"
                + "\"rackNo\":\"RK-2\",\"areaId\":" + areaId
                + ",\"equipmentNo\":\"EQ-02\",\"name\":\"甲区二排密集架\",\"aisle\":\"B 通道\"}"))
                .get("id").asLong();

        // ---- 格位：C1 普通 / C2 敏感 / C3 普通（架1）；C4 普通（架2）；C5 小承重（架2） ----
        long c1 = createCell("C-1", rack1, 1, 1, "100", false);
        long c2 = createCell("C-2", rack1, 1, 2, "100", true);
        long c3 = createCell("C-3", rack1, 2, 1, "100", false);
        long c4 = createCell("C-4", rack2, 1, 1, "100", false);
        createCell("C-5", rack2, 2, 1, "50", false);
        // 格位承重不得超过库区承重
        expectFail(postJson("/api/storage/cells", "{"
                + "\"cellNo\":\"C-BAD\",\"rackId\":" + rack1 + ",\"loadLimitKg\":5000}"),
                "超过库区承重");
        expectFail(postJson("/api/storage/cells", "{"
                + "\"cellNo\":\"C-1\",\"rackId\":" + rack1 + ",\"loadLimitKg\":10}"),
                "格位编号已存在");

        String win1 = "\"planStartTime\":\"2026-12-01 10:00:00\",\"planEndTime\":\"2026-12-01 11:00:00\"";

        // ---- 预约目标格位（新入库） ----
        JsonNode res1 = dataOf(postJson("/api/storage/reservations", "{"
                + "\"requestNo\":\"REQ-1\",\"targetCellId\":" + c1
                + ",\"boxNo\":\"BOX-1\",\"reservedBy\":\"预约员甲\"," + win1 + "}"));
        long res1Id = res1.get("id").asLong();
        assertEquals("RESERVED", res1.get("status").asText());
        assertEquals(c1, res1.get("holdKey").asLong());
        assertNotNull(res1.get("expireAt").asText());

        // 同一格位在途唯一：错峰也不行
        expectFail(postJson("/api/storage/reservations", "{"
                + "\"requestNo\":\"REQ-1B\",\"targetCellId\":" + c1
                + ",\"boxNo\":\"BOX-X\",\"reservedBy\":\"预约员乙\","
                + "\"planStartTime\":\"2026-12-01 15:00:00\",\"planEndTime\":\"2026-12-01 16:00:00\"}"),
                "在途预约");

        // ---- 请求流水号幂等：补传/补录/重试三次只形成一条 ----
        for (int i = 0; i < 2; i++) {
            JsonNode again = dataOf(postJson("/api/storage/reservations", "{"
                    + "\"requestNo\":\"REQ-1\",\"targetCellId\":" + c1
                    + ",\"boxNo\":\"BOX-1\",\"reservedBy\":\"预约员甲\"," + win1 + "}"));
            assertEquals(res1Id, again.get("id").asLong(), "同一流水号必须命中同一条预约");
        }
        JsonNode byReq = dataOf(getJson("/api/storage/reservations?requestNo=REQ-1"));
        assertEquals(1, byReq.size(), "补传/补录/重试只允许一条结果");

        // ---- 互斥设备：同架时段重叠拒绝；不同架同时段放行 ----
        expectFail(postJson("/api/storage/reservations", "{"
                + "\"requestNo\":\"REQ-2\",\"targetCellId\":" + c3
                + ",\"boxNo\":\"BOX-2\",\"reservedBy\":\"预约员乙\","
                + "\"planStartTime\":\"2026-12-01 10:30:00\",\"planEndTime\":\"2026-12-01 11:30:00\"}"),
                "互斥设备");
        // 不同架 EQ-02 同时段：放行，随后取消
        long res2Id = dataOf(postJson("/api/storage/reservations", "{"
                + "\"requestNo\":\"REQ-2\",\"targetCellId\":" + c4
                + ",\"boxNo\":\"BOX-2\",\"reservedBy\":\"预约员乙\","
                + "\"planStartTime\":\"2026-12-01 10:30:00\",\"planEndTime\":\"2026-12-01 11:30:00\"}"))
                .get("id").asLong();
        expectOk(postJson("/api/storage/reservations/" + res2Id + "/cancel",
                "{\"reason\":\"改期，取消架2预约\"}"));
        // 同架但时间端点相接（11:00 接 11:00）不算重叠：放行
        long res3Id = dataOf(postJson("/api/storage/reservations", "{"
                + "\"requestNo\":\"REQ-3\",\"targetCellId\":" + c3
                + ",\"boxNo\":\"BOX-3\",\"reservedBy\":\"预约员乙\","
                + "\"planStartTime\":\"2026-12-01 11:00:00\",\"planEndTime\":\"2026-12-01 12:00:00\"}"))
                .get("id").asLong();

        // ---- 开工移动：移动单四要素（原位/目的位/箱号/操作人员） ----
        JsonNode order1 = dataOf(postJson("/api/storage/reservations/" + res1Id + "/start",
                "{\"operator\":\"搬运工张三\"}"));
        assertEquals("MOVING", order1.get("status").asText());
        assertEquals("BOX-1", order1.get("boxNo").asText());
        assertEquals("C-1", order1.get("targetCellNo").asText());
        assertEquals("搬运工张三", order1.get("operator").asText());
        assertTrue(order1.get("originCellNo").isNull(), "新入库移动单无原位");
        // 重复开工幂等：回同一条移动单，不产生第二条
        JsonNode order1Again = dataOf(postJson("/api/storage/reservations/" + res1Id + "/start",
                "{\"operator\":\"搬运工张三\"}"));
        assertEquals(order1.get("id").asLong(), order1Again.get("id").asLong());
        // 已开工不可取消
        expectFail(postJson("/api/storage/reservations/" + res1Id + "/cancel",
                "{\"reason\":\"开工后想取消\"}"), "已开工");
        // 另一在途预约 RESERVED 可取消
        expectOk(postJson("/api/storage/reservations/" + res3Id + "/cancel",
                "{\"reason\":\"箱未到，取消\"}"));

        // ---- 完成移动：库存落到目的格 ----
        expectOk(postEmpty("/api/storage/reservations/" + res1Id + "/complete"));
        JsonNode stockAfter = dataOf(getJson("/api/storage/stock?role=WAREHOUSE_KEEPER"));
        JsonNode box1Row = findStock(stockAfter, "BOX-1");
        assertEquals("C-1", box1Row.get("cellNo").asText());
        // 重复完成幂等：BOX-1 在库存中仍只有一条
        expectOk(postEmpty("/api/storage/reservations/" + res1Id + "/complete"));
        assertEquals(1, countBox(dataOf(getJson(
                "/api/storage/stock?role=WAREHOUSE_KEEPER")), "BOX-1"));

        // ---- 库内移动：原位 -> 新格位，完成后清原格、立目的格 ----
        long res4Id = dataOf(postJson("/api/storage/reservations", "{"
                + "\"requestNo\":\"REQ-4\",\"originCellId\":" + c1 + ",\"targetCellId\":" + c3
                + ",\"boxNo\":\"BOX-1\",\"reservedBy\":\"预约员甲\","
                + "\"planStartTime\":\"2026-12-01 14:00:00\",\"planEndTime\":\"2026-12-01 15:00:00\"}"))
                .get("id").asLong();
        // 原格位没有该箱号时库内移动被拒
        expectFail(postJson("/api/storage/reservations", "{"
                + "\"requestNo\":\"REQ-BADORIGIN\",\"originCellId\":" + c4 + ",\"targetCellId\":" + c2
                + ",\"boxNo\":\"BOX-404\",\"reservedBy\":\"预约员甲\","
                + "\"planStartTime\":\"2026-12-02 09:00:00\",\"planEndTime\":\"2026-12-02 10:00:00\"}"),
                "当前未存放箱号");
        JsonNode order4 = dataOf(postJson("/api/storage/reservations/" + res4Id + "/start",
                "{\"operator\":\"搬运工李四\"}"));
        assertEquals("C-1", order4.get("originCellNo").asText());
        assertEquals("C-3", order4.get("targetCellNo").asText());
        expectOk(postEmpty("/api/storage/reservations/" + res4Id + "/complete"));
        JsonNode stockMoved = dataOf(getJson("/api/storage/stock?role=WAREHOUSE_KEEPER"));
        assertEquals("C-3", findStock(stockMoved, "BOX-1").get("cellNo").asText());

        // ---- 敏感格位落箱（BOX-9 -> C-2） ----
        long res9Id = dataOf(postJson("/api/storage/reservations", "{"
                + "\"requestNo\":\"REQ-9\",\"targetCellId\":" + c2
                + ",\"boxNo\":\"BOX-9\",\"reservedBy\":\"预约员甲\","
                + "\"planStartTime\":\"2026-12-01 08:00:00\",\"planEndTime\":\"2026-12-01 09:00:00\"}"))
                .get("id").asLong();
        expectOk(postJson("/api/storage/reservations/" + res9Id + "/start",
                "{\"operator\":\"搬运工王五\"}"));
        expectOk(postEmpty("/api/storage/reservations/" + res9Id + "/complete"));

        // ---- 承重校验：C-5 上限 50kg，60kg 拒约 ----
        long c5 = idOfCell("C-5");
        expectFail(postJson("/api/storage/reservations", "{"
                + "\"requestNo\":\"REQ-HEAVY\",\"targetCellId\":" + c5
                + ",\"boxNo\":\"BOX-H\",\"weightKg\":60,\"reservedBy\":\"甲\","
                + "\"planStartTime\":\"2026-12-03 08:00:00\","
                + "\"planEndTime\":\"2026-12-03 09:00:00\"}"),
                "超过格位承重");

        // ---- 超时自动释放：expireMinutes=0 立即到期 ----
        long res6Id = dataOf(postJson("/api/storage/reservations", "{"
                + "\"requestNo\":\"REQ-6\",\"targetCellId\":" + c4
                + ",\"boxNo\":\"BOX-6\",\"reservedBy\":\"预约员丙\",\"expireMinutes\":0,"
                + "\"planStartTime\":\"2026-12-02 08:00:00\",\"planEndTime\":\"2026-12-02 09:00:00\"}"))
                .get("id").asLong();
        // 已超时不能开工
        expectFail(postJson("/api/storage/reservations/" + res6Id + "/start",
                "{\"operator\":\"赵六\"}"), "已超时");
        JsonNode scan1 = dataOf(postEmpty("/api/storage/reservations/expire-scan"));
        assertEquals(1, scan1.get("released").asInt());
        assertEquals("EXPIRED", dataOf(getJson("/api/storage/reservations/" + res6Id))
                .get("reservation").get("status").asText());
        // 释放后格位可再约
        long res7Id = dataOf(postJson("/api/storage/reservations", "{"
                + "\"requestNo\":\"REQ-7\",\"targetCellId\":" + c4
                + ",\"boxNo\":\"BOX-7\",\"reservedBy\":\"预约员丙\","
                + "\"planStartTime\":\"2026-12-02 10:00:00\",\"planEndTime\":\"2026-12-02 11:00:00\"}"))
                .get("id").asLong();

        // ---- 已开工（MOVING）即使到期也不释放 ----
        expectOk(postJson("/api/storage/reservations/" + res7Id + "/start",
                "{\"operator\":\"搬运工赵六\"}"));
        forceExpirePast(res7Id); // 白盒：把在途移动单的超时时刻改到过去
        JsonNode scan2 = dataOf(postEmpty("/api/storage/reservations/expire-scan"));
        assertEquals(0, scan2.get("released").asInt(), "已开工格位不得被超时释放");
        JsonNode res7 = dataOf(getJson("/api/storage/reservations/" + res7Id)).get("reservation");
        assertEquals("MOVING", res7.get("status").asText());
        assertEquals(c4, res7.get("holdKey").asLong(), "MOVING 单继续持有格位");
        // 格位仍不可再约
        expectFail(postJson("/api/storage/reservations", "{"
                + "\"requestNo\":\"REQ-7B\",\"targetCellId\":" + c4
                + ",\"boxNo\":\"BOX-7B\",\"reservedBy\":\"他人\","
                + "\"planStartTime\":\"2026-12-02 10:30:00\",\"planEndTime\":\"2026-12-02 11:30:00\"}"),
                "在途预约");
        expectOk(postEmpty("/api/storage/reservations/" + res7Id + "/complete"));

        // ---- 停用格位不能新预约 ----
        expectOk(postEmpty("/api/storage/cells/" + c4 + "/disable"));
        expectFail(postJson("/api/storage/reservations", "{"
                + "\"requestNo\":\"REQ-DIS\",\"targetCellId\":" + c4
                + ",\"boxNo\":\"BOX-D\",\"reservedBy\":\"甲\","
                + "\"planStartTime\":\"2026-12-04 08:00:00\",\"planEndTime\":\"2026-12-04 09:00:00\"}"),
                "已停用");

        // ---- 库存查询按馆员权限隐藏敏感位置 ----
        // 访客：敏感箱 BOX-9 直接过滤；未声明 role 同访客
        JsonNode viewerStock = dataOf(getJson("/api/storage/stock?role=VIEWER"));
        assertFalse(hasBox(viewerStock, "BOX-9"), "访客不得见敏感格位");
        assertTrue(hasBox(viewerStock, "BOX-1"));
        JsonNode anonStock = dataOf(getJson("/api/storage/stock"));
        assertFalse(hasBox(anonStock, "BOX-9"), "未声明角色按访客过滤敏感格位");
        // 保管员：敏感箱保留行但精确位置脱敏
        JsonNode curatorSensitive = findStock(
                dataOf(getJson("/api/storage/stock?role=CURATOR")), "BOX-9");
        assertTrue(curatorSensitive.get("masked").asBoolean());
        assertEquals("敏感库位", curatorSensitive.get("locationName").asText());
        assertTrue(curatorSensitive.get("cellNo").isNull());
        assertTrue(curatorSensitive.get("rackId").isNull());
        // 保管员看普通箱仍有精确位置
        JsonNode curatorNormal = findStock(
                dataOf(getJson("/api/storage/stock?role=CURATOR")), "BOX-1");
        assertFalse(curatorNormal.get("masked").asBoolean());
        assertEquals("C-3", curatorNormal.get("cellNo").asText());
        // 库房管理员：敏感位置精确可见
        JsonNode keeperSensitive = findStock(
                dataOf(getJson("/api/storage/stock?role=WAREHOUSE_KEEPER")), "BOX-9");
        assertFalse(keeperSensitive.get("masked").asBoolean());
        assertEquals("C-2", keeperSensitive.get("cellNo").asText());

        // ---- 台账动作链完整可追溯 ----
        JsonNode ledger = dataOf(getJson("/api/storage/reservations/" + res1Id)).get("ledger");
        Set<String> actions = new HashSet<>();
        for (JsonNode entry : ledger) {
            actions.add(entry.get("action").asText());
        }
        assertTrue(actions.contains("RESERVE"));
        assertTrue(actions.contains("MOVE_START"));
        assertTrue(actions.contains("MOVE_COMPLETE"));
        // 超时释放也入台账
        JsonNode ledger6 = dataOf(getJson("/api/storage/reservations/" + res6Id)).get("ledger");
        assertEquals("RESERVE_EXPIRE", ledger6.get(ledger6.size() - 1).get("action").asText());
    }

    // ------------------------------------------------------------------

    private long createCell(String cellNo, long rackId, Integer shelf, Integer col,
                            String loadLimit, boolean sensitive) throws Exception {
        return dataOf(postJson("/api/storage/cells", "{"
                + "\"cellNo\":\"" + cellNo + "\",\"rackId\":" + rackId
                + ",\"shelfNo\":" + shelf + ",\"colNo\":" + col
                + ",\"loadLimitKg\":" + loadLimit
                + ",\"sensitive\":" + sensitive + "}")).get("id").asLong();
    }

    private long idOfCell(String cellNo) throws Exception {
        for (JsonNode cell : dataOf(getJson("/api/storage/cells"))) {
            if (cellNo.equals(cell.get("cellNo").asText())) {
                return cell.get("id").asLong();
            }
        }
        throw new IllegalStateException("未找到格位 " + cellNo);
    }

    private JsonNode findStock(JsonNode stock, String boxNo) {
        for (JsonNode row : stock) {
            if (boxNo.equals(row.get("boxNo").asText())) {
                return row;
            }
        }
        throw new IllegalStateException("库存中未找到箱号 " + boxNo + "，实际: " + stock);
    }

    private boolean hasBox(JsonNode stock, String boxNo) {
        for (JsonNode row : stock) {
            if (boxNo.equals(row.get("boxNo").asText())) {
                return true;
            }
        }
        return false;
    }

    private int countBox(JsonNode stock, String boxNo) {
        int n = 0;
        for (JsonNode row : stock) {
            if (boxNo.equals(row.get("boxNo").asText())) {
                n++;
            }
        }
        return n;
    }

    /** 白盒数据准备：把预约的超时时刻改到过去，验证 MOVING 不被超时扫描释放 */
    private void forceExpirePast(long reservationId) {
        // 通过取消/释放接口无法构造「已到期但 MOVING」，直接用 JdbcTemplate 回写超时时刻
        try {
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate =
                    new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            int rows = jdbcTemplate.update(
                    "UPDATE t_storage_reservation SET expire_at = ? WHERE id = ?",
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(1)),
                    reservationId);
            assertEquals(1, rows);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Autowired
    private javax.sql.DataSource dataSource;

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
        assertFalse(root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
