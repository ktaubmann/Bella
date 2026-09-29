package de.kiliantaubmann.bella.core.abap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the repository objects a piece of ABAP code uses: tables in
 * {@code SELECT}, types after {@code TYPE}, classes before {@code =>}, function
 * modules in {@code CALL FUNCTION} and so on. Pure text heuristics without SAP
 * access; a wrong candidate only costs one search that finds nothing.
 */
public final class AbapReferences {

	/** What kind of object the code position suggests; decides between search hits of the same name. */
	public enum Hint {
		TABLE, CLASS, TYPE, FUNCTION, ANY
	}

	/** A referenced object name, upper-cased. */
	public record Reference(String name, Hint hint) {
	}

	private static final String NAME = "(/?[A-Za-z_][A-Za-z0-9_]*(?:/[A-Za-z0-9_]+)*)";

	private static final Pattern CALL_FUNCTION = Pattern.compile("(?i)\\bCALL\\s+FUNCTION\\s+'([^']+)'");
	private static final Pattern TYPE = Pattern.compile("(?i)\\bTYPE\\s+(?:REF\\s+TO\\s+|(?:(?:STANDARD|SORTED|HASHED|ANY)\\s+)?TABLE\\s+OF\\s+(?:REF\\s+TO\\s+)?|RANGE\\s+OF\\s+|LINE\\s+OF\\s+)?"
			+ NAME);
	private static final Pattern FROM_JOIN = Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+" + NAME);
	private static final Pattern STATIC_ACCESS = Pattern.compile(NAME + "=>");
	private static final Pattern NEW_CAST = Pattern.compile("(?i)\\b(?:NEW|CAST)\\s+" + NAME + "\\s*\\(");
	private static final Pattern INTERFACES = Pattern.compile("(?i)^\\s*INTERFACES\\s+" + NAME);
	private static final Pattern INHERITING = Pattern.compile("(?i)\\bINHERITING\\s+FROM\\s+" + NAME);
	private static final Pattern LITERAL = Pattern.compile("'(?:[^']|'')*'|`(?:[^`]|``)*`|\\|[^|]*\\|");
	private static final Pattern WORD = Pattern.compile("/?[A-Za-z_][A-Za-z0-9_]*(?:/[A-Za-z0-9_]+)*");

	/** Built-in and generic types, pseudo components and keywords that follow TYPE. */
	private static final Set<String> BUILTIN = Set.of("I", "INT1", "INT2", "INT4", "INT8", "F", "P", "C", "N", "D", "T",
			"X", "B", "S", "STRING", "XSTRING", "DECFLOAT", "DECFLOAT16", "DECFLOAT34", "UTCLONG", "ABAP_BOOL",
			"ABAP_BOOLEAN", "SY", "SYST", "ANY", "DATA", "SIMPLE", "CSEQUENCE", "CLIKE", "NUMERIC", "XSEQUENCE",
			"OBJECT", "TABLE", "INDEX", "STANDARD", "SORTED", "HASHED", "REF", "LINE", "RANGE", "OF", "TO", "FOR",
			"ME", "SUPER", "BEGIN", "END", "VALUE", "LENGTH", "DECIMALS", "STRUCTURE", "RESPONSE", "REQUEST",
			"KEY", "EMPTY", "UNIQUE", "NON-UNIQUE", "WITH", "DEFAULT", "INITIAL", "SIZE", "BOXED", "READ-ONLY",
			"DUMMY", "ABAP_TRUE", "ABAP_FALSE", "SPACE", "SELECT", "SINGLE", "DISTINCT", "FIELDS", "SCREEN");

	/** Name prefixes of local types, classes and variables. */
	private static final Pattern LOCAL_PREFIX = Pattern.compile(
			"(?i)^(?:l?ty|l?tt|gty|gtt|lcl|lif|ltc|lth|lx|ts|th|lv|lt|ls|lo|lr|gv|gt|gs|go|mv|mt|ms|mo|iv|it|is|ev|et|es|cv|ct|cs|rv|rt|rs|ro)_");

	private AbapReferences() {
	}

