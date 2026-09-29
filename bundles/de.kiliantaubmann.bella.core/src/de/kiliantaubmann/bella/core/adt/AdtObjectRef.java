package de.kiliantaubmann.bella.core.adt;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A repository object as ADT addresses it.
 *
 * @param uri         ADT URI without source suffix, e.g. {@code /sap/bc/adt/oo/classes/zcl_x}
 * @param name        object name
 * @param type        ADT type, e.g. {@code CLAS/OC}
 * @param packageName package, may be empty
 * @param description short text, may be empty
 */
public record AdtObjectRef(String uri, String name, String type, String packageName, String description) {

	/**
	 * URI path for an object of a main type ({@code CLAS}, {@code INTF},
	 * {@code PROG}, {@code DTEL}, …) or of {@code TABL/DT} and {@code TABL/DS};
	 * {@code null} when only a search can tell (bare {@code TABL}, function modules).
	 */
	public static String uriFor(String name, String type) {
		if (type == null) {
			return null;
		}
		String full = type.toUpperCase(Locale.ROOT);
		switch (full) {
		case "TABL/DT":
			return "/sap/bc/adt/ddic/tables/" + encodeName(name);
		case "TABL/DS":
			return "/sap/bc/adt/ddic/structures/" + encodeName(name);
		case "FUGR/FF", "FUNC":
			return null; // function modules live below their group; found by search
		default:
			break;
		}
		String main = full;
		int slash = main.indexOf('/');
		if (slash > 0) {
			main = main.substring(0, slash);
		}
		String base = switch (main) {
		case "CLAS" -> "/sap/bc/adt/oo/classes/";
		case "INTF" -> "/sap/bc/adt/oo/interfaces/";
		case "PROG" -> "/sap/bc/adt/programs/programs/";
		case "INCL" -> "/sap/bc/adt/programs/includes/";
		case "FUGR" -> "/sap/bc/adt/functions/groups/";
		case "DDLS" -> "/sap/bc/adt/ddic/ddl/sources/";
		case "DCLS" -> "/sap/bc/adt/acm/dcl/sources/";
		case "BDEF" -> "/sap/bc/adt/bo/behaviordefinitions/";
		case "SRVD" -> "/sap/bc/adt/ddic/srvd/sources/";
		case "DTEL" -> "/sap/bc/adt/ddic/dataelements/";
		case "DOMA" -> "/sap/bc/adt/ddic/domains/";
		case "TTYP" -> "/sap/bc/adt/ddic/tabletypes/";
		case "MSAG" -> "/sap/bc/adt/messageclass/";
		default -> null;
		};
		return base == null ? null : base + encodeName(name);
	}

	/** Lower-cased and URL-encoded, so {@code /ABC/CL_X} becomes {@code %2fabc%2fcl_x}. */
	public static String encodeName(String name) {
		return URLEncoder.encode(name.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8).replace("%2F", "%2f");
	}

	/** Strips fragments ({@code #start=…}) and source suffixes to get the object URI. */
	public static String objectUri(String uri) {
		String u = uri;
		int hash = u.indexOf('#');
		if (hash >= 0) {
			u = u.substring(0, hash);
		}
		for (String suffix : new String[] { "/source/main", "/includes/" }) {
			int i = u.indexOf(suffix);
			if (i >= 0) {
				u = u.substring(0, i);
			}
		}
		return u;
	}

	/** URI of the source text of this object, optionally a class include. */
	public static String sourceUri(String objectUri, String include) {
		if (include == null || include.isBlank() || include.equalsIgnoreCase("main")) {
			return objectUri + "/source/main";
		}
		return objectUri + "/includes/" + include.toLowerCase(Locale.ROOT);
	}
}
