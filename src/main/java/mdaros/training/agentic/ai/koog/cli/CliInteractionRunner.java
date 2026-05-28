package mdaros.training.agentic.ai.koog.cli;

import ai.koog.agents.core.agent.AIAgent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty ( prefix = "app.cli", name = "enabled", havingValue = "true", matchIfMissing = true )
public class CliInteractionRunner implements CommandLineRunner {

	private static final String QUIT_COMMAND = "quit";

	private final ObjectProvider<AIAgent<String, ?>> agentProvider;
	private final BufferedReader input;
	private final PrintStream output;
	private final PrintStream error;


	@Autowired
	public CliInteractionRunner ( ObjectProvider<AIAgent<String, ?>> agentProvider ) {

		this ( agentProvider, new BufferedReader ( new InputStreamReader ( System.in ) ), System.out, System.err );
	}

	CliInteractionRunner ( ObjectProvider<AIAgent<String, ?>> agentProvider, BufferedReader input, PrintStream output, PrintStream error ) {

		this.agentProvider = agentProvider;
		this.input = input;
		this.output = output;
		this.error = error;
	}

	@Override
	public void run ( String... args ) throws IOException {

		output.println ( "Application started. Type a request for the agent or 'quit' to stop." );

		while ( true ) {

			output.print ( "> " );
			output.flush ();

			String command = input.readLine ();

			if ( command == null || isQuitCommand ( command ) ) {

				output.println ( "Stopping application." );
				return;
			}

			if ( command.isBlank () ) {

				continue;
			}

			executeAgentCommand ( command );
		}
	}

	static boolean isQuitCommand ( String command ) {

		return QUIT_COMMAND.equalsIgnoreCase ( command.trim () );
	}

	private void executeAgentCommand ( String command ) {

		try {

			Object response = agentProvider.getObject ().run ( command );
			output.println ( response );
		}
		catch ( Exception exception ) {

			error.println ( "Command failed: " + exception.getMessage () );
		}
	}
}