package place.icomb.archiver.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
public class McpTransportConfig {

  @Bean
  @ConditionalOnMissingBean
  public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
      @Autowired(required = false) @Qualifier("mcpServerObjectMapper") ObjectMapper mcpMapper,
      ObjectMapper defaultMapper) {
    ObjectMapper mapper = mcpMapper != null ? mcpMapper : defaultMapper;
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
