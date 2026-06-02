package mdaros.training.agentic.ai.koog.config;

import ai.koog.agents.core.agent.AIAgent;
import ai.koog.agents.core.agent.entity.*;
import ai.koog.agents.core.tools.ToolRegistry;
import ai.koog.agents.core.tools.ToolRegistryBuilder;
import ai.koog.http.client.KoogHttpClient;
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings;
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient;
import ai.koog.prompt.executor.model.PromptExecutor;
import ai.koog.prompt.executor.model.PromptExecutorBuilder;
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

import static mdaros.training.agentic.ai.koog.Constants.QA_ENGINEER_AGENT;
import static mdaros.training.agentic.ai.koog.Constants.SW_ANALYST_AGENT;
import static mdaros.training.agentic.ai.koog.utils.LlmClientUtils.llmClient;
import static mdaros.training.agentic.ai.koog.utils.LlmClientUtils.retryingClient;

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

		return new PromptExecutorBuilder ()
			.addClient ( retryingClient ( llmClient ( llmProperties ), llmProperties.getRetry () ) )
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
	public AIAgent<String, TestPlan> graphAgent ( AIAgentGraphStrategy<String, TestPlan> graphStrategy, PromptExecutor promptExecutor, LLModel llmModel, ToolRegistry toolregistry ) {

		return AIAgent.builder ()
			.promptExecutor ( promptExecutor )
			.llmModel ( llmModel )
			.toolRegistry ( toolregistry )
			.graphStrategy ( graphStrategy )
			.temperature ( 0.0 ) // TODO Rendere configurabile
			.build ();
	}

	@Bean
	@Lazy
	protected ToolRegistry toolRegistry () {

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
//			.transformed ( requirement -> requirement )
			.build ();

		var analystToQaEngineer = AIAgentEdge.builder ()
			.from ( analystSubGraph )
			.to ( qaEngineerSubGraph )
//			.transformed ( requirementAnalysis -> requirementAnalysis )
			.build ();

		var qaEngineerToFinish = AIAgentEdge.builder ()
			.from ( qaEngineerSubGraph )
			.to ( builder.nodeFinish )
//			.transformed ( testPlan -> testPlan )
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

	private static String requiredValue ( String value, String propertyName ) {

		if ( value == null || value.isBlank () ) {

			throw new IllegalStateException ( propertyName + " must be configured" );
		}

		return value;
	}
}