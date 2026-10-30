package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.Json;

/**
 * BAdI implementations (enhancement implementations of type ENHO/XHB) and
 * the enhancement spots (ENHS/XSB) they implement. ADT keeps an implementation
 * as one metadata document without source. Create follows Eclipse: POST the
 * container without implementations, then lock, PUT the whole document,
 * unlock; posting the implementations with the create fails in transportable
 * packages and leaves an empty TADIR entry. Document shape, media types and
 * filter trees follow ARC-1 ({@code src/adt/enhancement-impl.ts},
 * {@code src/adt/enho-filter.ts}, MIT), verified there on 7.58, 8.16 and BTP.
 */
final class AdtBadi {

	static final String ENHO_TYPE = "application/vnd.sap.adt.enh.enhoxhb.v4+xml";
	/** The spot type discovery names; the v1 to v4 names of enhsxsb answer 406. */
	static final String SPOT_TYPE = "application/vnd.sap.adt.enh.enhs.v2+xml";
	static final String COLLECTION = "/sap/bc/adt/enhancements/enhoxhb";
	private static final String SPOTS = "/sap/bc/adt/enhancements/enhsxsb/";

	/** Comparators SAP offers for filter values. */
	static final List<String> COMPARATORS = List.of("=", "<>", "<=", ">=", "<", ">", "CP", "NP");

	private static final Pattern NAME = Pattern.compile("(?:/[A-Z0-9_]+/)?[A-Z0-9_]+");

	private AdtBadi() {
	}

	static String uri(String name) {
		return COLLECTION + "/" + AdtObjectRef.encodeName(name.trim());
	}

	static String spotUri(String spot) {
		return SPOTS + AdtObjectRef.encodeName(spot.trim());
	}

	// ---- model -------------------------------------------------------------------

	/** One BAdI implementation as the tool takes it; {@code null} fields were not given. */
	record Input(String name, String badi, String implementingClass, String shortText, Boolean active,
			Boolean isDefault, String filter) {
	}

	/**
	 * One BAdI implementation as written. {@code example} and
	 * {@code customizingLock} are kept from the stored version ({@code null} on
	 * create, where SAP sets them); SAP stores only what the PUT sends.
	 */
	record Entry(String name, String badi, String implementingClass, String shortText, boolean active,
			boolean isDefault, String example, String customizingLock, String filterTree) {
	}

	/** A stored enhancement implementation; {@code filters} holds each implementation's condition as text. */
	record Stored(String name, String description, String pkg, String language, String languageVersion,
			String technology, String spot, List<Entry> entries, Map<String, String> filters) {
	}

	/** A filter declared by a BAdI definition: its type and the DDIC check, already as {@code enho:filterCheck}. */
	record FilterDecl(String name, String type, String shortText, String checkXml, String checkObject) {
	}

	record BadiDef(String name, String shortText, String iface, boolean singleUse, boolean filterRequired,
			Map<String, FilterDecl> filters) {
	}

	record Spot(String name, String pkg, boolean internal, Map<String, BadiDef> badis) {
	}

	// ---- tool input -----------------------------------------------------------------

	/** {@code badi_implementations} of a call, {@code null} if not given. */
	static List<Input> inputs(JsonObject in) throws AdtException {
		JsonArray arr = Json.arr(in, "badi_implementations");
		if (arr == null) {
			return null;
		}
		List<Input> out = new ArrayList<>();
		List<String> seen = new ArrayList<>();
		for (JsonElement e : arr) {
			if (!e.isJsonObject()) {
				throw new AdtException(400, "Every entry of 'badi_implementations' is an object with name, badi and "
						+ "implementing_class.");
			}
			JsonObject o = e.getAsJsonObject();
			String name = name(Json.str(o, "name"), "name");
			if (seen.contains(name)) {
				throw new AdtException(400, "BAdI implementation " + name + " is listed twice.");
			}
			seen.add(name);
			String badi = Json.str(o, "badi");
			String cls = Json.str(o, "implementing_class");
			String filter = Json.str(o, "filter");
			if (filter != null && !filter.isBlank()) {
				filter = render(parseFilter(filter)); // syntax now, the names against the spot later
			}
			out.add(new Input(name, badi == null || badi.isBlank() ? null : name(badi, "badi"),
					cls == null || cls.isBlank() ? null : name(cls, "implementing_class"), Json.str(o, "short_text"),
					bool(o, "active"), bool(o, "default"), filter == null ? null : filter.trim()));
		}
		return out;
	}

