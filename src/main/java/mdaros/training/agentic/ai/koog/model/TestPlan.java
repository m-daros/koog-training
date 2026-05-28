package mdaros.training.agentic.ai.koog.model;

import ai.koog.agents.core.tools.annotations.LLMDescription;
import com.fasterxml.jackson.annotation.JsonProperty;

@LLMDescription ( "Structured test plan object. Return this as a JSON object with analysis and testPlan fields, never as a plain string." )
public record TestPlan (

	@JsonProperty ( "analysis" )
	@LLMDescription ( "The requirement analysis used as input for creating the test plan. Do not use apostrophes or single quotes in this field." )
	String analysis,

	@JsonProperty ( "testPlan" )
	@LLMDescription ( "The complete test plan as plain text, including test strategy, functional tests, regression tests, integration tests, security tests, performance tests, negative tests, release risks, rollback actions, release notes, and deployment checklist. Do not use apostrophes or single quotes in this field." )
	String testPlan
) {
}