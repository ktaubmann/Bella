package de.kiliantaubmann.bella.core.abap;

import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Block;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Kind;
import de.kiliantaubmann.bella.core.abap.ClassSurgery.SurgeryException;
import de.kiliantaubmann.bella.core.util.Json;

/**
 * The actions of {@code adt_edit_code}: targeted changes to a class, program
 * or include that keep the rest of the source. The same code runs on the SAP
 * system's source and on an open editor's buffer.
 */
public final class CodeEdits {

	public static final List<String> ACTIONS = List.of("add_method", "edit_method_signature", "edit_class_definition",
			"change_method_visibility", "delete_method", "edit_unit", "add_unit");

	private CodeEdits() {
	}

	/**
	 * The source after the action in {@code input}.
	 *
	 * @param objectName the class (for class actions) or program the source belongs to
	 * @throws SurgeryException if the change cannot be made; the message says why
	 */
	public static String apply(String source, String objectName, JsonObject input) {
		String action = Json.str(input, "action");
		String a = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
		String method = Json.str(input, "method");
		String code = Json.str(input, "source");
		String visibility = Json.str(input, "visibility");
		return switch (a) {
		case "add_method" -> ClassSurgery.addMethod(source, objectName, code, visibility);
		case "edit_method_signature" -> ClassSurgery.editMethodSignature(source, objectName, method, code);
		case "edit_class_definition" -> ClassSurgery.editDefinition(source, objectName, code);
		case "change_method_visibility" -> ClassSurgery.changeVisibility(source, objectName, method, visibility);
		case "delete_method" -> ClassSurgery.deleteMethod(source, objectName, method);
		case "edit_unit" -> editUnit(source, Json.str(input, "unit"), code);
		case "add_unit" -> addUnit(source, code);
		default -> throw new SurgeryException("Unknown action '" + action + "'; use " + String.join(", ", ACTIONS) + ".");
		};
	}

	/** Replaces one FORM or MODULE (statement to ENDFORM/ENDMODULE) with {@code code}. */
	static String editUnit(String source, String unit, String code) {
		if (unit == null || unit.isBlank()) {
			throw new SurgeryException("Give 'unit', the name of the FORM or MODULE.");
		}
		Block old = unitBlock(source, unit).orElseThrow(() -> new SurgeryException("There is no FORM or MODULE "
				+ unit.toUpperCase(Locale.ROOT) + " in this source. Units: " + String.join(", ", units(source)) + "."));
		Block replacement = singleUnit(code);
		if (!replacement.name().equalsIgnoreCase(old.name()) || replacement.kind() != old.kind()) {
			throw new SurgeryException("The new code is " + replacement.kind() + " " + replacement.name()
					+ ", not " + old.kind() + " " + old.name() + ".");
		}
		return source.substring(0, old.start()) + code.strip() + source.substring(old.end());
	}

	/** Appends a FORM or MODULE at the end of the source. */
	static String addUnit(String source, String code) {
		Block unit = singleUnit(code);
		if (unitBlock(source, unit.name()).isPresent()) {
			throw new SurgeryException(unit.kind() + " " + unit.name() + " exists already; change it with edit_unit.");
		}
		String base = source.stripTrailing();
		return base + "\n\n" + code.strip() + "\n";
	}

	private static Block singleUnit(String code) {
		if (code == null || code.isBlank()) {
			throw new SurgeryException("Give 'source', the complete FORM … ENDFORM or MODULE … ENDMODULE.");
		}
		String text = code.strip();
		List<Block> blocks = AbapStructureScanner.blocks(text).stream()
				.filter(b -> b.kind() == Kind.FORM || b.kind() == Kind.MODULE).toList();
		if (blocks.size() != 1 || blocks.get(0).start() != 0 || blocks.get(0).end() != text.length()) {
			throw new SurgeryException("'source' must be exactly one FORM … ENDFORM. or MODULE … ENDMODULE.");
		}
		return blocks.get(0);
	}

	private static java.util.Optional<Block> unitBlock(String source, String name) {
		return AbapStructureScanner.blocks(source).stream()
				.filter(b -> (b.kind() == Kind.FORM || b.kind() == Kind.MODULE) && b.name().equalsIgnoreCase(name.trim()))
				.findFirst();
	}

	private static List<String> units(String source) {
		return AbapStructureScanner.blocks(source).stream()
				.filter(b -> b.kind() == Kind.FORM || b.kind() == Kind.MODULE).map(Block::name).toList();
	}
}
