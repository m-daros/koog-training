package mdaros.training.agentic.ai.koog.model;

import ai.koog.agents.core.tools.annotations.LLMDescription;
import com.fasterxml.jackson.annotation.JsonProperty;

@LLMDescription ( "Structured requirement analysis object. Return this as a JSON object with rawRequirement and analysis fields, never as a plain string." )
public record RequirementAnalysis (

	@JsonProperty ( "rawRequirement" )
	@LLMDescription ( "The original user requirement that is being analyzed." )
	String rawRequirement,

	@JsonProperty ( "analysis" )
	@LLMDescription ( "The complete requirement analysis as plain text, including business goal, requirements, user stories, acceptance criteria, assumptions, dependencies, constraints, and open questions. Do not use apostrophes or single quotes in this field." )
	String analysis
) {
}
