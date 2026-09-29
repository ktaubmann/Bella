package de.kiliantaubmann.bella.core.util;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Finds the right overload of an SDK method whose signature differs between
 * releases (e.g. ADT's {@code IRestResource.put} with or without trailing query
 * parameters). Parameters are filled by type from the values offered; array
 * parameters such as varargs get an empty array.
 */
public final class Reflection {

	/** A method ready to be invoked with {@code args}. */
	public record Call(Method method, Object[] args) {

		public Object invoke(Object target) throws ReflectiveOperationException {
			return method.invoke(target, args);
		}
	}

	private Reflection() {
	}

	/**
	 * The public method {@code name} of {@code type} whose parameters can all be
	 * filled from {@code values} (each value used at most once; a {@link Class}
	 * value only fills {@code Class} parameters, a {@code null} value any one
	 * reference parameter). Among several candidates the one that uses the most
	 * values wins.
	 */
	public static Optional<Call> bestMatch(Class<?> type, String name, Object... values) {
		Call best = null;
		int bestUsed = -1;
		for (Method m : type.getMethods()) {
			if (!m.getName().equals(name)) {
				continue;
			}
			Object[] args = fill(m.getParameterTypes(), values);
			if (args == null) {
				continue;
			}
			int used = 0;
			for (int i = 0; i < args.length; i++) {
				// empty arrays made up for varargs do not count
				if (!m.getParameterTypes()[i].isArray() || isOffered(args[i], values)) {
					used++;
				}
			}
			if (used > bestUsed) {
				best = new Call(m, args);
				bestUsed = used;
			}
		}
		return Optional.ofNullable(best);
	}

	private static boolean isOffered(Object arg, Object[] values) {
		for (Object v : values) {
			if (v == arg) {
				return true;
			}
		}
		return false;
	}

	/** Arguments for the parameter types, or {@code null} if one cannot be filled. */
	private static Object[] fill(Class<?>[] params, Object[] values) {
		Object[] args = new Object[params.length];
		boolean[] taken = new boolean[values.length];
		for (int i = 0; i < params.length; i++) {
			Class<?> p = params[i];
			int found = -1;
			for (int j = 0; j < values.length; j++) {
				if (taken[j]) {
					continue;
				}
				Object v = values[j];
				boolean fits = p == Class.class ? v instanceof Class<?>
						: !(v instanceof Class<?>) && v != null && box(p).isInstance(v);
				if (fits) {
					found = j;
					break;
				}
			}
			if (found < 0 && !p.isPrimitive() && !p.isArray()) {
				// a null value (e.g. no request body) fills one reference parameter
				for (int j = 0; j < values.length; j++) {
					if (!taken[j] && values[j] == null) {
						found = j;
						break;
					}
				}
			}
			if (found >= 0) {
				taken[found] = true;
				args[i] = values[found];
			} else if (p.isArray()) {
				args[i] = Array.newInstance(p.getComponentType(), 0);
			} else {
				return null;
			}
		}
		return args;
	}

	private static Class<?> box(Class<?> p) {
		if (!p.isPrimitive()) {
			return p;
		}
		return switch (p.getName()) {
		case "int" -> Integer.class;
		case "long" -> Long.class;
		case "boolean" -> Boolean.class;
		default -> Object.class;
		};
	}

	/** The overloads of {@code name}, e.g. {@code put(IProgressMonitor, IHeaders, Class, Object, IQueryParameter[])}. */
	public static List<String> signatures(Class<?> type, String name) {
		List<String> out = new ArrayList<>();
		for (Method m : type.getMethods()) {
			if (m.getName().equals(name)) {
				out.add(name + "(" + Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName)
						.collect(Collectors.joining(", ")) + ")");
			}
		}
		out.sort(null);
		return out;
	}
}
