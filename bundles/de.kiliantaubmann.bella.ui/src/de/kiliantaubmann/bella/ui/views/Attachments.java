package de.kiliantaubmann.bella.ui.views;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import de.kiliantaubmann.bella.core.llm.Attachment;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;

/**
 * Files for the next chat message: images (also pasted with Ctrl+V), PDFs and text files such as Markdown,
 * dropped on the input or chosen in a file dialog. A strip above the input shows each, with a button to take it
 * out again. UI thread.
 */
final class Attachments {

	/** Files per message. */
	static final int MAX_FILES = 10;
	/** Claude scales larger images down to about this edge anyway; smaller ones cost fewer tokens. */
	static final int MAX_EDGE = 1568;
	/** Edge of the preview in the chat (and the chat history). */
	static final int THUMBNAIL_EDGE = 240;
	/** Edge of the preview in the strip above the input. */
	static final int CHIP_EDGE = 40;
	/** A PNG larger than this is sent as JPEG, if that is smaller. */
	static final int PNG_LIMIT = 2 * 1024 * 1024;
	/** Characters of one text file, and of all text files of a message (about 100,000 tokens). */
	static final int MAX_TEXT_CHARS = 200_000;
	static final int MAX_TEXT_TOTAL = 400_000;
	static final List<String> IMAGE_EXTENSIONS = List.of("png", "jpg", "jpeg", "gif", "webp", "bmp");
	static final List<String> TEXT_EXTENSIONS = List.of("md", "markdown", "txt", "text", "log", "csv", "tsv",
			"json", "xml", "yaml", "yml", "abap", "asddls", "acds", "asbdef", "cds", "sql", "properties", "ini",
			"html", "htm", "js", "ts", "java", "py", "sh", "http", "edmx", "wsdl", "xsd");

	/** A file waiting to be sent: an {@link Attachment} (image, PDF) or the content of a text file. */
	record Pending(Attachment file, String text, String thumbnail, Image chip, String name) {
		boolean isImage() {
			return file != null && file.isImage();
		}
	}

	private final Composite strip;
	private final Runnable relayout;
	private final List<Pending> pending = new ArrayList<>();

	/**
	 * @param relayout lays the view out again after the strip appeared, changed or disappeared
	 */
	Attachments(Composite parent, Runnable relayout) {
		this.relayout = relayout;
		strip = new Composite(parent, SWT.NONE);
		RowLayout layout = new RowLayout(SWT.HORIZONTAL);
		layout.wrap = true;
		layout.center = true;
		layout.marginLeft = layout.marginRight = layout.marginTop = layout.marginBottom = 0;
		layout.spacing = 6;
		strip.setLayout(layout);
		strip.addListener(SWT.Dispose, e -> pending.forEach(p -> dispose(p.chip())));
		show(false);
	}

	Composite control() {
		return strip;
	}

	boolean isEmpty() {
		return pending.isEmpty();
	}

	/** Images and PDFs, sent to the model as they are. */
	List<Attachment> files() {
		return pending.stream().map(Pending::file).filter(Objects::nonNull).toList();
	}

	/** The image previews for the chat, as {@code data:} URLs. */
	List<String> thumbnails() {
		return pending.stream().map(Pending::thumbnail).filter(Objects::nonNull).toList();
	}

	/** Names of the attachments that are not images, for the chat. */
	List<String> fileNames() {
		return pending.stream().filter(p -> !p.isImage()).map(Pending::name).toList();
	}

	/** The text files for the message text, each in an {@code <attached_file>} element; "" without any. */
	String textFiles() {
		StringBuilder sb = new StringBuilder();
		for (Pending p : pending) {
			if (p.text() != null) {
				sb.append("\n\n<attached_file name=\"").append(p.name().replace("\"", "'")).append("\">\n")
						.append(p.text()).append("\n</attached_file>");
			}
		}
		return sb.toString();
	}

	void clear() {
		pending.forEach(p -> dispose(p.chip()));
		pending.clear();
		rebuild();
	}

	// ---- sources --------------------------------------------------------------------------

