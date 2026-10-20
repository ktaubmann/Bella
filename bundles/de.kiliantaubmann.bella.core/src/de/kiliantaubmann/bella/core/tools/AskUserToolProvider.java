package de.kiliantaubmann.bella.core.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.CancelToken.CancelledException;
import de.kiliantaubmann.bella.core.util.Json;

/**
 * The {@code ask_user} tool: the model asks the developer one to four
 * questions with two to four options each, as Claude Code's
 * AskUserQuestion does. The chat shows them as a menu; the developer picks
 * an option, several with {@code multiSelect}, or types an own answer.
 */
public final class AskUserToolProvider implements ToolProvider {

	public static final String TOOL = "ask_user";
	static final int MAX_QUESTIONS = 4;
	static final int MIN_OPTIONS = 2;
	static final int MAX_OPTIONS = 4;
	/** Longest chip text above a question, as in Claude Code. */
	static final int MAX_HEADER = 12;

	public record Option(String label, String description) {
	}

	public record Question(String header, String question, List<Option> options, boolean multiSelect) {
	}

	/**
	 * @param chosen labels of the options picked, in the order shown
	 * @param other  the developer's own answer, empty if none
	 */
	public record Answer(List<String> chosen, String other) {

		public Answer {
			chosen = chosen == null ? List.of() : List.copyOf(chosen);
			other = other == null ? "" : other.strip();
		}

		/** The answer as the chat and the model see it, e.g. "Diff mode, Automode" or the own text. */
		public String text() {
			List<String> parts = new ArrayList<>(chosen);
			if (!other.isEmpty()) {
				parts.add(other);
			}
			return String.join(", ", parts);
		}
	}

	/** Shows the questions to the developer. */
	public interface Asker {

		/** No chat to ask in, e.g. a tool call from an editor action. */
		Asker NONE = (questions, cancel) -> {
			throw new IllegalStateException("There is no chat to ask the developer in. Ask in your answer instead.");
		};

		/**
		 * Blocks until the developer answers.
		 *
		 * @return one answer per question, or empty if the developer dismissed the questions
		 * @throws IllegalStateException if there is no chat to ask in
		 */
		Optional<List<Answer>> ask(List<Question> questions, CancelToken cancel) throws CancelledException;
	}

	private final Asker asker;

	public AskUserToolProvider(Asker asker) {
		this.asker = asker == null ? Asker.NONE : asker;
	}

	@Override
	public String id() {
		return ToolRegistry.ASK_PROVIDER_ID;
	}

	@Override
	public String displayName() {
		return "Questions to the developer";
	}

	@Override
	public List<ToolSpec> listTools() {
		JsonObject option = new JsonObject();
		option.addProperty("type", "object");
		JsonObject optionProps = new JsonObject();
		optionProps.add("label", prop("string", "What the developer clicks, 1 to 5 words. Put the option you recommend "
				+ "first and end its label with \"(Recommended)\"."));
		optionProps.add("description", prop("string", "What this option means or what happens if it is chosen, "
				+ "with its trade-offs."));
		option.add("properties", optionProps);
		option.add("required", array("label", "description"));

		JsonObject question = new JsonObject();
		question.addProperty("type", "object");
		JsonObject questionProps = new JsonObject();
		questionProps.add("question", prop("string", "The complete question, ending with a question mark."));
		questionProps.add("header", prop("string", "Very short label shown as a chip above the question, at most "
				+ MAX_HEADER + " characters, e.g. \"Package\" or \"Approach\"."));
		JsonObject options = prop("array", MIN_OPTIONS + " to " + MAX_OPTIONS + " distinct choices. Do not add an "
				+ "\"Other\" option: the developer can always type an own answer.");
		options.add("items", option);
		options.addProperty("minItems", MIN_OPTIONS);
		options.addProperty("maxItems", MAX_OPTIONS);
		questionProps.add("options", options);
		questionProps.add("multiSelect", prop("boolean", "true if the developer may pick several options because "
				+ "they do not exclude each other."));
		question.add("properties", questionProps);
		question.add("required", array("question", "header", "options"));

		JsonObject questions = prop("array", "1 to " + MAX_QUESTIONS + " questions, shown together.");
		questions.add("items", question);
		questions.addProperty("minItems", 1);
		questions.addProperty("maxItems", MAX_QUESTIONS);
		JsonObject props = new JsonObject();
		props.add("questions", questions);
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", props);
		schema.add("required", array("questions"));

		return List.of(ToolSpec.of(TOOL,
				"Ask the developer questions with options to choose from; the chat shows them as a menu and returns "
						+ "the choices. Use it only when you are blocked on a decision that is the developer's to "
						+ "make and that you cannot settle from the code, the system or a sensible default: which of "
						+ "several approaches, objects or packages, or what the requirement means. Give 2 to 4 "
						+ "options per question; the developer can also type an own answer. Do not ask whether "
						+ "your plan is fine or whether to go on: in plan mode the developer accepts, changes or "
						+ "cancels the plan with buttons below the chat.",
				schema, null, ToolSpec.Kind.READ));
	}

