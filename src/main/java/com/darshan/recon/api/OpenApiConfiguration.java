package com.darshan.recon.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    static final String BASIC_AUTH = "basicAuth";

    @Bean
    public OpenAPI reconOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Transaction reconciliation API")
                        .version("v1")
                        .description("Launches and monitors the statement ingestion and reconciliation jobs and runs "
                                + "the break resolution workflow for the operations team. Roles: VIEWER reads, "
                                + "OPERATOR launches jobs and resolves breaks, APPROVER confirms large write-offs."))
                .components(new Components().addSecuritySchemes(BASIC_AUTH,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList(BASIC_AUTH));
    }
}
