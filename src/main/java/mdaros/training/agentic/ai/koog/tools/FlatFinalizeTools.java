package mdaros.training.agentic.ai.koog.tools;

import ai.koog.agents.core.tools.Tool;
import ai.koog.agents.core.tools.ToolDescriptor;
import ai.koog.agents.core.tools.schema.SchemaGeneratorKt;
import ai.koog.serialization.JSONElement;
import ai.koog.serialization.JSONObject;
import ai.koog.serialization.JSONPrimitive;
import ai.koog.serialization.JSONSerializer;
import ai.koog.serialization.TypeToken;
import kotlin.coroutines.Continuation;
import mdaros.training.agentic.ai.koog.model.RequirementAnalysis;
import mdaros.training.agentic.ai.koog.model.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;

/**
 * Finish tools whose JSON schema matches {@code rawRequirement}/{@code analysis} (or {@code testPlan})
 * at the top level. Koog's built-in {@code FinishTool} wraps values in an extra {@code result} property,
 * which Groq models often omit.
 */
public final class FlatFinalizeTools {

	public static final String TOOL_NAME = "finalize_task_result";
	private static final Logger LOGGER = LoggerFactory.getLogger ( FlatFinalizeTools.class );

	private FlatFinalizeTools () {
	}

	public static Tool<RequirementAnalysis, RequirementAnalysis> requirementAnalysis () {

		TypeToken type = TypeToken.of ( RequirementAnalysis.class );
		ToolDescriptor descriptor = SchemaGeneratorKt.getToolDescriptor (
			type,
			TOOL_NAME,
			"Return the concise requirement analysis with rawRequirement and analysis fields.",
			SchemaGeneratorKt.getDefaultJsonSchemaConfig ()
		);

		return new Tool<RequirementAnalysis, RequirementAnalysis> (
			type,
			type,
			descriptor,
			Collections.emptyMap ()
		) {

			@Override
			public Object execute ( RequirementAnalysis args, Continuation<? super RequirementAnalysis> continuation ) {

				return args;
			}

			@Override
			public RequirementAnalysis decodeArgs ( JSONObject rawArgs, JSONSerializer serializer ) {

				JSONObject args = unwrapResult ( rawArgs );

				return new RequirementAnalysis (
					stringField ( args, "rawRequirement", "raw_requirement", "requirement" ),
					stringField ( args, "analysis", "requirementsAnalysis", "requirements_analysis" )
				);
			}

			@Override
			public JSONElement encodeResult ( RequirementAnalysis result, JSONSerializer serializer ) {

				return new JSONObject ( Map.of (
					"rawRequirement", JSONPrimitive.of ( result.rawRequirement () ),
					"analysis", JSONPrimitive.of ( result.analysis () )
				) );
			}

			@Override
			public RequirementAnalysis decodeResult ( JSONElement rawResult, JSONSerializer serializer ) {

				return decodeArgs ( requireObject ( rawResult ), serializer );
			}
		};
	}

	public static Tool<TestPlan, TestPlan> testPlan () {

		TypeToken type = TypeToken.of ( TestPlan.class );
		ToolDescriptor descriptor = SchemaGeneratorKt.getToolDescriptor (
			type,
			TOOL_NAME,
			"Return the concise test plan with analysis and testPlan fields.",
			SchemaGeneratorKt.getDefaultJsonSchemaConfig ()
		);

		return new Tool<TestPlan, TestPlan> (
			type,
			type,
			descriptor,
			Collections.emptyMap ()
		) {

			@Override
			public Object execute ( TestPlan args, Continuation<? super TestPlan> continuation ) {

				return args;
			}

			@Override
			public TestPlan decodeArgs ( JSONObject rawArgs, JSONSerializer serializer ) {

				JSONObject args = unwrapResult ( rawArgs );

				return new TestPlan (
					stringField ( args, "analysis", "requirementAnalysis", "requirement_analysis" ),
					stringField ( args, "testPlan", "test_plan", "plan" )
				);
			}

			@Override
			public JSONElement encodeResult ( TestPlan result, JSONSerializer serializer ) {

				return new JSONObject ( Map.of (
					"analysis", JSONPrimitive.of ( result.analysis () ),
					"testPlan", JSONPrimitive.of ( result.testPlan () )
				) );
			}

			@Override
			public TestPlan decodeResult ( JSONElement rawResult, JSONSerializer serializer ) {

				return decodeArgs ( requireObject ( rawResult ), serializer );
			}
		};
	}

	private static JSONObject unwrapResult ( JSONObject args ) {

		JSONElement result = args.getEntries ().get ( "result" );

		if ( result instanceof JSONObject resultObject ) {

			return resultObject;
		}

		return args;
	}

	private static String stringField ( JSONObject args, String... names ) {

		for ( String name : names ) {

			JSONElement value = args.getEntries ().get ( name );

			if ( value instanceof JSONPrimitive primitive && primitive.getContentOrNull () != null ) {

				return primitive.getContentOrNull ();
			}
		}

		LOGGER.warn ( "Finalize tool arguments are missing field [{}]. Received args: {}", String.join ( ", ", names ), args );
		return "";
	}

	private static JSONObject requireObject ( JSONElement value ) {

		if ( value instanceof JSONObject object ) {

			return object;
		}

		throw new IllegalArgumentException ( "Expected JSON object, got " + value );
	}
}
