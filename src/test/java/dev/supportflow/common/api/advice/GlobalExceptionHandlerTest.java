package dev.supportflow.common.api.advice;

import dev.supportflow.order.application.exception.OrderNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @Test
    @DisplayName("订单查询业务异常被转换为统一的 404 JSON 响应")
    void shouldMapOrderNotFoundToHttpResponse() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ThrowingOrderController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/test/order-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.code").value(
                        "ORDER_NOT_FOUND"
                ))
                .andExpect(jsonPath("$.message").value(
                        "订单不存在或无权访问"
                ));
    }

    /**
     * 测试夹具：提供一个确定会抛出订单异常的请求入口。
     */
    @RestController
    static class ThrowingOrderController {

        @GetMapping("/test/order-not-found")
        public String getOrder() {
            throw new OrderNotFoundException();
        }
    }
}