	private static String name(String value, String key) throws AdtException {
		String n = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
		if (n.isEmpty()) {
			throw new AdtException(400, "Every BAdI implementation needs '" + key + "'.");
		}
		if (!NAME.matcher(n).matches() || n.length() > 30) {
			throw new AdtException(400, "'" + value + "' is not a valid ABAP name (" + key + ").");
		}
		return n;
	}

	private static Boolean bool(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : null;
	}

	/**
	 * The implementations to write on create: every input needs its BAdI and
	 * class, which the spot must define; filters become trees.
	 */
	static List<Entry> created(List<Input> inputs, Spot spot) throws AdtException {
		List<Entry> out = new ArrayList<>();
		for (Input i : inputs) {
			if (i.badi() == null || i.implementingClass() == null) {
				throw new AdtException(400, "BAdI implementation " + i.name() + " needs 'badi' and 'implementing_class'.");
			}
			out.add(new Entry(i.name(), i.badi(), i.implementingClass(), i.shortText() == null ? "" : i.shortText(),
					i.active() == null || i.active(), i.isDefault() != null && i.isDefault(), null, null,
					tree(i, spot)));
		}
		return out;
	}

	/**
	 * The implementations after an update: given ones are added or changed by
	 * name, fields not given keep their stored value, a stored filter stays as
	 * SAP serialised it unless a different one is given ({@code ""} removes
	 * it), {@code remove} deletes.
	 */
	static List<Entry> merged(Stored stored, List<Input> inputs, List<String> remove, Spot spot) throws AdtException {
		Map<String, Entry> byName = new LinkedHashMap<>();
		stored.entries().forEach(e -> byName.put(e.name(), e));
		for (String r : remove) {
			if (byName.remove(r) == null) {
				throw new AdtException(400, stored.name() + " has no BAdI implementation " + r + " (it has: "
						+ String.join(", ", stored.entries().stream().map(Entry::name).toList()) + ").");
			}
		}
		for (Input i : inputs) {
			Entry prev = byName.get(i.name());
			if (prev == null) {
				byName.put(i.name(), created(List.of(i), spot).get(0));
				continue;
			}
			String badi = i.badi() == null ? prev.badi() : i.badi();
			boolean sameBadi = badi.equals(prev.badi());
			boolean storedTree = prev.filterTree() != null && prev.filterTree().contains("filterToken");
			String storedFilter = stored.filters().getOrDefault(prev.name(), "");
			String tree;
			if (i.filter() == null || storedTree && !i.filter().isEmpty() && i.filter().equals(storedFilter)) {
				if (storedTree && !sameBadi) {
					throw new AdtException(400, i.name() + ": its filter (" + (storedFilter.isEmpty() ? "stored filter"
							: storedFilter) + ") belongs to BAdI " + prev.badi() + ". Give the filter for " + badi
							+ " in 'filter', or \"\" to remove it.");
				}
				tree = prev.filterTree();
			} else {
				tree = tree(new Input(i.name(), badi, null, null, null, null, i.filter()), spot);
			}
			byName.put(i.name(), new Entry(i.name(), badi,
					i.implementingClass() == null ? prev.implementingClass() : i.implementingClass(),
					i.shortText() == null ? prev.shortText() : i.shortText(),
					i.active() == null ? prev.active() : i.active(),
					i.isDefault() == null ? prev.isDefault() : i.isDefault(), prev.example(), prev.customizingLock(),
					tree));
		}
		for (Input i : inputs) {
			checkInSpot(i.badi() == null ? byName.get(i.name()).badi() : i.badi(), spot);
		}
		return new ArrayList<>(byName.values());
	}

