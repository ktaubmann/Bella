package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.Locale;

import org.w3c.dom.Element;

import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * Transport requests and packages: create, reassign, delete and remove
 * objects; releasing stays impossible. Requests follow ARC-1
 * ({@code src/adt/transport.ts}, {@code src/handlers/manage.ts},
 * {@code src/adt/ddic-xml.ts}, MIT).
 */
final class AdtManage {

	static final String ORGANIZER = "application/vnd.sap.adt.transportorganizer.v1+xml";
	static final String TM = "http://www.sap.com/cts/adt/tm";

	private AdtManage() {
	}

	private static String request(String id) throws AdtException {
		String n = id == null ? "" : id.trim().toUpperCase(Locale.ROOT);
		if (!n.matches("[A-Z0-9]{3}K\\d{6}")) {
			throw new AdtException(400, "Invalid transport request number " + id + ", e.g. DEVK900123.");
		}
		return n;
	}

	/**
	 * Creates a workbench request. With a target the request gets that
	 * transport target; otherwise SAP takes the route of the package.
	 *
	 * @return the new request number
	 */
	static String createTransport(AdtClient c, String description, String pkg, String layer, String target,
			CancelToken cancel) throws IOException {
		if (description == null || description.isBlank()) {
			throw new AdtException(400, "Give 'description' for the new transport request.");
		}
		if (target != null && !target.isBlank()) {
			String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tm:root xmlns:tm=\"" + TM
					+ "\" tm:useraction=\"newrequest\">\n  <tm:request tm:desc=\"" + AdtXml.escape(description)
					+ "\" tm:type=\"K\" tm:target=\"" + AdtXml.escape(target.trim().toUpperCase(Locale.ROOT))
					+ "\" tm:cts_project=\"\">\n    <tm:task/>\n  </tm:request>\n</tm:root>";
			AdtResponse r = c.exchange(AdtRequest.post("/sap/bc/adt/cts/transportrequests", ORGANIZER, body, "text/plain"),
					cancel);
			if (!r.ok()) {
				throw new AdtException(r.status(), "Could not create the transport request: " + AdtErrors.message(r));
			}
			for (Element e : AdtXml.elements(AdtXml.parse(r.body()), "request")) {
				String number = AdtXml.attr(e, "number");
				if (!number.isBlank()) {
					return number;
				}
			}
			throw new AdtException(502, "SAP did not return the number of the new transport request; check the "
					+ "Transport Organizer before creating another one.");
		}
		String devclass = pkg == null || pkg.isBlank() ? "$TMP" : pkg.trim().toUpperCase(Locale.ROOT);
		String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><asx:abap xmlns:asx=\"http://www.sap.com/abapxml\" version=\"1.0\">\n"
				+ "  <asx:values>\n    <DATA>\n      <DEVCLASS>" + AdtXml.escape(devclass) + "</DEVCLASS>\n"
				+ "      <REQUEST_TEXT>" + AdtXml.escape(description) + "</REQUEST_TEXT>\n      <REF/>\n"
				+ "      <OPERATION>I</OPERATION>\n    </DATA>\n  </asx:values>\n</asx:abap>";
		String path = "/sap/bc/adt/cts/transports"
				+ (layer == null || layer.isBlank() ? "" : "?transportLayer=" + AdtClient.enc(layer.trim()));
		AdtResponse r = c.exchange(AdtRequest.post(path, "text/plain", body,
				"application/vnd.sap.as+xml; charset=UTF-8; dataname=com.sap.adt.CreateCorrectionRequest"), cancel);
		if (!r.ok()) {
			throw new AdtException(r.status(), "Could not create the transport request: " + AdtErrors.message(r));
		}
		String id = r.body() == null ? "" : r.body().trim();
		id = id.substring(id.lastIndexOf('/') + 1);
		if (id.isBlank()) {
			throw new AdtException(502, "SAP did not return the number of the new transport request; check the "
					+ "Transport Organizer before creating another one.");
		}
		return id;
	}

