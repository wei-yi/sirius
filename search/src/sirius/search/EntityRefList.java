/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.search;

import com.google.common.cache.Cache;
import com.google.common.collect.Lists;
import sirius.kernel.commons.Strings;
import sirius.kernel.commons.Tuple;

import java.util.List;

/**
 * Used as field type which references a list of other entities.
 * <p>
 * This permits elegant lazy loading, as only the IDs are eagerly loaded and stored into the database. The objects
 * itself are only loaded on demand.
 * </p>
 *
 * @author Andreas Haufler (aha@scireum.de)
 * @since 2013/12
 */
public class EntityRefList<E extends Entity> {
    private List<E> values;
    private boolean valueFromCache;
    private Class<E> clazz;
    private List<String> ids = Lists.newArrayList();


    /**
     * Creates a new reference.
     * <p>
     * Fields of this type don't need to be initialized, as this is done by the
     * {@link sirius.search.properties.EntityProperty}.
     * </p>
     *
     * @param ref the type of the referenced entity
     */
    public EntityRefList(Class<E> ref) {
        this.clazz = ref;
    }

    /**
     * Determines if the entity value is present.
     *
     * @return <tt>true</tt> if the value was already loaded (or set). <tt>false</tt> otherwise.
     */
    public boolean isValueLoaded() {
        return values != null || ids.isEmpty();
    }

    /**
     * Returns the entity value represented by this reference.
     *
     * @return the value represented by this reference
     */
    public List<E> getValues() {
        if (!isValueLoaded() || valueFromCache) {
            List<E> result = Lists.newArrayList();
            for (String id : ids) {
                E obj = Index.find(clazz, id);
                if (obj != null) {
                    result.add(obj);
                }
            }
            values = result;
            valueFromCache = false;
        }
        return values;
    }

    /**
     * Returns the entity value represented by this reference.
     * <p>
     * The framework is permitted to load the value from a given local cache.
     * </p>
     *
     * @param localCache the cache to used when looking up values
     * @return the value represented by this reference
     */
    public List<E> getCachedValue(Cache<String, Object> localCache) {
        if (isValueLoaded()) {
            return values;
        }

        if (!isValueLoaded() || valueFromCache) {
            List<E> result = Lists.newArrayList();
            valueFromCache = false;
            for (String id : ids) {
                Tuple<E, Boolean> tuple = Index.fetch(clazz, id, localCache);
                if (tuple.getFirst() != null) {
                    result.add(tuple.getFirst());
                    valueFromCache |= tuple.getSecond();
                }
            }
            values = result;
        }
        return values;
    }

    /**
     * Returns the entity value represented by this reference.
     * <p>
     * The framework is permitted to load the value from the global cache.
     * </p>
     *
     * @return the value represented by this reference
     */
    public List<E> getCachedValue() {
        if (isValueLoaded()) {
            return values;
        }

        if (!isValueLoaded() || valueFromCache) {
            List<E> result = Lists.newArrayList();
            valueFromCache = false;
            for (String id : ids) {
                Tuple<E, Boolean> tuple = Index.fetch(clazz, id);
                if (tuple.getFirst() != null) {
                    result.add(tuple.getFirst());
                    valueFromCache |= tuple.getSecond();
                }
            }
            values = result;
        }
        return values;
    }

    /**
     * Adds the value to be represented by this reference.
     *
     * @param value the value to be stored
     */
    public void addValue(E value) {
        if (value != null) {
            if (ids.isEmpty()) {
                values = Lists.newArrayList();
            }
            this.ids.add(value.getId());
            if (values != null) {
                values.add(value);
            }
        }
    }

    public boolean contains(E value) {
        if (value == null) {
            return false;
        }
        return ids.contains(value.getId());
    }

    public boolean containsId(String id) {
        if (Strings.isEmpty(id)) {
            return false;
        }
        return ids.contains(id);
    }

    /**
     * Returns the ID of the represented value.
     * <p>
     * This can always be fetched without a DB lookup.
     * </p>
     *
     * @return the ID of the represented value
     */
    public List<String> getIds() {
        return ids;
    }

    /**
     * Sets the ID of the represented value.
     * <p>
     * If the object is available, consider using {@link #setValues(sirius.search.Entity)} as it also stores the value in a
     * temporary buffer which improves calls to {@link #getValues()} (which might happen in onSave handlers).
     * </p>
     *
     * @param ids the id of the represented value
     */
    public void setIds(List<String> ids) {
        this.ids.clear();
        if (ids != null) {
            for (String id : ids) {
                if (Strings.isFilled(id)) {
                    this.ids.add(id);
                }
            }
        }
        this.values = null;
        this.valueFromCache = false;
    }

}