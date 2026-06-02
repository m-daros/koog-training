package mdaros.training.agentic.ai.koog.config;

import ai.koog.agents.core.agent.AIAgent;
import ai.koog.agents.core.agent.entity.*;
import ai.koog.agents.core.tools.ToolRegistry;
import ai.koog.agents.core.tools.ToolRegistryBuilder;
import ai.koog.http.client.HttpClientFactoryResolver;
import ai.koog.http.client.KoogHttpClient;
import ai.koog.prompt.executor.clients.LLMClient;
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig;
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings;
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient;
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings;
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient;
import ai.koog.prompt.executor.clients.retry.RetryConfig;
import ai.koog.prompt.executor.clients.retry.RetryConfigBuilder;
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient;
import ai.koog.prompt.executor.model.PromptExecutor;
import ai.koog.prompt.executor.model.PromptExecutorBuilder;
import ai.koog.prompt.executor.ollama.client.OllamaClient;
import ai.koog.prompt.llm.LLMCapability;
import ai.koog.prompt.llm.LLMProvider;
import ai.koog.prompt.llm.LLModel;
import mdaros.training.agentic.ai.koog.model.RequirementAnalysis;
import mdaros.training.agentic.ai.koog.model.TestPlan;
import mdaros.training.agentic.ai.koog.tools.AskUserToolSet;
import mdaros.training.agentic.ai.koog.support.FlatFinalizeTools;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.time.Duration;

import static mdaros.training.agentic.ai.koog.Constants.QA_ENGINEER_AGENT;
import static mdaros.training.agentic.ai.koog.Constants.SW_ANALYST_AGENT;

@Configuration
@EnableConfigurationProperties ( AppLlmProperties.class )
@ConditionalOnProperty ( prefix = "app.cli", name = "enabled", havingValue = "true", matchIfMissing = true )
public class AppConfig {

	private static final String AGENT_PROMPTS_PATH = "agents/";
	private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
	private static final String DEFAULT_GROQ_BASE_URL = "https://api.groq.com";
	private static final String DEFAULT_GOOGLE_BASE_URL = "https://generativelanguage.googleapis.com";
	private static final String DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai";
	private static final String DEFAULT_MISTRAL_BASE_URL = "https://api.mistral.ai";

	private static final List<LLMCapability> DEFAULT_MODEL_CAPABILITIES = List.of (

		LLMCapability.Completion.INSTANCE,
		LLMCapability.Temperature.INSTANCE,
		LLMCapability.Schema.JSON.Basic.INSTANCE,
		LLMCapability.Tools.INSTANCE,
		LLMCapability.OpenAIEndpoint.Completions.INSTANCE
	);

	@Bean
	@Lazy
	public PromptExecutor promptExecutor ( AppLlmProperties llmProperties ) {

		return new PromptExecutorBuilder ()
			.addClient ( retryingClient ( llmClient ( llmProperties ), llmProperties.getRetry () ) )
			.build ();
	}

