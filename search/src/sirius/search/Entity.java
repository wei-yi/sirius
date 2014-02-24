/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.search;

import com.google.common.base.Objects;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import sirius.search.annotations.RefField;
import sirius.search.annotations.RefType;
import sirius.search.annotations.Transient;
import sirius.search.annotations.Unique;
import sirius.search.properties.Property;
import sirius.kernel.commons.Strings;
import sirius.kernel.commons.Tuple;
import sirius.kernel.health.Exceptions;
import sirius.kernel.health.HandledException;
import sirius.kernel.nls.NLS;
import sirius.web.controller.UserContext;
import sirius.web.http.WebContext;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Base class for all types which are stored in ElasticSearch.
 * <p>
 * Each subclass should wear a {@link sirius.search.annotations.Indexed} annotation to indicate which index should be
 * used.
 * </p>
 *
 * @author Andreas Haufler (aha@scireum.de)
 * @since 2013/12
 */
public abstract class Entity {

    /**
     * Contains the unique ID of this entity. This is normally auto generated by ElasticSearch.
     */
    @Transient
    protected String id;
    public static final String ID = "id";

    /**
     * Contains the version number of the currently loaded entity. Used for optimistic locking e.g. in
     * {@link Index#tryUpdate(Entity)}
     */
    @Transient
    protected long version;

    /**
     * Determines if this entity is or will be deleted.
     */
    @Transient
    protected boolean deleted;

    /**
     * Original data loaded from the database (ElasticSearch)
     */
    @Transient
    protected Map<String, Object> source;

