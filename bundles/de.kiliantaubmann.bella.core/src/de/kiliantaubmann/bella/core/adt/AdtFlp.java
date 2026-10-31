package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

/**
 * Changes of the Fiori launchpad customizing through SAP's page builder
 * service ({@code /UI2/PAGE_BUILDER_CUST}): catalogs, groups, static app
 * launcher tiles and tiles in groups. Payloads follow ARC-1
 * ({@code src/adt/flp.ts}, MIT). The requests run in one session with a
 * CSRF token from the service root ({@link AdtHttp#send}).
 */
final class AdtFlp {

	static final String SERVICE = "/sap/opu/odata/UI2/PAGE_BUILDER_CUST";
	static final String CATALOG_PAGE = "X-SAP-UI2-CATALOGPAGE:";
	static final String LAUNCHER = "X-SAP-UI2-CHIP:/UI2/STATIC_APPLAUNCHER";
	/** The catalog SAP keeps the launchpad groups in. */
	static final String GROUPS = "/UI2/FLPD_CATALOG";

	private AdtFlp() {
	}

	/** A catalog id without the catalog page prefix, as the page ids need it. */
	static String catalogDomain(String catalog) {
		String c = catalog == null ? "" : catalog.trim();
		return c.startsWith(CATALOG_PAGE) ? c.substring(CATALOG_PAGE.length()) : c;
	}

	/**
	 * The request of an action: method, path and JSON body.
	 *
	 * @throws IllegalArgumentException when a required field is missing
	 */
	static String[] request(String action, JsonObject in) {
		return switch (action) {
		case "create_catalog" -> {
			JsonObject b = new JsonObject();
			b.addProperty("domainId", required(in, "catalog"));
			b.addProperty("title", required(in, "title"));
			b.addProperty("type", "CATALOG_PAGE");
			yield new String[] { "POST", SERVICE + "/Catalogs", Json.GSON.toJson(b) };
		}
		case "create_group" -> {
			JsonObject b = new JsonObject();
			b.addProperty("id", required(in, "group"));
			b.addProperty("title", required(in, "title"));
			b.addProperty("catalogId", GROUPS);
			b.addProperty("layout", "");
			yield new String[] { "POST", SERVICE + "/Pages", Json.GSON.toJson(b) };
		}
		case "create_tile" -> {
			JsonObject tile = new JsonObject();
			tile.addProperty("id", optional(in, "tile_id", "id" + Long.toHexString(System.currentTimeMillis())));
			tile.addProperty("display_title_text", required(in, "title"));
			tile.addProperty("semantic_object", required(in, "semantic_object"));
			tile.addProperty("semantic_action", required(in, "semantic_action"));
			for (String[] f : new String[][] { { "icon", "display_icon_url" }, { "url", "url" },
					{ "subtitle", "display_subtitle_text" }, { "info", "display_info_text" } }) {
				String v = Json.str(in, f[0]);
				if (v != null && !v.isBlank()) {
					tile.addProperty(f[1], v.trim());
				}
			}
			JsonObject configuration = new JsonObject();
			configuration.addProperty("tileConfiguration", Json.GSON.toJson(tile));
			JsonObject b = new JsonObject();
			b.addProperty("chipId", LAUNCHER);
			b.addProperty("pageId", CATALOG_PAGE + catalogDomain(required(in, "catalog")));
			b.addProperty("scope", "CUSTOMIZING");
			b.addProperty("title", required(in, "title"));
			b.addProperty("configuration", Json.GSON.toJson(configuration));
			yield new String[] { "POST", SERVICE + "/PageChipInstances", Json.GSON.toJson(b) };
		}
		case "add_tile_to_group" -> {
			JsonObject b = new JsonObject();
			b.addProperty("chipId", "X-SAP-UI2-PAGE:" + CATALOG_PAGE + catalogDomain(required(in, "catalog")) + ":"
					+ required(in, "tile_instance_id"));
			b.addProperty("pageId", required(in, "group"));
			yield new String[] { "POST", SERVICE + "/PageChipInstances", Json.GSON.toJson(b) };
		}
		case "delete_catalog" -> new String[] { "DELETE",
				SERVICE + "/Catalogs('" + AdtClient.enc(required(in, "catalog").replace("'", "''")).replace("+", "%20")
						+ "')",
				null };
		default -> throw new IllegalArgumentException(
				"action is create_catalog, create_group, create_tile, add_tile_to_group or delete_catalog.");
		};
	}

	private static String required(JsonObject in, String field) {
		String v = Json.str(in, field);
		if (v == null || v.isBlank()) {
			throw new IllegalArgumentException("Give '" + field + "'.");
		}
		if (v.chars().anyMatch(ch -> ch < 0x20)) {
			throw new IllegalArgumentException("'" + field + "' has control characters.");
		}
		return v.trim();
	}

	private static String optional(JsonObject in, String field, String fallback) {
		String v = Json.str(in, field);
		return v == null || v.isBlank() ? fallback : v.trim();
	}

	/** Sends the action's request and returns SAP's answer. */
	static String send(AdtTransport.Session session, String action, JsonObject in, CancelToken cancel)
			throws IOException {
		String[] r = request(action.toLowerCase(Locale.ROOT), in);
		return AdtHttp.send(session, r[0], r[1], Map.of("Accept", "application/json"), r[2],
				r[2] == null ? null : "application/json", null, cancel);
	}
}
