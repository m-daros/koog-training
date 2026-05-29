package mdaros.training.agentic.ai.koog.model;

import ai.koog.agents.core.tools.annotations.LLMDescription;
import com.fasterxml.jackson.annotation.JsonProperty;

@LLMDescription ( "Concise structured requirement analysis result." )
public record RequirementAnalysis (

	@JsonProperty ( "rawRequirement" )
	@LLMDescription ( "Original user requirement without apostrophes." )
	String rawRequirement,

	@JsonProperty ( "analysis" )
	@LLMDescription ( "Concise requirement analysis, maximum 3500 characters. Include goal, requirements, user stories, acceptance criteria, assumptions, dependencies, constraints, and open questions. Do not use apostrophes or single quotes." )
	String analysis
) {
}
