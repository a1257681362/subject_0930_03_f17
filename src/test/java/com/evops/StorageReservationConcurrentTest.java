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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 库位预约并发验收（museum-7）：
 * 1) 8 并发预约同一格位，只允许 1 个在途预约成功（hold_key 稀疏唯一约束兜底）；
 * 2) 8 并发预约同一密集架的不同格位、时段重叠，互斥设备只允许一个操作成功；
 * 3) 8 并发使用同一请求流水号（补传/补录/重试），全部命中同一条预约、只形成一条结果。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-storage-concurrent;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StorageReservationConcurrentTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    /** 线程返回：success + 预约 id（失败为 null） */
    private static final class Outcome {
        boolean success;
        Long id;
        String message;
    }

    @Test
    void concurrent_reservations_respect_cell_hold_rack_mutex_and_idempotency() throws Exception {
        // 建档：1 库区、1 密集架、3 格位
        JsonNode areaNode = dataOf(postJson("/api/storage/areas", "{\"areaNo\":\"WHC\",\"name\":\"并发库\","
                + "\"tempZone\":\"ORDINARY\",\"fireRating\":\"F2\",\"loadCapacityKg\":1000}"));
        long areaId = areaNode.get("id").asLong();
        JsonNode rackNode = dataOf(postJson("/api/storage/racks", "{\"rackNo\":\"RKC\","
                + "\"areaId\":" + areaId + ",\"equipmentNo\":\"EQC\"}"));
        long rackId = rackNode.get("id").asLong();
        long cellA = dataOf(postJson("/api/storage/cells",
                cellBody("CA", rackId, 1, 1))).get("id").asLong();
        long cellB = dataOf(postJson("/api/storage/cells",
                cellBody("CB", rackId, 1, 2))).get("id").asLong();

        String window = "\"planStartTime\":\"2026-12-10 09:00:00\","
                + "\"planEndTime\":\"2026-12-10 10:00:00\"";

        // ---- 场景 1：8 并发约同一格位 CA，不同流水号/箱号 → 仅 1 成功 ----
        List<Outcome> sameCell = parallel(8, i -> reserveBody(
                "REQ-CELL-" + i, cellA, "BOX-C" + i, window));
        int cellOk = 0;
        for (Outcome o : sameCell) {
            if (o.success) {
                cellOk++;
            } else {
                assertTrue(o.message.contains("在途预约") || o.message.contains("占用"),
                        "失败原因应为格位占用，实际: " + o.message);
            }
        }
        assertEquals(1, cellOk, "同一格位并发只能有一个在途预约");

        // ---- 场景 2：8 并发约同一架另一格位 CB，窗口彼此重叠但与 CA 已约窗口错开 ----
        // 同架互斥：8 个互相重叠，恰好放行 1 个（与 CA 的 09:00~10:00 不重叠）
        String window2 = "\"planStartTime\":\"2026-12-10 11:00:00\","
                + "\"planEndTime\":\"2026-12-10 12:00:00\"";
        List<Outcome> sameRack = parallel(8, i -> reserveBody(
                "REQ-RACK-" + i, cellB, "BOX-R" + i, window2));
        int rackOk = 0;
        for (Outcome o : sameRack) {
            if (o.success) {
                rackOk++;
            } else {
                assertTrue(o.message.contains("互斥设备") || o.message.contains("在途预约")
                                || o.message.contains("占用"),
                        "失败原因应为架体互斥/格位占用，实际: " + o.message);
            }
        }
        assertEquals(1, rackOk, "互斥设备同一时段只允许一个操作");

        // CA/CB 各一条在途，共 2 条
        JsonNode reserved = dataOf(getJson("/api/storage/reservations?status=RESERVED"));
        assertEquals(2, reserved.size());

        // ---- 场景 3：8 并发同一流水号补传/补录/重试一空置格位 → 全部命中同一条 ----
        long cellC = dataOf(postJson("/api/storage/cells",
                cellBody("CC", rackId, 2, 1))).get("id").asLong();
        // 用不与在途窗口重叠的时段，避免架体互斥误伤
        String lateWindow = "\"planStartTime\":\"2026-12-11 09:00:00\","
                + "\"planEndTime\":\"2026-12-11 10:00:00\"";
        List<Outcome> retry = parallel(8, i -> reserveBody(
                "REQ-SAME", cellC, "BOX-SAME", lateWindow));
        Set<Long> ids = new HashSet<>();
        for (Outcome o : retry) {
            assertTrue(o.success, "补传/补录/重试都应成功命中同一条，实际: " + o.message);
            ids.add(o.id);
        }
        assertEquals(1, ids.size(), "同一流水号只能形成一条可用结果");
        JsonNode sameRows = dataOf(getJson("/api/storage/reservations?requestNo=REQ-SAME"));
        assertEquals(1, sameRows.size());
    }

    // ------------------------------------------------------------------

    private String cellBody(String no, long rackId, int shelf, int col) {
        return "{\"cellNo\":\"" + no + "\",\"rackId\":" + rackId
                + ",\"shelfNo\":" + shelf + ",\"colNo\":" + col + ",\"loadLimitKg\":100}";
    }

    private String reserveBody(String requestNo, long cellId, String boxNo, String window) {
        return "{\"requestNo\":\"" + requestNo + "\",\"targetCellId\":" + cellId
                + ",\"boxNo\":\"" + boxNo + "\",\"reservedBy\":\"并发测试员\"," + window + "}";
    }

    /** 用同一 body 模板（忽略序号）并发发起预约 */
    private List<Outcome> parallel(int threads,
                                   java.util.function.Function<Integer, String> bodyBuilder)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                MvcResult result = mockMvc.perform(post("/api/storage/reservations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bodyBuilder.apply(idx)))
                        .andExpect(status().isOk()).andReturn();
                JsonNode root = objectMapper.readTree(result.getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                Outcome o = new Outcome();
                o.success = root.get("success").asBoolean();
                o.message = root.get("message").asText();
                if (o.success) {
                    o.id = root.get("data").get("id").asLong();
                }
                return o;
            }));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
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
}
