package mdaros.training.agentic.ai.koog.agents;

import lombok.Getter;

@Getter
public enum Agents {

	ANALYST 	( "analyst" ),
	QA_ENGINEER ( "qa-engineer" ),;

	private final String name;

	Agents ( String name ) {

		this.name = name;
	}
}