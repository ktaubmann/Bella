package de.kiliantaubmann.bella.core.adt;

/**
 * An ABAP project in the workspace.
 *
 * @param destinationId ADT destination id (unique key)
 * @param projectName   Eclipse project name
 * @param systemId      SID, e.g. {@code S4H}
 * @param client        client, may be {@code null} for cloud systems
 * @param user          logon user, may be {@code null}
 * @param loggedOn      whether a logon exists (Bella never opens a logon dialog on its own)
 * @param language      logon language, e.g. {@code DE}; {@code null} when unknown
 */
public record AdtSystem(String destinationId, String projectName, String systemId, String client, String user,
		boolean loggedOn, String language) {

	public AdtSystem(String destinationId, String projectName, String systemId, String client, String user,
			boolean loggedOn) {
		this(destinationId, projectName, systemId, client, user, loggedOn, null);
	}

	public String label() {
		StringBuilder sb = new StringBuilder(projectName);
		if (systemId != null && !projectName.contains(systemId)) {
			sb.append(" (").append(systemId);
			if (client != null) {
				sb.append('/').append(client);
			}
			sb.append(')');
		}
		return sb.toString();
	}
}
