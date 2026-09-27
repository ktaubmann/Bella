package de.kiliantaubmann.bella.core.prompt;

/**
 * What the model is told about the code the developer is looking at.
 *
 * @param objectName  e.g. {@code ZCL_ORDER}; may be {@code null}
 * @param objectType  ADT type, e.g. {@code CLAS/OC}; may be {@code null}
 * @param system      system id / project, e.g. {@code S4H (100)}; may be {@code null}
 * @param source      the complete source of the editor
 * @param selection   selected text; empty when nothing is selected
 * @param selectionOffset offset of the selection (or the cursor) in {@code source}
 */
public record EditorContext(String objectName, String objectType, String system, String source, String selection,
		int selectionOffset) {

	public String describeObject() {
		StringBuilder sb = new StringBuilder();
		sb.append(objectName == null ? "(unknown object)" : objectName);
		if (objectType != null) {
			sb.append(" [").append(objectType).append(']');
		}
		if (system != null) {
			sb.append(" in system ").append(system);
		}
		return sb.toString();
	}
}