	private static void checkInSpot(String badi, Spot spot) throws AdtException {
		if (!spot.badis().containsKey(badi)) {
			throw new AdtException(400, "BAdI " + badi + " is not defined in enhancement spot " + spot.name()
					+ " (defined: " + String.join(", ", spot.badis().keySet()) + ").");
		}
	}

	/** The filter tree of a new or changed filter, empty without one. */
	private static String tree(Input i, Spot spot) throws AdtException {
		checkInSpot(i.badi(), spot);
		if (i.filter() == null || i.filter().isBlank()) {
			return "";
		}
		return filterTree(parseFilter(i.filter()), i.name(), spot.badis().get(i.badi()));
	}

	// ---- documents -------------------------------------------------------------------

	/**
	 * The enhancement implementation document. Element order and references
	 * follow SAP's own GET serialisation; xsi and enhcore are declared on the
	 * root, so stored filter trees can be re-sent as they are.
	 */
	static String xml(String name, String description, String pkg, String language, String responsible,
			String languageVersion, String spot, List<Entry> entries) {
		String spotRef = "adtcore:uri=\"" + spotUri(spot) + "\" adtcore:type=\"ENHS/XSB\" adtcore:name=\"" + x(spot)
				+ "\"";
		StringBuilder impls = new StringBuilder();
		for (Entry e : entries) {
			impls.append("<enho:badiImplementation enho:name=\"").append(x(e.name())).append("\" enho:shortText=\"")
					.append(x(e.shortText())).append("\" enho:example=\"").append("true".equals(e.example()))
					.append("\" enho:default=\"").append(e.isDefault()).append("\" enho:active=\"").append(e.active())
					.append('"');
			if (e.customizingLock() != null) {
				impls.append(" enho:customizingLock=\"").append(x(e.customizingLock())).append('"');
			}
			impls.append("><enho:enhancementSpot ").append(spotRef).append("/>")
					.append("<enho:badiDefinition adtcore:uri=\"/sap/bc/adt/vit/wb/object_type/enhsxb/object_name/")
					.append(java.net.URLEncoder.encode(spot, java.nio.charset.StandardCharsets.UTF_8))
					.append("\" adtcore:type=\"ENHS/XB\" adtcore:name=\"").append(x(e.badi())).append("\"/>")
					.append("<enho:implementingClass adtcore:uri=\"/sap/bc/adt/oo/classes/")
					.append(AdtObjectRef.encodeName(e.implementingClass()))
					.append("\" adtcore:type=\"CLAS/OC\" adtcore:name=\"").append(x(e.implementingClass())).append("\"/>")
					.append(e.filterTree() == null ? "" : e.filterTree()).append("</enho:badiImplementation>");
		}
		String version = languageVersion == null || languageVersion.isBlank() ? ""
				: " adtcore:abapLanguageVersion=\"" + x(languageVersion) + "\"";
		String owner = responsible == null || responsible.isBlank() || responsible.length() > 12 ? ""
				: " adtcore:responsible=\"" + x(responsible.trim().toUpperCase(Locale.ROOT)) + "\"";
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<enho:objectData"
				+ " xmlns:enho=\"http://www.sap.com/adt/enhancements/enho\" xmlns:adtcore=\"http://www.sap.com/adt/core\""
				+ " xmlns:enhcore=\"http://www.sap.com/abapsource/enhancementscore\""
				+ " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" adtcore:type=\"ENHO/XHB\" adtcore:name=\""
				+ x(name) + "\" adtcore:description=\"" + x(description) + "\" adtcore:masterLanguage=\"" + x(language)
				+ "\"" + version + owner + ">\n  <adtcore:packageRef adtcore:name=\"" + x(pkg) + "\"/>\n"
				+ "  <enho:contentCommon enho:toolType=\"BADI_IMPL\"><enho:usages><enhcore:referencedObject"
				+ " enhcore:program_id=\"R3TR\" enhcore:element_usage=\"EXTO\"><enhcore:objectReference " + spotRef
				+ "/><enhcore:mainObjectReference " + spotRef + "/></enhcore:referencedObject></enho:usages>"
				+ "</enho:contentCommon>\n  <enho:contentSpecific><enho:badiTechnology><enho:badiImplementations>"
				+ impls + "</enho:badiImplementations></enho:badiTechnology></enho:contentSpecific>\n</enho:objectData>";
	}