	/** Gives a request (and with {@code withTasks} its open tasks) to another owner. */
	static void reassign(AdtClient c, String requestId, String owner, boolean withTasks, CancelToken cancel)
			throws IOException {
		String id = request(requestId);
		String newOwner = owner == null ? "" : owner.trim().toUpperCase(Locale.ROOT);
		if (!newOwner.matches("[A-Z0-9_.@-]{1,12}")) {
			throw new AdtException(400, "Give 'owner', the SAP user name of the new owner.");
		}
		if (withTasks) {
			for (AdtTransportRequest.Task t : c.transport(id, cancel).map(AdtTransportRequest::tasks)
					.orElse(java.util.List.of())) {
				if (!"R".equals(t.status())) {
					changeOwner(c, t.id(), newOwner, cancel);
				}
			}
		}
		changeOwner(c, id, newOwner, cancel);
	}

	private static void changeOwner(AdtClient c, String id, String owner, CancelToken cancel) throws IOException {
		String body = "<?xml version=\"1.0\" encoding=\"ASCII\"?>\n<tm:root xmlns:tm=\"" + TM + "\" tm:number=\""
				+ AdtXml.escape(id) + "\" tm:targetuser=\"" + AdtXml.escape(owner) + "\" tm:useraction=\"changeowner\"/>";
		AdtResponse r = c.exchange(AdtRequest.put("/sap/bc/adt/cts/transportrequests/" + AdtClient.enc(id), body,
				ORGANIZER).withHeader("Accept", ORGANIZER), cancel);
		if (!r.ok()) {
			throw new AdtException(r.status(), "Could not change the owner of " + id + ": " + AdtErrors.message(r));
		}
	}

	/** Deletes a request, with {@code withTasks} its open tasks first. */
	static void deleteTransport(AdtClient c, String requestId, boolean withTasks, CancelToken cancel)
			throws IOException {
		String id = request(requestId);
		if (withTasks) {
			for (AdtTransportRequest.Task t : c.transport(id, cancel).map(AdtTransportRequest::tasks)
					.orElse(java.util.List.of())) {
				if (!"R".equals(t.status())) {
					delete(c, t.id(), cancel);
				}
			}
		}
		delete(c, id, cancel);
	}

	private static void delete(AdtClient c, String id, CancelToken cancel) throws IOException {
		AdtResponse r = c.exchange(AdtRequest.delete("/sap/bc/adt/cts/transportrequests/" + AdtClient.enc(id)), cancel);
		if (!r.ok()) {
			throw new AdtException(r.status(), "Could not delete " + id + ": " + AdtErrors.message(r));
		}
	}

	/**
	 * Removes one object (pgmid, type, name) from a request, keeping the
	 * request; the entry's position comes from the request's object list.
	 *
	 * @return the task the object was removed from
	 */
	static String removeObject(AdtClient c, String requestId, String pgmid, String type, String name,
			CancelToken cancel) throws IOException {
		String id = request(requestId);
		AdtResponse r = c.exchange(AdtRequest.get("/sap/bc/adt/cts/transportrequests/" + AdtClient.enc(id), ORGANIZER),
				cancel);
		if (!r.ok()) {
			throw new AdtException(r.status(), AdtErrors.message(r));
		}
		for (Element task : AdtXml.elements(AdtXml.parse(r.body()), "task")) {
			for (Element o : AdtXml.elements(task, "abap_object")) {
				if (AdtXml.attr(o, "pgmid").equalsIgnoreCase(pgmid.trim())
						&& AdtXml.attr(o, "type").equalsIgnoreCase(type.trim())
						&& AdtXml.attr(o, "name").equalsIgnoreCase(name.trim())) {
					String taskId = AdtXml.attr(task, "number");
					String body = "<?xml version=\"1.0\" encoding=\"ASCII\"?>\n<tm:root xmlns:tm=\"" + TM + "\" tm:number=\""
							+ AdtXml.escape(taskId) + "\" tm:useraction=\"removeobject\">\n  <tm:request>\n"
							+ "    <tm:abap_object tm:pgmid=\"" + AdtXml.escape(AdtXml.attr(o, "pgmid")) + "\" tm:type=\""
							+ AdtXml.escape(AdtXml.attr(o, "type")) + "\" tm:name=\"" + AdtXml.escape(AdtXml.attr(o, "name"))
							+ "\" tm:position=\"" + AdtXml.escape(AdtXml.attr(o, "position")) + "\" tm:obj_desc=\""
							+ AdtXml.escape(AdtXml.attr(o, "obj_desc")) + "\"/>\n  </tm:request>\n</tm:root>";
					AdtResponse put = c.exchange(AdtRequest.put("/sap/bc/adt/cts/transportrequests/"
							+ AdtClient.enc(taskId), body, ORGANIZER).withHeader("Accept", ORGANIZER), cancel);
					if (!put.ok()) {
						throw new AdtException(put.status(), "Could not remove the object: " + AdtErrors.message(put));
					}
					return taskId;
				}
			}
		}
		throw new AdtException(404, pgmid.toUpperCase(Locale.ROOT) + " " + type.toUpperCase(Locale.ROOT) + " "
				+ name.toUpperCase(Locale.ROOT) + " is not in a task of " + id + ".");
	}

