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
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 并发起步验收：8 个请求同时打「起步」，只能有一次播种，全部命中同一条样本。
 * 使用独立内存库，避免与其他测试类的起步锁互相影响。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-bootstrap-concurrent;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrentBootstrapTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void concurrent_requests_still_create_one_sample() throws Exception {
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<JsonNode>> futures = new ArrayList<Future<JsonNode>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                MvcResult result = mockMvc.perform(post("/api/bootstrap")
                                .contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk()).andReturn();
                return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            }));
        }
        long firstRelicId = -1;
        int createdCount = 0;
        for (Future<JsonNode> future : futures) {
            JsonNode node = future.get(10, TimeUnit.SECONDS);
            assertTrue(node.get("success").asBoolean());
            long relicId = node.get("data").get("relicId").asLong();
            if (firstRelicId == -1) {
                firstRelicId = relicId;
            }
            assertEquals(firstRelicId, relicId, "并发起步必须命中同一条样本");
            if (node.get("data").get("created").asBoolean()) {
                createdCount++;
            }
        }
        pool.shutdown();
        assertEquals(1, createdCount, "并发下只能有一次播种");
    }
}