	private static final Pattern IMPLEMENTATION = Pattern.compile(
			"<enho:badiImplementation\\s((?:[^>\"]|\"[^\"]*\")*?)(?:/>|>([\\s\\S]*?)</enho:badiImplementation>)");
	private static final Pattern FILTER_TREE = Pattern
			.compile("<enho:filterTree[\\s>][\\s\\S]*</enho:filterTree>|<enho:filterTree\\s*/>");
	private static final Pattern NAME_ATTR = Pattern.compile("(?:^|\\s)enho:name=\"([^\"]*)\"");

	/** A stored enhancement implementation; filter trees are cut from the raw XML, to be re-sent unchanged. */
	static Stored parse(String xml) throws IOException {
		Map<String, String> trees = new HashMap<>();
		Matcher m = IMPLEMENTATION.matcher(xml);
		while (m.find()) {
			Matcher n = NAME_ATTR.matcher(m.group(1));
			Matcher t = FILTER_TREE.matcher(m.group(2) == null ? "" : m.group(2));
			if (n.find() && t.find()) {
				trees.put(n.group(1).toUpperCase(Locale.ROOT), t.group());
			}
		}
		Document d = AdtXml.parse(xml);
		Element root = d.getDocumentElement();
		String pkg = "";
		for (Element e : AdtXml.elements(d, "packageRef")) {
			pkg = AdtXml.attr(e, "name");
			break;
		}
		String technology = "";
		String spot = "";
		for (Element common : AdtXml.elements(d, "contentCommon")) {
			technology = AdtXml.attr(common, "toolType");
			for (Element ref : AdtXml.elements(common, "objectReference")) {
				if (AdtXml.attr(ref, "type").equals("ENHS/XSB")) {
					spot = AdtXml.attr(ref, "name").toUpperCase(Locale.ROOT);
				}
			}
		}
		List<Entry> entries = new ArrayList<>();
		Map<String, String> filters = new HashMap<>();
		for (Element impl : AdtXml.elements(d, "badiImplementation")) {
			String name = AdtXml.attr(impl, "name").toUpperCase(Locale.ROOT);
			String badi = refName(impl, "badiDefinition");
			if (spot.isEmpty()) {
				spot = refName(impl, "enhancementSpot");
			}
			Node lock = impl.getAttributes().getNamedItemNS("http://www.sap.com/adt/enhancements/enho",
					"customizingLock");
			entries.add(new Entry(name, badi, refName(impl, "implementingClass"), AdtXml.attr(impl, "shortText"),
					!"false".equals(AdtXml.attr(impl, "active")) && !"false".equals(AdtXml.attr(impl, "isActive")),
					"true".equals(AdtXml.attr(impl, "default")) || "true".equals(AdtXml.attr(impl, "isDefault")),
					AdtXml.attr(impl, "example"), lock == null ? "" : lock.getNodeValue(),
					trees.getOrDefault(name, "")));
			for (Element tree : AdtXml.elements(impl, "filterTree")) {
				String text = storedFilter(tree);
				if (!text.isEmpty()) {
					filters.put(name, text);
				}
			}
		}
		String language = AdtXml.attr(root, "masterLanguage");
		return new Stored(AdtXml.attr(root, "name"), AdtXml.attr(root, "description"), pkg,
				language.isEmpty() ? AdtXml.attr(root, "language") : language,
				AdtXml.attr(root, "abapLanguageVersion"), technology, spot, entries, filters);
	}

	private static String refName(Element impl, String child) {
		for (Element e : AdtXml.elements(impl, child)) {
			return AdtXml.attr(e, "name").toUpperCase(Locale.ROOT);
		}
		return "";
	}

