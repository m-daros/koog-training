package mdaros.training.agentic.ai.koog.config;

import ai.koog.agents.core.tools.ToolDescriptor;
import ai.koog.prompt.dsl.ModerationResult;
import ai.koog.prompt.dsl.Prompt;
import ai.koog.prompt.executor.clients.LLMClient;
import ai.koog.prompt.llm.LLMProvider;
import ai.koog.prompt.llm.LLModel;
import ai.koog.prompt.message.Message;
import ai.koog.prompt.streaming.StreamFrame;
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator;
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

final class RateLimitedLLMClient extends LLMClient {

	private final LLMClient delegate;
	private final long minIntervalMillis;
	private final int requestsPerDay;
	private final Path stateFile;
	private long nextRequestAtMillis;

	RateLimitedLLMClient ( LLMClient delegate, int requestsPerMinute ) {

		this ( delegate, requestsPerMinute, 0, null );
	}

	RateLimitedLLMClient ( LLMClient delegate, int requestsPerMinute, int requestsPerDay, String stateFile ) {

		this.delegate = Objects.requireNonNull ( delegate, "delegate" );

		if ( requestsPerMinute < 1 ) {

			throw new IllegalArgumentException ( "requestsPerMinute must be at least 1" );
		}

		this.minIntervalMillis = Math.ceilDiv ( 60_000L, requestsPerMinute );
		this.requestsPerDay = requestsPerDay;
		this.stateFile = stateFile == null || stateFile.isBlank () ? null : Path.of ( stateFile );
	}

	@Override
	public LLMProvider llmProvider () {

		return delegate.llmProvider ();
	}

	@Override
	public Object execute ( Prompt prompt, LLModel model, List<? extends ToolDescriptor> tools, Continuation<? super List<? extends Message.Response>> continuation ) {

		acquirePermit ( model, true );
		return delegate.execute ( prompt, model, tools, continuation );
	}

	@Override
	public Flow<StreamFrame> executeStreaming ( Prompt prompt, LLModel model, List<? extends ToolDescriptor> tools ) {

		acquirePermit ( model, true );
		return delegate.executeStreaming ( prompt, model, tools );
	}

	@Override
	public Object executeMultipleChoices ( Prompt prompt, LLModel model, List<? extends ToolDescriptor> tools, Continuation<? super List<? extends List<? extends Message.Response>>> continuation ) {

		acquirePermit ( model, true );
		return delegate.executeMultipleChoices ( prompt, model, tools, continuation );
	}

	@Override
	public Object moderate ( Prompt prompt, LLModel model, Continuation<? super ModerationResult> continuation ) {

		acquirePermit ( model, false );
		return delegate.moderate ( prompt, model, continuation );
	}

	@Override
	public Object models ( Continuation<? super List<LLModel>> continuation ) {

		acquirePermit ( null, false );
		return delegate.models ( continuation );
	}

	@Override
	public Object embed ( String text, LLModel model, Continuation<? super List<Double>> continuation ) {

		acquirePermit ( model, false );
		return delegate.embed ( text, model, continuation );
	}

	@Override
	public Object embed ( List<String> inputs, LLModel model, Continuation<? super List<? extends List<Double>>> continuation ) {

		acquirePermit ( model, false );
		return delegate.embed ( inputs, model, continuation );
	}

	@Override
	public StandardJsonSchemaGenerator getStandardJsonSchemaGenerator () {

		return delegate.getStandardJsonSchemaGenerator ();
	}

	@Override
	public BasicJsonSchemaGenerator getBasicJsonSchemaGenerator () {

		return delegate.getBasicJsonSchemaGenerator ();
	}

	@Override
	public String getClientName () {

		return delegate.getClientName ();
	}

	@Override
	public void close () {

		try {

			delegate.close ();
		}
		catch ( Exception exception ) {

			throw new IllegalStateException ( "Error closing rate limited LLM client", exception );
		}
	}

	private void acquirePermit ( LLModel model, boolean countDailyQuota ) {

		if ( countDailyQuota ) {

			acquireDailyQuota ( model );
		}

		long sleepMillis;

		synchronized ( this ) {

			long now = System.currentTimeMillis ();
			sleepMillis = Math.max ( 0L, nextRequestAtMillis - now );
			nextRequestAtMillis = Math.max ( now, nextRequestAtMillis ) + minIntervalMillis;
		}

		if ( sleepMillis > 0L ) {

			sleep ( sleepMillis );
		}
	}

	private void acquireDailyQuota ( LLModel model ) {

		if ( requestsPerDay < 1 || stateFile == null ) {

			return;
		}

		String bucket = quotaBucket ( model );

		try {

			Path parent = stateFile.toAbsolutePath ().getParent ();

			if ( parent != null ) {

				Files.createDirectories ( parent );
			}

			try ( FileChannel channel = FileChannel.open (
					stateFile,
					StandardOpenOption.CREATE,
					StandardOpenOption.READ,
					StandardOpenOption.WRITE
				);
				FileLock ignored = channel.lock ()
			) {

				Properties properties = loadProperties ( channel );
				String today = LocalDate.now ().toString ();
				String dateKey = bucket + ".date";
				String countKey = bucket + ".count";

				int count = today.equals ( properties.getProperty ( dateKey ) )
					? Integer.parseInt ( properties.getProperty ( countKey, "0" ) )
					: 0;

				if ( count >= requestsPerDay ) {

					throw new IllegalStateException (
						"Local LLM daily quota reached for " + bucket + ": " + count + "/" + requestsPerDay
							+ ". Stop requests until the provider quota resets, or increase app.llm.rate-limit.requests-per-day if your plan allows it."
					);
				}

				properties.setProperty ( dateKey, today );
				properties.setProperty ( countKey, Integer.toString ( count + 1 ) );
				storeProperties ( channel, properties );
			}
		}
		catch ( IOException exception ) {

			throw new IllegalStateException ( "Cannot update LLM quota state file " + stateFile, exception );
		}
	}

	private static Properties loadProperties ( FileChannel channel ) throws IOException {

		Properties properties = new Properties ();

		if ( channel.size () == 0L ) {

			return properties;
		}

		channel.position ( 0L );

		ByteBuffer buffer = ByteBuffer.allocate ( Math.toIntExact ( channel.size () ) );
		channel.read ( buffer );
		buffer.flip ();
		properties.load ( new ByteArrayInputStream ( buffer.array (), 0, buffer.limit () ) );

		return properties;
	}

	private static void storeProperties ( FileChannel channel, Properties properties ) throws IOException {

		channel.truncate ( 0L );
		channel.position ( 0L );

		ByteArrayOutputStream output = new ByteArrayOutputStream ();
		properties.store ( output, "Local LLM quota counters" );
		channel.write ( ByteBuffer.wrap ( output.toByteArray () ) );
	}

	private static String quotaBucket ( LLModel model ) {

		if ( model == null ) {

			return "unknown";
		}

		return sanitize ( model.getProvider ().toString () + "." + model.getId () );
	}

	private static String sanitize ( String value ) {

		return value.replaceAll ( "[^A-Za-z0-9_.-]", "_" );
	}

	private static void sleep ( long millis ) {

		try {

			Thread.sleep ( millis );
		}
		catch ( InterruptedException exception ) {

			Thread.currentThread ().interrupt ();
			throw new IllegalStateException ( "Interrupted while waiting for LLM rate limit permit", exception );
		}
	}
}