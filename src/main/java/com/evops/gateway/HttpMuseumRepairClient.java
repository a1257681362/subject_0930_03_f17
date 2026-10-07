package com.evops.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部修复依赖网关的 HTTP 实现：向 {@code museum.packaging.repair-notify-url}
 * 投递装箱确认通知。
 *
 * <p>未配置地址或远端无响应/超时/非 2xx 一律抛
 * {@link MuseumRepairUnavailableException}——<b>绝不吞掉失败返回伪成功</b>。
 * 通知载荷带 {@code notifyKey} 幂等键，外部恢复后即使重复收到也只处理一次。</p>
 */
@Component
public class HttpMuseumRepairClient implements MuseumRepairClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMuseumRepairClient.class);

    private final String notifyUrl;
    private final RestTemplate restTemplate;

    public HttpMuseumRepairClient(
            @Value("${museum.packaging.repair-notify-url:}") String notifyUrl,
            @Value("${museum.packaging.repair-notify-timeout-ms:3000}") int timeoutMs) {
        this.notifyUrl = notifyUrl == null ? "" : notifyUrl.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public String notifyPackConfirmed(PackConfirmNotice notice) {
        if (notifyUrl.isEmpty()) {
            // 未配置外部依赖等同于依赖不可达：不得假装成功
            throw new MuseumRepairUnavailableException(
                    "博物馆文物修复外部依赖未配置/无响应，装箱确认通知未送达：" + notice.getOrderNo());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notifyKey", notice.getNotifyKey());
        payload.put("orderNo", notice.getOrderNo());
        payload.put("boxNo", notice.getBoxNo());
        payload.put("relicId", notice.getRelicId());
        payload.put("agreementId", notice.getAgreementId());
        payload.put("specVersionNo", notice.getSpecVersionNo());
        payload.put("packType", notice.getPackType());
        payload.put("actualWeightG", notice.getActualWeightG());
        payload.put("confirmedAt", notice.getConfirmedAt());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String receipt = restTemplate.postForObject(
                    notifyUrl, new HttpEntity<>(payload, headers), String.class);
            log.info("装箱确认通知已送达外部修复依赖：orderNo={}, key={}",
                    notice.getOrderNo(), notice.getNotifyKey());
            return receipt == null ? "ACK" : receipt;
        } catch (Exception e) {
            throw new MuseumRepairUnavailableException(
                    "博物馆文物修复外部依赖无响应，装箱确认通知未送达：" + notice.getOrderNo()
                            + "（将保留待确认状态，恢复后重试且不重复通知）", e);
        }
    }
}