	/** The BAdI definitions of a spot with their interfaces and declared filters. */
	static Spot parseSpot(String xml) throws IOException {
		Document d = AdtXml.parse(xml);
		Element root = d.getDocumentElement();
		String pkg = "";
		for (Element e : AdtXml.elements(d, "packageRef")) {
			pkg = AdtXml.attr(e, "name");
			break;
		}
		boolean internal = false;
		for (Element common : AdtXml.elements(d, "contentCommon")) {
			internal |= "true".equals(AdtXml.attr(common, "internal"));
		}
		// the checks are copied into the implementations as SAP wrote them, so they are cut from the raw XML
		Map<String, Map<String, String>> checks = rawChecks(xml);
		Map<String, BadiDef> badis = new LinkedHashMap<>();
		for (Element b : AdtXml.elements(d, "badiDefinition")) {
			String name = AdtXml.attr(b, "name").toUpperCase(Locale.ROOT);
			if (name.isEmpty()) {
				continue;
			}
			String iface = "";
			for (Element i : AdtXml.elements(b, "interface")) {
				iface = AdtXml.attr(i, "name").toUpperCase(Locale.ROOT);
				break;
			}
			Map<String, FilterDecl> filters = new LinkedHashMap<>();
			for (Element f : AdtXml.elements(b, "filter")) {
				String fn = AdtXml.attr(f, "filterName").toUpperCase(Locale.ROOT);
				if (fn.isEmpty()) {
					continue;
				}
				String object = "";
				for (Element o : AdtXml.elements(f, "checkObject")) {
					object = AdtXml.attr(o, "type") + " " + AdtXml.attr(o, "name");
					break;
				}
				filters.put(fn, new FilterDecl(fn, AdtXml.attr(f, "filterType"), AdtXml.attr(f, "shorttext"),
						checks.getOrDefault(name, Map.of()).getOrDefault(fn, ""), object.trim()));
			}
			badis.put(name, new BadiDef(name, AdtXml.attr(b, "shorttext"), iface,
					"true".equals(AdtXml.attr(b, "singleUse")), "true".equals(AdtXml.attr(b, "filterLimitation")),
					filters));
		}
		return new Spot(AdtXml.attr(root, "name").toUpperCase(Locale.ROOT), pkg, internal, badis);
	}

	private static final Pattern SPOT_BADI = Pattern.compile(
			"<enhs:badiDefinition\\s((?:[^>\"]|\"[^\"]*\")*?)(?:/>|>([\\s\\S]*?)</enhs:badiDefinition>)");
	private static final Pattern SPOT_FILTER = Pattern
			.compile("<enhs:filter\\s((?:[^>\"]|\"[^\"]*\")*?)(?:/>|>([\\s\\S]*?)</enhs:filter>)");
	private static final Pattern SPOT_CHECK = Pattern
			.compile("<enhs:filterCheck[\\s>][\\s\\S]*?</enhs:filterCheck>|<enhs:filterCheck[^>]*/>");

	/** BAdI → filter → its {@code enhs:filterCheck}, renamed to {@code enho:filterCheck}. */
	private static Map<String, Map<String, String>> rawChecks(String xml) {
		Map<String, Map<String, String>> out = new HashMap<>();
		Matcher b = SPOT_BADI.matcher(xml);
		while (b.find()) {
			String badi = rawAttr(b.group(1), "enhs:name").toUpperCase(Locale.ROOT);
			Map<String, String> filters = new HashMap<>();
			Matcher f = SPOT_FILTER.matcher(b.group(2) == null ? "" : b.group(2));
			while (f.find()) {
				Matcher c = SPOT_CHECK.matcher(f.group(2) == null ? "" : f.group(2));
				if (c.find()) {
					filters.put(rawAttr(f.group(1), "enhs:filterName").toUpperCase(Locale.ROOT),
							c.group().replaceAll("<(/?)enhs:filterCheck", "<$1enho:filterCheck"));
				}
			}
			out.put(badi, filters);
		}
		return out;
	}

	private static String rawAttr(String attrs, String name) {
		Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(attrs);
		return m.find() ? m.group(1) : "";
	}