	/**
	 * Takes files from the clipboard: files copied in the file manager, or an image (screenshot) without text.
	 * Text on the clipboard is left to the input, also when an image comes with it (e.g. cells copied from a
	 * spreadsheet).
	 *
	 * @return whether something was taken; the caller then cancels the text paste
	 */
	boolean paste() {
		Clipboard clipboard = new Clipboard(strip.getDisplay());
		try {
			String[] files = (String[]) clipboard.getContents(FileTransfer.getInstance());
			if (files != null && files.length > 0 && Arrays.stream(files).allMatch(Attachments::isSupported)) {
				addFiles(files);
				return true;
			}
			Object text = clipboard.getContents(TextTransfer.getInstance());
			if (text instanceof String s && !s.isEmpty()) {
				return false;
			}
			ImageData data = (ImageData) clipboard.getContents(ImageTransfer.getInstance());
			if (data == null) {
				return false;
			}
			add(() -> encode(data, Messages.get("chat.image.clipboard")));
			return true;
		} catch (SWTException e) {
			BellaPlugin.log("Cannot read the clipboard", e);
			return false;
		} finally {
			clipboard.dispose();
		}
	}

	/** Lets files and images be dropped on {@code target}. */
	void acceptDrops(Control target) {
		DropTarget drop = new DropTarget(target, DND.DROP_COPY | DND.DROP_DEFAULT | DND.DROP_LINK);
		drop.setTransfer(new Transfer[] { FileTransfer.getInstance(), ImageTransfer.getInstance() });
		drop.addDropListener(new DropTargetAdapter() {
			@Override
			public void dragEnter(DropTargetEvent event) {
				if (event.detail == DND.DROP_DEFAULT || event.detail == DND.DROP_LINK) {
					event.detail = (event.operations & DND.DROP_COPY) != 0 ? DND.DROP_COPY : DND.DROP_NONE;
				}
			}

			@Override
			public void drop(DropTargetEvent event) {
				if (event.data instanceof String[] files) {
					addFiles(files);
				} else if (event.data instanceof ImageData data) {
					add(() -> encode(data, Messages.get("chat.image.dropped")));
				}
			}
		});
	}

	/** Asks for files and adds them. */
	void choose(Shell shell) {
		FileDialog dialog = new FileDialog(shell, SWT.OPEN | SWT.MULTI);
		dialog.setText(Messages.get("chat.attach"));
		List<String> all = new ArrayList<>(IMAGE_EXTENSIONS);
		all.add("pdf");
		all.addAll(TEXT_EXTENSIONS);
		dialog.setFilterExtensions(new String[] { patterns(all), patterns(IMAGE_EXTENSIONS), "*.pdf",
				patterns(TEXT_EXTENSIONS), "*.*" });
		dialog.setFilterNames(new String[] { Messages.get("chat.attach.filterAll"),
				Messages.get("chat.attach.filterImages"), "PDF", Messages.get("chat.attach.filterText"), "*.*" });
		if (dialog.open() == null) {
			return;
		}
		String dir = dialog.getFilterPath();
		addFiles(Arrays.stream(dialog.getFileNames()).map(n -> Path.of(dir, n).toString()).toArray(String[]::new));
	}

	private static String patterns(List<String> extensions) {
		return String.join(";", extensions.stream().map(e -> "*." + e).toList());
	}

	void addFiles(String[] files) {
		for (String file : files) {
			Path path = Path.of(file);
			if (!isSupported(file)) {
				warn(Messages.fmt("chat.attach.unsupported", path.getFileName()));
				continue;
			}
			if (!add(() -> load(path))) {
				return;
			}
		}
	}

	private interface Source {
		Pending get() throws IOException;
	}

	/** @return {@code false} if no more files fit */
	private boolean add(Source source) {
		if (pending.size() >= MAX_FILES) {
			warn(Messages.fmt("chat.attach.tooMany", MAX_FILES));
			return false;
		}
		try {
			pending.add(source.get());
			rebuild();
		} catch (IOException | SWTException | IllegalArgumentException e) {
			warn(e.getMessage() == null ? e.toString() : e.getMessage());
		}
		return true;
	}