	/** Candidates in the order they appear in {@code source}, without duplicates and local names. */
	public static List<Reference> extract(String source) {
		if (source == null || source.isBlank()) {
			return List.of();
		}
		List<AbapStructureScanner.Statement> statements = AbapStructureScanner.statements(source);
		Set<String> local = localNames(statements);
		Map<String, Hint> found = new LinkedHashMap<>();
		for (AbapStructureScanner.Statement st : statements) {
			String raw = st.text();
			Matcher f = CALL_FUNCTION.matcher(raw);
			while (f.find()) {
				add(found, local, f.group(1).trim(), Hint.FUNCTION);
			}
			String code = LITERAL.matcher(raw).replaceAll("''");
			String first = st.words(1).isEmpty() ? "" : st.words(1).get(0);
			if (code.toUpperCase(Locale.ROOT).matches("(?s).*\\bSELECT\\b.*")) {
				collect(FROM_JOIN, code, found, local, Hint.TABLE);
			}
			if (first.equals("CLASS")) {
				collect(INHERITING, code, found, local, Hint.CLASS);
			}
			collect(INTERFACES, code, found, local, Hint.CLASS);
			collect(STATIC_ACCESS, code, found, local, Hint.CLASS);
			collect(NEW_CAST, code, found, local, Hint.CLASS);
			collect(TYPE, code, found, local, Hint.TYPE);
		}
		List<Reference> out = new ArrayList<>();
		found.forEach((n, h) -> out.add(new Reference(n, h)));
		return out;
	}

	private static void collect(Pattern p, String code, Map<String, Hint> found, Set<String> local, Hint hint) {
		Matcher m = p.matcher(code);
		while (m.find()) {
			// "@x" is a host variable; for "x-comp" the name pattern already stops at the dash
			if (m.start(1) == 0 || code.charAt(m.start(1) - 1) != '@') {
				add(found, local, m.group(1), hint);
			}
		}
	}

	private static void add(Map<String, Hint> found, Set<String> local, String name, Hint hint) {
		String n = name.toUpperCase(Locale.ROOT);
		if (n.length() < 2 || BUILTIN.contains(n) || local.contains(n) || LOCAL_PREFIX.matcher(n).find()
				|| n.startsWith("<")) {
			return;
		}
		found.putIfAbsent(n, hint);
	}

	private static final Pattern DECLARED = Pattern.compile("(?i)^\\s*(?:DATA|TYPES|CONSTANTS|STATICS|CLASS-DATA|PARAMETERS|SELECT-OPTIONS|FIELD-SYMBOLS)\\b\\s*:?\\s*(.*)$");
	private static final Pattern INLINE = Pattern.compile("(?i)\\b(?:DATA|FINAL|FIELD-SYMBOL)\\(\\s*([^)\\s]+)\\s*\\)");

	/** Names declared in the code itself: variables, local types, local classes and interfaces. */
	static Set<String> localNames(List<AbapStructureScanner.Statement> statements) {
		Set<String> names = new HashSet<>();
		for (AbapStructureScanner.Statement st : statements) {
			String text = st.text();
			List<String> w = st.words(3);
			Matcher inline = INLINE.matcher(text);
			while (inline.find()) {
				names.add(inline.group(1).toUpperCase(Locale.ROOT));
			}
			if (w.size() >= 2 && (w.get(0).equals("CLASS") || w.get(0).equals("INTERFACE"))) {
				names.add(w.get(1));
			}
			Matcher d = DECLARED.matcher(text);
			if (d.matches()) {
				// "DATA: a TYPE x, b TYPE y." declares a and b
				for (String part : d.group(1).split(",")) {
					String p = part.trim();
					if (p.toUpperCase(Locale.ROOT).startsWith("BEGIN OF ") || p.toUpperCase(Locale.ROOT).startsWith("END OF ")) {
						p = p.substring(p.toUpperCase(Locale.ROOT).startsWith("BEGIN") ? 9 : 7).trim();
					}
					Matcher n = WORD.matcher(p);
					if (n.lookingAt()) {
						names.add(n.group().toUpperCase(Locale.ROOT));
					}
				}
			}
		}
		return names;
	}