	/** An implementation as {@code adt_read_source} shows it: the JSON {@code adt_write_metadata} takes back. */
	static String describe(Stored s) {
		if (!s.technology().isEmpty() && !s.technology().equals("BADI_IMPL")) {
			return null;
		}
		JsonObject o = new JsonObject();
		o.addProperty("name", s.name());
		o.addProperty("type", "ENHO");
		o.addProperty("description", s.description());
		o.addProperty("package", s.pkg());
		o.addProperty("enhancement_spot", s.spot());
		JsonArray arr = new JsonArray();
		for (Entry e : s.entries()) {
			JsonObject i = new JsonObject();
			i.addProperty("name", e.name());
			i.addProperty("badi", e.badi());
			i.addProperty("implementing_class", e.implementingClass());
			i.addProperty("short_text", e.shortText());
			i.addProperty("active", e.active());
			i.addProperty("default", e.isDefault());
			String filter = s.filters().get(e.name());
			if (filter != null) {
				i.addProperty("filter", filter);
			} else if (e.filterTree() != null && e.filterTree().contains("filterToken")) {
				i.addProperty("filter_note", "SAP stores a filter Bella cannot show; it stays when 'filter' is left out.");
			}
			arr.add(i);
		}
		o.add("badi_implementations", arr);
		return "BAdI implementations of enhancement spot " + s.spot() + ". Change them with adt_write_metadata "
				+ "(same keys; 'remove_implementations' deletes), then activate.\n" + Json.PRETTY.toJson(o);
	}

	static String describe(Spot s) {
		StringBuilder sb = new StringBuilder("Enhancement spot ").append(s.name());
		if (!s.pkg().isEmpty()) {
			sb.append(" (package ").append(s.pkg()).append(')');
		}
		if (s.internal()) {
			sb.append(". SAP-internal: no implementations in the customer namespace");
		}
		sb.append(". BAdI definitions:");
		for (BadiDef b : s.badis().values()) {
			sb.append("\n- ").append(b.name());
			if (!b.shortText().isEmpty()) {
				sb.append(" (").append(b.shortText()).append(')');
			}
			sb.append(": interface ").append(b.iface().isEmpty() ? "?" : b.iface())
					.append(b.singleUse() ? ", single use" : ", multiple use");
			if (b.filterRequired()) {
				sb.append(", needs a filter");
			}
			if (!b.filters().isEmpty()) {
				sb.append(", filters ");
				sb.append(String.join(", ", b.filters().values().stream().map(f -> f.name() + " (type " + f.type()
						+ (f.checkObject().isEmpty() ? "" : ", " + f.checkObject()) + ")").toList()));
			}
		}
		if (s.badis().isEmpty()) {
			sb.append(" none");
		}
		return sb.toString();
	}

	// ---- filters ---------------------------------------------------------------------

	/** A filter condition: a comparison or an AND/OR group. */
	sealed interface Cond permits Leaf, Group {
	}

	record Leaf(String name, String comparator, String value) implements Cond {
	}

	record Group(boolean and, List<Cond> children) implements Cond {
	}

	/** Same-kind groups are flattened, so A OR (B OR C) equals A OR B OR C. */
	private static Cond group(boolean and, List<Cond> children) {
		List<Cond> flat = new ArrayList<>();
		for (Cond c : children) {
			if (c instanceof Group g && g.and() == and) {
				flat.addAll(g.children());
			} else {
				flat.add(c);
			}
		}
		return flat.size() == 1 ? flat.get(0) : new Group(and, flat);
	}

	/** Canonical text: NAME OP 'VALUE', AND binds tighter than OR, nested groups in parentheses. */
	static String render(Cond c) {
		return render(c, false);
	}

	private static String render(Cond c, boolean nested) {
		if (c instanceof Leaf l) {
			return l.name() + " " + l.comparator() + " '" + l.value().replace("'", "''") + "'";
		}
		Group g = (Group) c;
		String text = String.join(g.and() ? " AND " : " OR ", g.children().stream().map(x -> render(x, true)).toList());
		return nested ? "(" + text + ")" : text;
	}

	private static final Pattern TOKEN = Pattern.compile("\\(|\\)|'(?:[^']|'')*'|<>|<=|>=|=|<|>|[^\\s()'<>=]+");

