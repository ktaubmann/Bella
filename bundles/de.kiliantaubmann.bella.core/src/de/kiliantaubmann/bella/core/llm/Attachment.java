package de.kiliantaubmann.bella.core.llm;

import java.util.Base64;
import java.util.List;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.Json;

/**
 * A file the developer attached to a chat message and that goes to the model as it is: an image (screenshot,
 * diagram) or a PDF. Text files do not need this; they go into the message text.
 *
 * @param name      file name, for the model and the chat; may be {@code null}
 * @param mediaType one of {@link #IMAGE_TYPES} or {@link #PDF}
 * @param base64    the file, Base64 encoded
 */
public record Attachment(String name, String mediaType, String base64) {

	/** The image formats Claude reads. */
	public static final List<String> IMAGE_TYPES = List.of("image/png", "image/jpeg", "image/gif", "image/webp");
	public static final String PDF = "application/pdf";

	/** Claude takes at most 5 MB per image, counted on the Base64 text. */
	public static final int MAX_IMAGE_BASE64 = 5 * 1024 * 1024;
	/** A request may have 32 MB; a PDF gets most of it, with room for the rest of the chat. */
	public static final int MAX_PDF_BASE64 = 20 * 1024 * 1024;

	public Attachment {
		if (!IMAGE_TYPES.contains(mediaType) && !PDF.equals(mediaType)) {
			throw new IllegalArgumentException("Unsupported attachment type: " + mediaType);
		}
		if (base64 == null || base64.isEmpty()) {
			throw new IllegalArgumentException("Empty attachment");
		}
		int max = PDF.equals(mediaType) ? MAX_PDF_BASE64 : MAX_IMAGE_BASE64;
		if (base64.length() > max) {
			throw new IllegalArgumentException(
					(name == null ? "Attachment" : name) + " is larger than " + max * 3 / 4 / (1024 * 1024) + " MB");
		}
	}

	/** An image without a file name, e.g. from the clipboard. */
	public Attachment(String mediaType, String base64) {
		this(null, mediaType, base64);
	}

	public static Attachment of(String name, String mediaType, byte[] data) {
		return new Attachment(name, mediaType, Base64.getEncoder().encodeToString(data));
	}

	public static Attachment of(String mediaType, byte[] data) {
		return of(null, mediaType, data);
	}

	public boolean isImage() {
		return IMAGE_TYPES.contains(mediaType);
	}

	public boolean isPdf() {
		return PDF.equals(mediaType);
	}

	/**
	 * Content block of the Anthropic Messages API (also read by the Claude Code CLI): {@code image}, or
	 * {@code document} for a PDF.
	 */
	public JsonObject anthropicBlock() {
		JsonObject source = new JsonObject();
		source.addProperty("type", "base64");
		source.addProperty("media_type", mediaType);
		source.addProperty("data", base64);
		JsonObject block = new JsonObject();
		block.addProperty("type", isPdf() ? "document" : "image");
		block.add("source", source);
		if (isPdf() && name != null && !name.isBlank()) {
			block.addProperty("title", name);
		}
		return block;
	}

	/** {@code data:} URL, as OpenAI-compatible endpoints take it. */
	public String dataUrl() {
		return "data:" + mediaType + ";base64," + base64;
	}

	/** The attachment of an Anthropic {@code image} or {@code document} block, {@code null} for other blocks. */
	public static Attachment fromAnthropicBlock(JsonObject block) {
		String type = block == null ? null : Json.str(block, "type");
		if (!"image".equals(type) && !"document".equals(type)) {
			return null;
		}
		JsonObject source = Json.obj(block, "source");
		if (source == null || !"base64".equals(Json.str(source, "type"))) {
			return null;
		}
		try {
			return new Attachment(Json.str(block, "title"), Json.str(source, "media_type"), Json.str(source, "data"));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
