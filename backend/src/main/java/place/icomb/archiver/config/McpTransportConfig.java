package place.icomb.archiver.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
@ConditionalOnProperty(
    prefix = "spring.ai.mcp.server",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class McpTransportConfig {

  @Bean
  @ConditionalOnMissingBean
  public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
      ObjectMapper objectMapper) {
    return WebMvcStreamableServerTransportProvider.builder()
        .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
        .mcpEndpoint("/api/mcp/sse")
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(name = "webMvcStreamableServerRouterFunction")
  public RouterFunction<ServerResponse> webMvcStreamableServerRouterFunction(
      WebMvcStreamableServerTransportProvider webMvcProvider) {
    return webMvcProvider.getRouterFunction();
  }
}
