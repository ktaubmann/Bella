package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.kiliantaubmann.bella.core.abap.AbapStructureScanner;
import de.kiliantaubmann.bella.core.abap.ClassSurgery;
import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * RAP helpers: the handler methods a behavior definition requires in its
 * behavior pool (as Eclipse's "Generate Behavior Implementation" does) and
 * publishing service bindings. The handler rules follow ARC-1
 * ({@code src/adt/rap-handlers.ts}, {@code src/adt/devtools.ts}, MIT).
 */
final class AdtRap {

	private AdtRap() {
	}

	/** A handler method one entity of a behavior definition needs. */
	record Handler(String alias, String method, String signature) {
	}

	private static final Pattern DEFINE = Pattern.compile(
			"(?im)^\\s*define\\s+behavior\\s+for\\s+([^\\s{]+)(?:\\s+alias\\s+([A-Za-z_]\\w*))?");
	private static final Pattern ACTION = Pattern.compile(
			"(?i)^\\s*(?:static\\s+)?(?:(?:internal|factory)\\s+)*action(?:\\s*\\([^)]*\\))?\\s+([A-Za-z_]\\w*)\\b");
	private static final Pattern DETERMINATION = Pattern.compile(
			"(?i)^\\s*determination\\s+([A-Za-z_]\\w*)\\s+on\\s+(modify|save)\\b");
	private static final Pattern VALIDATION = Pattern.compile(
			"(?i)^\\s*validation\\s+([A-Za-z_]\\w*)\\s+on\\s+(modify|save)\\b");
	private static final Pattern INSTANCE_AUTH = Pattern.compile("(?i)\\bauthorization\\s+master\\s*\\(\\s*instance\\s*\\)");
	private static final Pattern GLOBAL_AUTH = Pattern.compile("(?i)\\bauthorization\\s+master\\s*\\(\\s*global\\s*\\)");
	private static final Pattern INSTANCE_FEATURES = Pattern.compile("(?i)\\(\\s*features\\s*:\\s*instance\\s*\\)");

	/** The handler methods per entity alias, in the order of the behavior definition. */
	static Map<String, List<Handler>> handlers(String bdef) {
		Map<String, List<Handler>> out = new LinkedHashMap<>();
		String[] lines = bdef.replace("\r\n", "\n").split("\n");
		for (int i = 0; i < lines.length; i++) {
			Matcher d = DEFINE.matcher(lines[i]);
			if (!d.find()) {
				continue;
			}
			String alias = d.group(2) != null ? d.group(2) : derivedAlias(d.group(1));
			// the block of this entity: up to the brace that closes it
			int depth = 0;
			boolean opened = false;
			List<String> block = new ArrayList<>();
			for (int j = i; j < lines.length; j++) {
				block.add(lines[j]);
				for (char ch : lines[j].toCharArray()) {
					if (ch == '{') {
						depth++;
						opened = true;
					} else if (ch == '}') {
						depth--;
					}
				}
				if (opened && depth <= 0) {
					i = j;
					break;
				}
			}
			out.put(alias, handlersOf(alias, block));
		}
		return out;
	}

	private static List<Handler> handlersOf(String alias, List<String> block) {
		List<Handler> h = new ArrayList<>();
		String body = String.join("\n", block);
		for (int k = 0; k < block.size(); k++) {
			String line = block.get(k);
			Matcher a = ACTION.matcher(line);
			if (a.find()) {
				String statement = statement(block, k);
				String name = a.group(1);
				h.add(new Handler(alias, name.toLowerCase(Locale.ROOT), "METHODS " + name.toLowerCase(Locale.ROOT)
						+ " FOR MODIFY IMPORTING keys FOR ACTION " + alias + "~" + name
						+ (statement.toLowerCase(Locale.ROOT).matches("(?s).*\\bresult\\b.*") ? " RESULT result" : "") + "."));
			}
			Matcher d = DETERMINATION.matcher(line);
			if (d.find()) {
				h.add(new Handler(alias, d.group(1).toLowerCase(Locale.ROOT), "METHODS " + d.group(1).toLowerCase(Locale.ROOT)
						+ " FOR DETERMINE ON " + d.group(2).toUpperCase(Locale.ROOT) + " IMPORTING keys FOR " + alias + "~"
						+ d.group(1) + "."));
			}
			Matcher v = VALIDATION.matcher(line);
			if (v.find()) {
				h.add(new Handler(alias, v.group(1).toLowerCase(Locale.ROOT), "METHODS " + v.group(1).toLowerCase(Locale.ROOT)
						+ " FOR VALIDATE ON " + v.group(2).toUpperCase(Locale.ROOT) + " IMPORTING keys FOR " + alias + "~"
						+ v.group(1) + "."));
			}
		}
		if (INSTANCE_FEATURES.matcher(body).find()) {
			h.add(new Handler(alias, "get_instance_features", "METHODS get_instance_features FOR INSTANCE FEATURES "
					+ "IMPORTING keys REQUEST requested_features FOR " + alias + " RESULT result."));
		}
		if (INSTANCE_AUTH.matcher(body).find()) {
			h.add(new Handler(alias, "get_instance_authorizations", "METHODS get_instance_authorizations FOR INSTANCE "
					+ "AUTHORIZATION IMPORTING keys REQUEST requested_authorizations FOR " + alias + " RESULT result."));
		}
		if (GLOBAL_AUTH.matcher(body).find()) {
			h.add(new Handler(alias, "get_global_authorizations", "METHODS get_global_authorizations FOR GLOBAL "
					+ "AUTHORIZATION IMPORTING REQUEST requested_authorizations FOR " + alias + " RESULT result."));
		}
		return h;
	}

	/** The declaration starting at {@code from}, up to its semicolon. */
	private static String statement(List<String> lines, int from) {
		StringBuilder sb = new StringBuilder();
		for (int j = from; j < lines.size() && j < from + 20; j++) {
			sb.append(lines.get(j)).append(' ');
			if (lines.get(j).contains(";")) {
				break;
			}
		}
		return sb.toString();
	}

	private static String derivedAlias(String entity) {
		String noNamespace = entity.substring(entity.lastIndexOf('/') + 1);
		String noPrefix = noNamespace.replaceFirst("^[A-Za-z]{1,4}_", "");
		String n = (noPrefix.isEmpty() ? noNamespace : noPrefix).replaceAll("[^A-Za-z0-9_]", "");
		return n.isEmpty() ? "Entity" : n;
	}

	/**
	 * The local types include of a behavior pool with a handler class per
	 * entity and every required method: missing classes are added whole,
	 * missing methods are added to existing classes.
	 *
	 * @return the new include, and the methods added in {@code added}
	 */
	static String scaffold(String include, Map<String, List<Handler>> handlers, List<String> added) {
		String out = include == null ? "" : include;
		for (Map.Entry<String, List<Handler>> e : handlers.entrySet()) {
			if (e.getValue().isEmpty()) {
				continue;
			}
			String cls = "lhc_" + e.getKey().toLowerCase(Locale.ROOT);
			boolean exists = AbapStructureScanner.blocks(out).stream()
					.anyMatch(b -> b.kind() == AbapStructureScanner.Kind.CLASS_DEFINITION && b.name().equalsIgnoreCase(cls));
			if (!exists) {
				StringBuilder sb = new StringBuilder(out.isBlank() ? "" : out.stripTrailing() + "\n\n");
				sb.append("CLASS ").append(cls).append(" DEFINITION INHERITING FROM cl_abap_behavior_handler.\n")
						.append("  PRIVATE SECTION.\n");
				for (Handler h : e.getValue()) {
					sb.append("    ").append(h.signature()).append('\n');
					added.add(cls + "->" + h.method());
				}
				sb.append("ENDCLASS.\n\nCLASS ").append(cls).append(" IMPLEMENTATION.\n");
				for (Handler h : e.getValue()) {
					sb.append("  METHOD ").append(h.method()).append(".\n  ENDMETHOD.\n\n");
				}
				out = sb.toString().stripTrailing() + "\nENDCLASS.\n";
				continue;
			}
			List<String> declared = ClassSurgery.declarations(out, cls).stream()
					.map(d -> d.name().toLowerCase(Locale.ROOT)).toList();
			for (Handler h : e.getValue()) {
				if (!declared.contains(h.method())) {
					out = ClassSurgery.addMethod(out, cls, h.signature(), "private");
					added.add(cls + "->" + h.method());
				}
			}
		}
		return out;
	}

	/** The behavior definition a behavior pool belongs to ({@code FOR BEHAVIOR OF x}), or {@code null}. */
	static String behaviorOf(String classSource) {
		Matcher m = Pattern.compile("(?i)\\bFOR\\s+BEHAVIOR\\s+OF\\s+([\\w/]+)").matcher(classSource);
		return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
	}

	// ---- service bindings ------------------------------------------------------

	/**
	 * Publishes or unpublishes an OData service binding.
	 *
	 * @return SAP's message
	 */
	static String publish(AdtClient c, String name, boolean publish, String odataVersion, String serviceVersion,
			CancelToken cancel) throws IOException {
		String type = "V4".equalsIgnoreCase(odataVersion) ? "odatav4" : "odatav2";
		String job = publish ? "publishjob" : "unpublishjob";
		String path = "/sap/bc/adt/businessservices/" + type + "/" + job + "s?servicename=" + AdtClient.enc(name)
				+ "&serviceversion=" + AdtClient.enc(serviceVersion == null || serviceVersion.isBlank() ? "0001" : serviceVersion);
		String body = "<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\"><adtcore:objectReference "
				+ "adtcore:name=\"" + AdtXml.escape(name) + "\"/></adtcore:objectReferences>";
		AdtResponse r = c.exchange(AdtRequest.post(path, "application/vnd.sap.as+xml, application/*;q=0.8", body,
				"application/xml"), cancel);
		if (r.status() == 406 || r.status() == 415) {
			// on-prem releases want the AS-XML media type of the job
			String asXml = "application/vnd.sap.as+xml; charset=UTF-8; dataname=com.sap.adt.businessservices." + type + "."
					+ job;
			r = c.exchange(AdtRequest.post(path, asXml, body, asXml), cancel);
		}
		if (!r.ok()) {
			throw new AdtException(r.status(), "Could not " + (publish ? "publish " : "unpublish ") + name + ": "
					+ AdtErrors.message(r));
		}
		String xml = r.body() == null ? "" : r.body();
		String severity = tag(xml, "SEVERITY");
		String text = tag(xml, "SHORT_TEXT");
		String longText = tag(xml, "LONG_TEXT");
		if (severity.equalsIgnoreCase("ERROR")) {
			throw new AdtException(400, text + (longText.isEmpty() ? "" : " " + longText));
		}
		return (text.isEmpty() ? (publish ? "Published " : "Unpublished ") + name + "." : text)
				+ (longText.isEmpty() ? "" : " " + longText);
	}

	private static String tag(String xml, String name) {
		Matcher m = Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(xml);
		return m.find() ? m.group(1).trim() : "";
	}

	/** OData version and service version of a service binding, from its metadata. */
	static String[] bindingVersions(String metadata) {
		Matcher binding = Pattern.compile("<srvb:binding\\b[^>]*\\bsrvb:version=\"([^\"]*)\"").matcher(metadata);
		Matcher content = Pattern.compile("<srvb:content\\b[^>]*\\bsrvb:version=\"([^\"]*)\"").matcher(metadata);
		return new String[] { binding.find() ? binding.group(1) : "V2", content.find() ? content.group(1) : "0001" };
	}
}
