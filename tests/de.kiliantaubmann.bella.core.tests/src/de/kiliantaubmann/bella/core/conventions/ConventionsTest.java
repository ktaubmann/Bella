package de.kiliantaubmann.bella.core.conventions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.adt.AdtObjectRef;
import de.kiliantaubmann.bella.core.prompt.AbapPrompts;

class ConventionsTest {

	@Test
	void parsesRulesAndReportsBadLines() {
		NamingRules.Parsed p = NamingRules.parse("""
				# comment
				local_data = lv_*, lt_*   # trailing comment
				Field-Symbol = <ls_*>
				nonsense = x
				class
				method =
				""");
		assertEquals(List.of("Line 4: unknown kind \"nonsense\"", "Line 5: expected kind = pattern",
				"Line 6: no pattern for method"), p.errors());
		NamingRules r = p.rules();
		assertTrue(r.rule(NamingRules.Kind.LOCAL_DATA).orElseThrow().matches("LV_COUNT"));
		assertFalse(r.rule(NamingRules.Kind.LOCAL_DATA).orElseThrow().matches("count"));
		assertTrue(r.rule(NamingRules.Kind.FIELD_SYMBOL).orElseThrow().matches("<ls_row>"));
		assertFalse(r.rule(NamingRules.Kind.FIELD_SYMBOL).orElseThrow().matches("<row>"));
		assertEquals("local_data = lv_*, lt_*\nfield_symbol = <ls_*>\n", r.describe());
		assertTrue(NamingRules.parse(NamingRules.TEMPLATE).errors().isEmpty());
		assertTrue(NamingRules.parse("  \n").rules().isEmpty());
	}

	@Test
	void questionMarkIsOneCharacter() {
		NamingRules r = NamingRules.parse("class = ZCL_??_*").rules();
		assertTrue(r.rule(NamingRules.Kind.CLASS).orElseThrow().matches("ZCL_SD_ORDER"));
		assertFalse(r.rule(NamingRules.Kind.CLASS).orElseThrow().matches("ZCL_SDX_ORDER"));
	}

	@Test
	void systemConventionsAddToTheGeneralOnes() {
		ProjectConventions general = ProjectConventions.of("All: use ABAP Cloud.", "local_data = lv_*\nclass = ZCL_*");
		ProjectConventions system = ProjectConventions.of("S4H: packages ZSD_*.", "class = ZCL_SD_*");
		ProjectConventions merged = ProjectConventions.merge(general, system);
		assertEquals("All: use ABAP Cloud.\n\nS4H: packages ZSD_*.", merged.text());
		assertEquals("class = ZCL_SD_*\nlocal_data = lv_*\n", merged.naming().describe());
		assertEquals(general, ProjectConventions.merge(general, ProjectConventions.NONE));
		assertTrue(ProjectConventions.NONE.promptSection().isEmpty());

		String section = merged.promptSection();
		assertTrue(section.contains("Project conventions (follow them"), section);
		assertTrue(section.contains("S4H: packages ZSD_*.\nNaming rules (kind = allowed patterns, * any characters):\nclass = ZCL_SD_*"),
				section);
		String sys = new AbapPrompts(null, null, merged).chatSystem();
		assertTrue(sys.endsWith(section), sys);
		assertFalse(new AbapPrompts(null, null).chatSystem().contains("Project conventions"));
	}

	@Test
	void longTextIsCutInThePrompt() {
		ProjectConventions big = new ProjectConventions("x".repeat(ProjectConventions.MAX_PROMPT_CHARS + 100), NamingRules.NONE);
		assertTrue(big.promptSection().length() < ProjectConventions.MAX_PROMPT_CHARS + 300);
		assertTrue(big.promptSection().contains("x\n…"));
	}

	@Test
	void proposalBlocksAndObjectList() {
		ConventionsProposal p = ConventionsProposal.parse("""
				Here is my proposal.
				```markdown
				- Package ZSALES holds the sales order API.
				```
				```naming
				class = ZCL_SD_*
				```
				""");
		assertEquals("- Package ZSALES holds the sales order API.", p.text());
		assertEquals("class = ZCL_SD_*", p.naming());
		assertEquals(new ConventionsProposal("", ""), ConventionsProposal.parse("no blocks"));
		assertEquals("CLAS/OC ZCL_SD_ORDER – Sales order\nPROG/P ZSD_REPORT\n", ConventionsProposal.objectList(List.of(
				new AdtObjectRef("/u", "ZCL_SD_ORDER", "CLAS/OC", "ZSALES", "Sales order"),
				new AdtObjectRef("/v", "ZSD_REPORT", "PROG/P", "ZSALES", ""))));
		String prompt = new AbapPrompts(null, null).deriveConventions("ZSALES", "CLAS/OC ZCL_SD_ORDER\n", "### x").user();
		assertTrue(prompt.contains("package ZSALES from its objects and code below, as a starting point"), prompt);
		assertTrue(prompt.contains("Allowed kinds: class, interface,"), prompt);
		assertFalse(prompt.contains("\t"), "continued lines are joined");
	}
}