	static LLMClient llmClient ( AppLlmProperties llmProperties ) {

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

	private static LLMClient retryingClient ( LLMClient client, AppLlmProperties.Retry retry ) {

		if ( ! retry.isEnabled () ) {

			return client;
		}

		return new RetryingLLMClient ( client, retryConfig ( retry ) );
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

	@Bean
	@Lazy
	public LLModel llmModel ( AppLlmProperties llmProperties ) {

		String model = requiredValue ( llmProperties.getModel (), "app.llm.model" );

		return new LLModel (

			provider ( llmProperties.getProvider () ),
			model,
			DEFAULT_MODEL_CAPABILITIES,
			llmProperties.getContextLength (),
			llmProperties.getMaxOutputTokens ()
		);
	}

	@Bean
	@Lazy
	public AIAgent<String, TestPlan> graphAgent ( AIAgentGraphStrategy<String, TestPlan> graphStrategy, PromptExecutor promptExecutor, LLModel llmModel ) {

		return AIAgent.builder ()
			.promptExecutor ( promptExecutor )
			.llmModel ( llmModel )
			.toolRegistry ( toolRegistry () )
			.graphStrategy ( graphStrategy )
			.temperature ( 0.0 )
			.build ();
	}

	private static ToolRegistry toolRegistry () {

		// Tools must be executable from the agent registry; subgraphs decide visibility with limitedTools.
		return new ToolRegistryBuilder ()
			.tools ( new AskUserToolSet () )
			.build ();
	}

	private static LLMProvider provider ( AppLlmProperties.Provider provider ) {

		return switch ( provider ) {

			case OLLAMA -> LLMProvider.Ollama;
			case OPENAI -> LLMProvider.OpenAI;
			case GOOGLE -> LLMProvider.Google;
			case ANTHROPIC -> LLMProvider.Anthropic;
			case OPENROUTER -> LLMProvider.OpenRouter;
			case MISTRAL -> LLMProvider.MistralAI;
			case GROQ -> LLMProvider.OpenAI;
		};
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

	private static String requiredValue ( String value, String propertyName ) {

		if ( value == null || value.isBlank () ) {

			throw new IllegalStateException ( propertyName + " must be configured" );
		}

		return value;
	}

	private static String configuredValue ( String value, String defaultValue ) {

		if ( value == null || value.isBlank () ) {

			return defaultValue;
		}

		return value;
	}

	private static final class ProviderAwareOpenAILLMClient extends OpenAILLMClient {

		private final LLMProvider provider;

		private ProviderAwareOpenAILLMClient (
			String apiKey,
			OpenAIClientSettings settings,
			KoogHttpClient.Factory httpClientFactory,
			LLMProvider provider ) {

			super ( apiKey, settings, httpClientFactory );
			this.provider = provider;
		}

		@Override
		public LLMProvider llmProvider () {

			return provider;
		}
	}

	private static String loadAgentPrompt ( String promptFileName ) {

		String resourcePath = AGENT_PROMPTS_PATH + promptFileName;
		ClassLoader classLoader = Thread.currentThread ().getContextClassLoader ();

		try ( InputStream inputStream = classLoader.getResourceAsStream ( resourcePath ) ) {

			if ( inputStream == null ) {

				throw new IllegalStateException ( "Agent prompt resource not found: " + resourcePath );
			}

			return new String ( inputStream.readAllBytes (), StandardCharsets.UTF_8 );
		}
		catch ( IOException exception ) {

			throw new IllegalStateException ( "Cannot load agent prompt resource: " + resourcePath, exception );
		}
	}

	@Bean
	@Lazy
	public AIAgentGraphStrategy<String, TestPlan> graphStrategy (

		@Qualifier ( SW_ANALYST_AGENT ) AIAgentSubgraphBase<String, RequirementAnalysis> analystSubGraph,
		@Qualifier ( QA_ENGINEER_AGENT ) AIAgentSubgraphBase<RequirementAnalysis, TestPlan> qaEngineerSubGraph ) {

		var builder = AIAgentGraphStrategy.builder ()
			.withInput ( String.class )
			.withOutput ( TestPlan.class );

		var startToAnalyst = AIAgentEdge.builder ()
			.from ( builder.nodeStart )
			.to ( analystSubGraph )
			.transformed ( requirement -> requirement )
			.build ();

		var analystToQaEngineer = AIAgentEdge.builder ()
			.from ( analystSubGraph )
			.to ( qaEngineerSubGraph )
			.transformed ( requirementAnalysis -> requirementAnalysis )
			.build ();

		var qaEngineerToFinish = AIAgentEdge.builder ()
			.from ( qaEngineerSubGraph )
			.to ( builder.nodeFinish )
			.transformed ( testPlan -> testPlan )
			.build ();

		builder.edge ( startToAnalyst );
		builder.edge ( analystToQaEngineer );
		builder.edge ( qaEngineerToFinish );

		return builder.build ();
	}

	@Bean
	@Lazy
	@Qualifier ( SW_ANALYST_AGENT )
	public AIAgentSubgraphBase<String, RequirementAnalysis> analystAgent ( PromptExecutor promptExecutor ) {

		var systemPrompt = loadAgentPrompt ( "sw-analyst.md" );

		return AIAgentSubgraph.builder ( SW_ANALYST_AGENT )
			.limitedTools ( new AskUserToolSet () )
			.withInput ( String.class )
			.withFinishTool ( FlatFinalizeTools.requirementAnalysis () )
			.withTask ( requirement ->  systemPrompt + "\n. Analyze the following requirement: " + requirement  )
			.parallelTools ( false )
			.build ();
	}

	@Bean
	@Lazy
	@Qualifier ( QA_ENGINEER_AGENT )
	public AIAgentSubgraphBase<RequirementAnalysis, TestPlan> qaEngineerAgent ( PromptExecutor promptExecutor ) {

		var systemPrompt = loadAgentPrompt ( "qa-engineer.md" );

		return AIAgentSubgraph.builder ( QA_ENGINEER_AGENT )
			.limitedTools ( Collections.emptyList () )
			.withInput ( RequirementAnalysis.class )
			.withFinishTool ( FlatFinalizeTools.testPlan () )
			.withTask ( requirementAnalysis ->  systemPrompt + "\n. Prepare a test plan for the given requirement analysis: " + requirementAnalysis  )
			.parallelTools ( false )
			.build ();
	}
}