	/**
	 * Parses a condition such as {@code COUNTRY = 'BE' OR (COUNTRY = 'NL' AND
	 * MODEL <> 'X')}; values are quoted ({@code ''} escapes a quote) or bare.
	 */
	static Cond parseFilter(String text) throws AdtException {
		if (text.length() > 32_768) {
			throw filterError("the condition is longer than 32768 characters.");
		}
		List<String> tokens = new ArrayList<>();
		String rest = text.trim();
		while (!rest.isEmpty()) {
			Matcher m = TOKEN.matcher(rest);
			if (!m.lookingAt()) {
				throw filterError("cannot read \"" + rest.substring(0, Math.min(20, rest.length()))
						+ "\" (unterminated quote?).");
			}
			tokens.add(m.group());
			rest = rest.substring(m.end()).stripLeading();
		}
		if (tokens.isEmpty()) {
			throw filterError("the condition is empty.");
		}
		int[] pos = { 0 };
		Cond c = expr(tokens, pos, 0);
		if (pos[0] < tokens.size()) {
			throw filterError("unexpected \"" + tokens.get(pos[0]) + "\"; combine conditions with AND or OR.");
		}
		return c;
	}

	private static Cond expr(List<String> t, int[] pos, int depth) throws AdtException {
		List<Cond> or = new ArrayList<>();
		while (true) {
			List<Cond> and = new ArrayList<>();
			and.add(factor(t, pos, depth));
			while (pos[0] < t.size() && t.get(pos[0]).equalsIgnoreCase("AND")) {
				pos[0]++;
				and.add(factor(t, pos, depth));
			}
			or.add(group(true, and));
			if (pos[0] >= t.size() || !t.get(pos[0]).equalsIgnoreCase("OR")) {
				return group(false, or);
			}
			pos[0]++;
		}
	}

	private static Cond factor(List<String> t, int[] pos, int depth) throws AdtException {
		if (pos[0] >= t.size()) {
			throw filterError("the condition ends early.");
		}
		String token = t.get(pos[0]++);
		if (token.equals("(")) {
			if (depth >= 32) {
				throw filterError("parentheses are nested more than 32 levels.");
			}
			Cond inner = expr(t, pos, depth + 1);
			if (pos[0] >= t.size() || !t.get(pos[0]++).equals(")")) {
				throw filterError("a closing parenthesis is missing.");
			}
			return inner;
		}
		String name = token.toUpperCase(Locale.ROOT);
		if (!NAME.matcher(name).matches()) {
			throw filterError("\"" + token + "\" is not a filter name.");
		}
		String comparator = pos[0] < t.size() ? t.get(pos[0]++).toUpperCase(Locale.ROOT) : "";
		if (!COMPARATORS.contains(comparator)) {
			throw filterError("after " + name + " expected one of " + String.join(" ", COMPARATORS) + ", got \""
					+ comparator + "\".");
		}
		String raw = pos[0] < t.size() ? t.get(pos[0]++) : null;
		// a symbol here is a second comparator (A = <>), not a value
		if (raw == null || raw.matches("[()<>=].*")) {
			throw filterError(name + " " + comparator + " needs a value.");
		}
		String value = raw.startsWith("'") ? raw.substring(1, raw.length() - 1).replace("''", "'") : raw;
		return new Leaf(name, comparator, value);
	}

	private static AdtException filterError(String message) {
		return new AdtException(400, "Invalid BAdI filter: " + message);
	}

	private static List<String> names(Cond c, List<String> into) {
		if (c instanceof Leaf l) {
			if (!into.contains(l.name())) {
				into.add(l.name());
			}
		} else {
			for (Cond x : ((Group) c).children()) {
				names(x, into);
			}
		}
		return into;
	}

