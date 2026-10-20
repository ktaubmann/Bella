package de.kiliantaubmann.bella.core.agent;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.Log;

/**
 * The chat history on disk: one JSON file per chat in a folder of the
 * workspace's metadata. Files that cannot be read are skipped.
 */
public final class ChatStore {

	/** What a listing shows of a chat. */
	public record Summary(String id, String title, long created, long updated) {
	}

	private static final String AREA = "history";
	private static final String SUFFIX = ".json";
	/** Chat ids are UUIDs; anything else is no file name of ours. */
	private static final Pattern ID = Pattern.compile("[0-9a-fA-F-]{8,64}");

	private final Path dir;

	public ChatStore(Path dir) {
		this.dir = dir;
	}

	public Path dir() {
		return dir;
	}

	/** The chats, the one changed last first. */
	public List<Summary> list() {
		List<Summary> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return out;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
			for (Path f : files) {
				readSummary(f).ifPresent(out::add);
			}
		} catch (IOException e) {
			Log.warn(AREA, "cannot list " + dir + ": " + e.getMessage());
		}
		out.sort(Comparator.comparingLong(Summary::updated).reversed().thenComparing(Summary::id));
		return out;
	}

	/** Reads the header fields only; they come before the entries. */
	static Optional<Summary> readSummary(Path f) {
		String id = null;
		String title = "";
		long created = 0;
		long updated = 0;
		try (Reader in = Files.newBufferedReader(f, StandardCharsets.UTF_8); JsonReader r = new JsonReader(in)) {
			r.beginObject();
			while (r.hasNext()) {
				String name = r.nextName();
				if (name.equals("entries") || name.equals("modelHistory")) {
					break;
				}
				if (r.peek() == JsonToken.NULL) {
					r.nextNull();
					continue;
				}
				switch (name) {
				case "id" -> id = r.nextString();
				case "title" -> title = r.nextString();
				case "created" -> created = r.nextLong();
				case "updated" -> updated = r.nextLong();
				default -> r.skipValue();
				}
			}
		} catch (IOException | IllegalStateException | NumberFormatException | JsonParseException e) {
			Log.warn(AREA, "skipping " + f.getFileName() + ": " + e.getMessage());
			return Optional.empty();
		}
		if (id == null || !f.getFileName().toString().equals(id + SUFFIX)) {
			return Optional.empty();
		}
		return Optional.of(new Summary(id, title, created, Math.max(created, updated)));
	}

	public Optional<ChatRecord> load(String id) {
		Path f = file(id);
		if (f == null || !Files.isRegularFile(f)) {
			return Optional.empty();
		}
		try {
			return Optional.of(ChatRecord.fromJson(Json.parseObject(Files.readString(f, StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException e) {
			Log.warn(AREA, "cannot read chat " + id + ": " + e.getMessage());
			return Optional.empty();
		}
	}

	/** Writes the chat (to a new file, then moved over the old one); an empty chat is not written. */
	public void save(ChatRecord chat) throws IOException {
		if (chat.isEmpty()) {
			return;
		}
		Path f = file(chat.id());
		if (f == null) {
			throw new IOException("invalid chat id " + chat.id());
		}
		Files.createDirectories(dir);
		Path tmp = Files.createTempFile(dir, "chat-", ".tmp");
		try {
			Files.writeString(tmp, Json.GSON.toJson(chat.toJson()), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	public void delete(String id) {
		Path f = file(id);
		if (f == null) {
			return;
		}
		try {
			Files.deleteIfExists(f);
		} catch (IOException e) {
			Log.warn(AREA, "cannot delete chat " + id + ": " + e.getMessage());
		}
	}

	public void deleteAll() {
		list().forEach(s -> delete(s.id()));
	}

	/** Deletes the chats beyond the {@code keep} changed last; {@code keep} below 1 keeps one. */
	public void prune(int keep) {
		List<Summary> all = list();
		for (int i = Math.max(1, keep); i < all.size(); i++) {
			delete(all.get(i).id());
		}
	}

	private Path file(String id) {
		return id == null || !ID.matcher(id).matches() ? null : dir.resolve(id + SUFFIX);
	}
}
