package mdaros.training.agentic.ai.koog.config;

import ai.koog.agents.core.agent.AIAgent;
import ai.koog.agents.core.agent.ToolCalls;
import ai.koog.agents.core.agent.entity.*;
import ai.koog.agents.core.tools.ToolRegistry;
import ai.koog.agents.core.tools.ToolRegistryBuilder;
import ai.koog.prompt.executor.clients.LLMClient;
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig;
import ai.koog.prompt.executor.clients.google.GoogleLLMClient;
import ai.koog.prompt.executor.clients.google.GoogleModels;
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings;
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient;
import ai.koog.prompt.executor.clients.retry.RetryConfig;
import ai.koog.prompt.executor.clients.retry.RetryConfigBuilder;
import ai.koog.prompt.executor.clients.retry.RetryablePattern;
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient;
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor;
import ai.koog.prompt.executor.model.PromptExecutor;
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

import static ai.koog.prompt.executor.llms.all.SimplePromptExecutorsKt.simpleAnthropicExecutor;
import static ai.koog.prompt.executor.llms.all.SimplePromptExecutorsKt.simpleMistralAIExecutor;
import static ai.koog.prompt.executor.llms.all.SimplePromptExecutorsKt.simpleOllamaAIExecutor;
import static ai.koog.prompt.executor.llms.all.SimplePromptExecutorsKt.simpleOpenAIExecutor;
import static ai.koog.prompt.executor.llms.all.SimplePromptExecutorsKt.simpleOpenRouterExecutor;
import static mdaros.training.agentic.ai.koog.Constants.QA_ENGINEER_AGENT;
import static mdaros.training.agentic.ai.koog.Constants.SW_ANALYST_AGENT;

@Configuration
@EnableConfigurationProperties ( AppLlmProperties.class )
@ConditionalOnProperty ( prefix = "app.cli", name = "enabled", havingValue = "true", matchIfMissing = true )
public class AppConfig {

	private static final String AGENT_PROMPTS_PATH = "agents/";

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