	private void warn(String message) {
		MessageDialog.openWarning(strip.getShell(), Messages.get("chat.attach"), message);
	}

	static String extension(String file) {
		int dot = file.lastIndexOf('.');
		int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
		return dot > slash ? file.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
	}

	static boolean isSupported(String file) {
		String ext = extension(file);
		return IMAGE_EXTENSIONS.contains(ext) || TEXT_EXTENSIONS.contains(ext) || "pdf".equals(ext);
	}

	// ---- loading --------------------------------------------------------------------------

	private Pending load(Path path) throws IOException {
		String name = path.getFileName().toString();
		String ext = extension(name);
		if ("pdf".equals(ext)) {
			byte[] bytes = Files.readAllBytes(path);
			if (bytes.length * 4L / 3 + 4 > Attachment.MAX_PDF_BASE64) {
				throw new IOException(Messages.fmt("chat.attach.tooLarge", name,
						Attachment.MAX_PDF_BASE64 * 3 / 4 / (1024 * 1024) + " MB"));
			}
			return new Pending(Attachment.of(name, Attachment.PDF, bytes), null, null, null, name);
		}
		if (TEXT_EXTENSIONS.contains(ext)) {
			return new Pending(null, readText(path, name), null, null, name);
		}
		return loadImage(path, name);
	}

	/** A text file in UTF-8 or, failing that, Windows-1252 (as SAP GUI writes downloads). */
	private String readText(Path path, String name) throws IOException {
		if (Files.size(path) > MAX_TEXT_CHARS * 4L) {
			throw new IOException(Messages.fmt("chat.attach.tooLarge", name, MAX_TEXT_CHARS / 1000 + "k"));
		}
		byte[] bytes = Files.readAllBytes(path);
		String text = decode(bytes);
		if (text == null) {
			throw new IOException(Messages.fmt("chat.attach.notText", name));
		}
		if (text.length() > MAX_TEXT_CHARS) {
			throw new IOException(Messages.fmt("chat.attach.tooLarge", name, MAX_TEXT_CHARS / 1000 + "k"));
		}
		int total = text.length();
		for (Pending p : pending) {
			total += p.text() == null ? 0 : p.text().length();
		}
		if (total > MAX_TEXT_TOTAL) {
			throw new IOException(Messages.fmt("chat.attach.tooLarge", name, MAX_TEXT_TOTAL / 1000 + "k"));
		}
		return text;
	}

