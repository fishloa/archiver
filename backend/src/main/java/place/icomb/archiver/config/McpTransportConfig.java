package place.icomb.archiver.config;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@ConditionalOnProperty(
    prefix = "spring.ai.mcp.server",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class McpTransportConfig {

  @Bean
  @ConditionalOnMissingBean
  public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider() {
    JsonMapper mapper =
        JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
            .build();
    return WebMvcStreamableServerTransportProvider.builder()
        .jsonMapper(new JacksonMcpJsonMapper(mapper))
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
