/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.search;

import sirius.kernel.commons.Strings;
import sirius.kernel.commons.Tuple;

/**
 * Used as field type which references other entities.
 * <p>
 * This permits elegant lazy loading, as only the ID is eagerly loaded and stored into the database. The object
 * itself is only loaded on demand.
 * </p>
 *
 * @author Andreas Haufler (aha@scireum.de)
 * @since 2013/12
 */
public class EntityRef<E extends Entity> {
    private E value;
    private boolean valueFromCache;
    private Class<E> clazz;
    private String id;


    /**
     * Creates a new reference.
     * <p>
     * Fields of this type don't need to be initialized, as this is done by the
     * {@link sirius.search.properties.EntityProperty}.
     * </p>
     *
     * @param ref the type of the referenced entity
     */
    public EntityRef(Class<E> ref) {
        this.clazz = ref;
    }

    /**
     * Determines if the entity value is present.
     *
     * @return <tt>true</tt> if the value was already loaded (or set). <tt>false</tt> otherwise.
     */
    public boolean isValueLoaded() {
        return value != null || id == null;
    }

    /**
     * Returns the entity value represented by this reference.
     *
     * @return the value represented by this reference
     */
    public E getValue() {
        if (!isValueLoaded() || valueFromCache) {
            value = Index.find(clazz, id);
            valueFromCache = false;
        }
        return value;
    }

    /**
     * Returns the entity value represented by this reference.
     * <p>
     * The framework is permitted to load the value from a local cache.
     * </p>
     *
     * @return the value represented by this reference
     */
    public E getCachedValue() {
        if (isValueLoaded()) {
            return value;
        }

        Tuple<E, Boolean> tuple = Index.fetch(clazz, id);
        value = tuple.getFirst();
        valueFromCache = tuple.getSecond();
        return value;
    }

    /**
     * Sets the value to be represented by this reference.
     *
     * @param value the value to be stored
     */
    public void setValue(E value) {
        this.value = value;
        this.valueFromCache = false;
        this.id = value == null ? null : value.id;
    }

    /**
     * Returns the ID of the represented value.
     * <p>
     * This can always be fetched without a DB lookup.
     * </p>
     *
     * @return the ID of the represented value
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the ID of the represented value.
     * <p>
     * If the object is available, consider using {@link #setValue(Entity)} as it also stores the value in a
     * temporary buffer which improves calls to {@link #getValue()} (which might happen in onSave handlers).
     * </p>
     *
     * @param id the id of the represented value
     */
    public void setId(String id) {
        this.id = id;
        this.value = null;
        this.valueFromCache = false;
    }

    /**
     * Determines if an entity is referenced by this field or not.
     * <p>
     * This is not be confused with {@link #isValueLoaded()} which indicates if the value has already been
     * loaded from the database.
     * </p>
     *
     * @return <tt>true</tt> if an entity is referenced, <tt>false</tt> otherwise
     */
    public boolean isFilled() {
        return Strings.isFilled(id);
    }

    /**
     * Determines if the id of the referenced entity equals the given id.
     *
     * @param id the id to check
     * @return <tt>true</tt> if the id of the referenced entity equals the given id, <tt>false</tt> otherwise
     */
    public boolean containsId(String id) {
        return Strings.areEqual(this.id, id);
    }
}