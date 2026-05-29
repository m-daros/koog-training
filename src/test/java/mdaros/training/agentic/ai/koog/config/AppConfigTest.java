package mdaros.training.agentic.ai.koog.config;

import ai.koog.agents.core.agent.entity.AIAgentSubgraphBase;
import mdaros.training.agentic.ai.koog.model.RequirementAnalysis;
import mdaros.training.agentic.ai.koog.model.TestPlan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

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
}
