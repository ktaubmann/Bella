package de.kiliantaubmann.bella.core.abap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.abap.ClassSurgery.SurgeryException;
import de.kiliantaubmann.bella.core.util.Json;

public class ClassSurgeryTest {

	/** A global class source, also used by the tool tests. */
	public static final String CLASS = """
			CLASS zcl_a DEFINITION PUBLIC FINAL CREATE PUBLIC.
			  PUBLIC SECTION.
			    METHODS run IMPORTING iv_id TYPE i.
			  PRIVATE SECTION.
			    "! Reads the rows
			    METHODS read RETURNING VALUE(rt) TYPE string_table.
			    METHODS: a, b.
			ENDCLASS.

			CLASS zcl_a IMPLEMENTATION.
			  METHOD run.
			    DATA(rows) = read( ).
			  ENDMETHOD.

			  METHOD read.
			    rt = VALUE #( ( `x` ) ).
			  ENDMETHOD.

			  METHOD a.
			  ENDMETHOD.

			  METHOD b.
			  ENDMETHOD.
			ENDCLASS.
			""";

	@Test
	void listsDeclarations() {
		List<ClassSurgery.Declaration> d = ClassSurgery.declarations(CLASS, "zcl_a");
		assertEquals(List.of("RUN", "READ", "A", "B"), d.stream().map(ClassSurgery.Declaration::name).toList());
		assertEquals(List.of("public", "private", "private", "private"),
				d.stream().map(ClassSurgery.Declaration::visibility).toList());
		assertTrue(d.get(2).chained());
	}

	@Test
	void addsMethodWithStub() {
		String out = ClassSurgery.addMethod(CLASS, "ZCL_A", "METHODS count RETURNING VALUE(rv) TYPE i", "public");
		assertTrue(out.contains("    METHODS run IMPORTING iv_id TYPE i.\n    METHODS count RETURNING VALUE(rv) TYPE i.\n"
				+ "  PRIVATE SECTION."), out);
		assertTrue(out.contains("  METHOD b.\n  ENDMETHOD.\n\n  METHOD count.\n  ENDMETHOD.\nENDCLASS."), out);
		String abs = ClassSurgery.addMethod(CLASS, "ZCL_A", "METHODS hook ABSTRACT.", "private");
		assertTrue(abs.contains("METHODS hook ABSTRACT.\nENDCLASS."), abs);
		assertTrue(!abs.contains("METHOD hook."), abs);
		assertThrows(SurgeryException.class, () -> ClassSurgery.addMethod(CLASS, "ZCL_A", "METHODS run.", "public"));
		assertThrows(SurgeryException.class, () -> ClassSurgery.addMethod(CLASS, "ZCL_A", "METHODS x.", "protected"));
		assertThrows(SurgeryException.class, () -> ClassSurgery.addMethod(CLASS, "ZCL_A", "DATA x TYPE i.", "public"));
		String wrapped = ClassSurgery.addMethod(CLASS, "ZCL_A", "METHODS\n      size RETURNING VALUE(rv) TYPE i.", "public");
		assertTrue(wrapped.contains("METHOD size.\n  ENDMETHOD."), wrapped);
		SurgeryException chained = assertThrows(SurgeryException.class,
				() -> ClassSurgery.addMethod(CLASS, "ZCL_A", "METHODS: size.", "public"));
		assertTrue(chained.getMessage().contains("chain colon"), chained.getMessage());
	}