	/** Common words of instructions (German and English) and ABAP keywords that are not object names. */
	private static final Set<String> STOPWORDS = setOf(
			// English
			"THE", "AND", "FOR", "WITH", "FROM", "INTO", "THAT", "THIS", "ALL", "ANY", "ONE", "TWO", "NEW", "NOT",
			"ARE", "WAS", "BUT", "CAN", "HAS", "HAVE", "USE", "USING", "SHOW", "LIST", "READ", "WRITE", "OUTPUT",
			"DISPLAY", "PRINT", "CREATE", "ADD", "GET", "SET", "LOAD", "SAVE", "UPDATE", "DELETE", "INSERT", "CALL",
			"RETURN", "RETURNS", "EACH", "EVERY", "ENTRIES", "ENTRY", "FIELD", "FIELDS", "TABLE", "TABLES", "DATA",
			"CODE", "METHOD", "METHODS", "CLASS", "FUNCTION", "MODULE", "REPORT", "PROGRAM", "VALUE", "VALUES",
			"FIRST", "LAST", "ROWS", "ROW", "LINES", "LINE", "RECORDS", "RECORD", "TEST", "TESTS", "UNIT",
			"ERROR", "ERRORS", "MESSAGE", "MESSAGES", "CHECK", "CHECKS", "WHERE", "WHEN", "THEN", "ELSE", "LOOP",
			"OVER", "SORT", "SORTED", "BY", "ORDER", "GROUP", "SUM", "COUNT", "MAX", "MIN", "SIMPLE", "SMALL",
			"LIKE", "SAME", "ALSO", "ONLY", "SOME", "MORE", "LESS", "SHOULD", "MUST", "WILL", "WOULD", "LET",
			"MAKE", "BUILD", "IMPLEMENT", "REFACTOR", "FIX", "CHANGE", "RENAME", "SELECT", "SINGLE", "JOIN",
			"ALV", "SALV", "GRID", "TYPE", "TYPES", "STRUCTURE", "OBJECT", "OBJECTS", "LOCAL", "GLOBAL", "EXCEPTION",
			"EXCEPTIONS", "PARAMETER", "PARAMETERS", "INPUT", "RESULT", "RESULTS", "CUSTOMER", "CUSTOMERS",
			"MATERIAL", "MATERIALS", "ORDERS", "USER", "USERS", "DATE", "TIME", "TODAY", "NUMBER", "NAME", "NAMES",
			"TEXT", "TEXTS", "STRING", "INTEGER", "ABAP", "SAP", "YOU", "YOUR", "PLEASE", "HERE", "THERE", "WHICH",
			"WHAT", "HOW", "WHY",
			// German
			"DER", "DIE", "DAS", "DEN", "DEM", "DES", "EIN", "EINE", "EINEN", "EINEM", "EINER", "UND", "ODER",
			"MIT", "VON", "FÜR", "FUER", "AUS", "AUF", "ALLE", "ALLEN", "ALLER", "JEDE", "JEDEN", "NICHT", "NUR",
			"AUCH", "ALS", "WIE", "WENN", "DANN", "SONST", "IST", "SIND", "WIRD", "WERDEN", "SOLL", "SOLLEN",
			"ZEIGE", "ZEIGEN", "ANZEIGEN", "AUSGEBEN", "AUSGABE", "LESEN", "LIES", "SCHREIBE", "SCHREIBEN",
			"ERSTELLE", "ERSTELLEN", "ERZEUGE", "ERZEUGEN", "HOLE", "HOLEN", "LADE", "LADEN", "SPEICHERN",
			"ÄNDERN", "AENDERN", "LÖSCHEN", "LOESCHEN", "TABELLE", "TABELLEN", "FELD", "FELDER", "DATEN",
			"EINTRAG", "EINTRÄGE", "ZEILE", "ZEILEN", "SATZ", "SÄTZE", "ERSTE", "ERSTEN", "LETZTE", "LETZTEN",
			"METHODE", "KLASSE", "BAUSTEIN", "FUNKTIONSBAUSTEIN", "PROGRAMM", "NACH", "BEI", "ZUM", "ZUR",
			"UEBER", "ÜBER", "UNTER", "PRO", "JE", "SORTIERT", "SORTIEREN", "SUMME", "ANZAHL", "BITTE", "HIER",
			"FEHLER", "MELDUNG", "MELDUNGEN", "PRÜFE", "PRUEFE", "PRÜFEN", "TEST", "TESTEN", "KUNDE", "KUNDEN",
			"MATERIALIEN", "AUFTRAG", "AUFTRÄGE", "BENUTZER", "DATUM", "ZEIT", "HEUTE", "NUMMER", "TEXTE",
			"IMPLEMENTIERE", "IMPLEMENTIEREN", "UMBAUEN", "VERWENDE", "VERWENDEN", "NUTZE", "NUTZEN",
			"ZURÜCK", "ZURUECK", "GIB", "GEBE", "WELCHE", "WELCHER", "WAS", "WARUM",
			// verbs of change requests
			"ENTFERNEN", "ENTFERNE", "LOESCHE", "HINZUFUEGEN", "HINZU", "FUEGE", "ERGAENZEN", "ERGAENZE",
			"ERSETZEN", "ERSETZE", "UMBENENNEN", "BENENNE", "VERSCHIEBEN", "VERSCHIEBE", "BEREINIGEN",
			"AUFRAEUMEN", "KORRIGIEREN", "KORRIGIERE", "VERBESSERN", "VERBESSERE", "ANPASSEN", "PASSE",
			"REMOVE", "REPLACE", "RENAME", "MOVE", "DROP", "CLEAN", "CLEANUP", "CORRECT", "IMPROVE", "ADAPT",
			"EXTRACT", "SPLIT", "MERGE", "SIMPLIFY", "OPTIMIZE", "OPTIMIERE", "OPTIMIEREN", "VEREINFACHE",
			"VEREINFACHEN", "MODERNISIEREN", "MODERNIZE", "EXPLAIN", "ERKLAERE", "ERKLAEREN");

