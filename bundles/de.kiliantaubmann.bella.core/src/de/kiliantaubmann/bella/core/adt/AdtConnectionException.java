package de.kiliantaubmann.bella.core.adt;

/**
 * The connection to the SAP system broke (network, VPN, gateway), as opposed
 * to an HTTP error from the system. ADT may still report the project as
 * logged on.
 */
public class AdtConnectionException extends AdtException {

	private static final long serialVersionUID = 1L;

	public AdtConnectionException(String destinationId, String detail) {
		super(0, "Connection to SAP system " + destinationId + " lost" + (detail.isEmpty() ? "" : " (" + detail + ")")
				+ ". Check the network or VPN; if it persists, log on to the project again in ADT.");
	}

	/**
	 * The first line of an SDK error message, without the destination data
	 * (host, user) and request dump the ADT communication layer appends, and
	 * without quoted partner addresses.
	 */
	public static String shortDetail(String sdkMessage) {
		if (sdkMessage == null) {
			return "";
		}
		String s = sdkMessage.strip();
		for (String cut : new String[] { "\n", "\r", "; Destination data", "------------ Request" }) {
			int i = s.indexOf(cut);
			if (i >= 0) {
				s = s.substring(0, i);
			}
		}
		int colon = s.lastIndexOf("Exception: ");
		if (colon >= 0) {
			s = s.substring(colon + "Exception: ".length());
		}
		s = s.replaceAll(" ?'[^']*'", "").strip();
		return s.length() > 200 ? s.substring(0, 200) + "…" : s;
	}
}