	@Test
	void editsMovesAndDeletes() {
		String signed = ClassSurgery.editMethodSignature(CLASS, "zcl_a", "run",
				"METHODS run IMPORTING iv_id TYPE i iv_flag TYPE abap_bool OPTIONAL.");
		assertTrue(signed.contains("    METHODS run IMPORTING iv_id TYPE i iv_flag TYPE abap_bool OPTIONAL.\n"), signed);
		assertThrows(SurgeryException.class, () -> ClassSurgery.editMethodSignature(CLASS, "zcl_a", "run",
				"METHODS walk."));
		assertThrows(SurgeryException.class, () -> ClassSurgery.editMethodSignature(CLASS, "zcl_a", "a", "METHODS a."));

		String moved = ClassSurgery.changeVisibility(CLASS, "zcl_a", "read", "public");
		assertTrue(moved.contains("    METHODS run IMPORTING iv_id TYPE i.\n    \"! Reads the rows\n    METHODS read "
				+ "RETURNING VALUE(rt) TYPE string_table.\n  PRIVATE SECTION.\n    METHODS: a, b."), moved);
		assertTrue(moved.contains("rt = VALUE #( ( `x` ) )."), moved);
		assertEquals(CLASS, ClassSurgery.changeVisibility(CLASS, "zcl_a", "run", "public"));

		String deleted = ClassSurgery.deleteMethod(CLASS, "zcl_a", "read");
		assertTrue(!deleted.contains("METHODS read") && !deleted.contains("METHOD read.")
				&& !deleted.contains("Reads the rows"), deleted);
		assertTrue(deleted.contains("  PRIVATE SECTION.\n    METHODS: a, b."), deleted);
		assertThrows(SurgeryException.class, () -> ClassSurgery.deleteMethod(CLASS, "zcl_a", "nope"));
	}

	@Test
	void replacesTheDefinitionOnlyWhenImplementationsMatch() {
		String def = """
				CLASS zcl_a DEFINITION PUBLIC FINAL CREATE PUBLIC.
				  PUBLIC SECTION.
				    METHODS run IMPORTING iv_id TYPE i.
				    METHODS read RETURNING VALUE(rt) TYPE string_table.
				    METHODS: a, b.
				ENDCLASS.""";
		String out = ClassSurgery.editDefinition(CLASS, "zcl_a", def);
		assertTrue(out.startsWith(def + "\n\nCLASS zcl_a IMPLEMENTATION."), out);
		SurgeryException orphan = assertThrows(SurgeryException.class, () -> ClassSurgery.editDefinition(CLASS, "zcl_a",
				def.replace("    METHODS read RETURNING VALUE(rt) TYPE string_table.\n", "")));
		assertTrue(orphan.getMessage().contains("drops READ"), orphan.getMessage());
		SurgeryException missing = assertThrows(SurgeryException.class, () -> ClassSurgery.editDefinition(CLASS,
				"zcl_a", def.replace("METHODS: a, b.", "METHODS: a, b, c.")));
		assertTrue(missing.getMessage().contains("declares C without implementation"), missing.getMessage());
	}

	@Test
	void editsUnitsOfPrograms() {
		String prog = "REPORT z.\n\nSTART-OF-SELECTION.\n  PERFORM main.\n\nFORM main.\n  WRITE TEXT-001.\nENDFORM.\n";
		String edited = CodeEdits.apply(prog, "Z", Json.parseObject(
				"{\"action\":\"edit_unit\",\"unit\":\"main\",\"source\":\"FORM main.\\n  WRITE TEXT-002.\\nENDFORM.\"}"));
		assertEquals(prog.replace("TEXT-001", "TEXT-002"), edited);
		String added = CodeEdits.apply(prog, "Z", Json.parseObject(
				"{\"action\":\"add_unit\",\"source\":\"MODULE status_0100 OUTPUT.\\nENDMODULE.\"}"));
		assertTrue(added.endsWith("ENDFORM.\n\nMODULE status_0100 OUTPUT.\nENDMODULE.\n"), added);
		assertThrows(SurgeryException.class, () -> CodeEdits.apply(prog, "Z", Json.parseObject(
				"{\"action\":\"edit_unit\",\"unit\":\"main\",\"source\":\"FORM other.\\nENDFORM.\"}")));
		assertThrows(SurgeryException.class, () -> CodeEdits.apply(prog, "Z", Json.parseObject(
				"{\"action\":\"add_unit\",\"source\":\"FORM main.\\nENDFORM.\"}")));
		assertThrows(SurgeryException.class, () -> CodeEdits.apply(prog, "Z", Json.parseObject("{\"action\":\"x\"}")));
	}
}