	// ---- packages ----------------------------------------------------------------

	static final java.util.List<String> PACKAGE_TYPES = java.util.List.of("development", "structure", "main");

	static String packageXml(String name, String description, String superPackage, String softwareComponent,
			String transportLayer, String packageType, String responsible) {
		String component = softwareComponent == null || softwareComponent.isBlank() ? "LOCAL"
				: softwareComponent.trim().toUpperCase(Locale.ROOT);
		String layer = transportLayer == null ? "" : transportLayer.trim().toUpperCase(Locale.ROOT);
		boolean record = !component.equals("LOCAL") || !layer.isEmpty();
		String type = packageType == null || !PACKAGE_TYPES.contains(packageType) ? "development" : packageType;
		String n = name.trim().toUpperCase(Locale.ROOT);
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<pak:package xmlns:pak=\"http://www.sap.com/adt/packages\" "
				+ "xmlns:adtcore=\"http://www.sap.com/adt/core\" adtcore:description=\"" + AdtXml.escape(description)
				+ "\" adtcore:name=\"" + AdtXml.escape(n) + "\" adtcore:type=\"DEVC/K\" adtcore:version=\"active\""
				+ (responsible == null || responsible.isBlank() || responsible.length() > 12 ? ""
						: " adtcore:responsible=\"" + AdtXml.escape(responsible.toUpperCase(Locale.ROOT)) + "\"")
				+ ">\n  <adtcore:packageRef adtcore:name=\"" + AdtXml.escape(n) + "\"/>\n"
				+ "  <pak:attributes pak:packageType=\"" + type + "\" pak:recordChanges=\"" + record + "\"/>\n"
				+ "  <pak:superPackage adtcore:name=\"" + AdtXml.escape(superPackage == null ? ""
						: superPackage.trim().toUpperCase(Locale.ROOT)) + "\"/>\n  <pak:applicationComponent/>\n"
				+ "  <pak:transport>\n    <pak:softwareComponent pak:name=\"" + AdtXml.escape(component) + "\"/>\n"
				+ "    <pak:transportLayer pak:name=\"" + AdtXml.escape(layer) + "\"/>\n  </pak:transport>\n"
				+ "  <pak:translation/>\n  <pak:useAccesses/>\n  <pak:packageInterfaces/>\n  <pak:subPackages/>\n"
				+ "</pak:package>";
	}

	static void createPackage(AdtClient c, String xml, String transport, CancelToken cancel) throws IOException {
		String path = "/sap/bc/adt/packages"
				+ (transport == null || transport.isBlank() ? "" : "?corrNr=" + AdtClient.enc(transport.trim()));
		AdtResponse r = c.exchange(AdtRequest.post(path, "application/*", xml, "application/*"), cancel);
		if (!r.ok()) {
			throw new AdtException(r.status(), "Could not create the package: " + AdtErrors.message(r));
		}
	}

	static String packageUri(String name) {
		return "/sap/bc/adt/packages/" + AdtObjectRef.encodeName(name.trim());
	}
}
