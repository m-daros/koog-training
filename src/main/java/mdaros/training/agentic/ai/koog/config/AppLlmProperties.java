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
	private String baseUrl = "http://localhost:11434";
	private String apiKey;
	private String apiKeyEnv;
	private Retry retry = new Retry ();

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
}