	/** Like {@link Set#of} but tolerates duplicates in long word lists. */
	private static Set<String> setOf(String... words) {
		return Set.copyOf(List.of(words));
	}

	/** At most this many names are taken from an instruction. */
	public static final int MAX_INSTRUCTION_NAMES = 8;

	/**
	 * Object names mentioned in a natural-language instruction, e.g.
	 * {@code MARA} in "select mara and show". Ordinary words are filtered by a
	 * German and English stop word list; the rest are candidates of any type.
	 */
	public static List<Reference> fromInstruction(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		Map<String, Hint> found = new LinkedHashMap<>();
		Matcher f = CALL_FUNCTION.matcher(text);
		while (f.find()) {
			found.putIfAbsent(f.group(1).trim().toUpperCase(Locale.ROOT), Hint.FUNCTION);
		}
		Matcher m = Pattern.compile("/?[\\p{L}_][\\p{L}0-9_]*(?:/[\\p{L}0-9_]+)*").matcher(text);
		while (m.find() && found.size() < MAX_INSTRUCTION_NAMES) {
			String w = m.group().toUpperCase(Locale.ROOT);
			if (w.length() < 3 || w.length() > 30 || STOPWORDS.contains(w) || BUILTIN.contains(w)
					|| !w.matches("/?[A-Z_][A-Z0-9_]*(?:/[A-Z0-9_]+)*") || LOCAL_PREFIX.matcher(w).find()) {
				continue;
			}
			found.putIfAbsent(w, Hint.ANY);
		}
		List<Reference> out = new ArrayList<>();
		found.forEach((n, h) -> out.add(new Reference(n, h)));
		return out.size() > MAX_INSTRUCTION_NAMES ? out.subList(0, MAX_INSTRUCTION_NAMES) : out;
	}

	/** Joins candidate lists, keeping the first hint of each name. */
	public static List<Reference> merge(List<Reference> first, List<Reference> second) {
		Map<String, Hint> found = new LinkedHashMap<>();
		for (List<Reference> list : List.of(first, second)) {
			for (Reference r : list) {
				found.putIfAbsent(r.name(), r.hint());
			}
		}
		List<Reference> out = new ArrayList<>();
		found.forEach((n, h) -> out.add(new Reference(n, h)));
		return out;
	}
}
