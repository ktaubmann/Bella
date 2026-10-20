package de.kiliantaubmann.bella.ui.views;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.swt.widgets.Display;

import de.kiliantaubmann.bella.core.tools.AskUserToolProvider;
import de.kiliantaubmann.bella.core.tools.AskUserToolProvider.Answer;
import de.kiliantaubmann.bella.core.tools.AskUserToolProvider.Question;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.CancelToken.CancelledException;

/** Shows the questions of {@code ask_user} as a menu in the running answer of the chat view. */
public final class ChatAsker implements AskUserToolProvider.Asker {

	@Override
	public Optional<List<Answer>> ask(List<Question> questions, CancelToken cancel) throws CancelledException {
		CompletableFuture<Optional<List<Answer>>> answer = new CompletableFuture<>();
		AtomicBoolean shown = new AtomicBoolean();
		Display display = Display.getDefault();
		display.syncExec(() -> ChatView.find().ifPresent(v -> shown.set(v.showQuestions(questions, answer))));
		if (!shown.get()) {
			throw new IllegalStateException("There is no running chat answer to show the questions in. Ask in your "
					+ "answer instead.");
		}
		try {
			while (true) {
				cancel.throwIfCancelled();
				try {
					return answer.get(200, TimeUnit.MILLISECONDS);
				} catch (TimeoutException e) {
					// keep waiting for the developer
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new CancelledException();
				} catch (ExecutionException e) {
					return Optional.empty();
				}
			}
		} finally {
			if (!answer.isDone()) {
				display.asyncExec(() -> ChatView.find().ifPresent(ChatView::closeQuestions));
			}
		}
	}
}
