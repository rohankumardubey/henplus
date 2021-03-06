/*
 * This is free software, licensed under the Gnu Public License (GPL) get a copy from <http://www.gnu.org/licenses/gpl.html> $Id:
 * EnumeratedPropertyHolder.java,v 1.6 2004-03-07 14:22:02 hzeller Exp $ author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.property;

import henplus.view.util.NameCompleter;

import java.util.Collection;
import java.util.Iterator;

/**
 * A PropertyHolder, that can change its values to a fixed set of values.
 */
public abstract class EnumeratedPropertyHolder extends PropertyHolder {

    private final String[] _values;
    private final NameCompleter _completer;

    /**
     * create a new EnumeratedPropertyHolder that gets an array of Strings with possible values of this property.
     * 
     * @param enumeratedValues
     *            the Values this property can take.
     */
    public EnumeratedPropertyHolder(final String[] enumeratedValues) {
        _values = enumeratedValues;
        _completer = new NameCompleter(enumeratedValues);
    }

    /**
     * same with collection as Input.
     */
    public EnumeratedPropertyHolder(final Collection<String> values) {
        this(values.toArray(new String[values.size()]));
    }

    /**
     * do not override this method but the {@link #enumeratedPropertyChanged(int, String)} method instead.
     */
    @Override
    protected final String propertyChanged(String newValue) throws Exception {
        if (newValue == null) {
            throw new Exception("'null' not a valid value");
        }
        newValue = newValue.trim();

        final Iterator possibleValues = _completer.getAlternatives(newValue);
        if (possibleValues == null || !possibleValues.hasNext()) {
            final StringBuilder expected = new StringBuilder();
            for (int i = 0; i < _values.length; ++i) {
                if (i != 0) {
                    expected.append(", ");
                }
                expected.append(_values[i]);
            }
            throw new Exception("'" + newValue + "' does not match any of [" + expected.toString() + "]");
        }

        final String value = (String) possibleValues.next();
        if (possibleValues.hasNext()) {
            final StringBuilder matching = new StringBuilder(value);
            do {
                matching.append(", ");
                matching.append((String) possibleValues.next());
            } while (possibleValues.hasNext());

            throw new Exception("'" + newValue + "' ambiguous. Matches [" + matching.toString() + "]");
        }

        int index = -1;
        // small size of array -- linear search acceptable
        for (int i = 0; i < _values.length; ++i) {
            if (value.equals(_values[i])) {
                index = i;
                break;
            }
        }

        enumeratedPropertyChanged(index, value);
        return value;
    }

    /**
     * to be overridden to get informed of the change and veto it.
     * 
     * @param index
     *            the index of the property that changed
     * @param value
     *            the new value of that property
     * @throws Exception
     *             to veto that change.
     */
    protected abstract void enumeratedPropertyChanged(int index, String value) throws Exception;

    @Override
    public Iterator completeValue(final String partialValue) {
        return _completer.getAlternatives(partialValue);
    }
}

/*
 * Local variables: c-basic-offset: 4 compile-command:
 * "ant -emacs -find build.xml" End:
 */
