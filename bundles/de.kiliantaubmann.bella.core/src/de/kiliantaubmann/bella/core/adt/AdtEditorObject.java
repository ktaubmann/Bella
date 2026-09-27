package de.kiliantaubmann.bella.core.adt;

/**
 * The repository object behind an ADT editor.
 *
 * @param destinationId system the object belongs to
 * @param uri           ADT URI, e.g. {@code /sap/bc/adt/oo/classes/zcl_order}
 * @param name          object name, upper case
 * @param type          ADT type, e.g. {@code CLAS/OC}
 */
public record AdtEditorObject(String destinationId, String uri, String name, String type) {
}
