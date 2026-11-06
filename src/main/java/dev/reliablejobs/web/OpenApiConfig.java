package dev.reliablejobs.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfig {
  @Bean
  OpenAPI openAPI() {
    return new OpenAPI()
        .info(
            new Info()
                .title("ReliableJobs: Task Processing and Execution API")
                .version("1.0.0")
                .description(
                    "Session authentication; fetch /api/auth/csrf before login and again after"
                        + " login. Supply X-CSRF-TOKEN on every mutation. See docs/API.md for login"
                        + " and replay semantics."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "session",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("JSESSIONID")))
        .addSecurityItem(new SecurityRequirement().addList("session"));
  }
}