		return switch ( llmProperties.getProvider () ) {

			case OLLAMA -> simpleOllamaAIExecutor ( requiredValue ( llmProperties.getBaseUrl (), "app.llm.base-url" ) );
			case OPENAI -> simpleOpenAIExecutor ( apiKey ( llmProperties, "OPENAI_API_KEY" ) );
			case GOOGLE -> googlePromptExecutor ( llmProperties );
			case ANTHROPIC -> simpleAnthropicExecutor ( apiKey ( llmProperties, "ANTHROPIC_API_KEY" ) );
			case OPENROUTER -> simpleOpenRouterExecutor ( apiKey ( llmProperties, "OPENROUTER_API_KEY" ) );
			case MISTRAL -> simpleMistralAIExecutor ( apiKey ( llmProperties, "MISTRAL_API_KEY" ) );
			case GROQ -> groqPromptExecutor ( llmProperties );
		};
	}

	private static PromptExecutor googlePromptExecutor ( AppLlmProperties llmProperties ) {

		LLMClient client = new GoogleLLMClient ( apiKey ( llmProperties, "GOOGLE_API_KEY" ) );

		if ( llmProperties.getRateLimit ().isEnabled () ) {

			client = new RateLimitedLLMClient (
				client,
				llmProperties.getRateLimit ().getRequestsPerMinute (),
				llmProperties.getRateLimit ().getRequestsPerDay (),
				llmProperties.getRateLimit ().getStateFile (),
				llmProperties.getRateLimit ().getTokensPerMinute (),
				llmProperties.getRateLimit ().getTokenEstimateOverhead ()
			);
		}

		if ( llmProperties.getRetry ().isEnabled () ) {

			client = new RetryingLLMClient ( client, retryConfig ( llmProperties.getRetry () ) );
		}

		return new SingleLLMPromptExecutor ( client );
	}

	private static PromptExecutor groqPromptExecutor ( AppLlmProperties llmProperties ) {

		LLMClient client = new OpenAILLMClient (
			apiKey ( llmProperties, "GROQ_API_KEY" ),
			new OpenAIClientSettings (
				requiredValue ( llmProperties.getBaseUrl (), "app.llm.base-url" ),
				new ConnectionTimeoutConfig (),
				"openai/v1/chat/completions",
				"openai/v1/responses",
				"openai/v1/embeddings",
				"openai/v1/moderations",
				"openai/v1/models"
			)
		);

		if ( llmProperties.getRateLimit ().isEnabled () ) {

			client = new RateLimitedLLMClient (
				client,
				llmProperties.getRateLimit ().getRequestsPerMinute (),
				llmProperties.getRateLimit ().getRequestsPerDay (),
				llmProperties.getRateLimit ().getStateFile (),
				llmProperties.getRateLimit ().getTokensPerMinute (),
				llmProperties.getRateLimit ().getTokenEstimateOverhead ()
			);
		}

		if ( llmProperties.getRetry ().isEnabled () ) {

			client = new RetryingLLMClient ( client, retryConfig ( llmProperties.getRetry () ) );
		}

		return new SingleLLMPromptExecutor ( client );
	}

	private static RetryConfig retryConfig ( AppLlmProperties.Retry retry ) {

		return new RetryConfigBuilder ()
			.maxAttempts ( retry.getMaxAttempts () )
			.initialDelay ( Duration.ofMillis ( retry.getInitialDelayMillis () ) )
			.maxDelay ( Duration.ofMillis ( retry.getMaxDelayMillis () ) )
			.backoffMultiplier ( retry.getBackoffMultiplier () )
			.jitterFactor ( retry.getJitterFactor () )
			.retryablePatterns ( List.of ( new RetryablePattern.Custom ( AppConfig::isRetryableProviderError ) ) )
			.build ();
	}

	private static boolean isRetryableProviderError ( String message ) {

		String lowerCaseMessage = message.toLowerCase ();

		if (
			lowerCaseMessage.contains ( "generaterequestsperday" )
				|| lowerCaseMessage.contains ( "perday" )
				|| lowerCaseMessage.contains ( "requests per day" )
		) {

			return false;
		}

		return lowerCaseMessage.contains ( "429" )
			|| lowerCaseMessage.contains ( "500" )
			|| lowerCaseMessage.contains ( "502" )
			|| lowerCaseMessage.contains ( "503" )
			|| lowerCaseMessage.contains ( "504" )
			|| lowerCaseMessage.contains ( "529" )
			|| lowerCaseMessage.contains ( "rate limit" )
			|| lowerCaseMessage.contains ( "too many requests" )
			|| lowerCaseMessage.contains ( "overloaded" )
			|| lowerCaseMessage.contains ( "request timeout" )
			|| lowerCaseMessage.contains ( "connection timeout" )
			|| lowerCaseMessage.contains ( "temporarily unavailable" )
			|| lowerCaseMessage.contains ( "service unavailable" );
	}

	@Bean
	@Lazy
	public LLModel llmModel ( AppLlmProperties llmProperties ) {

		return knownModel ( llmProperties );
	}

	private static LLModel knownModel ( AppLlmProperties llmProperties ) {

		String model = requiredValue ( llmProperties.getModel (), "app.llm.model" );

		if ( llmProperties.getProvider () == AppLlmProperties.Provider.GOOGLE ) {

			return switch ( model ) {
				case "gemini-2.0-flash" -> GoogleModels.Gemini2_0Flash;
				case "gemini-2.0-flash-001" -> GoogleModels.Gemini2_0Flash001;
				case "gemini-2.0-flash-lite" -> GoogleModels.Gemini2_0FlashLite;
				case "gemini-2.0-flash-lite-001" -> GoogleModels.Gemini2_0FlashLite001;
				case "gemini-2.5-pro" -> GoogleModels.Gemini2_5Pro;
				case "gemini-2.5-flash" -> GoogleModels.Gemini2_5Flash;
				case "gemini-2.5-flash-lite" -> GoogleModels.Gemini2_5FlashLite;
				case "gemini-3-flash-preview" -> GoogleModels.Gemini3_Flash_Preview;
				case "gemini-3-pro-preview" -> GoogleModels.Gemini3_Pro_Preview;
				default -> customModel ( llmProperties );
			};
		}

		return customModel ( llmProperties );
	}

	private static LLModel customModel ( AppLlmProperties llmProperties ) {

		return new LLModel (

			provider ( llmProperties.getProvider () ),
			llmProperties.getModel (),
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
			.runMode ( ToolCalls.SINGLE_RUN_SEQUENTIAL )
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
			.runMode ( ToolCalls.SINGLE_RUN_SEQUENTIAL )
			.build ();
	}
}