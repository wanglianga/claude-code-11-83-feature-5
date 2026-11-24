package com.foodsafety.exchange.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  OpenAPI exchangeOpenApi() {
    return new OpenAPI()
        .info(new Info()
            .title("省食品安全平台报文交换服务")
            .version("1.0.0")
            .description("入站不可变库、规范版本解析、样品/报告/修订身份关联、修订状态机、"
                + "操作号幂等投递、未知查询回执协调、事件重放与版本化投影对账；"
                + "食品污染源头候选推导（样品账/污染谱账/生产图 → 待调查候选与反证，非责任认定）。"))
        .addSecurityItem(new SecurityRequirement().addList("basicAuth"))
        .components(new Components().addSecuritySchemes("basicAuth",
            new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")));
  }
}