	@Override
	public ToolResult call(String name, JsonObject input, CancelToken cancel) throws Exception {
		if (!TOOL.equals(name)) {
			return ToolResult.error("Unknown tool " + name);
		}
		List<Question> questions;
		try {
			questions = parse(input);
		} catch (IllegalArgumentException e) {
			return ToolResult.error(e.getMessage());
		}
		Optional<List<Answer>> answers;
		try {
			answers = asker.ask(questions, cancel);
		} catch (IllegalStateException e) {
			return ToolResult.error(e.getMessage());
		}
		if (answers.isEmpty()) {
			return ToolResult.ok("The developer dismissed the questions without answering. Do not ask the same "
					+ "again; go on with what is clear, or ask in your answer what you still need.");
		}
		return ToolResult.ok(format(questions, answers.get()));
	}

	/** The questions in {@code input}; {@link IllegalArgumentException} with a message for the model if invalid. */
	static List<Question> parse(JsonObject input) {
		JsonArray qs = input == null ? null : Json.arr(input, "questions");
		if (qs == null && input != null && input.has("questions") && input.get("questions").isJsonPrimitive()) {
			// some models send the array as a JSON string
			try {
				JsonElement parsed = Json.parseStrict(input.get("questions").getAsString());
				qs = parsed != null && parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
			} catch (RuntimeException e) {
				qs = null;
			}
		}
		if (qs == null || qs.isEmpty() || qs.size() > MAX_QUESTIONS) {
			throw new IllegalArgumentException("Give 'questions' with 1 to " + MAX_QUESTIONS + " questions.");
		}
		List<Question> out = new ArrayList<>();
		Set<String> seenQuestions = new HashSet<>();
		for (JsonElement e : qs) {
			if (!e.isJsonObject()) {
				throw new IllegalArgumentException("Each question is an object with 'question', 'header' and 'options'.");
			}
			JsonObject q = e.getAsJsonObject();
			String text = strip(Json.str(q, "question"));
			if (text.isEmpty()) {
				throw new IllegalArgumentException("Each question needs the text in 'question'.");
			}
			if (!seenQuestions.add(text.toLowerCase(Locale.ROOT))) {
				throw new IllegalArgumentException("The question \"" + text + "\" is given twice.");
			}
			String header = strip(Json.str(q, "header"));
			if (header.length() > MAX_HEADER) {
				header = header.substring(0, MAX_HEADER - 1).strip() + "…";
			}
			JsonArray os = Json.arr(q, "options");
			if (os == null || os.size() < MIN_OPTIONS || os.size() > MAX_OPTIONS) {
				throw new IllegalArgumentException("The question \"" + text + "\" needs " + MIN_OPTIONS + " to "
						+ MAX_OPTIONS + " 'options'.");
			}
			List<Option> options = new ArrayList<>();
			Set<String> labels = new HashSet<>();
			for (JsonElement oe : os) {
				String label;
				String description = "";
				if (oe.isJsonObject()) {
					label = strip(Json.str(oe.getAsJsonObject(), "label"));
					description = strip(Json.str(oe.getAsJsonObject(), "description"));
				} else if (oe.isJsonPrimitive()) {
					label = strip(oe.getAsString());
				} else {
					label = "";
				}
				if (label.isEmpty()) {
					throw new IllegalArgumentException("Each option of \"" + text + "\" needs a 'label'.");
				}
				if (!labels.add(label.toLowerCase(Locale.ROOT))) {
					throw new IllegalArgumentException("The option \"" + label + "\" of \"" + text
							+ "\" is given twice.");
				}
				options.add(new Option(label, description));
			}
			boolean multi = q.has("multiSelect") && q.get("multiSelect").isJsonPrimitive()
					&& q.get("multiSelect").getAsBoolean();
			out.add(new Question(header, text, List.copyOf(options), multi));
		}
		return List.copyOf(out);
	}

