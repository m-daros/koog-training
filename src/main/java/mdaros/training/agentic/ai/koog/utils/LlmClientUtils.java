package mdaros.training.agentic.ai.koog.utils;

import ai.koog.http.client.HttpClientFactoryResolver;
import ai.koog.http.client.KoogHttpClient;
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig;
import ai.koog.prompt.executor.clients.LLMClient;
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings;
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient;
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings;
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient;
import ai.koog.prompt.executor.clients.retry.RetryConfig;
import ai.koog.prompt.executor.clients.retry.RetryConfigBuilder;
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient;
import ai.koog.prompt.executor.ollama.client.OllamaClient;
import ai.koog.prompt.llm.LLMProvider;
import mdaros.training.agentic.ai.koog.config.AppLlmProperties;

import java.time.Duration;

public class LlmClientUtils {

	private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
	private static final String DEFAULT_GROQ_BASE_URL = "https://api.groq.com";
	private static final String DEFAULT_GOOGLE_BASE_URL = "https://generativelanguage.googleapis.com";
	private static final String DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai";
	private static final String DEFAULT_MISTRAL_BASE_URL = "https://api.mistral.ai";

	public static LLMClient llmClient ( AppLlmProperties llmProperties ) {

		AppLlmProperties.Provider provider = llmProperties.getProvider ();

		if ( provider == null ) {

			throw unsupportedLlmProvider ( unsupportedLlmProviderMessage ( null ) );
		}

		return switch ( provider ) {

			case OLLAMA -> ollamaClient ( llmProperties );
			case OPENAI -> openAiClient ( llmProperties );
			case GOOGLE -> openAiCompatibleClient (
					llmProperties,
					LLMProvider.Google,
					"GEMINI_API_KEY",
					DEFAULT_GOOGLE_BASE_URL,
					"v1beta/openai/chat/completions",
					"v1beta/openai/responses",
					"v1beta/openai/embeddings",
					"v1beta/openai/moderations",
					"v1beta/openai/models"
			);
			case ANTHROPIC -> anthropicClient ( llmProperties );
			case OPENROUTER -> openAiCompatibleClient (
					llmProperties,
					LLMProvider.OpenRouter,
					"OPENROUTER_API_KEY",
					DEFAULT_OPENROUTER_BASE_URL,
					"api/v1/chat/completions",
					"api/v1/responses",
					"api/v1/embeddings",
					"api/v1/moderations",
					"api/v1/models"
			);
			case MISTRAL -> openAiCompatibleClient (
					llmProperties,
					LLMProvider.MistralAI,
					"MISTRAL_API_KEY",
					DEFAULT_MISTRAL_BASE_URL,
					"v1/chat/completions",
					"v1/responses",
					"v1/embeddings",
					"v1/moderations",
					"v1/models"
			);
			case GROQ -> openAiCompatibleClient (
					llmProperties,
					LLMProvider.OpenAI,
					"GROQ_API_KEY",
					DEFAULT_GROQ_BASE_URL,
					"openai/v1/chat/completions",
					"openai/v1/responses",
					"openai/v1/embeddings",
					"openai/v1/moderations",
					"openai/v1/models"
			);
			default -> throw unsupportedLlmProvider ( unsupportedLlmProviderMessage ( provider ) );
		};
	}

	public static LLMClient retryingClient ( LLMClient client, AppLlmProperties.Retry retry ) {

		if ( ! retry.isEnabled () ) {

			return client;
		}

		return new RetryingLLMClient ( client, retryConfig ( retry ) );
	}

	private static String apiKey ( AppLlmProperties llmProperties, String defaultEnvVar ) {

		String configuredApiKey = llmProperties.getApiKey ();

		if ( configuredApiKey != null && !configuredApiKey.isBlank () ) {

			return configuredApiKey;
		}

		String envVar = llmProperties.getApiKeyEnv ();

		if ( envVar == null || envVar.isBlank () ) {

			envVar = defaultEnvVar;
		}

		String apiKey = System.getenv ( envVar );

		if ( apiKey == null || apiKey.isBlank () ) {

			throw new IllegalStateException ( "Configure app.llm.api-key or set the " + envVar + " environment variable to use " + llmProperties.getProvider () );
		}

		return apiKey;
	}

