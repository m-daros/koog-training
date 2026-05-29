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
import ai.koog.prompt.tokenizer.OnDemandTokenizer;
import ai.koog.prompt.tokenizer.PromptTokenizer;
import ai.koog.prompt.tokenizer.SimpleRegexBasedTokenizer;
import ai.koog.prompt.tokenizer.Tokenizer;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

final class RateLimitedLLMClient extends LLMClient {

	private static final Logger LOGGER = LoggerFactory.getLogger ( RateLimitedLLMClient.class );
	private static final long TOKEN_WINDOW_MILLIS = 60_000L;

	private final LLMClient delegate;
	private final long minIntervalMillis;
	private final int requestsPerDay;
	private final Path stateFile;
	private final int tokensPerMinute;
	private final int tokenEstimateOverhead;
	private final PromptTokenizer promptTokenizer;
	private final Tokenizer tokenizer;
	private final Deque<TokenReservation> tokenReservations = new ArrayDeque<> ();
	private long nextRequestAtMillis;

	RateLimitedLLMClient ( LLMClient delegate, int requestsPerMinute ) {

		this ( delegate, requestsPerMinute, 0, null );
	}

	RateLimitedLLMClient ( LLMClient delegate, int requestsPerMinute, int requestsPerDay, String stateFile ) {

		this ( delegate, requestsPerMinute, requestsPerDay, stateFile, 0, 0 );
	}

	RateLimitedLLMClient (
		LLMClient delegate,
		int requestsPerMinute,
		int requestsPerDay,
		String stateFile,
		int tokensPerMinute,
		int tokenEstimateOverhead
	) {

		this.delegate = Objects.requireNonNull ( delegate, "delegate" );

		if ( requestsPerMinute < 1 ) {

			throw new IllegalArgumentException ( "requestsPerMinute must be at least 1" );
		}

		if ( tokensPerMinute < 0 ) {

			throw new IllegalArgumentException ( "tokensPerMinute cannot be negative" );
		}

		if ( tokenEstimateOverhead < 0 ) {

			throw new IllegalArgumentException ( "tokenEstimateOverhead cannot be negative" );
		}

		this.minIntervalMillis = Math.ceilDiv ( 60_000L, requestsPerMinute );
		this.requestsPerDay = requestsPerDay;
		this.stateFile = stateFile == null || stateFile.isBlank () ? null : Path.of ( stateFile );
		this.tokensPerMinute = tokensPerMinute;
		this.tokenEstimateOverhead = tokenEstimateOverhead;
		this.tokenizer = new SimpleRegexBasedTokenizer ();
		this.promptTokenizer = new OnDemandTokenizer ( tokenizer );
	}

	@Override
	public LLMProvider llmProvider () {

		return delegate.llmProvider ();
	}

	@Override
	public Object execute ( Prompt prompt, LLModel model, List<? extends ToolDescriptor> tools, Continuation<? super List<? extends Message.Response>> continuation ) {

		acquirePermit ( prompt, model, tools, true );
		return delegate.execute ( prompt, model, tools, continuation );
	}

	@Override
	public Flow<StreamFrame> executeStreaming ( Prompt prompt, LLModel model, List<? extends ToolDescriptor> tools ) {

		acquirePermit ( prompt, model, tools, true );
		return delegate.executeStreaming ( prompt, model, tools );
	}

	@Override
	public Object executeMultipleChoices ( Prompt prompt, LLModel model, List<? extends ToolDescriptor> tools, Continuation<? super List<? extends List<? extends Message.Response>>> continuation ) {

		acquirePermit ( prompt, model, tools, true );
		return delegate.executeMultipleChoices ( prompt, model, tools, continuation );
	}

	@Override
	public Object moderate ( Prompt prompt, LLModel model, Continuation<? super ModerationResult> continuation ) {

		acquirePermit ( prompt, model, List.of (), false );
		return delegate.moderate ( prompt, model, continuation );
	}

	@Override
	public Object models ( Continuation<? super List<LLModel>> continuation ) {

		acquirePermit ( null, null, List.of (), false );
		return delegate.models ( continuation );
	}

	@Override
	public Object embed ( String text, LLModel model, Continuation<? super List<Double>> continuation ) {

		acquirePermit ( null, model, List.of (), false );
		return delegate.embed ( text, model, continuation );
	}