	static String format(List<Question> questions, List<Answer> answers) {
		StringBuilder sb = new StringBuilder("The developer answered:\n");
		for (int i = 0; i < questions.size(); i++) {
			Answer a = i < answers.size() ? answers.get(i) : new Answer(List.of(), "");
			sb.append("- \"").append(questions.get(i).question()).append("\" = ");
			if (a.chosen().isEmpty() && a.other().isEmpty()) {
				sb.append("(no answer)");
			} else {
				List<String> parts = new ArrayList<>();
				a.chosen().forEach(c -> parts.add("\"" + c + "\""));
				if (!a.other().isEmpty()) {
					parts.add("own answer: \"" + a.other() + "\"");
				}
				sb.append(String.join(", ", parts));
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	/** {@code {"questions":[{header, question, options:[{label, description}], multiSelect}]}} for the chat page. */
	public static JsonObject toJson(List<Question> questions) {
		JsonArray qs = new JsonArray();
		for (Question q : questions) {
			JsonObject o = new JsonObject();
			o.addProperty("header", q.header());
			o.addProperty("question", q.question());
			JsonArray opts = new JsonArray();
			for (Option opt : q.options()) {
				JsonObject oo = new JsonObject();
				oo.addProperty("label", opt.label());
				oo.addProperty("description", opt.description());
				opts.add(oo);
			}
			o.add("options", opts);
			o.addProperty("multiSelect", q.multiSelect());
			qs.add(o);
		}
		JsonObject out = new JsonObject();
		out.add("questions", qs);
		return out;
	}

	/** The questions {@link #toJson} wrote; invalid entries make an empty list. */
	public static List<Question> fromJson(JsonObject json) {
		try {
			return parse(json);
		} catch (IllegalArgumentException e) {
			return List.of();
		}
	}

	/** {@code [{chosen:[…], other}]}, or {@code null} for dismissed questions. */
	public static JsonArray answersToJson(Optional<List<Answer>> answers) {
		if (answers.isEmpty()) {
			return null;
		}
		JsonArray out = new JsonArray();
		for (Answer a : answers.get()) {
			JsonObject o = new JsonObject();
			JsonArray chosen = new JsonArray();
			a.chosen().forEach(chosen::add);
			o.add("chosen", chosen);
			o.addProperty("other", a.other());
			out.add(o);
		}
		return out;
	}

	/** The answers of the chat page; anything but an array (e.g. {@code null}) means dismissed. */
	public static Optional<List<Answer>> answersFromJson(JsonElement json) {
		if (json == null || !json.isJsonArray()) {
			return Optional.empty();
		}
		List<Answer> out = new ArrayList<>();
		for (JsonElement e : json.getAsJsonArray()) {
			if (!e.isJsonObject()) {
				out.add(new Answer(List.of(), ""));
				continue;
			}
			List<String> chosen = new ArrayList<>();
			JsonArray c = Json.arr(e.getAsJsonObject(), "chosen");
			if (c != null) {
				c.forEach(x -> {
					if (x.isJsonPrimitive()) {
						chosen.add(x.getAsString());
					}
				});
			}
			out.add(new Answer(chosen, Json.str(e.getAsJsonObject(), "other")));
		}
		return Optional.of(List.copyOf(out));
	}

	private static String strip(String s) {
		return s == null ? "" : s.strip();
	}

	private static JsonObject prop(String type, String description) {
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("description", description);
		return p;
	}

	private static JsonArray array(String... values) {
		JsonArray a = new JsonArray();
		for (String v : values) {
			a.add(v);
		}
		return a;
	}
}
