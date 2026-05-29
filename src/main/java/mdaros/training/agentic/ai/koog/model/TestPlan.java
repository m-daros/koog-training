package mdaros.training.agentic.ai.koog.model;

import ai.koog.agents.core.tools.annotations.LLMDescription;
import com.fasterxml.jackson.annotation.JsonProperty;

@LLMDescription ( "Concise structured test plan result." )
public record TestPlan (

	@JsonProperty ( "analysis" )
	@LLMDescription ( "Short summary of the requirement analysis, maximum 800 characters." )
	String analysis,

	@JsonProperty ( "testPlan" )
	@LLMDescription ( "Compact Markdown test plan, maximum 4500 characters. Do not use apostrophes or single quotes." )
	String testPlan
) {
}