    /**
     * Creates and initializes a new instance.
     * <p>
     * All mapped properties will be initialized by their {@link Property} if necessary.
     * </p>
     */
    public Entity() {
        if (Index.schema != null) {
            for (Property p : Index.getDescriptor(getClass()).getProperties()) {
                try {
                    p.init(this);
                } catch (Throwable e) {
                    Index.LOG.WARN("Cannot initialize %s of %s", p.getName(), getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * Determines if the entity is new.
     *
     * @return determines if the entity is new (<tt>true</tt>) or if it was loaded from the database (<tt>false</tt>).
     */
    public boolean isNew() {
        return id == null || Index.NEW.equals(id);
    }

    /**
     * Determines if the entity still exists and is not about to be deleted.
     *
     * @return <tt>true</tt> if the entity is neither new, nor marked as deleted. <tt>false</tt> otherwise
     */
    public boolean exists() {
        return !isNew() && !deleted;
    }

    /**
     * Determines if the entity is marked as deleted.
     *
     * @return <tt>true</tt> if the entity is marked as deleted, <tt>false</tt> otherwise
     */
    public boolean isDeleted() {
        return deleted;
    }

    /**
     * Returns the unique ID of the entity.
     * <p>
     * Unless the entity is new, this is never <tt>null</tt>.
     * </p>
     *
     * @return the id of this entity
     */
    public String getId() {
        return id;
    }

    /**
     * Returns an ID which is guaranteed to be globally unique.
     * <p>
     * Note that new entities always have default (non-unique) id.
     * </p>
     *
     * @return the globally unique ID of this entity.
     */
    public String getUniqueId() {
        return Index.getDescriptor(getClass()).getType() + "-" + id;
    }

    /**
     * Sets the ID of this entity.
     *
     * @param id the ID for this entity
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the version of this entity.
     *
     * @return the version which was loaded from the database
     */
    public long getVersion() {
        return version;
    }

    /**
     * Sets the version of this entity.
     *
     * @param version the version to set
     */
    public void setVersion(long version) {
        this.version = version;
    }

    /**
     * Sets the deleted flag.
     *
     * @param deleted the new value of the deleted flag
     */
    protected void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    /**
     * Invoked immediately before {@link #performSaveChecks()} and permits to fill missing values.
     */
    public void beforeSaveChecks() {
    }

    /**
     * Performs consistency checks before an entity is saved into the database.
     */
    public void performSaveChecks() {
        HandledException error = null;
        EntityDescriptor descriptor = Index.getDescriptor(getClass());
        for (Property p : descriptor.getProperties()) {
            // Automatically fill RefFields....
            if (p.getField().isAnnotationPresent(RefField.class)) {
                try {
                    RefField ref = p.getField().getAnnotation(RefField.class);
                    Property entityRef = descriptor.getProperty(ref.localRef());
                    EntityDescriptor remoteDescriptor = Index.getDescriptor(entityRef.getField()
                                                                                     .getAnnotation(RefType.class)
                                                                                     .type());

                    EntityRef<?> value = (EntityRef<?>) entityRef.getField().get(this);
                    if (value.isValueLoaded() && value.getValue() != null) {
                        p.getField()
                         .set(this, remoteDescriptor.getProperty(ref.remoteField()).getField().get(value.getValue()));
                    }
                } catch (Throwable e) {
                    Exceptions.handle()
                              .to(Index.LOG)
                              .error(e)
                              .withSystemErrorMessage(
                                      "Error updating an RefField for an RefType: Property %s in class %s: %s (%s)",
                                      p.getName(),
                                      this.getClass().getName())
                              .handle();
                }

            }
            Object value = p.writeToSource(this);
            if (!p.isNullAllowed() && Strings.isEmpty(value)) {
                UserContext.setFieldError(p.getName(), null);
                if (error == null) {
                    error = Exceptions.createHandled()
                                      .withNLSKey("Entity.fieldMustBeFilled")
                                      .set("field", NLS.get(getClass().getSimpleName() + "." + p.getName()))
                                      .handle();
                }
            }
            if (p.getField().isAnnotationPresent(Unique.class) && !Strings.isEmpty(value)) {
                Query<?> qry = Index.select(getClass()).eq(p.getName(), value);
                if (!isNew()) {
                    qry.notEq("_id", id);
                }
                Unique unique = p.getField().getAnnotation(Unique.class);
                if (Strings.isFilled(unique.within())) {
                    qry.eq(unique.within(), descriptor.getProperty(unique.within()).writeToSource(this));
                }
                if (qry.exists()) {
                    UserContext.setFieldError(p.getName(), NLS.toUserString(value));
                    if (error == null) {
                        try {
                            error = Exceptions.createHandled()
                                              .withNLSKey("Entity.fieldMustBeUnique")
                                              .set("field", NLS.get(getClass().getSimpleName() + "." + p.getName()))
                                              .set("value", NLS.toUserString(p.getField().get(this)))
                                              .handle();
                        } catch (Throwable e) {
                            Exceptions.handle(e);
                        }
                    }
                }
            }
        }
        if (error != null) {
            throw error;
        }
    }

    /**
     * Cascades the delete of an entity of this class.
     */
    protected void cascadeDelete() {
        for (ForeignKey fk : Index.getDescriptor(getClass()).remoteForeignKeys) {
            fk.onDelete(this);
        }
    }

    /**
     * Checks if an entity can be consistently deleted.
     */
    protected void performDeleteChecks() {
        for (ForeignKey fk : Index.getDescriptor(getClass()).remoteForeignKeys) {
            fk.checkDelete(this);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(id + " (Version: " + version + ") {");
        boolean first = true;
        for (Property p : Index.getDescriptor(getClass()).getProperties()) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append(p.getName());
            sb.append(": ");
            sb.append("'");
            sb.append(p.writeToSource(this));
            sb.append("'");
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (isNew()) {
            return false;
        }
        if (!(obj instanceof Entity)) {
            return false;
        }
        return getId().equals(((Entity) obj).getId());
    }

    @Override
    public int hashCode() {
        if (isNew()) {
            return super.hashCode();
        }
        return getId().hashCode();
    }

    /**
     * Invoked once an entity was completely saved.
     */
    public void afterSave() {
        for (ForeignKey fk : Index.getDescriptor(getClass()).remoteForeignKeys) {
            fk.onSave(this);
        }
    }

    /**
     * Loads the given list of values from a form submit in the given {@link WebContext}.
     *
     * @param ctx              the context which contains the data of the submitted form.
     * @param propertiesToRead the list of properties to read. This is used to have fine control over which values
     *                         are actually loaded from the form and which aren't.
     * @return a map of changed properties, containing the old and new value for each given property
     */
    public Map<String, Tuple<Object, Object>> load(WebContext ctx, String... propertiesToRead) {
        Map<String, Tuple<Object, Object>> changeList = Maps.newTreeMap();
        Set<String> allowedProperties = Sets.newTreeSet(Arrays.asList(propertiesToRead));
        for (Property p : Index.getDescriptor(getClass()).getProperties()) {
            if (allowedProperties.contains(p.getName())) {
                Object oldValue = p.writeToSource(this);
                p.readFromRequest(this, ctx);
                Object newValue = p.writeToSource(this);
                if (!Objects.equal(newValue, oldValue)) {
                    changeList.put(p.getName(), Tuple.create(oldValue, newValue));
                }
            }
        }

        return changeList;
    }

    /**
     * Invoked before an entity is saved into the database.
     * <p>
     * This method is not intended to be overridden. Override {@link #onSave()} or {@link #internalOnSave()}.
     * </p>
     */
    public final void beforeSave() {
        internalOnSave();
        onSave();
    }

    /**
     * Intended for classes providing additional on save handlers. Will be invoked before the entity will be saved,
     * but after it has been validated.
     * <p>
     * This method MUST call <code>super.internalOnSave</code> to ensure that all save handlers are called. This is
     * intended to be overridden by framework classes. Application classes should simply override
     * <code>onSave()</code>.
     * </p>
     */
    protected void internalOnSave() {

    }

    /**
     * Intended for classes providing on save handlers. Will be invoked before the entity will be saved,
     * but after it has been validated.
     * <p>
     * This method SHOULD call <code>super.internalOnSave</code> to ensure that all save handlers are called. However,
     * frameworks should rely on internalOnSave, which should not be overridden by application classes.
     * </p>
     */
    protected void onSave() {

    }

    /**
     * Enables tracking of source field (which contain the original state of the database before the entity was changed.
     * <p>
     * This will be set by @{@link Index#find(Class, String)}.
     * </p>
     */
    protected void initSourceTracing() {
        source = Maps.newTreeMap();
    }

    /**
     * Sets a source field when reading an entity from elasticsearch.
     * <p>
     * This is used by {@link Property#readFromSource(Entity, Object)}.
     * </p>
     *
     * @param name name of the field
     * @param val  persisted value of the field.
     */
    public void setSource(String name, Object val) {
        if (source != null) {
            source.put(name, val);
        }
    }

    /**
     * Checks if the given field has changed (since the entity was loaded from the database).
     *
     * @param field the field to check
     * @param value the current value which is to be compared
     * @return <tt>true</tt> if the value loaded from the database is not equal to the given value, <tt>false</tt>
     *         otherwise.
     */
    protected boolean isChanged(String field, Object value) {
        return source != null && !Objects.equal(value, source.get(field));
    }

    /**
     * Returns the name of the index which is used to store the entities.
     *
     * @return the name of the ElasticSearch index used to store the entities. Returns <tt>null</tt> to indicate that
     *         the default index (given by the {@link sirius.search.annotations.Indexed} annotation should be used).
     */
    public String getIndex() {
        return null;
    }

}