	/** SAP answers 400 "I::000 BADI_IMPL" to an OR of different filters below an AND (ARC-1, 8.16). */
	private static Cond mixedOrBelowAnd(Cond c, boolean belowAnd) {
		if (!(c instanceof Group g)) {
			return null;
		}
		if (!g.and() && belowAnd && names(g, new ArrayList<>()).size() > 1) {
			return g;
		}
		for (Cond x : g.children()) {
			Cond found = mixedOrBelowAnd(x, belowAnd || g.and());
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	/**
	 * The {@code enho:filterTree} as SAP returns it: one root token, then one
	 * filterProperty per filter used, copied from the BAdI's declaration.
	 */
	static String filterTree(Cond c, String implName, BadiDef badi) throws AdtException {
		List<String> used = names(c, new ArrayList<>());
		List<String> unknown = used.stream().filter(n -> !badi.filters().containsKey(n)).toList();
		if (!unknown.isEmpty()) {
			throw filterError("BAdI " + badi.name() + " declares no filter " + String.join(", ", unknown)
					+ " (declared: " + (badi.filters().isEmpty() ? "none" : String.join(", ", badi.filters().keySet()))
					+ ").");
		}
		Cond mixed = mixedOrBelowAnd(c, false);
		if (mixed != null) {
			throw filterError("SAP does not accept an OR of different filters inside an AND: " + render(mixed, true)
					+ ". Write it as an OR of AND groups, e.g. (A = '1' AND C = '3') OR (B = '2' AND C = '3').");
		}
		StringBuilder sb = new StringBuilder("<enho:filterTree>");
		token(c, implName, sb);
		for (String n : used) {
			FilterDecl f = badi.filters().get(n);
			sb.append("<enho:filterProperty enho:filterName=\"").append(x(n)).append("\" enho:filterType=\"")
					.append(x(f.type())).append('"');
			sb.append(f.checkXml().isEmpty() ? "/>" : ">" + f.checkXml() + "</enho:filterProperty>");
		}
		return sb.append("</enho:filterTree>").toString();
	}

	private static void token(Cond c, String implName, StringBuilder sb) {
		if (c instanceof Leaf l) {
			sb.append("<enho:filterToken xsi:type=\"enho:Filter\" enho:filterName=\"").append(x(l.name()))
					.append("\" enho:comparator=\"").append(x(l.comparator())).append("\" enho:value=\"")
					.append(x(l.value())).append("\" enho:filterProperty=\"")
					.append(x("#//badi/contentSpecific///" + implName + "/filterTree/filterProperty." + l.name()))
					.append("\"/>");
			return;
		}
		Group g = (Group) c;
		sb.append("<enho:filterToken xsi:type=\"enho:").append(g.and() ? "And" : "Or").append("\">");
		g.children().forEach(x -> token(x, implName, sb));
		sb.append("</enho:filterToken>");
	}

	/** Text of a stored filter tree; empty when it is empty or uses a token Bella does not know. */
	private static String storedFilter(Element tree) {
		List<Cond> parts = new ArrayList<>();
		for (Element t : children(tree, "filterToken")) {
			Cond c = fromToken(t);
			if (c == null) {
				return "";
			}
			parts.add(c);
		}
		return parts.isEmpty() ? "" : render(group(true, parts));
	}

	private static Cond fromToken(Element t) {
		String kind = t.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "type");
		kind = kind.substring(kind.indexOf(':') + 1);
		if (kind.equals("Filter")) {
			return new Leaf(AdtXml.attr(t, "filterName"), AdtXml.attr(t, "comparator"), AdtXml.attr(t, "value"));
		}
		if (!kind.equals("And") && !kind.equals("Or")) {
			return null;
		}
		List<Cond> children = new ArrayList<>();
		for (Element c : children(t, "filterToken")) {
			Cond x = fromToken(c);
			if (x == null) {
				return null;
			}
			children.add(x);
		}
		return children.isEmpty() ? null : group(kind.equals("And"), children);
	}

	private static List<Element> children(Element e, String localName) {
		List<Element> out = new ArrayList<>();
		NodeList nodes = e.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			if (nodes.item(i) instanceof Element c && localName.equals(c.getLocalName())) {
				out.add(c);
			}
		}
		return out;
	}

	private static String x(String s) {
		return AdtXml.escape(s == null ? "" : s);
	}
}
