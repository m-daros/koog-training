package mdaros.training.agentic.ai.koog.tools;

import ai.koog.agents.core.tools.annotations.LLMDescription;
import ai.koog.agents.core.tools.annotations.Tool;
import ai.koog.agents.core.tools.reflect.ToolSet;

import java.util.Scanner;

public class AskUserToolSet implements ToolSet {

	@Tool
	@LLMDescription ( "Ask user clarification" )
	public String askUser ( @LLMDescription ( "Question from the agent" ) String question ) {

		System.out.println ( question );
		Scanner scanner = new Scanner ( System.in );

		return scanner.nextLine ();
	}
}