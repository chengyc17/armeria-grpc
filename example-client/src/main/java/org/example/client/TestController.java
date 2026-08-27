package org.example.client;

import com.example.orderservice.GetOrderResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 订单服务 HTTP 测试入口：把 HTTP 请求转成 gRPC 调用，方便用浏览器或 curl 验证。
 *
 * <p>示例：{@code GET /order/1000} 会调用 {@link OrderClientService#getOrder(long)}，
 * 通过 Nacos 发现 example-server 并发起 gRPC 请求，结果以 JSON 返回。
 */
@RestController
@RequestMapping("/order")
public class TestController {

    private static final Logger log = LoggerFactory.getLogger(TestController.class);

    private final OrderClientService orderClientService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonFormat.Printer jsonPrinter = JsonFormat.printer();

    public TestController(OrderClientService orderClientService) {
        this.orderClientService = orderClientService;
    }

    /**
     * 查询订单：{@code GET /order/{orderId}}。
     */
    @GetMapping(value = "/{orderId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getOrder(@PathVariable("orderId") long orderId) {
        log.info("HTTP getOrder -> orderId={}", orderId);
        try {
            GetOrderResponse response = orderClientService.getOrder(orderId);
            return ResponseEntity.ok(jsonPrinter.print(response));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(jsonError(e.getMessage()));
        } catch (Exception e) {
            log.error("getOrder failed, orderId={}", orderId, e);
            return ResponseEntity.internalServerError()
                    .body(jsonError("调用订单服务失败: " + e.getMessage()));
        }
    }

    private String jsonError(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("message", "failed", "error", message));
        } catch (JsonProcessingException e) {
            return "{\"message\":\"failed\"}";
        }
    }
}