	private static String configuredValue ( String value, String defaultValue ) {

		if ( value == null || value.isBlank () ) {

			return defaultValue;
		}

		return value;
	}

	private static IllegalStateException unsupportedLlmProvider ( String message ) {

		return new IllegalStateException ( message );
	}

	private static String unsupportedLlmProviderMessage ( AppLlmProperties.Provider provider ) {

		return "Unsupported or missing LLM provider: " + provider;
	}

	private static LLMClient ollamaClient ( AppLlmProperties llmProperties ) {

		return new OllamaClient ( httpClientFactory (), configuredValue ( llmProperties.getBaseUrl (), DEFAULT_OLLAMA_BASE_URL ) );
	}

	private static LLMClient openAiClient ( AppLlmProperties llmProperties ) {

		return new OpenAILLMClient (
			apiKey ( llmProperties, "OPENAI_API_KEY" ),
			openAiCompatibleSettings (
				configuredValue ( llmProperties.getBaseUrl (), "https://api.openai.com" ),
				"v1/chat/completions",
				"v1/responses",
				"v1/embeddings",
				"v1/moderations",
				"v1/models"
			),
			httpClientFactory ()
		);
	}

	private static LLMClient anthropicClient ( AppLlmProperties llmProperties ) {

		return new AnthropicLLMClient ( apiKey ( llmProperties, "ANTHROPIC_API_KEY" ), new AnthropicClientSettings (), httpClientFactory () );
	}

	private static LLMClient openAiCompatibleClient (
		AppLlmProperties llmProperties,
		LLMProvider provider,
		String defaultEnvVar,
		String defaultBaseUrl,
		String chatCompletionsPath,
		String responsesApiPath,
		String embeddingsPath,
		String moderationsPath,
		String modelsPath ) {

		return new ProviderAwareOpenAILLMClient (
			apiKey ( llmProperties, defaultEnvVar ),
			openAiCompatibleSettings (
				configuredValue ( llmProperties.getBaseUrl (), defaultBaseUrl ),
				chatCompletionsPath,
				responsesApiPath,
				embeddingsPath,
				moderationsPath,
				modelsPath
			),
			httpClientFactory (),
			provider
		);
	}

	private static OpenAIClientSettings openAiCompatibleSettings (
		String baseUrl,
		String chatCompletionsPath,
		String responsesApiPath,
		String embeddingsPath,
		String moderationsPath,
		String modelsPath ) {

		return new OpenAIClientSettings (
			baseUrl,
			new ConnectionTimeoutConfig (),
			chatCompletionsPath,
			responsesApiPath,
			embeddingsPath,
			moderationsPath,
			modelsPath
		);
	}

	private static KoogHttpClient.Factory httpClientFactory () {

		return HttpClientFactoryResolver.INSTANCE.resolve ();
	}

	private static RetryConfig retryConfig ( AppLlmProperties.Retry retry ) {

		return new RetryConfigBuilder ()
			.maxAttempts ( retry.getMaxAttempts () )
			.initialDelay ( Duration.ofMillis ( retry.getInitialDelayMillis () ) )
			.maxDelay ( Duration.ofMillis ( retry.getMaxDelayMillis () ) )
			.backoffMultiplier ( retry.getBackoffMultiplier () )
			.jitterFactor ( retry.getJitterFactor () )
			.build ();
	}

	private static final class ProviderAwareOpenAILLMClient extends OpenAILLMClient {

		private final LLMProvider provider;

		private ProviderAwareOpenAILLMClient ( String apiKey, OpenAIClientSettings settings, KoogHttpClient.Factory httpClientFactory, LLMProvider provider ) {

			super ( apiKey, settings, httpClientFactory );
			this.provider = provider;
		}

		@Override
		public LLMProvider llmProvider () {

			return provider;
		}
	}
}