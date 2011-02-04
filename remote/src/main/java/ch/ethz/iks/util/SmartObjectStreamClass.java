package ch.ethz.iks.util;

import java.io.Externalizable;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class SmartObjectStreamClass implements Externalizable {

	private String clazzName;
	private String[] fieldNames;
	private Object[] fieldValues;
	private SmartObjectStreamClass superStreamClass;
	private Object restored;

	public SmartObjectStreamClass(final Object obj, final Class clazz)
			throws NotSerializableException {

		clazzName = clazz.getName();

		// check for native methods
		final Method[] methods = clazz.getDeclaredMethods();
		for (int j = 0; j < methods.length; j++) {
			final int mod = methods[j].getModifiers();
			if (Modifier.isNative(mod)) {
				throw new NotSerializableException(
						"Class " //$NON-NLS-1$
								+ clazz.getName()
								+ " contains native methods and is therefore not serializable."); //$NON-NLS-1$ 
			}
		}

		try {
			final Field[] fields = clazz.getDeclaredFields();
			final int fieldCount = fields.length;
			int realFieldCount = 0;
			for (int i = 0; i < fieldCount; i++) {
				final int mod = fields[i].getModifiers();
				if (!(Modifier.isStatic(mod) || Modifier.isTransient(mod))) {
					realFieldCount++;
				}
			}

			fieldNames = new String[realFieldCount];
			fieldValues = new Object[realFieldCount];

			for (int i = 0; i < fieldCount; i++) {
				final int mod = fields[i].getModifiers();
				if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) {
					continue;
				} else if (!Modifier.isPublic(mod)) {
					fields[i].setAccessible(true);
				}
				fieldNames[i] = fields[i].getName();
				fieldValues[i] = fields[i].get(obj);
			}
		} catch (final Exception e) {
			throw new NotSerializableException(
					"Exception while serializing " + obj.toString() //$NON-NLS-1$
							+ ":\n" + e.getMessage()); //$NON-NLS-1$ 
		}

		final Class superClazz = clazz.getSuperclass();
		if (superClazz != null && superClazz != Object.class) {
			superStreamClass = new SmartObjectStreamClass(obj, superClazz);
		}
	}

	public SmartObjectStreamClass() {

	}

	public void writeExternal(final ObjectOutput out) throws IOException {
		out.writeUTF(clazzName);
		out.writeInt(fieldNames.length);
		for (int i = 0; i < fieldNames.length; i++) {
			out.writeUTF(fieldNames[i]);
			out.writeObject(fieldValues[i]);
		}
	}

	public void readExternal(final ObjectInput in) throws IOException,
			ClassNotFoundException {
		clazzName = in.readUTF();

		try {
			final Class clazz = Class.forName(clazzName);
			final Constructor constr = clazz.getDeclaredConstructor(null);
			constr.setAccessible(true);
			restored = constr.newInstance(null);
		} catch (Exception e) {
			final IOException f = new IOException(
					"Exception while resolving object");
			f.initCause(e);
			throw f;
		}

		((SmartObjectInputStream.EnhancedObjectInputStream) in)
				.fixHandle(restored);

		final int fieldCount = in.readInt();
		fieldNames = new String[fieldCount];
		fieldValues = new Object[fieldCount];
		for (int i = 0; i < fieldCount; i++) {
			fieldNames[i] = in.readUTF();
			fieldValues[i] = in.readObject();
		}
	}

	public Object restoreObject() throws Exception {
		restoreFields(restored);
		return restored;
	}

	private void restoreFields(final Object o) throws Exception {
		final Class clazz = Class.forName(clazzName);
		for (int i = 0; i < fieldNames.length; i++) {
			final Field field = clazz.getDeclaredField(fieldNames[i]);

			final int mod = field.getModifiers();
			if (!Modifier.isPublic(mod)) {
				field.setAccessible(true);
			}

			field.set(o, fieldValues[i]);
		}

		if (superStreamClass != null) {
			superStreamClass.restoreFields(o);
		}
	}
}