	@Override
	public Object embed ( List<String> inputs, LLModel model, Continuation<? super List<? extends List<Double>>> continuation ) {

		acquirePermit ( null, model, List.of (), false );
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

	private void acquirePermit ( Prompt prompt, LLModel model, List<? extends ToolDescriptor> tools, boolean countDailyQuota ) {

		int requestedTokens = estimateRequestedTokens ( prompt, model, tools );
		validateRequestSize ( model, requestedTokens );

		if ( countDailyQuota ) {

			acquireDailyQuota ( model );
		}

		acquireRatePermits ( model, requestedTokens );
	}

	private void validateRequestSize ( LLModel model, int requestedTokens ) {

		if ( tokensPerMinute > 0 && requestedTokens > tokensPerMinute ) {

			throw new IllegalStateException (
				"Estimated LLM request for " + quotaBucket ( model ) + " is " + requestedTokens
					+ " tokens, above app.llm.rate-limit.tokens-per-minute=" + tokensPerMinute
					+ ". This is a single request size issue, not a waitable rate limit. Reduce prompt/history/output size before calling the provider."
			);
		}
	}

	private void acquireRatePermits ( LLModel model, int requestedTokens ) {

		RateLimitWait wait;

		synchronized ( this ) {

			long now = System.currentTimeMillis ();
			long requestAvailableAtMillis = Math.max ( now, nextRequestAtMillis );
			long availableAtMillis = requestAvailableAtMillis;

			if ( requestedTokens > 0 ) {

				availableAtMillis = nextTokenPermitAt ( model, availableAtMillis, requestedTokens );
				tokenReservations.addLast ( new TokenReservation ( availableAtMillis, requestedTokens ) );
			}

			nextRequestAtMillis = availableAtMillis + minIntervalMillis;
			wait = new RateLimitWait (
				Math.max ( 0L, availableAtMillis - now ),
				waitReason ( requestAvailableAtMillis, availableAtMillis ),
				quotaBucket ( model ),
				requestedTokens,
				tokensPerMinute
			);
		}

		if ( wait.sleepMillis () > 0L ) {

			LOGGER.info (
				"Waiting {} ms ({}) before LLM request to respect {} rate limit. model={}, estimatedTokens={}, tokensPerMinute={}",
				wait.sleepMillis (),
				formatSeconds ( wait.sleepMillis () ),
				wait.reason (),
				wait.model (),
				wait.estimatedTokens (),
				wait.tokensPerMinute ()
			);
			sleep ( wait.sleepMillis () );
		}
	}

	private static String waitReason ( long requestAvailableAtMillis, long availableAtMillis ) {

		return availableAtMillis > requestAvailableAtMillis ? "token-per-minute" : "request-per-minute";
	}

	private static String formatSeconds ( long millis ) {

		return String.format ( Locale.ROOT, "%.1fs", millis / 1_000.0 );
	}

	private long nextTokenPermitAt ( LLModel model, long candidateMillis, int requestedTokens ) {

		long availableAtMillis = candidateMillis;

		while ( true ) {

			purgeExpiredTokenReservations ( availableAtMillis );

			int usedTokens = tokenReservations.stream ()
				.mapToInt ( TokenReservation::tokens )
				.sum ();

			if ( usedTokens + requestedTokens <= tokensPerMinute || tokenReservations.isEmpty () ) {

				return availableAtMillis;
			}

			TokenReservation earliestReservation = tokenReservations.peekFirst ();

			if ( earliestReservation == null ) {

				return availableAtMillis;
			}

			availableAtMillis = Math.max (
				availableAtMillis,
				earliestReservation.timestampMillis () + TOKEN_WINDOW_MILLIS
			);
		}
	}

	private void purgeExpiredTokenReservations ( long nowMillis ) {

		while (
			! tokenReservations.isEmpty ()
				&& tokenReservations.peekFirst ().timestampMillis () + TOKEN_WINDOW_MILLIS <= nowMillis
		) {

			tokenReservations.removeFirst ();
		}
	}

	private int estimateRequestedTokens ( Prompt prompt, LLModel model, List<? extends ToolDescriptor> tools ) {

		if ( tokensPerMinute < 1 ) {

			return 0;
		}

		long estimatedTokens = tokenEstimateOverhead;

		if ( prompt != null ) {

			estimatedTokens += promptTokenizer.tokenCountFor ( prompt );
			estimatedTokens += maxOutputTokens ( prompt, model );
		}

		if ( tools != null ) {

			for ( ToolDescriptor tool : tools ) {

				estimatedTokens += tokenizer.countTokens ( tool.toString () );
			}
		}

		return Math.toIntExact ( Math.min ( Integer.MAX_VALUE, Math.max ( 1L, estimatedTokens ) ) );
	}

	private static long maxOutputTokens ( Prompt prompt, LLModel model ) {

		if ( prompt != null && prompt.getParams () != null && prompt.getParams ().getMaxTokens () != null ) {

			return prompt.getParams ().getMaxTokens ();
		}

		if ( model != null && model.getMaxOutputTokens () != null ) {

			return model.getMaxOutputTokens ();
		}

		return 0L;
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

	private record TokenReservation ( long timestampMillis, int tokens ) {
	}

	private record RateLimitWait (
		long sleepMillis,
		String reason,
		String model,
		int estimatedTokens,
		int tokensPerMinute
	) {
	}
}