	/** The text of a file, {@code null} if it is binary. */
	static String decode(byte[] bytes) {
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			text = new String(bytes, Charset.forName("windows-1252"));
		}
		if (text.indexOf('\0') >= 0) {
			return null;
		}
		return !text.isEmpty() && text.charAt(0) == 0xFEFF ? text.substring(1) : text;
	}

	/**
	 * An image file: sent as it is if Claude reads its format and it is small enough, otherwise scaled down and
	 * encoded again.
	 */
	private Pending loadImage(Path path, String name) throws IOException {
		byte[] bytes = Files.readAllBytes(path);
		String type = imageType(name);
		ImageData data;
		try {
			ImageData[] frames = new ImageLoader().load(new ByteArrayInputStream(bytes));
			data = frames.length == 0 ? null : frames[0];
		} catch (SWTException e) {
			data = null; // e.g. WebP, which SWT cannot read
		}
		if (data == null) {
			if (type == null || bytes.length * 4L / 3 + 4 > Attachment.MAX_IMAGE_BASE64) {
				throw new IOException(Messages.fmt("chat.attach.unsupported", name));
			}
			return new Pending(Attachment.of(name, type, bytes), null, null, null, name);
		}
		if (type != null && Math.max(data.width, data.height) <= MAX_EDGE && bytes.length <= PNG_LIMIT) {
			return new Pending(Attachment.of(name, type, bytes), null, thumbnail(data), chip(data), name);
		}
		return encode(data, name);
	}

	private static String imageType(String name) {
		return switch (extension(name)) {
		case "png" -> "image/png";
		case "jpg", "jpeg" -> "image/jpeg";
		case "gif" -> "image/gif";
		case "webp" -> "image/webp";
		default -> null;
		};
	}

	/** An image from the clipboard or a drop: at most {@link #MAX_EDGE}, as PNG or, if much smaller, as JPEG. */
	private Pending encode(ImageData data, String name) {
		ImageData fitted = fit(data, MAX_EDGE);
		byte[] png = save(fitted, SWT.IMAGE_PNG, 0);
		Attachment image;
		if (png.length > PNG_LIMIT) {
			byte[] jpeg = save(fitted, SWT.IMAGE_JPEG, 85);
			image = jpeg.length < png.length ? Attachment.of("image/jpeg", jpeg) : Attachment.of("image/png", png);
		} else {
			image = Attachment.of("image/png", png);
		}
		return new Pending(image, null, thumbnail(fitted), chip(fitted), name);
	}

	private static String thumbnail(ImageData data) {
		byte[] jpeg = save(fit(data, THUMBNAIL_EDGE), SWT.IMAGE_JPEG, 80);
		return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg);
	}

	private Image chip(ImageData data) {
		return new Image(strip.getDisplay(), fit(data, CHIP_EDGE));
	}

	/**
	 * Draws the image on white at no more than {@code edge} pixels per side, smoothly scaled. Also turns the
	 * many pixel formats of clipboard images into one that PNG and JPEG take.
	 */
	static ImageData fit(ImageData data, int edge) {
		double f = Math.min(1.0, (double) edge / Math.max(data.width, data.height));
		int w = Math.max(1, (int) Math.round(data.width * f));
		int h = Math.max(1, (int) Math.round(data.height * f));
		Display display = Display.getCurrent();
		Image source = new Image(display, data);
		Image target = new Image(display, w, h);
		GC gc = new GC(target);
		try {
			gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
			gc.fillRectangle(0, 0, w, h);
			gc.setAntialias(SWT.ON);
			gc.setInterpolation(SWT.HIGH);
			gc.drawImage(source, 0, 0, data.width, data.height, 0, 0, w, h);
			return target.getImageData();
		} finally {
			gc.dispose();
			source.dispose();
			target.dispose();
		}
	}

	private static byte[] save(ImageData data, int format, int quality) {
		ImageLoader loader = new ImageLoader();
		loader.data = new ImageData[] { data };
		if (quality > 0) {
			loader.compression = quality;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		loader.save(out, format);
		return out.toByteArray();
	}

	// ---- strip ----------------------------------------------------------------------------

	private void rebuild() {
		if (strip.isDisposed()) {
			return;
		}
		for (Control c : strip.getChildren()) {
			c.dispose();
		}
		for (Pending p : pending) {
			Composite chip = new Composite(strip, SWT.BORDER);
			RowLayout layout = new RowLayout(SWT.HORIZONTAL);
			layout.center = true;
			layout.marginLeft = layout.marginRight = layout.marginTop = layout.marginBottom = 2;
			layout.spacing = 2;
			chip.setLayout(layout);
			Label preview = new Label(chip, SWT.NONE);
			if (p.chip() != null) {
				preview.setImage(p.chip());
			} else {
				preview.setText((p.isImage() ? "🖼 " : "📄 ") + p.name());
			}
			preview.setToolTipText(p.name());
			Button remove = new Button(chip, SWT.PUSH | SWT.FLAT);
			remove.setText("✕");
			remove.setToolTipText(Messages.get("chat.attach.remove"));
			remove.addListener(SWT.Selection, e -> {
				pending.remove(p);
				dispose(p.chip());
				rebuild();
			});
		}
		show(!pending.isEmpty());
	}

	private void show(boolean visible) {
		strip.setVisible(visible);
		Object data = strip.getLayoutData();
		if (data instanceof GridData gd) {
			gd.exclude = !visible;
		} else {
			GridDataFactory.fillDefaults().grab(true, false).exclude(!visible).applyTo(strip);
		}
		relayout.run();
	}

	private static void dispose(Image image) {
		if (image != null && !image.isDisposed()) {
			image.dispose();
		}
	}
}
