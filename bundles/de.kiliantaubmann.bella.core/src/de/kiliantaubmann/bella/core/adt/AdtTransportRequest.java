package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * A transport request as the ADT transport organizer describes it: header,
 * tasks and the object entries (E071) recorded in them.
 */
public record AdtTransportRequest(String id, String description, String owner, String status, String type,
		String target, List<Task> tasks, List<Entry> requestObjects) {

	/** One object entry, e.g. {@code R3TR CLAS ZCL_X} or {@code LIMU METH ZCL_X   RUN}. */
	public record Entry(String pgmid, String type, String name, String wbtype, String description, String taskId) {
	}

	public record Task(String id, String description, String owner, String status, List<Entry> objects) {
	}

	/** The request id and the ids of its tasks, upper case. */
	public Set<String> ids() {
		Set<String> ids = new LinkedHashSet<>();
		ids.add(id.toUpperCase(Locale.ROOT));
		tasks.forEach(t -> ids.add(t.id().toUpperCase(Locale.ROOT)));
		return ids;
	}

	/**
	 * The repository object entries: those of the tasks, or those on the
	 * request itself when the tasks list none. Release comments ({@code CORR})
	 * are left out.
	 */
	public List<Entry> entries() {
		List<Entry> out = new ArrayList<>();
		tasks.forEach(t -> out.addAll(t.objects()));
		if (out.isEmpty()) {
			out.addAll(requestObjects);
		}
		out.removeIf(e -> e.pgmid().equalsIgnoreCase("CORR"));
		return out;
	}

	public boolean released() {
		return status.equalsIgnoreCase("R") || status.equalsIgnoreCase("O");
	}

	/** Parses a transport organizer document (one request or a list). */
	public static List<AdtTransportRequest> parse(String xml) throws IOException {
		List<AdtTransportRequest> out = new ArrayList<>();
		for (Element req : AdtXml.elements(AdtXml.parse(xml), "request")) {
			String id = AdtXml.attr(req, "number");
			List<Task> tasks = new ArrayList<>();
			for (Element t : AdtXml.elements(req, "task")) {
				String taskId = AdtXml.attr(t, "number");
				List<Entry> objects = new ArrayList<>();
				for (Element o : AdtXml.elements(t, "abap_object")) {
					objects.add(entry(o, taskId));
				}
				tasks.add(new Task(taskId, AdtXml.attr(t, "desc"), AdtXml.attr(t, "owner"), AdtXml.attr(t, "status"),
						objects));
			}
			List<Entry> direct = new ArrayList<>();
			for (Node n = req.getFirstChild(); n != null; n = n.getNextSibling()) {
				if (n instanceof Element e && "abap_object".equals(localName(e))) {
					direct.add(entry(e, id));
				}
			}
			out.add(new AdtTransportRequest(id, AdtXml.attr(req, "desc"), AdtXml.attr(req, "owner"),
					AdtXml.attr(req, "status"), AdtXml.attr(req, "type"), AdtXml.attr(req, "target"), tasks, direct));
		}
		return out;
	}

	private static Entry entry(Element o, String taskId) {
		String desc = AdtXml.attr(o, "obj_desc");
		return new Entry(AdtXml.attr(o, "pgmid"), AdtXml.attr(o, "type"), AdtXml.attr(o, "name"),
				AdtXml.attr(o, "wbtype"), desc.isEmpty() ? AdtXml.attr(o, "obj_info") : desc, taskId);
	}

	private static String localName(Element e) {
		String ln = e.getLocalName() != null ? e.getLocalName() : e.getNodeName();
		int colon = ln.indexOf(':');
		return colon >= 0 ? ln.substring(colon + 1) : ln;
	}
}
