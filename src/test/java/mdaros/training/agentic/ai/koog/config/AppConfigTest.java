package mdaros.training.agentic.ai.koog.config;

import ai.koog.agents.core.agent.entity.AIAgentSubgraphBase;
import ai.koog.prompt.executor.clients.LLMClient;
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient;
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient;
import ai.koog.prompt.executor.ollama.client.OllamaClient;
import mdaros.training.agentic.ai.koog.model.RequirementAnalysis;
import mdaros.training.agentic.ai.koog.model.TestPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppConfigTest {

	@Test
	void llmModelUsesConfiguredTokenLimitsForCustomModels () {

		AppConfig config = new AppConfig ();
		AppLlmProperties properties = new AppLlmProperties ();
		properties.setProvider ( AppLlmProperties.Provider.GROQ );
		properties.setModel ( "llama-3.3-70b-versatile" );
		properties.setContextLength ( 131_072L );
		properties.setMaxOutputTokens ( 1_536L );

		var model = config.llmModel ( properties );

		assertThat ( model.getContextLength () )
			.isEqualTo ( 131_072L );
		assertThat ( model.getMaxOutputTokens () )
			.isEqualTo ( 1_536L );
	}

	@Test
	void agentPromptsLoadFromClasspath () {

		AppConfig config = new AppConfig ();

		assertThat ( config.analystAgent ( null ) )
			.isNotNull ();
		assertThat ( config.qaEngineerAgent ( null ) )
			.isNotNull ();
	}

	@Test
	void graphStrategyUsesDifferentSubgraphQualifiers () throws NoSuchMethodException {

		Method graphStrategy = AppConfig.class.getDeclaredMethod (
			"graphStrategy",
			AIAgentSubgraphBase.class,
			AIAgentSubgraphBase.class
			);

		assertThat ( graphStrategy.getParameters () )
			.extracting ( parameter -> parameter.getAnnotation ( Qualifier.class ).value () )
			.containsExactly ( "sw-analyst", "qa-engineer" );
	}

	@Test
	void graphStrategyConnectsAnalystThenQaEngineerThenFinish () {

		AppConfig config = new AppConfig ();
		AIAgentSubgraphBase<String, RequirementAnalysis> analyst = config.analystAgent ( null );
		AIAgentSubgraphBase<RequirementAnalysis, TestPlan> qaEngineer = config.qaEngineerAgent ( null );

		var graphStrategy = config.graphStrategy ( analyst, qaEngineer );

		assertThat ( graphStrategy.getNodeStart ().getEdges () )
			.singleElement ()
			.extracting ( edge -> edge.getToNode ().getName () )
			.isEqualTo ( "sw-analyst" );

		assertThat ( analyst.getEdges () )
			.singleElement ()
			.extracting ( edge -> edge.getToNode ().getName () )
			.isEqualTo ( "qa-engineer" );

		assertThat ( qaEngineer.getEdges () )
			.singleElement ()
			.extracting ( edge -> edge.getToNode ().getName () )
			.isEqualTo ( "__finish__" );
	}

	@ParameterizedTest
	@EnumSource ( value = AppLlmProperties.Provider.class, names = { "OLLAMA", "OPENAI", "ANTHROPIC", "GROQ" } )
	void llmClientSupportsConfiguredProvider ( AppLlmProperties.Provider provider ) throws Exception {

		LLMClient client = AppConfig.llmClient ( llmProperties ( provider ) );

		try {

			assertInstanceOf ( expectedClientType ( provider ), client );
		}
		finally {

			client.close ();
		}
	}

	@ParameterizedTest
	@EnumSource ( value = AppLlmProperties.Provider.class, names = { "GOOGLE", "OPENROUTER", "MISTRAL" } )
	void llmClientFailsWhenProviderIsNotSupportedByCurrentKoogClasspath ( AppLlmProperties.Provider provider ) {

		AppLlmProperties llmProperties = llmProperties ( provider );

		IllegalStateException exception = assertThrows (
			IllegalStateException.class,
			() -> AppConfig.llmClient ( llmProperties )
		);

		assertEquals ( "Unsupported or missing LLM provider: " + provider, exception.getMessage () );
	}

	@Test
	void llmClientFailsWhenProviderIsMissing () {

		AppLlmProperties llmProperties = llmProperties ( null );

		IllegalStateException exception = assertThrows (
			IllegalStateException.class,
			() -> AppConfig.llmClient ( llmProperties )
		);

		assertEquals ( "Unsupported or missing LLM provider: null", exception.getMessage () );
	}

	private static AppLlmProperties llmProperties ( AppLlmProperties.Provider provider ) {

		AppLlmProperties llmProperties = new AppLlmProperties ();
		llmProperties.setProvider ( provider );
		llmProperties.setApiKey ( "test-api-key" );
		llmProperties.setBaseUrl ( "https://api.groq.com" );

		return llmProperties;
	}

	private static Class<? extends LLMClient> expectedClientType ( AppLlmProperties.Provider provider ) {

		return switch ( provider ) {

			case OLLAMA -> OllamaClient.class;
			case OPENAI, GROQ -> OpenAILLMClient.class;
			case ANTHROPIC -> AnthropicLLMClient.class;
			default -> throw new IllegalArgumentException ( "Unsupported test provider: " + provider );
		};
	}
}
