package mdaros.training.agentic.ai.koog.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties ( prefix = "app.llm" )
public class AppLlmProperties {

	private Provider provider = Provider.OLLAMA;
	private String model = "qwen3:4b";
	private Long contextLength;
	private Long maxOutputTokens;
	private String baseUrl;
	private String apiKey;
	private String apiKeyEnv;
	private Retry retry = new Retry ();
	private DataSourceConfig dataSourceConfig = new DataSourceConfig ();

	public enum Provider {

		OLLAMA,
		OPENAI,
		GOOGLE,
		ANTHROPIC,
		OPENROUTER,
		MISTRAL,
		GROQ
	}

	@Setter
	@Getter
	public static class Retry {

		private boolean enabled = true;
		private int maxAttempts = 3;
		private long initialDelayMillis = 1_000L;
		private long maxDelayMillis = 20_000L;
		private double backoffMultiplier = 2.0;
		private double jitterFactor = 0.2;
	}

	@Setter
	@Getter
	public static class DataSourceConfig {

		private String url;
		private String username;
		private String password;
		private String driverClassName;
		private int    maximumPoolSize   = 10;
		private int    minimumIdle       = 2;
		private long   idleTimeout       = 30_000L;
		private long   connectionTimeout = 30_000L;
		private long   maxLifetime       = 1_800_000L;
	}
}
